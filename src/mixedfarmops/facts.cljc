(ns mixedfarmops.facts
  "Reference facts for mixed-farming operations coordination: supply
  category cost policy, and crop/livestock classification. This namespace
  contains pure lookup functions for domain reference data -- the Governor
  and Advisor consult these instead of inventing thresholds. Mirrors
  `cattleops.facts` (cloud-itonami-isic-0141) / `cerealops.facts`
  (cloud-itonami-isic-0111) in shape, broadened to cover BOTH crop-field
  and herd records within the same operation (ISIC 0150: mixed farming --
  combination of crop growing and animal raising on the same farm where
  neither activity accounts for >=66% of the operation's standard gross
  margin; if either crosses that threshold the operation is classified
  under the dominant single-activity ISIC class instead).")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (farmer/ops-manager). Spans BOTH crop inputs (seed,
  fertilizer) and livestock inputs (feed, veterinary-supply), plus shared
  equipment."
  {"seed"
   {:id "seed" :name "種子" :cost-threshold 500}

   "fertilizer"
   {:id "fertilizer" :name "肥料" :cost-threshold 500}

   "feed"
   {:id "feed" :name "飼料" :cost-threshold 500}

   "veterinary-supply"
   {:id "veterinary-supply" :name "獣医用品" :cost-threshold 500}

   "equipment"
   {:id "equipment" :name "設備" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def crops
  "Crops this actor's farm records may cover on the crop side of a mixed
  operation (representative field/garden crops grown alongside livestock;
  not an exhaustive botanical list)."
  {"wheat"       {:id "wheat" :name "小麦"}
   "maize"       {:id "maize" :name "とうもろこし"}
   "vegetables"  {:id "vegetables" :name "野菜"}
   "fodder-crop" {:id "fodder-crop" :name "飼料作物"}})

(defn crop-by-id [id]
  (get crops id))

(def species
  "Species this actor's farm records may cover on the livestock side of a
  mixed operation (representative, not exhaustive)."
  {"cattle"     {:id "cattle" :name "牛"}
   "swine"      {:id "swine" :name "豚"}
   "poultry"    {:id "poultry" :name "家禽"}
   "sheep-goat" {:id "sheep-goat" :name "羊・ヤギ"}})

(defn species-by-id [id]
  (get species id))
