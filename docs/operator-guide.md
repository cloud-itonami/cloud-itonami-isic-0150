# Operator Guide: Mixed-Farming Operations Coordinator

## Overview

The Mixed-Farming Operations Coordinator is a farm-management robot that:

1. **Logs operational data** — crop yields, herd counts/weights, combined
   health notes
2. **Schedules coordination** — field operations, veterinary appointments,
   supply orders
3. **Escalates concerns** — any crop or animal health/welfare issue
4. **Maintains transparency** — audit ledger traces all decisions

The robot is **not** the decision-maker. The farmer/agronomist/veterinarian
make all decisions about crop treatment, animal welfare, and economic
choices. The robot **proposes** actions and escalates when human input is
needed.

## Operating the Actor

### Prerequisites

1. **Farm Registration** — your mixed-farming operation must be registered
   in the system before any operation can proceed
2. **Authorized User** — operator must be authenticated and authorized
3. **Clear Request Type** — specify what you're doing:
   - `:log-farm-record` — record crop yield and/or herd data
   - `:schedule-farm-operation` — arrange a field task or vet appointment
   - `:flag-health-concern` — report a crop or animal concern
   - `:order-supplies` — procurement request

### Workflow

1. **Submit Request**
   ```clojure
   {:farm-id "farm-001"
    :op :log-farm-record
    :yield 12.5
    :count 30
    :health-status "healthy"}
   ```

2. **Actor Processes** (`operation/run-operation store request context`)
   - `:advise` — `MixedFarmAdvisor` proposes an action (`mixedfarmops.advisor`)
   - `:govern` — `MixedFarmingOperationsGovernor` checks hard invariants and escalation gates (`mixedfarmops.governor`)
   - phase gate — rollout-phase constraints applied on top of the Governor's verdict (`mixedfarmops.phase`)

3. **Outcomes** (`:disposition` on the return value)
   - **`:commit`** — operation logged, robot proceeds (`:record` is present)
   - **`:escalate`** — operation held pending human decision (audit fact `:t :approval-requested`)
   - **`:hold`** — operation blocked, hard violation (audit fact `:t :governor-hold`, cites `:violations`)

### Escalation Scenarios

**Automatic escalation (always human sign-off):**
- `:flag-health-concern` — any crop or animal health/welfare issue
- Supply orders over cost threshold (default 500 currency units)
- Low confidence operations (< 0.7)

**Hard blocks (no override):**
- `:operate-field-equipment` — direct field-equipment operation is farmer authority
- `:operate-animal-handling-equipment` — direct animal-handling equipment operation is farmer authority
- `:finalize-pesticide-application` — pesticide decisions are farmer/agronomist authority
- `:order-slaughter` — economic decisions are human authority
- Missing/unregistered farm — must register first

### Resuming Escalated Operations

`mixedfarmops.operation` is currently a synchronous stub (see its
docstring): one call to `(operation/run-operation store request context)`
runs the full `advise -> govern -> phase-gate` flow and returns immediately
with a `:disposition` of `:commit`, `:escalate`, or `:hold`. There is **no
persisted pause/resume yet** — that requires the deferred `langgraph-clj`
StateGraph integration (`interrupt-before` + checkpoint-based resume,
mirroring `cloud-itonami-isic-0141` / `cloud-itonami-isic-0111`). Until
then, an `:escalate` disposition means: **do not commit** — the caller
(production integration layer) is responsible for holding the proposal for
human review and re-submitting a follow-up operation once approved.

## Audit & Transparency

Every operation run returns an `:audit` vector containing an
advisor-proposal trace and a disposition fact (`:committed`,
`:governor-hold`, or `:approval-requested`). Production integration is
responsible for appending these facts to an append-only ledger (the
reference implementation does not include a ledger-writer — that's a
backend-integration concern, same seam point as the `Store`).

- Every proposal produces a trace, regardless of outcome
- Every hold cites the specific Governor rule(s) violated (`:violations`)
- Every escalation cites its `:reason` (always-escalate op / high cost / low confidence)

## Integration

The actor provides a standard protocol (`mixedfarmops.store/Store`) for
backend integration:

- **Farm lookup** — `(store/registered-farm store farm-id)`

Implementations include in-memory `MemStore` (testing,
`mixedfarmops.store`), and future Datomic/kotoba-server backends (the same
seam point all cloud-itonami actors use). Record-commit and ledger-append
are integration responsibilities on top of `operation/run-operation`'s
return value, not part of the `Store` protocol itself.

## Safety Guarantees

- **No unsupervised decisions** — no field-equipment dispatch, pesticide
  application, animal treatment, or slaughter/culling decision is made by
  the robot
- **No suppressed concerns** — crop and animal health concerns cannot be
  hidden or delayed
- **No unlogged operations** — every action is recorded in the audit ledger
- **No direct execution** — the governor gates every robot action

The robot is safe because:
1. It never decides — it proposes
2. It always escalates when needed
3. It never hides information
4. Every action is auditable
