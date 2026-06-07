(ns workbench.testfix
  "コンパイルエラーを束ねて AI 修正パッチへつなぐプロトタイプ。

   この namespace は node を受け取る独立ワークフローとして設計し、
   compile error doc 取得 → bucketing → 周辺コード抽出 → 依存情報収集
   → GitHub Models API で修正案生成 → パッチ適用 → 再チェック
   を一通り扱う。"
  (:require
   [clojure.java.io    :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string     :as str]
   [workbench.codegen  :as codegen]
   [workbench.javac    :as javac]
   [workbench.jref     :as jref]
   [workbench.query    :as query]))

(defn- absolute-path
  [path]
  (.getAbsolutePath (io/file path)))

(defn- trim-message
  [msg]
  (-> (or msg "")
      str
      str/trim))

(defn- message-head
  [msg]
  (-> (trim-message msg)
      (str/split-lines)
      first
      (or "")))

(defn- format-compiler-error
  [err]
  (str "[L" (:line err) "] " (:kind err) "\n"
       (trim-message (:msg err))))

(defn- error-receiver-type
  [err]
  (or (second (re-find #"location:\s+variable\s+\S+\s+of type\s+([A-Za-z0-9_$.]+)"
                       (trim-message (:msg err))))
      (second (re-find #"symbol:\s+class\s+([A-Za-z0-9_$.]+)"
                       (trim-message (:msg err))))))

