#!/usr/bin/env clojure
;;
;; classpath_smoke.clj - compile-ok-java-files の :classpath 対応確認
;;
;; 実行方法:
;;   guix shell -m manifest.scm -- clojure -M:xtdb trials/samples/self-analysis/classpath_smoke.clj

(require '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str]
         '[workbench.core :as core])

(defn assert= [label expected actual]
  (if (= expected actual)
    (println (str "  ok  " label ": " actual))
    (do
      (println (str "  ng  " label " expected=" expected " actual=" actual))
      (System/exit 1))))

(let [sample-dir "trials/samples/self-analysis/classpath"
      out-dir "/tmp/self-analysis-classpath-classes"
      helper-java (str sample-dir "/dep/Helper.java")
      helper-result (sh "javac" "-d" out-dir helper-java)]
  (when-not (zero? (:exit helper-result))
    (println "  ng  Helper.java compile failed")
    (println (:err helper-result))
    (System/exit 1))
  (println "\n=== classpath smoke ===")
  (core/start! {:persist? false})
  (try
    (let [no-cp (->> (core/compile-ok-java-files sample-dir)
                     (mapv #(-> % io/file .getName))
                     sort)
          with-cp (->> (core/compile-ok-java-files sample-dir :classpath out-dir)
                       (mapv #(-> % io/file .getName))
                       sort)]
      (assert= "compile-ok-java-files without classpath" ["Helper.java"] no-cp)
      (assert= "compile-ok-java-files with classpath" ["Helper.java" "UseHelper.java"] with-cp)
      (println "\n=== classpath smoke passed ==="))
    (finally
      (core/stop!))))
