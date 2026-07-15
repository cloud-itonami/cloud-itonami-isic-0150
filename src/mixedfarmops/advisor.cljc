(ns mixedfarmops.advisor
  "MixedFarmAdvisor -- the contained LLM/decision node. This actor's
  intelligence layer proposes back-office coordination actions across BOTH
  sides of a mixed-farming operation (crop-field AND herd record logging,
  field-operation AND veterinary-visit scheduling, crop/animal health
  concern flags, seed/feed/fertilizer/veterinary-supply procurement) based
  on farm state and operator input. The advisor is SEALED into the
  `:advise` step of the operation flow; every proposal is routed through
  the independent Governor before committing.

  The advisor makes proposals but has NO direct authority. Proposals are
  always censored by:
    1. Governor (farm registration, closed-op allowlist, cost/health
       gates)
    2. Phase gate (rollout stage)
    3. Human operator (for escalated actions)

  Current implementation is a mock advisor for testing. Production should
  use langchain/Claude or similar LLM backend (same seam point as
  `cattleops.advisor` / `cerealops.advisor`)."
  )

;; Protocol for swappable advisor implementations
(defprotocol Advisor
  (-advise [advisor store request]
    "Given store and request, return a proposal map with :op, :effect,
    :value, :cites, :summary, :confidence (plus any op-specific top-level
    keys the Governor independently verifies, e.g. :yield/:count/:cost)."))

(defn- farm-record-value
  "Build the :value payload for a :log-farm-record proposal. A mixed-farm
  record may carry the crop-side metric (:yield), the herd-side metric
  (:count), both, or neither (e.g. a health-status-only note) -- only the
  metrics actually present on the request are copied through, both into
  :value and onto the proposal's top level (where the Governor
  independently re-verifies them)."
  [request farm-id]
  (cond-> {:farm-id farm-id
           :health-status (:health-status request "unspecified")}
    (contains? request :yield) (assoc :yield (:yield request))
    (contains? request :count) (assoc :count (:count request))))

;; Mock advisor for testing
(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor _store request]
    (let [{:keys [op farm-id]} request]
      (case op
        :log-farm-record
        (cond-> {:op :log-farm-record
                 :effect :propose
                 :value (farm-record-value request farm-id)
                 :cites ["operator-submitted-record"]
                 :summary "Farm record (crop yield and/or herd count/weight) logged from operator submission"
                 :confidence 0.9}
          (contains? request :yield) (assoc :yield (:yield request))
          (contains? request :count) (assoc :count (:count request)))

        :schedule-farm-operation
        {:op :schedule-farm-operation
         :effect :propose
         :value {:farm-id farm-id
                 :operation-type (:operation-type request "field-operation")
                 :requested-date (:requested-date request)
                 :reason (:reason request "routine-check")}
         :cites ["operator-scheduling-request"]
         :summary "Farm operation (field task or veterinary visit) proposed per operator request"
         :confidence 0.85}

        :flag-health-concern
        {:op :flag-health-concern
         :effect :propose
         :concern (:concern request "unspecified concern")
         :value {:farm-id farm-id
                 :concern-domain (:concern-domain request "unspecified")
                 :concern (:concern request "unspecified concern")
                 :recommended-action "expert-review"}
         :cites ["operator-observation"]
         :summary "Crop or animal health/welfare concern flagged for agronomist/veterinarian review"
         :confidence 0.8}

        :order-supplies
        {:op :order-supplies
         :effect :propose
         :cost (:cost request 0)
         :value {:farm-id farm-id
                 :category (:category request "seed")
                 :cost (:cost request 0)}
         :cites ["operator-procurement-request"]
         :summary "Supply order (seed/feed/fertilizer/veterinary-supply/equipment) proposed for farm"
         :confidence 0.85}

        ;; fallback -- unrecognized op. The Governor's closed allowlist
        ;; independently rejects this regardless of what the advisor says.
        {:op op
         :effect :propose
         :value {}
         :cites []
         :summary "Operation not recognized"
         :confidence 0.0}))))

(defn mock-advisor []
  (MockAdvisor.))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a proposal
  is generated, regardless of whether it's approved."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :farm-id (:farm-id request)
   :proposal-summary (:summary proposal)
   :confidence (:confidence proposal)})
