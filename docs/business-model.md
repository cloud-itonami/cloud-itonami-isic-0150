# Business Model: Mixed-Farming Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0150`
- ISIC Rev. 4: `0150`
- Industry: Mixed farming (combined crop growing and animal raising,
  neither dominating the operation's standard gross margin)
- Social impact: food-security, rural-employment, animal-welfare

## Customer

- Small-to-medium diversified/mixed farms
- Smallholder subsistence-plus-market operations combining crops and
  livestock
- Family farms transitioning between crop-only and livestock-only
  specialization

## Offer

- Combined crop-field and herd record-keeping
- Field-operation and veterinary-appointment coordination
- Crop and animal health/welfare tracking
- Supply procurement coordination (seed, feed, fertilizer, veterinary
  supplies, equipment)
- Audit trail and transparency

## Revenue

- SaaS subscription (per-hectare + per-head pricing)
- Supply chain integration fees
- API access for agronomist and veterinary partners
- Data analytics and reporting add-ons

## Trust Controls

- No direct field/animal-handling equipment operation without human
  sign-off
- No pesticide-application decisions made by the actor
- No slaughter or culling decisions without human sign-off
- All field/veterinary recommendations are proposals, not commands
- Farm registration is required before any operation
- All crop and animal health concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we do NOT do

- **Pesticide-application decisions** — the farmer/agronomist decides
- **Veterinary treatment decisions** — the veterinarian decides treatment
- **Animal welfare decisions** — the farmer decides welfare actions
- **Economic decisions** (slaughter, culling, breeding, planting mix) —
  remain human authority
- **Direct field/animal-handling equipment operation** — the robot
  manages records and logistics only

## Supported Operations

### Farm Record Logging
- Crop yield tracking
- Herd counts and weight tracking
- Combined health status notes (crop and animal)
- Birth/death and harvest records (logging only, not decision-making)

### Field & Veterinary Coordination
- Schedule field operations (planting, harvest, maintenance)
- Schedule veterinary visits
- Track exam/inspection results
- Propose follow-up care (not order it directly)

### Health Concern Escalation
- Flag suspected crop disease/pest/drought-stress
- Flag suspected animal disease, injury, or welfare concern
- Automatic escalation to farmer/agronomist/veterinarian

### Supply Procurement
- Seed and fertilizer orders
- Feed and veterinary supply orders
- Equipment procurement
- Cost threshold escalation for large orders
