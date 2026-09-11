(ns mixedfarmops.governor
  "Mixed-Farming Operations Governor -- the independent compliance layer
  that earns the MixedFarmAdvisor the right to commit. The LLM has no
  notion of:
    - Whether the farm a proposal targets is actually registered
    - Whether a proposal is a real actuation (`:effect :propose` only --
      this actor NEVER directly controls field/animal-handling equipment,
      applies pesticide, or orders slaughter/culling)
    - Whether an op is inside this actor's closed coordination allowlist
    - Whether a logged crop yield and/or herd count is a plausible
      positive observation
    - Whether a supply-order's cost exceeds the escalation threshold

  This MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor is a back-office OPERATIONS COORDINATOR only -- direct
  field/animal-handling equipment operation, finalizing a
  pesticide-application decision, and slaughter/culling decisions are
  categorically outside its authority (farmer/agronomist/veterinarian
  exclusive). The Governor enforces that boundary structurally, not by
  trusting the advisor's judgment.

  CRITICAL: Any proposal to flag a crop-health OR animal-health/welfare
  concern ALWAYS escalates to a human (farmer/agronomist/veterinarian)
  for final sign-off. The LLM's confidence is never sufficient for either
  kind of health decision.

  Hard violations (always HOLD, no override, permanent):
    1. Farm not registered (farm-id missing or unknown to Store)
    2. Proposal `:effect` is not `:propose` (no direct execution, ever)
    3. Op is `:operate-field-equipment`, `:operate-animal-handling-equipment`,
       `:finalize-pesticide-application`, or `:order-slaughter` -- direct
       field/animal-handling equipment operation, finalizing a
       pesticide-application decision, and slaughter/culling decisions
       are PERMANENTLY blocked regardless of proposal content or
       confidence
    4. Op is outside the closed proposal-op allowlist
    5. `:log-farm-record` with a non-positive crop yield and/or a
       non-positive herd count (whichever metric is present on the
       proposal is independently checked)

  Soft gates (always escalate for human):
    - `:flag-health-concern` -- ALWAYS escalates (crop or animal)
    - `:order-supplies` above its category cost threshold
    - Low confidence

  This design mirrors `cattleops.governor` (cloud-itonami-isic-0141) and
  `cerealops.governor` (cloud-itonami-isic-0111) but broadens the closed
  op set and the hard-block set to cover BOTH crop-field and herd
  concerns within a single mixed-farming operation (ISIC 0150), since
  neither side dominates the operation's gross margin by definition."
  (:require [mixedfarmops.facts :as facts]
            [mixedfarmops.registry :as registry]
            [mixedfarmops.store :as store]))

(def confidence-floor 0.7)

(def blocked-ops
  "Direct field/animal-handling equipment operation, finalizing a
  pesticide-application decision, and slaughter/culling decisions sit
  outside this actor's coordination-only authority. ALWAYS a hard,
  permanent block -- never escalate, never override, regardless of
  confidence or cites."
  #{:operate-field-equipment :operate-animal-handling-equipment
    :finalize-pesticide-application :order-slaughter})

(def known-ops
  "The closed allowlist of proposal ops this actor may make -- all
  `:effect :propose` (see ADR domain design)."
  #{:log-farm-record :schedule-farm-operation
    :flag-health-concern :order-supplies})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off even when the Governor finds no
  hard violation and confidence is high. Flagging a crop or animal
  health/welfare concern is never something this actor resolves
  autonomously."
  #{:flag-health-concern})

(def all-recognized-ops
  "known-ops (allowed to proceed) union blocked-ops (recognized but
  permanently forbidden). Anything outside this union is an unknown op --
  a HARD violation, not a silent no-op."
  (into known-ops blocked-ops))

;; ----------------------------- checks -----------------------------

