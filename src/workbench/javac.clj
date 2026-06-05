
;; --------------------------------------------------
;; DiagnosticCollectorでJavaファイルをコンパイルし、診断情報を返すPoC
;; --------------------------------------------------

(ns workbench.javac
  "DiagnosticCollectorでJavaファイルをコンパイルし、診断情報をマップ列として返すPoC"
  (:require
    [xtdb.api])
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

  opts:
    :classpath - javac に渡す classpath 文字列。省略時は従来どおり classpath なし。"
  [^String java-file-path & {:keys [classpath]}]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)
        file-obj (file->javafileobject java-file-path)
        options (cond-> []
                  classpath (into ["-classpath" classpath]))
        task (.getTask compiler nil nil diagnostics options nil [file-obj])]
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
  :node にはxtdbノード、:java-file-path にはファイルパスを指定する。

  opts:
    :classpath - javac に渡す classpath 文字列。"
  [node java-file-path & {:keys [classpath]}]
  (let [errors (compile-with-diagnostics java-file-path :classpath classpath)
        doc {:xt/id java-file-path
             :java/compile-errors errors
             :file/path java-file-path
             :java/compile-error? (not (empty? errors))}]
    (xtdb.api/submit-tx node [[:put-docs :java-compile-errors doc]])
    doc))