(defn- error-symbol-name
  [err]
  (or (second (re-find #"symbol:\s+method\s+([A-Za-z0-9_$.]+)\(" (trim-message (:msg err))))
      (second (re-find #"symbol:\s+class\s+([A-Za-z0-9_$.]+)" (trim-message (:msg err))))))

(defn- current-import-lines
  [java-path]
  (->> (str/split-lines (slurp java-path))
       (filter #(str/starts-with? (str/trim %) "import "))
       vec))

(defn- trim-to
  [s n]
  (let [text (or s "")]
    (if (> (count text) n)
      (str (subs text 0 n) "\n...<truncated>...")
      text)))

(defn- imported-class-names
  [import-lines]
  (->> import-lines
       (keep #(second (re-find #"^import\s+[\w.]+\.(\w+);$" %)))
       distinct
       vec))

(defn- find-class-source-file
  [src-root class-name]
  (when src-root
    (let [java-name (str class-name ".java")]
      (first
       (filter #(and (.isFile ^java.io.File %)
                     (= (.getName ^java.io.File %) java-name))
               (file-seq (io/file src-root)))))))

(defn- target-import-lines
  [src-root class-name]
  (when-let [f (find-class-source-file src-root class-name)]
    (->> (str/split-lines (slurp f))
         (filter #(str/starts-with? (str/trim %) "import "))
         vec)))

(defn- find-enum-values
  [src-root class-name]
  (when-let [f (find-class-source-file src-root class-name)]
    (let [content (slurp f)]
      (when (re-find #"\benum\b" content)
        (->> (str/split-lines content)
             (keep #(when-let [m (re-find #"^\s*([A-Z][A-Z0-9_]*)[\s(,;]" %)]
                      (second m)))
             distinct
             vec)))))

(defn compile-error-doc
  "XTDB の :java-compile-errors から対象ファイルの doc を返す。"
  [node java-path]
  (let [abs-path (absolute-path java-path)]
    (->> (query/q node '(from :java-compile-errors [*]))
         (filter #(= abs-path (:file/path %)))
         first)))

(defn compile-error-buckets
  "compile error doc を kind / message ベースで束ねる。

   opts:
     :group-by      - :kind / :kind+message / :message (default: :kind+message)
     :include-notes - NOTE を含めるか (default: false)"
  [compile-doc & {:keys [group-by include-notes]
                  :or   {group-by :kind+message
                         include-notes false}}]
  (let [bucket-by group-by
        errors (->> (:java/compile-errors compile-doc)
                    (filter #(or include-notes
                                 (not= "NOTE" (str (:kind %))))))]
    (->> errors
         (clojure.core/group-by (fn [err]
                     (case bucket-by
                       :kind         {:kind (str (:kind err))}
                       :message      {:message (message-head (:msg err))}
                       :kind+message {:kind (str (:kind err))
                                      :message (message-head (:msg err))}
                       {:kind (str (:kind err))
                        :message (message-head (:msg err))})))
         seq
         (mapv (fn [[k grouped]]
                 {:bucket-key k
                  :kind       (:kind k)
                  :message    (:message k)
                  :count      (count grouped)
                  :lines      (->> grouped (map :line) distinct sort vec)
                  :errors     (vec grouped)}))
         (sort-by (juxt (comp - :count)
                        #(or (:kind %) "")
                        #(or (:message %) "")))
         vec)))

(defn code-window
  "ファイルの指定行の前後コードを返す。line は 1-based。"
  [java-path line & {:keys [before after]
                     :or   {before 12 after 12}}]
  (let [lines      (vec (str/split-lines (slurp java-path)))
        line-idx   (max 0 (dec (long (or line 1))))
        start-idx  (max 0 (- line-idx before))
        end-idx    (min (count lines) (+ line-idx after 1))
        numbered   (->> (subvec lines start-idx end-idx)
                        (map-indexed
                         (fn [idx code-line]
                           (format "%4d | %s" (+ start-idx idx 1) code-line)))
                        (str/join "\n"))]
    {:line       line
     :start-line (inc start-idx)
     :end-line   end-idx
     :snippet    numbered}))

(defn dependency-signatures
  "テストファイルと対象クラス import から依存クラス候補を集め、XTDB :jsigs から返す。"
  [node trial java-path & {:keys [src-root class-name]}]
  (let [test-imports   (current-import-lines java-path)
        target-imports (or (target-import-lines src-root class-name) [])
        dep-classes    (->> (concat (imported-class-names test-imports)
                                    (imported-class-names target-imports))
                            (remove #(re-find #"^(Test|Assertions|Mockito|InjectMocks|Mock|BeforeEach|UUID|List|Map|Optional)$" %))
                            distinct
                            vec)]
    {:test-imports    test-imports
     :target-imports  target-imports
     :dep-signatures  (->> dep-classes
                           (mapcat (fn [cls]
                                     (map (fn [s]
                                            {:class   cls
                                             :package (:jsig/package s)
                                             :method  (:jsig/method s)
                                             :params  (:jsig/params s)
                                             :return  (:jsig/return s)
                                             :throws  (:jsig/throws s)
                                             :mods    (:jsig/mods s)})
                                          (jref/jsigs node :trial trial :class cls))))
                           vec)
     :dep-enum-values (->> dep-classes
                           (keep #(when-let [vals (find-enum-values src-root %)]
                                    [% vals]))
                           (into {}))}))

(defn- extract-first-code-block
  [markdown]
  (some->> (re-find #"(?s)```(?:java)?\s*(.*?)\s*```" (or markdown ""))
           second
           str/trim))

(defn method-md-context
  "gen-tests/<ClassName>/ 配下の per-method .md を修正コンテキストとして収集する。

   opts:
     :target-methods - 関連メソッド候補。指定時はその basename と一致する .md を優先
     :limit          - 返す件数上限 (default: 3)"
  [java-path & {:keys [target-methods limit]
                :or   {limit 3}}]
  (let [class-dir (some-> java-path io/file .getParentFile)
        target-set (set (or target-methods []))
        md-files (when (.isDirectory ^java.io.File class-dir)
                   (->> (.listFiles ^java.io.File class-dir)
                        (filter #(and (.isFile ^java.io.File %)
                                      (str/ends-with? (.getName ^java.io.File %) ".md")))
                        (remove #(= "DocumentAggregateServiceImplTest.java" (.getName ^java.io.File %)))
                        (sort-by #(.getName ^java.io.File %))))
        scored  (for [^java.io.File f md-files
                      :let [method-name (str/replace (.getName f) #"\.md$" "")
                            content     (slurp f)
                            code-block  (extract-first-code-block content)
                            preview     (trim-to (or code-block content) 2400)
                            score       (if (contains? target-set method-name) 0 1)]]
                  {:method  method-name
                   :path    (.getAbsolutePath f)
                   :score   score
                   :preview preview})]
    (->> scored
         (sort-by (juxt :score :method))
         (take limit)
         (mapv #(dissoc % :score)))))

(defn target-class-context
  "エラー束に関連しそうな被テストクラス側の文脈を抽出する。
   receiver type を軸に jrefs / jbodies / jsigs を薄く集める。"
  [node trial class-name bucket]
  (let [receiver-types   (->> (:errors bucket)
                              (keep error-receiver-type)
                              distinct
                              vec)
        target-refs      (->> (query/q node '(from :refs [*]))
                              (filter #(= trial (:ref/trial %)))
                              (filter #(str/starts-with? (:ref/from %) (str class-name "/"))))
        relevant-refs    (if (seq receiver-types)
                           (->> target-refs
                                (filter (fn [r]
                                          (some #(str/starts-with? (:ref/to r) (str % "/"))
                                                receiver-types))))
                           [])
        target-methods   (->> relevant-refs
                              (map :ref/from)
                              (map #(second (str/split % #"/" 2)))
                              distinct
                              vec)
        body-docs        (mapcat (fn [m]
                                   (jref/jbodies node :trial trial :class class-name :method m))
                                 target-methods)
        receiver-sigs    (->> receiver-types
                              (mapcat (fn [cls]
                                        (jref/jsigs node :trial trial :class cls)))
                              vec)]
    {:receiver-types receiver-types
     :target-methods target-methods
     :relevant-refs  (vec relevant-refs)
     :target-bodies  (->> body-docs
                          (map (fn [b]
                                 {:method (:jbody/method b)
                                  :body   (:jbody/body b)}))
                          distinct
                          vec)
     :receiver-sigs  receiver-sigs}))

(defn prepare-fix-request
  "1テストファイルに対する AI 修正リクエスト素材を作る。

   opts:
     :trial        - トライアル識別子
     :class-name   - 被テストクラス名
     :src-root     - production source root
     :bucket-index - compile-error-buckets の対象 index (default: 0)
     :group-by     - bucketing 単位 (default: :kind+message)"
  [node java-path & {:keys [trial class-name src-root bucket-index group-by]
                     :or   {bucket-index 0
                            group-by :kind+message}}]
  (let [compile-doc (or (compile-error-doc node java-path)
                        (throw (ex-info "compile error doc not found"
                                        {:java-path java-path})))
        buckets     (compile-error-buckets compile-doc :group-by group-by)
        bucket      (or (nth buckets bucket-index nil)
                        (throw (ex-info "compile error bucket not found"
                                        {:java-path java-path
                                         :bucket-index bucket-index
                                         :bucket-count (count buckets)})))
        deps        (dependency-signatures node trial java-path
                                           :src-root src-root
                                           :class-name class-name)
        target-ctx   (when (and trial class-name)
                       (target-class-context node trial class-name bucket))
        md-context   (method-md-context java-path
                                        :target-methods (or (:target-methods target-ctx) [])
                                        :limit 3)
        windows     (->> (:errors bucket)
                         (map :line)
                         distinct
                         sort
                         (mapv #(code-window java-path %)))]
    {:java-path         (absolute-path java-path)
     :trial             trial
     :class-name        class-name
     :src-root          src-root
     :compile-doc       compile-doc
     :buckets           buckets
     :bucket-index      bucket-index
     :bucket            bucket
     :current-code      (slurp java-path)
     :code-windows      windows
     :test-imports      (:test-imports deps)
     :target-imports    (:target-imports deps)
     :dep-signatures    (:dep-signatures deps)
     :dep-enum-values   (:dep-enum-values deps)
     :target-context    target-ctx
     :method-md-context md-context}))

(defn- signatures-text
  [dep-signatures]
  (if (seq dep-signatures)
    (str/join
     "\n"
     (map (fn [s]
            (let [params-str (str/join ", "
                                       (map #(str (:type %) " " (:name %))
                                            (:params s)))]
              (str "  " (:return s) " " (:class s) "." (:method s)
                   "(" params-str ")"
                   (when (seq (:throws s))
                     (str " throws " (str/join ", " (:throws s)))))))
          dep-signatures))
    "  (情報なし)"))

(defn- enum-text
  [dep-enum-values]
  (when (seq dep-enum-values)
    (str/join "\n"
              (map (fn [[cls vals]]
                     (str "  " cls ": " (str/join ", " vals)))
                   dep-enum-values))))

(defn- refs-text
  [refs]
  (if (seq refs)
    (str/join "\n"
              (map (fn [r]
                     (str "  " (:ref/from r) " -> " (:ref/to r)
                          (when-let [line (:ref/line r)]
                            (str " [L" line "]"))))
                   refs))
    "  (情報なし)"))

(defn- bodies-text
  [body-docs]
  (if (seq body-docs)
    (str/join
     "\n\n"
     (map (fn [b]
            (str "// " (:method b) "\n" (:body b)))
          body-docs))
    nil))

(defn- md-context-text
  [entries]
  (when (seq entries)
    (str/join
     "\n\n"
     (map (fn [{:keys [method path preview]}]
            (str "### " method "\n"
                 "source: " path "\n"
                 "```java\n" preview "\n```"))
          entries))))

(defn build-fix-prompt
  "prepare-fix-request の結果から GitHub Models API 用プロンプトを組み立てる。"
  [{:keys [java-path class-name bucket current-code code-windows
           test-imports target-imports dep-signatures dep-enum-values target-context method-md-context]}
   & {:keys [extra-context]}]
  (let [bucket-lines (->> (:errors bucket)
                          (map (fn [err]
                                 (str "- " (format-compiler-error err))))
                          (str/join "\n\n"))
        receiver-types (:receiver-types target-context)
        target-bodies  (bodies-text (:target-bodies target-context))
        receiver-sigs  (:receiver-sigs target-context)
        md-context     (md-context-text method-md-context)]
    (str "以下の Java 生成テストを、コンパイルエラーの束をまとめて解消する形で修正してください。\n"
         "存在しないクラス・メソッドを新造せず、与えたシグネチャと周辺コードに合わせて直してください。\n\n"
         "## 対象ファイル\n"
         java-path "\n\n"
         (when class-name
           (str "## 被テストクラス\n" class-name "\n\n"))
         "## 修正対象エラー束\n"
         bucket-lines "\n\n"
         (when (seq receiver-types)
           (str "## エラー当事者の receiver type\n"
                (str/join ", " receiver-types) "\n\n"))
         "## エラー周辺コード\n"
         (str/join "\n\n" (map :snippet code-windows)) "\n\n"
         "## 現在のテストコード全文\n"
         "```java\n" current-code "\n```\n\n"
         "## 現在のテスト import\n"
         (if (seq test-imports)
           (str/join "\n" test-imports)
           "  (情報なし)") "\n\n"
         "## 被テストクラスの実 import\n"
         (if (seq target-imports)
           (str/join "\n" target-imports)
           "  (情報なし)") "\n\n"
         "## 依存クラスの正確なメソッドシグネチャ\n"
         (signatures-text dep-signatures) "\n\n"
         (when (seq receiver-sigs)
           (str "## エラー当事者 receiver の実メソッドシグネチャ（優先参照）\n"
                (signatures-text receiver-sigs) "\n\n"))
         (when-let [refs-txt (some-> (:relevant-refs target-context) seq refs-text)]
           (str "## 被テストクラスから当事者 receiver への実呼び出し\n"
                refs-txt "\n\n"))
         (when target-bodies
           (str "## 被テストクラスの関連メソッド実装（存在しない呼び出しを作らないこと）\n"
                "```java\n" target-bodies "\n```\n\n"))
         (when md-context
           (str "## 生成時の per-method コンテキスト（意図の補助情報。事実確認は jsigs / jbodies を優先）\n"
                md-context "\n\n"))
         (when-let [enum-txt (enum-text dep-enum-values)]
           (str "## enum定数\n" enum-txt "\n\n"))
         (when (seq extra-context)
           (str "## 追加コンテキスト\n" extra-context "\n\n"))
         "## 出力形式\n"
         "以下のマーカー付きプレーンテキストだけを返してください。Markdown や説明文は不要です。\n"
         "SUMMARY-BEGIN\n"
         "修正方針の短い要約。各エラーにどう対応したかを1件ずつ簡潔に含めること\n"
         "SUMMARY-END\n"
         "UPDATED-CODE-BEGIN\n"
         "修正後の Java ファイル全文\n"
         "UPDATED-CODE-END\n\n"
         "## ルール\n"
         "- updated_code はファイル全文にすること\n"
         "- package/import/class 宣言を含めた完全な Java コードを返すこと\n"
         "- エラー束に関係ない大規模な書き換えは避けること\n"
         "- 既存テスト名・意図は可能な限り維持すること\n"
         "- 修正対象は「修正対象エラー束」に列挙された各エラーに限定し、各エラーに対応する具体的な修正を行うこと\n"
         "- 存在しないメソッド呼び出しについて、置換先が複数あるまたは不確定な場合は、その当該テストメソッド全体を @Disabled で削除推奨とすること。曖昧な置換は行わないこと\n"
         "  例：@Disabled(\"置換先メソッドが確定できない\") public void testXxx() { ... }\n"
         "- 既存の stub / verify / assertion は、対象メソッド内で本当に不要と判断できる場合を除いて削除しないこと\n"
         "- stub を削除する場合は、その戻り値や副作用を参照しているテスト行が無い場合に限ること\n"
         "- どうしても置換不能な場合は、その1件だけを最小変更に留め、他の2件は必ず直接修正すること\n"
         "- private / protected メソッドを mock する呼び出しは削除すること。テストから mock できない（アクセス権がない）ため\n"
         "  例：privateヘルパーメソッドは when(serviceInstance.helperMethod(...)) に置換不可\n"
         "  → その stub 行ごと削除する。または全テストメソッドを @Disabled でマークして削除推奨とすること\n"
         "- 複数の類似メソッドシグネチャが存在する場合、置換先が判定不可のため、そのテストメソッド全体を @Disabled アノテーション付きで残し、SUMMARY で『削除推奨』と明記すること\n"
         "  例: @Disabled(\"置換先メソッドが確定できない\") public void testXxx() { ... }\n"
         "- 削除推奨メソッドでも、元のコード（stub, verify, assertions含む）は削除せず、@Disabled で保持すること\n"
         "- SUMMARY には、各エラー行について「元の呼び出し -> 修正後 or @Disabled削除推奨」を1件ずつ書くこと\n"
         "- メソッド呼び出しの引数型・数は上記シグネチャに従うこと\n"
         "- enum 値は提示されたものだけを使うこと\n"
         "- コードブロック記法 ``` は使わないこと\n"
         "- SUMMARY-BEGIN / SUMMARY-END / UPDATED-CODE-BEGIN / UPDATED-CODE-END を必ずそのまま使うこと")))

(defn- strip-code-fence
  [text]
  (let [trimmed (str/trim (or text ""))]
    (if-let [[_ body] (re-find #"(?s)^```(?:\w+)?\s*(.*?)\s*```$" trimmed)]
      body
      trimmed)))

(defn- parse-marked-response
  [text]
  (let [summary (some->> (re-find #"(?s)SUMMARY-BEGIN\s*(.*?)\s*SUMMARY-END" text)
                         second
                         strip-code-fence)
        updated (some->> (re-find #"(?s)UPDATED-CODE-BEGIN\s*(.*?)\s*UPDATED-CODE-END" text)
                         second
                         strip-code-fence)]
    (when-not (seq updated)
      (throw (ex-info "AI response missing UPDATED-CODE markers"
                      {:response text})))
    {:summary summary
     :updated_code updated}))

(defn- split-args
  [s]
  (let [text (str/trim (or s ""))]
    (if (str/blank? text)
      []
      (loop [chars (seq text)
             depth 0
             token ""
             acc   []]
        (if-let [ch (first chars)]
          (cond
            (= ch \,) (if (zero? depth)
                        (recur (next chars) depth "" (conj acc (str/trim token)))
                        (recur (next chars) depth (str token ch) acc))
            (= ch \() (recur (next chars) (inc depth) (str token ch) acc)
            (= ch \)) (recur (next chars) (max 0 (dec depth)) (str token ch) acc)
            :else     (recur (next chars) depth (str token ch) acc))
          (conj acc (str/trim token)))))))

(defn- parse-call-text
  [s]
  (when-let [[_ receiver method args]
             (re-find #"(?s)(?:(\w+)\.)?([A-Za-z0-9_]+)\(([^()]*(?:\([^)]*\)[^()]*)*)\)"
                      (str/trim (or s "")))]
    {:receiver receiver
     :method   method
     :args     (split-args args)
     :arity    (count (split-args args))
     :raw      (str/trim s)}))

(defn parse-summary-replacements
  "AI の summary から、行番号ごとの置換候補を抽出する。"
  [summary]
  (->> (str/split-lines (or summary ""))
       (keep (fn [line]
               (when-let [[_ line-no from to]
                          (re-find #"^\[L(\d+)\]\s+(.+?)\s+->\s+(.+?)(?:\s*[（(].*)?$" line)]
                 {:line        (Long/parseLong line-no)
                  :from-text   (str/trim from)
                  :to-text     (str/trim to)
                  :from-call   (parse-call-text from)
                  :to-call     (parse-call-text to)})))
       vec))

(defn validate-replacement-candidates
  "fix-request と AI summary を照合し、置換候補が jsigs / jrefs / jbodies と
   どの程度整合するかを返す。

   verdict:
     :strong - receiver 側シグネチャがあり、被テスト側の実呼び出しにも出てくる
     :medium - receiver 側シグネチャはあるが、被テスト側の実呼び出しには見えない
     :weak   - メソッド名は見えるが arity が合わない、または文脈が弱い
     :missing - receiver 側シグネチャが見つからない"
  [fix-request summary]
  (let [repls         (parse-summary-replacements summary)
        errors-by-line (into {} (map (juxt :line identity) (get-in fix-request [:bucket :errors])))
        relevant-refs  (or (get-in fix-request [:target-context :relevant-refs]) [])
        target-bodies  (or (get-in fix-request [:target-context :target-bodies]) [])
        receiver-sigs  (or (get-in fix-request [:target-context :receiver-sigs]) [])]
    (mapv
     (fn [{:keys [line from-call to-call] :as repl}]
       (let [err            (get errors-by-line line)
             receiver-type  (error-receiver-type err)
             original-name  (error-symbol-name err)
             sigs-for-type  (filter #(= receiver-type (:jsig/class %)) receiver-sigs)
             method-matches (filter #(= (:method to-call) (:jsig/method %)) sigs-for-type)
             arity-matches  (filter #(= (:arity to-call) (count (:jsig/params %))) method-matches)
             called-by-target? (boolean
                                (some #(= (:ref/to %) (str receiver-type "/" (:method to-call)))
                                      relevant-refs))
             mentioned-in-body? (boolean
                                 (some #(re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote (:method to-call)) "\\b"))
                                                 (:body %))
                                       target-bodies))
             verdict        (cond
                              (empty? method-matches) :missing
                              (and (seq arity-matches) called-by-target?) :strong
                              (seq arity-matches) :medium
                              :else :weak)]
         {:line                line
          :receiver-type       receiver-type
          :original-symbol     original-name
          :from-call           from-call
          :to-call             to-call
          :method-match-count  (count method-matches)
          :arity-match-count   (count arity-matches)
          :called-by-target?   called-by-target?
          :mentioned-in-body?  mentioned-in-body?
          :matching-signatures (vec (map (fn [s]
                                           {:method (:jsig/method s)
                                            :params (:jsig/params s)
                                            :return (:jsig/return s)})
                                         (or (seq arity-matches)
                                             (seq method-matches)
                                             [])))
          :verdict             verdict}))
     repls)))

(defn- unified-diff
  [original updated file-label]
  (let [orig-file (java.io.File/createTempFile "workbench-testfix-orig-" ".java")
        new-file  (java.io.File/createTempFile "workbench-testfix-new-" ".java")]
    (try
      (spit orig-file original)
      (spit new-file updated)
      (let [{:keys [out err exit]} (sh "diff" "-u"
                                       "--label" (str file-label " (before)")
                                       "--label" (str file-label " (after)")
                                       (.getAbsolutePath orig-file)
                                       (.getAbsolutePath new-file))]
        (if (<= exit 1)
          out
          (str out err)))
      (finally
        (.delete orig-file)
        (.delete new-file)))))

(defn generate-fix-patch!
  "GitHub Models API を呼び、更新コードと diff を含む patch map を返す。"
  [fix-request & {:keys [model extra-context]
                  :or   {model "openai/gpt-4.1"}}]
  (let [prompt   (build-fix-prompt fix-request :extra-context extra-context)
        response (codegen/chat-complete
                  [{:role "system"
                    :content "あなたは Java テスト修正の専門家です。指定されたエラー束だけを一貫して解消し、指定のマーカー形式だけを返します。"}
                   {:role "user"
                    :content prompt}]
                  :model model)
        parsed   (parse-marked-response response)
        original (:current-code fix-request)
        updated  (:updated_code parsed)]
    {:java-path     (:java-path fix-request)
     :bucket-index  (:bucket-index fix-request)
     :summary       (:summary parsed)
     :original-code original
     :updated-code  updated
     :diff          (unified-diff original updated (:java-path fix-request))
     :raw-response  response}))

(defn apply-fix-patch!
  "generate-fix-patch! が返した patch をファイルへ反映する。"
  [patch]
  (spit (:java-path patch) (:updated-code patch))
  (assoc patch :applied? true))

(defn recheck-file!
  "javac で対象ファイルを再チェックし、必要なら XTDB の compile error doc も更新する。"
  [node java-path & {:keys [classpath update-xtdb?]
                     :or   {update-xtdb? true}}]
  (let [abs-path    (absolute-path java-path)
        diagnostics (javac/compile-with-diagnostics abs-path :classpath classpath)
        failed?     (javac/compile-failed? diagnostics)]
    (when update-xtdb?
      (javac/compile-errors-to-xtdb! node abs-path :classpath classpath))
    {:java-path    abs-path
     :diagnostics  diagnostics
     :error-count  (count (filter javac/diagnostic-error? diagnostics))
     :compile-ok?  (not failed?)}))

(defn fix-bucket!
  "1つの error bucket を end-to-end で修正するプロトタイプ。

   戻り値:
     {:request ...
      :patch ...
      :recheck ...}"
  [node java-path & {:keys [trial class-name src-root bucket-index classpath model extra-context apply?]
                     :or   {bucket-index 0
                            model "openai/gpt-4.1"
                            apply? true}}]
  (let [request (prepare-fix-request node java-path
                                     :trial trial
                                     :class-name class-name
                                     :src-root src-root
                                     :bucket-index bucket-index)
        patch   (generate-fix-patch! request
                                     :model model
                                     :extra-context extra-context)
        applied (if apply?
                  (apply-fix-patch! patch)
                  patch)
        recheck (when apply?
                  (recheck-file! node java-path :classpath classpath))]
    {:request request
     :patch   applied
     :recheck recheck}))
