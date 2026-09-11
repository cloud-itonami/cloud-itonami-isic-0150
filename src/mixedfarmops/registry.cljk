(ns mixedfarmops.registry
  "Pure validation functions for mixed-farming operations. These are
  called by the Governor to independently verify proposal parameters --
  the LLM advisor's confidence is NOT sufficient to override these checks.
  Mirrors `cattleops.registry` (cloud-itonami-isic-0141) /
  `cerealops.registry` (cloud-itonami-isic-0111) in shape, with BOTH a
  crop-side (`yield-non-positive?`) and a herd-side
  (`herd-count-non-positive?`) record check since a mixed-farming record
  may carry either metric.")

(defn cost-exceeds-threshold?
  "Independently verify a proposed spend against its category/default
  threshold. Inclusive at the boundary (exactly-at-threshold does not
  escalate)."
  [cost threshold]
  (> cost threshold))

(defn yield-non-positive?
  "A logged crop yield of zero or negative is not a real observation --
  reject it as a HARD violation rather than silently accepting bad data
  into the farm record."
  [yield]
  (<= yield 0))

(defn herd-count-non-positive?
  "A logged herd count of zero or negative is not a real observation --
  reject it as a HARD violation rather than silently accepting bad data
  into the farm record."
  [count]
  (<= count 0))

(defn confidence-below-floor?
  "Independently verify a proposal's stated confidence against the
  Governor's confidence floor."
  [confidence floor]
  (< confidence floor))
