(ns mixedfarmops.sim
  "Simple simulation/demo runner for the Mixed-Farming Operations
  Coordinator actor. Used to validate that the actor flow compiles and
  basic proposal flow works. Mirrors `cattleops.sim` (cloud-itonami-isic-0141)
  / `cerealops.sim` (cloud-itonami-isic-0111)."
  (:require [mixedfarmops.operation :as operation]
            [mixedfarmops.store :as store]))

(defn demo
  "Run a simple demo scenario: register a farm, propose a combined
  crop-yield + herd-count record log, and check the disposition flow."
  []
  (let [;; Create store with a registered farm
        st (store/mem-store
            {:initial-farms
             {"farm-001"
              {:id "farm-001"
               :name "Test Mixed Farm"
               :crops ["wheat"]
               :species ["cattle"]}}})

        ;; Build actor
        actor (operation/build st)

        ;; Create a request to log a combined farm record (crop yield AND
        ;; herd count in the same submission -- demonstrates the mixed
        ;; nature of this operation)
        request {:op :log-farm-record
                 :farm-id "farm-001"
                 :yield 12.5
                 :count 30
                 :health-status "healthy"}

        ;; Context with phase 0 (simulation)
        context {:actor-id "mixed-farm-ops-01"
                 :role :farm-operator
                 :phase :phase-0}]

    (println "=== Mixed-Farming Operations Coordinator Demo ===")
    (println "Demo farm: farm-001")
    (println "Request: log-farm-record (crop yield + herd count)")
    (println "Phase: phase-0 (simulation)")
    (println "Expected: escalate (phase-0 forces human review of all commits)")
    (println)
    (let [result (actor request context)]
      (println "Result disposition:" (:disposition result))
      result)))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
)
