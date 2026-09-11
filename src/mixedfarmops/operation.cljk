(ns mixedfarmops.operation
  "OperationActor -- one mixed-farming operation = one supervised actor
  run. The advisor (MixedFarmAdvisor) is sealed into a single node
  (:advise); its proposal is ALWAYS routed through the Mixed-Farming
  Operations Governor (:govern) and the rollout phase gate (:decide)
  before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next
                     seam)
    - the Advisor  (mock | real LLM)
    - the Phase    (0->3 rollout)

  One run = one mixed-farming coordination operation (intake -> advise ->
  govern -> decide -> commit | hold | approval). No unbounded inner loop
  -- each operation is auditable and checkpointed. A farm's operating
  history is advanced by MANY operations (log-farm-record /
  schedule-farm-operation / flag-health-concern / order-supplies), each
  its own independent run.

  Human-in-the-loop = real approval workflow: an `:escalate` disposition
  hands the decision to a human operator (farmer / agronomist /
  veterinarian). The approver resumes the pending request once a decision
  is made. `:flag-health-concern` ALWAYS reaches escalation when the
  Governor is clean -- see `mixedfarmops.governor/always-escalate-ops`.

  NOTE: langgraph-clj StateGraph integration is deferred (mirrors
  `cattleops.operation` / `cerealops.operation`). This stub version
  defines the high-level flow synchronously; production build wires this
  into a langgraph-clj StateGraph with `interrupt-before` for the
  escalation node and checkpoint-based resume."
  (:require [mixedfarmops.advisor :as advisor]
            [mixedfarmops.governor :as governor]
            [mixedfarmops.phase :as phase]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:farm-id request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:farm-id request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn run-operation
  "Run one mixed-farming operation through the advisor -> governor ->
  phase gate -> decision flow. Returns a map with :disposition, :audit,
  :record, :verdict.

  This is the core synchronous flow that will be embedded in the
  langgraph-clj StateGraph in production. For testing/development, can be
  called directly."
  [store request context & [{:keys [advisor]
                             :or {advisor (advisor/mock-advisor)}}]]
  (let [;; Step 1: Advisor proposes
        proposal (advisor/-advise advisor store request)
        advisor-trace (advisor/trace request proposal)

        ;; Step 2: Governor censors
        verdict (governor/check request context proposal store)

        ;; Step 3: Phase gate applies rollout constraints
        base-disposition (phase/verdict->disposition verdict)
        ph (:phase context phase/default-phase)
        {:keys [disposition reason]} (phase/gate ph request base-disposition)

        ;; Step 4: Assemble result
        disposition-fact (case disposition
                           :hold (cond-> (governor/hold-fact request context verdict)
                                   reason (assoc :phase-reason reason :phase ph))
                           :escalate {:t :approval-requested
                                      :op (:op request) :subject (:farm-id request)
                                      :reason (or reason
                                                  (cond (:high-stakes? verdict) :always-escalate
                                                        :else :low-confidence))}
                           :commit (commit-fact request context proposal))
        record (when (= :commit disposition)
                 (commit-record request context proposal))]
    {:disposition disposition
     :audit [advisor-trace disposition-fact]
     :record record
     :verdict verdict}))

(defn build
  "Stub for building a langgraph-clj StateGraph. Production implementation
  requires langgraph-clj. This version provides the flow logic via
  run-operation for testing. opts:
    :advisor -- a `mixedfarmops.advisor/Advisor` (default: mock-advisor)"
  [store & [opts]]
  ;; Return a function that mimics the graph interface
  (fn invoke-operation [request context]
    (run-operation store request context opts)))