(defn- farm-violations
  "A proposal referencing an unregistered (or absent) farm-id is a HARD
  violation -- never act on behalf of a farm this actor cannot
  independently verify."
  [{:keys [farm-id]} st]
  (when-not (store/registered-farm st farm-id)
    [{:rule :farm-not-registered
      :detail (str "farm-id " (pr-str farm-id) " は登録済み農場として確認できない -- 農場登録前の提案は進められない")}]))

(defn- execution-violations
  "This actor never executes directly. Any proposal whose `:effect` isn't
  `:propose` is a HARD violation, independent of what op it claims."
  [proposal]
  (when-not (= :propose (:effect proposal))
    [{:rule :no-execution
      :detail "提案の :effect は :propose でなければならない -- governor は直接実行/作動を許可しない"}]))

(defn- equipment-pesticide-or-slaughter-violations
  "Direct field/animal-handling equipment operation, finalizing a
  pesticide-application decision, and slaughter/culling decisions are a
  HARD, permanent block -- machinery-operation, crop-protection and
  livestock-disposition authority remains exclusively human."
  [proposal]
  (when (contains? blocked-ops (:op proposal))
    [{:rule :equipment-pesticide-or-slaughter-blocked
      :detail (str (:op proposal) " は圃場/家畜取扱設備の直接操作、農薬散布判断の確定、またはと畜/淘汰判断であり、恒久的にブロックされる -- 農家/アグロノミスト/獣医の専権事項")}]))

(defn- unknown-op-violations
  "Enforce the closed proposal-op allowlist independently of the
  advisor's claim -- an op outside `all-recognized-ops` is a HARD
  violation, never a silent pass-through."
  [proposal]
  (when-not (contains? all-recognized-ops (:op proposal))
    [{:rule :op-not-allowed
      :detail (str (:op proposal) " はクローズドallowlist外の操作")}]))

(defn- farm-record-invalid-violations
  "For `:log-farm-record`, INDEPENDENTLY verify any logged crop yield
  and/or herd count is a plausible positive observation via
  `registry/yield-non-positive?` / `registry/herd-count-non-positive?`.
  A mixed-farming record may carry EITHER metric, BOTH, or neither (e.g.
  a health-status-only note) -- whichever metric is present is checked
  independently, so a valid herd count never masks an invalid yield (or
  vice versa)."
  [proposal]
  (when (= :log-farm-record (:op proposal))
    (into []
          (concat
           (when (and (contains? proposal :yield)
                      (registry/yield-non-positive? (:yield proposal)))
             [{:rule :farm-record-invalid
               :detail (str "収量 " (:yield proposal) " は正の数でなければならない -- 記録提案は進められない")}])
           (when (and (contains? proposal :count)
                      (registry/herd-count-non-positive? (:count proposal)))
             [{:rule :farm-record-invalid
               :detail (str "頭数 " (:count proposal) " は正の数でなければならない -- 記録提案は進められない")}])))))

(defn- cost-threshold-for
  "Resolve the escalation threshold for a supply-order proposal: the
  category-specific threshold from `mixedfarmops.facts` if the category is
  known, else the conservative default."
  [proposal]
  (let [category (get-in proposal [:value :category])
        c (and category (facts/supply-category-by-id category))]
    (or (:cost-threshold c) facts/default-cost-threshold)))

(defn check
  "Censors a MixedFarmAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (farm-violations request st)
                           (execution-violations proposal)
                           (equipment-pesticide-or-slaughter-violations proposal)
                           (unknown-op-violations proposal)
                           (farm-record-invalid-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (registry/confidence-below-floor? conf confidence-floor)
        cost (:cost proposal)
        high-cost? (boolean (and cost (registry/cost-exceeds-threshold?
                                        cost (cost-threshold-for proposal))))
        always-escalate? (contains? always-escalate-ops (:op proposal))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not high-cost?) (not always-escalate?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? high-cost? always-escalate?))
     :high-stakes? (boolean (or high-cost? always-escalate?))}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:farm-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
