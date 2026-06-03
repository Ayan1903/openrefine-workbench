
;; --------------------------------------------------
;; DiagnosticCollectorでJavaファイルをコンパイルし、エラー情報をJSON形式で返すPoC
;; --------------------------------------------------

(ns workbench.javac
  "DiagnosticCollectorでJavaファイルをコンパイルし、エラー情報をJSON形式で返すPoC"
  (:import
    [javax.tools ToolProvider DiagnosticCollector JavaFileObject SimpleJavaFileObject JavaFileObject$Kind]
    [java.net URI]
    [java.nio.file Files Paths]))

;; Javaファイルパス→JavaFileObject変換
(defn- file->javafileobject [^String path]
  (let [p (Paths/get path (make-array String 0))
        uri (.toUri p)]
    (proxy [SimpleJavaFileObject] [uri JavaFileObject$Kind/SOURCE]
      (getCharContent [_] (String. (Files/readAllBytes p))))))

;; DiagnosticCollectorでコンパイルし、エラー情報をマップで返す
(defn compile-with-diagnostics
  "指定JavaファイルをDiagnosticCollector付きでコンパイルし、エラー情報をマップで返す。
  
  オプション:
  - :classpath - コロン区切りの classpath（複数パスは ':' で区切る）。
    未指定時は公開 API のみでコンパイル。"
  [^String java-file-path & {:keys [classpath]}]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)
        file-obj (file->javafileobject java-file-path)
        ;; classpath がある場合は -cp オプションを設定
        options (if classpath
                  ["-cp" classpath]
                  [])
        task (.getTask compiler nil nil diagnostics options nil [file-obj])]
    (when classpath
      (println (str "      [DEBUG javac] classpath provided, length: " (count classpath) ", first 100 chars: " (subs classpath 0 (min 100 (count classpath))))))
    (when-not classpath
      (println "      [DEBUG javac] NO classpath provided - compiling with public API only"))
    (.call task)
    (map (fn [diag]
           {:kind (str (.getKind diag))
            :msg (.getMessage diag nil)
            :line (.getLineNumber diag)
            :col (.getColumnNumber diag)
            :file (str (.getSource diag))})
         (.getDiagnostics diagnostics))))

;; DiagnosticCollectorのエラー情報をXTDBに格納する
(defn compile-errors-to-xtdb!
  "指定Javaファイルをコンパイルし、エラー情報をXTDBノードに格納する。
  
  引数:
  - node - XTDBノード
  - java-file-path - Javaファイルパス
  
  オプション:
  - :classpath - コロン区切り classpath（オプション）"
  [node java-file-path & {:keys [classpath]}]
  (let [errors (compile-with-diagnostics java-file-path :classpath classpath)
        doc {:xt/id java-file-path
             :java/compile-errors errors
             :file/path java-file-path
             :java/compile-error? (not (empty? errors))}]
    (xtdb.api/submit-tx node [[:put-docs :java-compile-errors doc]])
    doc))