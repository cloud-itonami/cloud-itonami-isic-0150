(ns mixedfarmops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [mixedfarmops.phase :as phase]))

(deftest verdict-to-disposition
  (testing "Clean verdict -> commit"
    (let [verdict {:escalate? false :hard? false}
          disposition (phase/verdict->disposition verdict)]
      (is (= :commit disposition))))

  (testing "Escalate verdict -> escalate"
    (let [verdict {:escalate? true :hard? false}
          disposition (phase/verdict->disposition verdict)]
      (is (= :escalate disposition))))

  (testing "Hard violation -> hold"
    (let [verdict {:escalate? false :hard? true}
          disposition (phase/verdict->disposition verdict)]
      (is (= :hold disposition)))))

(deftest phase-0-gate
  (testing "A would-be commit is forced to escalate in phase-0 (no autonomous commits during simulation)"
    (let [request {:op :log-farm-record}
          result (phase/gate :phase-0 request :commit)]
      (is (= :escalate (:disposition result)))
      (is (= :phase-0-simulation-only (:reason result)))))

  (testing "Hold passes through unchanged in phase-0"
    (let [request {:op :operate-field-equipment}
          result (phase/gate :phase-0 request :hold)]
      (is (= :hold (:disposition result)))
      (is (nil? (:reason result)))))

  (testing "Escalate passes through unchanged in phase-0"
    (let [request {:op :flag-health-concern}
          result (phase/gate :phase-0 request :escalate)]
      (is (= :escalate (:disposition result)))
      (is (nil? (:reason result))))))

(deftest phase-1-gate
  (testing "Always-escalate op forces escalate even if the Governor is clean"
    (let [request {:op :flag-health-concern}
          result (phase/gate :phase-1 request :commit)]
      (is (= :escalate (:disposition result)))
      (is (= :phase-1-always-escalate (:reason result)))))

  (testing "Routine op commits in phase-1 when clean"
    (let [request {:op :log-farm-record}
          result (phase/gate :phase-1 request :commit)]
      (is (= :commit (:disposition result)))
      (is (nil? (:reason result)))))

  (testing "Hold passes through unchanged in phase-1"
    (let [request {:op :log-farm-record}
          result (phase/gate :phase-1 request :hold)]
      (is (= :hold (:disposition result))))))

(deftest phase-2-gate
  (testing "Disposition passed through unchanged"
    (let [request {:op :anything}]
      (doseq [disp [:commit :escalate :hold]]
        (let [result (phase/gate :phase-2 request disp)]
          (is (= disp (:disposition result)))
          (is (nil? (:reason result))))))))

(deftest phase-3-gate
  (testing "Disposition passed through unchanged"
    (let [request {:op :anything}]
      (doseq [disp [:commit :escalate :hold]]
        (let [result (phase/gate :phase-3 request disp)]
          (is (= disp (:disposition result)))
          (is (nil? (:reason result))))))))

(deftest unknown-phase-gate
  (testing "Unknown phase -> conservative hold"
    (let [request {:op :anything}
          result (phase/gate :unknown-phase request :commit)]
      (is (= :hold (:disposition result)))
      (is (= :unknown-phase (:reason result))))))
