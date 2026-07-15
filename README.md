# cloud-itonami-isic-0150

Open Occupation Blueprint for **ISIC Rev. 4 0150**: Mixed farming.

This repository implements a forkable OSS **mixed-farming operations
coordinator**: a farm-management robot manages BOTH crop-field records and
herd records for a single mixed operation (crop growing and animal raising
combined, where neither activity dominates the operation's standard gross
margin -- ISIC 0150 by definition sits between the single-activity crop
classes 011x and the single-activity livestock classes 014x), scheduling
of field operations and veterinary visits, and supply procurement, under a
governor-gated actor -- so a mixed farm keeps its own operational records
and maintains full transparency over decisions.

**Maturity: `:implemented`.** `src/mixedfarmops/` implements the
`MixedFarmAdvisor` (`mixedfarmops.advisor`) and the independent
`MixedFarmingOperationsGovernor` (`mixedfarmops.governor`), composed by
`mixedfarmops.operation` following the itonami actor pattern
(ADR-2607011000): `advise -> govern -> phase-gate -> commit | escalate |
hold`. 39 tests / 120 assertions green (`clojure -M:test`).

`mixedfarmops.operation` is a synchronous stub of this flow (see its
docstring) -- production wiring into a `langgraph-clj` StateGraph with
`interrupt-before`/checkpoint-based human-in-the-loop resume for escalated
operations is deferred, mirroring `cloud-itonami-isic-0141`'s
`cattleops.operation` and `cloud-itonami-isic-0111`'s
`cerealops.operation`.

## What this does NOT do

This actor coordinates **back-office logistics only**, across both the
crop and livestock sides of the operation. It explicitly does **NOT**:

- **Direct field/animal-handling equipment operation** — remains the
  farmer's exclusive authority
- **Pesticide-application decisions** — remains the farmer/agronomist
  authority
- **Veterinary treatment decisions** — remains the veterinarian/farmer
  authority
- **Slaughter or culling decisions** — economic and ethical authority
  remains human

## HARD invariants (always hold, never overridable)

1. **farm-not-registered** — the request's `farm-id` must resolve to a
   registered farm in the Store before any proposal can proceed
2. **no-execution** — every proposal's `:effect` must be `:propose` (the
   governor never directly handles field/animal-handling equipment, never
   applies pesticide, never orders slaughter)
3. **equipment-pesticide-or-slaughter-blocked** — `:operate-field-equipment`,
   `:operate-animal-handling-equipment`, `:finalize-pesticide-application`,
   and `:order-slaughter` proposals are unconditionally, permanently
   blocked
4. **op-not-allowed** — any op outside the closed allowlist below is
   rejected
5. **farm-record-invalid** — `:log-farm-record` with a non-positive crop
   yield and/or a non-positive herd count is rejected (whichever metric
   is present on the proposal is checked independently, so a valid herd
   count never masks an invalid yield or vice versa)

## Always-escalate operations (human sign-off, regardless of confidence)

- `:flag-health-concern` — any crop OR animal health/welfare concern →
  automatic escalation
- `:order-supplies` over its category cost threshold (default 500
  currency units; see `mixedfarmops.facts/supply-categories`)
- Any proposal with confidence below the Governor's floor (0.7)

## Operational requests (closed allowlist, all `:effect :propose`)

```text
:log-farm-record
  — combined crop-field AND herd data logging (yield, herd count, weight)
  — requires a registered farm; a present-but-non-positive yield or
    count is rejected (either metric, both, or neither may be present)

:schedule-farm-operation
  — combined field-operation AND veterinary-visit scheduling proposal
  — does NOT make treatment or field-equipment-dispatch decisions

:flag-health-concern
  — surface EITHER a crop-health OR an animal-health/welfare concern
  — ALWAYS escalates for human review

:order-supplies
  — procurement for seed, feed, fertilizer, veterinary supplies, equipment
  — escalates if cost exceeds its category threshold
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a farm-management robot
handles:

- Crop-field and herd record logging and entry
- Field-operation and veterinary-appointment scheduling and reminders
- Supply inventory and ordering (crop and livestock inputs)
- Audit ledger maintenance

The **MixedFarmingOperationsGovernor** is the independent safety layer
that gates all proposals before a robot action is executed. The governor
never dispatches hardware directly; `:high`/`:safety-critical` actions
(such as escalated health concerns or high-cost supply orders) require
human sign-off.

## Core Contract

```text
operational request (log, schedule, concern, order)
        |
        v
MixedFarmAdvisor -> MixedFarmingOperationsGovernor -> phase gate -> commit, or escalate for human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated operation can dispatch a robot action the governor refuses,
suppress an operating record, or hide a crop/animal health concern
without governor approval and audit evidence.

## Module structure

Mirrors `cloud-itonami-isic-0141` (`cattleops.*`) and
`cloud-itonami-isic-0111` (`cerealops.*`) module-for-module, broadened to
a single actor spanning both domains:

- `mixedfarmops.facts` — reference data: supply-category cost thresholds,
  crops, species
- `mixedfarmops.registry` — pure independent verification functions
  (cost/yield/herd-count/confidence)
- `mixedfarmops.store` — `Store` protocol + in-memory `MemStore` (farm
  registration lookup)
- `mixedfarmops.advisor` — `Advisor` protocol + `MockAdvisor` (the sealed
  LLM/decision node)
- `mixedfarmops.governor` — `MixedFarmingOperationsGovernor`: hard
  invariants + escalation gates
- `mixedfarmops.phase` — 0→3 rollout phase gate
- `mixedfarmops.operation` — composes advisor → governor → phase into one
  operation run
- `mixedfarmops.sim` — demo runner (`clojure -M:run`)

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISIC Rev. 4 `0150`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Testing

```bash
clojure -M:test   # 39 tests / 120 assertions
clojure -M:lint   # clj-kondo, 0 errors / 0 warnings
clojure -M:run    # demo runner
```

## License

AGPL-3.0-or-later.
