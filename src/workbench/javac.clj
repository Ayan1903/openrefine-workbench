
;; --------------------------------------------------
;; DiagnosticCollectorでJavaファイルをコンパイルし、診断情報を返すPoC
;; --------------------------------------------------

(ns workbench.javac
  "DiagnosticCollectorでJavaファイルをコンパイルし、診断情報をマップ列として返すPoC"
  (:require
    [xtdb.api])
  (:import
    [javax.tools ToolProvider DiagnosticCollector JavaFileObject SimpleJavaFileObject JavaFileObject$Kind]
    [java.nio.file Files Paths]))

;; Javaファイルパス→JavaFileObject変換
(defn- file->javafileobject [^String path]
  (let [p (Paths/get path (make-array String 0))
        uri (.toUri p)]
    (proxy [SimpleJavaFileObject] [uri JavaFileObject$Kind/SOURCE]
      (getCharContent [_] (String. (Files/readAllBytes p))))))

(defn diagnostic-error?
  "Diagnostic map がコンパイル失敗を意味する ERROR かどうか。"
  [diag]
  (= "ERROR" (str (:kind diag))))

(defn compile-failed?
  "Diagnostic maps に ERROR が1件でも含まれていれば true。"
  [diagnostics]
  (boolean (some diagnostic-error? diagnostics)))

(defn- delete-tree!
  "一時ディレクトリを再帰削除する。失敗しても診断処理は継続する。"
  [path]
  (when path
    (try
      (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (doseq [p (->> (.toArray stream)
                       (map #(cast java.nio.file.Path %))
                       (sort #(compare %2 %1)))]
          (Files/deleteIfExists p)))
      (catch Exception _
        nil))))

;; DiagnosticCollectorでコンパイルし、エラー情報をマップで返す
(defn compile-with-diagnostics
  "指定JavaファイルをDiagnosticCollector付きでコンパイルし、エラー情報をマップで返す。

  opts:
    :classpath - javac に渡す classpath 文字列。省略時は従来どおり classpath なし。"
  [^String java-file-path & {:keys [classpath]}]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)
        file-obj (file->javafileobject java-file-path)
        out-dir (Files/createTempDirectory "workbench-javac-" (make-array java.nio.file.attribute.FileAttribute 0))
        options (cond-> ["-d" (str out-dir)]
                  classpath (into ["-classpath" classpath]))
        task (.getTask compiler nil nil diagnostics options nil [file-obj])]
    (try
      (.call task)
      (map (fn [diag]
             {:kind (str (.getKind diag))
              :msg (.getMessage diag nil)
              :line (.getLineNumber diag)
              :col (.getColumnNumber diag)
              :file (str (.getSource diag))})
           (.getDiagnostics diagnostics))
      (finally
        (delete-tree! out-dir)))))

;; DiagnosticCollectorのエラー情報をXTDBに格納する
(defn compile-errors-to-xtdb!
  "指定Javaファイルをコンパイルし、エラー情報をXTDBノードに格納する。
  :node にはxtdbノード、:java-file-path にはファイルパスを指定する。

  opts:
    :classpath - javac に渡す classpath 文字列。"
  [node java-file-path & {:keys [classpath]}]
  (let [errors (compile-with-diagnostics java-file-path :classpath classpath)
        doc {:xt/id java-file-path
             :java/compile-errors errors
             :file/path java-file-path
             :java/compile-error? (compile-failed? errors)}]
    (xtdb.api/submit-tx node [[:put-docs :java-compile-errors doc]])
    doc))
