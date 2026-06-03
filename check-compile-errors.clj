(require '[workbench.core :as w])

(println "Starting XTDB...")
(w/start!)

(println "=== Detailed Error Analysis ===")

;; Wait for DB to stabilize
(Thread/sleep 1000)

;; Query: Get files and their error status
(println "\nFile-by-file analysis (first 20 files):")
(try
  (let [result (w/q '(from :java-compile-errors [{:xt/id id :java/compile-errors errs :java/compile-error? err?}] (limit 20)))]
    (doseq [[i row] (map-indexed vector result)]
      (let [file-name (clojure.string/replace (:id row) #".*/gen-tests/" "")
            err-list (:errs row)
            has-errors? (not (empty? err-list))
            flag? (:err? row)]
        (println (format "%2d. %s | errs:%d | err?:%s | has-errors:%s" 
                         (inc i) 
                         (subs (str file-name "                    ") 0 30)
                         (count (or err-list []))
                         flag?
                         has-errors?)))))
  (catch Exception e
    (println "Error:" (.getMessage e))))

;; Query 2: Count files with EMPTY errors list vs non-empty
(println "\nSummary:")
(try
  (let [empty-count (w/q '(from :java-compile-errors [{:xt/id id :java/compile-errors errs}] (where (or (nil? errs) (empty? errs)))))
        has-errors (w/q '(from :java-compile-errors [{:xt/id id :java/compile-errors errs}] (where (and errs (not (empty? errs))))))]
    (println (format "  Files with empty error list: %d" (count empty-count)))
    (println (format "  Files with actual errors: %d" (count has-errors)))
    (println (format "  Total: %d" (+ (count empty-count) (count has-errors)))))
  (catch Exception e
    (println "Error:" (.getMessage e))))

(println "\n=== Complete ===")
(System/exit 0)
