(ns mixedfarmops.phase
  "Phase gate for the mixed-farming operations coordinator rollout (0->3
  maturity). Mirrors `cattleops.phase` (cloud-itonami-isic-0141) /
  `cerealops.phase` (cloud-itonami-isic-0111) in shape, adapted to this
  actor's broader op set (no separate request-level :stake -- the
  request's :op IS the stake, since every op this actor may propose is
  already coordination-only, spanning both crop and herd sides).

  Phase 0: simulation/test only -- NO proposal ever autonomously commits;
    even a Governor-clean proposal escalates for human review.
  Phase 1: supervised operation -- always-escalate ops
    (`mixedfarmops.governor/always-escalate-ops`) escalate even when
    clean; other ops commit if the Governor approves.
  Phase 2: reduced supervision -- escalate only on Governor
    violation/low-confidence/high-cost; routine ops commit.
  Phase 3: full autonomy -- the Governor's verdict is authoritative.

  See `operation.cljc` for how the phase gate is invoked."
  (:require [mixedfarmops.governor :as governor]))

(def default-phase :phase-0)

(defn verdict->disposition
  "Translate Governor verdict to initial disposition before the phase
  gate.
  Governor's hard violation -> `:hold` (already rejected, non-negotiable)
  Governor's `:escalate?` -> `:escalate` (human review needed)
  Otherwise -> `:commit`"
  [{:keys [escalate? hard?]}]
  (cond
    hard?     :hold
    escalate? :escalate
    :else     :commit))

(defn gate
  "Phase gate: given the current phase, the request, and the
  pre-phase-gate disposition, return
  {:disposition :commit|:hold|:escalate :reason nil|keyword}

  Phase 0 (Simulation):
    - Any disposition that would otherwise `:commit` is forced to
      `:escalate` (no autonomous commits during simulation/test rollout)
    - `:hold`/`:escalate` pass through unchanged

  Phase 1 (Supervised):
    - always-escalate ops escalate even if the Governor is clean
    - other dispositions pass through unchanged

  Phase 2 (Reduced Supervision):
    - Disposition passes through unchanged

  Phase 3 (Full Autonomy):
    - Disposition passes through unchanged"
  [phase request disposition]
  (case phase
    :phase-0
    (if (= :commit disposition)
      {:disposition :escalate :reason :phase-0-simulation-only}
      {:disposition disposition :reason nil})

    :phase-1
    (if (contains? governor/always-escalate-ops (:op request))
      {:disposition :escalate :reason :phase-1-always-escalate}
      {:disposition disposition :reason nil})

    :phase-2
    {:disposition disposition :reason nil}

    :phase-3
    {:disposition disposition :reason nil}

    ;; default: unknown phase -> conservative hold
    {:disposition :hold :reason :unknown-phase}))
