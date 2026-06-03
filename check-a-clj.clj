(require '[workbench.core :as w] '[clojure.string :as str])

(println "Starting XTDB (if not started)...")
(w/start!)

(let [path "/home/mtok/dev.home/openrefine-work/trials/experiments/2026-04-28-tradehub/exports/gen-tests/AclServiceImpl/AclServiceImplTest.java"
      q '(from :java-compile-errors [{:xt/id id :java/compile-errors errs :file/path fpath}])
      docs (w/q q)
      matches (filter #(= (:fpath %) path) docs)]
  (println "Matches:" (count matches))
  (if (empty? matches)
    (println "No records found for:" path)
    (doseq [m matches]
      (println "--- DOC ---")
      (println "xt/id:" (:id m))
      (println "file/path:" (:fpath m))
      (println "error count:" (count (:errs m)))
      (println "--- ERRORS ---")
      (doseq [e (:errs m)] (println e)))))

(System/exit 0)
