;; Simple helper to query XTDB's :java-compile-errors by class name.
;; Simple helper to query XTDB's :java-compile-errors by class name.
(require '[workbench.core :as w]
         '[clojure.string :as str])

(println "Starting XTDB (if not started)...")
(w/start!)

(def class-name (or (System/getenv "CLASS") "DocumentAggregateServiceImpl"))
(def q '(from :java-compile-errors [{:xt/id id :java/compile-errors errs :file/path fpath}]))

(println "Querying XTDB for class:" class-name)

(let [docs (w/q q)
      matches (filter (fn [doc]
                        (when-let [p (:fpath doc)]
                          (str/includes? (str p) class-name)))
                      docs)]
  (println "Matches found:" (count matches))
  (if (seq matches)
    (doseq [m matches]
      (println "--- DOC ---")
      (println "xt/id:" (:id m))
      (println "file/path:" (:fpath m))
      (let [errs (or (:errs m) [])]
        (println "error-count:" (count errs))
        (when (seq errs)
          (println "--- ERRORS ---")
          (doseq [e errs]
            (println e)
            (println "----")))))
    (println "No compile-error documents found for:" class-name)))

(System/exit 0)
