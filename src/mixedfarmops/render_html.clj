(ns mixedfarmops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and no generator at all (`docs/` held
  only prose + a product `index.html`). Everything the console shows is
  produced by driving the REAL actor stack at build time --
  `mixedfarmops.operation/build` -> `mixedfarmops.advisor` -> the
  independent `mixedfarmops.governor` -> `mixedfarmops.phase` gate ->
  the disposition, verdict and audit facts that flow back out. Nothing
  on the page is hand-authored domain content.

  This repo has NO langgraph StateGraph: `mixedfarmops.operation/build`
  returns a plain invoke function (`operation.cljc` documents the
  StateGraph wiring as deferred, mirroring `cattleops.operation` /
  `cerealops.operation`), so the actor entry point IS
  `(operation/build store opts)` and that is what is called here. There
  is no `g/run*` to call.

  Likewise this repo's `mixedfarmops.store` is READ-ONLY -- its protocol
  has a single method, `registered-farm`. There is no commit / approve /
  resume entry point anywhere in the repo, so there is no ledger inside
  the store to read back: the audit ledger rendered here is the ordered
  concatenation of the `:audit` vectors the real runs returned. The
  approver-attribution section MEASURES that shape at render time rather
  than asserting it (see `approval-measurement`) -- if a write path is
  added later the page re-derives itself instead of keeping a stale
  claim.

  Two kinds of HOLD, and why the page separates them
  --------------------------------------------------
  `operation.cljc` reuses `governor/hold-fact` for BOTH a Governor hard
  violation AND the phase gate's conservative unknown-phase branch, so
  every hold arrives as the same `:t :governor-hold` fact. Fact type
  alone therefore does NOT tell a refusal from a rollout gate. The
  discriminator implemented in `hold-class` is: fact type first, then
  whether that fact carries Governor `:violations`. A hold that carries
  BOTH violations and a `:phase-reason` (a blocked op run at an unknown
  phase) is a Governor refusal -- the Governor rejected it on its own
  authority, phase or no phase.

  Provenance of every literal in `scenarios` below (the console must not
  assert anything this repo's own data does not contain):
    - farm id/name        `farm-001`/\"Test Mixed Farm\" from
                          `mixedfarmops.sim/demo` (verbatim, including its
                          `:crops [\"wheat\"] :species [\"cattle\"]`);
                          `farm-002`/\"New Mixed Farm\" from
                          `test/mixedfarmops/store_test.cljc`
    - crop ids            `wheat`/`maize`/`vegetables` from
                          `mixedfarmops.facts/crops`
    - species ids         `cattle`/`swine`/`poultry` from
                          `mixedfarmops.facts/species`
    - supply categories   `seed`/`feed`/`equipment` and the 500 / 1000
                          thresholds from
                          `mixedfarmops.facts/supply-categories`
    - costs               100 / 500 / 800 / 1000 / 1200 -- from
                          `test/mixedfarmops/governor_test.cljc` or the
                          thresholds themselves (500 is the exact
                          boundary; `registry/cost-exceeds-threshold?`
                          is exclusive there, so it does NOT escalate)
    - yields              12.5 and 0 and -1 from `sim` / `governor_test`
    - herd counts         30 and 0 and -2 from `sim` / `governor_test`
    - confidences         0.5 and 0.95 from `governor_test`; 0.7 is the
                          literal `governor/confidence-floor`
    - concern text        \"疫病の可能性\" from `governor_test`
    - unknown op          `:dispatch-robot-arm` from `governor_test`
  Three values are deliberate probes with no seed counterpart and are
  labelled as such on the page: `farm-003` (an id absent from the
  store), a request with no `farm-id` at all, and `:phase-unknown` (the
  phase gate's conservative default branch). `farm-002`'s crop/species
  lists are drawn from `facts/crops` / `facts/species` -- `store_test`
  gives that farm only an id and a name.

  Determinism: no timestamps, no random ids, no map-iteration order --
  scenarios are an ordered vector and every fold over a set/map sorts
  first. Two runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [mixedfarmops.advisor :as advisor]
            [mixedfarmops.facts :as facts]
            [mixedfarmops.governor :as governor]
            [mixedfarmops.operation :as operation]
            [mixedfarmops.phase :as phase]
            [mixedfarmops.store :as store]))

;; ------------------------------ seed ------------------------------

(def ^:private seed-farms
  "Farm records seeded into the real `mixedfarmops.store/mem-store`.
  `farm-001` is `mixedfarmops.sim/demo`'s farm verbatim; `farm-002` is
  `store_test`'s id+name, given crop/species lists drawn from
  `mixedfarmops.facts`. The Store docstring states farm data is opaque
  to it, so these maps are the whole record."
  {"farm-001" {:id "farm-001" :name "Test Mixed Farm"
               :crops ["wheat"] :species ["cattle"]}
   "farm-002" {:id "farm-002" :name "New Mixed Farm"
               :crops ["maize" "vegetables"] :species ["swine" "poultry"]}})

(def ^:private unregistered-farm-id
  "An id deliberately NOT seeded, to exercise the Governor's
  `:farm-not-registered` hard rule. The console proves it is absent by
  calling `store/registered-farm` rather than asserting it."
  "farm-003")

(def ^:private operator-context
  {:actor-id "mixed-farm-ops-01" :role :farm-operator})

;; ---------------------------- advisors ----------------------------

(defrecord OverrideAdvisor [base overrides]
  advisor/Advisor
  (-advise [_this st request]
    (merge (advisor/-advise base st request) overrides)))

(defn- advisor-for
  "The stock `mock-advisor`, or -- when a scenario needs to show the
  Governor rejecting a MISBEHAVING advisor -- the same advisor with its
  proposal overridden. The advisor is an injected seam
  (`operation/run-operation`'s `:advisor` opt), so this is the supported
  way to prove the Governor does not trust its advisor."
  [overrides]
  (let [mock (advisor/mock-advisor)]
    (if (seq overrides) (->OverrideAdvisor mock overrides) mock)))

;; ---------------------------- scenarios ----------------------------

(def ^:private scenarios
  "Ordered scenario table. Each entry is fed to the real actor; nothing
  here records an expected outcome -- the disposition rendered on the
  page is whatever the Governor and phase gate actually returned."
  [;; --- commits -----------------------------------------------------
   {:id "S01" :phase :phase-2
    :intent "混合記録: 収量と頭数を同時に記録 (ISIC 0150 の中核)"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 12.5 :count 30
              :health-status "healthy"}}
   {:id "S02" :phase :phase-2
    :intent "作物側のみの記録 (頭数なし) -- 片側だけでも通る"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 12.5
              :health-status "healthy"}}
   {:id "S03" :phase :phase-2
    :intent "家畜側のみの記録 (収量なし)"
    :request {:op :log-farm-record :farm-id "farm-002" :count 30
              :health-status "healthy"}}
   {:id "S04" :phase :phase-2
    :intent "健康状態のみの記録 (収量も頭数も無い) -- 記録不正ではない"
    :request {:op :log-farm-record :farm-id "farm-002"
              :health-status "healthy"}}
   {:id "S05" :phase :phase-2
    :intent "圃場作業/往診のスケジュール"
    :request {:op :schedule-farm-operation :farm-id "farm-001"
              :operation-type "field-operation" :reason "routine-check"}}
   {:id "S06" :phase :phase-3
    :intent "しきい値内の種子発注 (100 <= 500)"
    :request {:op :order-supplies :farm-id "farm-001" :category "seed"
              :cost 100}}
   {:id "S07" :phase :phase-3
    :intent "しきい値ちょうどの飼料発注 (500、境界は通過)"
    :request {:op :order-supplies :farm-id "farm-001" :category "feed"
              :cost 500}}
   {:id "S08" :phase :phase-3
    :intent "設備発注、カテゴリ別しきい値内 (800 <= 1000)"
    :request {:op :order-supplies :farm-id "farm-002" :category "equipment"
              :cost 800}}
   {:id "S09" :phase :phase-3
    :intent "確信度がフロアちょうど (0.7、境界は通過)"
    :overrides {:confidence 0.7}
    :request {:op :log-farm-record :farm-id "farm-002" :count 30
              :health-status "healthy"}}
   ;; --- escalations -------------------------------------------------
   {:id "S10" :phase :phase-3
    :intent "作物/家畜の健康懸念 -- Governor が常に人間へ回す"
    :request {:op :flag-health-concern :farm-id "farm-001"
              :concern-domain "crop" :concern "疫病の可能性"}}
   {:id "S11" :phase :phase-1
    :intent "同上を phase-1 で -- phase ゲート側でも常時エスカレート"
    :request {:op :flag-health-concern :farm-id "farm-002"
              :concern-domain "livestock" :concern "疫病の可能性"}}
   {:id "S12" :phase :phase-2
    :intent "既定しきい値超過の飼料発注 (1000 > 500)"
    :request {:op :order-supplies :farm-id "farm-001" :category "feed"
              :cost 1000}}
   {:id "S13" :phase :phase-2
    :intent "カテゴリ別しきい値超過の設備発注 (1200 > 1000)"
    :request {:op :order-supplies :farm-id "farm-002" :category "equipment"
              :cost 1200}}
   {:id "S14" :phase :phase-2
    :intent "未知カテゴリの発注 -- 既定しきい値 500 にフォールバック"
    :request {:op :order-supplies :farm-id "farm-001" :category "drone-parts"
              :cost 800}}
   {:id "S15" :phase :phase-2
    :intent "確信度フロア未満 (0.5 < 0.7)"
    :overrides {:confidence 0.5}
    :request {:op :log-farm-record :farm-id "farm-001" :count 30
              :health-status "healthy"}}
   {:id "S16" :phase :phase-0
    :intent "phase-0 では Governor が clean でも自律コミットしない"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 12.5 :count 30
              :health-status "healthy"}}
   ;; --- Governor hard refusals --------------------------------------
   {:id "S17" :phase :phase-2
    :intent "未登録農場 (probe: farm-003 は seed に無い)"
    :request {:op :log-farm-record :farm-id unregistered-farm-id :count 30
              :health-status "healthy"}}
   {:id "S18" :phase :phase-2
    :intent "farm-id 自体が無い要求 (probe)"
    :request {:op :log-farm-record :count 30 :health-status "healthy"}}
   {:id "S19" :phase :phase-2
    :intent "暴走 advisor が :effect :execute を提案 -- 直接実行は恒久禁止"
    :overrides {:effect :execute}
    :request {:op :log-farm-record :farm-id "farm-001" :count 30
              :health-status "healthy"}}
   {:id "S20" :phase :phase-2
    :intent "圃場設備の直接操作 -- 農家の専権事項"
    :request {:op :operate-field-equipment :farm-id "farm-001"}}
   {:id "S21" :phase :phase-2
    :intent "家畜取扱設備の直接操作 -- 農家の専権事項"
    :request {:op :operate-animal-handling-equipment :farm-id "farm-001"}}
   {:id "S22" :phase :phase-2
    :intent "農薬散布判断の確定 -- 農家/アグロノミストの専権事項"
    :request {:op :finalize-pesticide-application :farm-id "farm-001"}}
   {:id "S23" :phase :phase-2
    :intent "と畜/淘汰の判断 -- 経済的・倫理的判断は人間に残る"
    :request {:op :order-slaughter :farm-id "farm-001"}}
   {:id "S24" :phase :phase-2
    :intent "allowlist 外の操作 (governor_test の :dispatch-robot-arm)"
    :request {:op :dispatch-robot-arm :farm-id "farm-001"}}
   {:id "S25" :phase :phase-2
    :intent "収量 0 の記録提案"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 0
              :health-status "healthy"}}
   {:id "S26" :phase :phase-2
    :intent "頭数 0 の記録提案"
    :request {:op :log-farm-record :farm-id "farm-001" :count 0
              :health-status "healthy"}}
   {:id "S27" :phase :phase-2
    :intent "正しい頭数は不正な収量を隠さない (yield -1, count 30)"
    :request {:op :log-farm-record :farm-id "farm-001" :yield -1 :count 30
              :health-status "healthy"}}
   {:id "S28" :phase :phase-2
    :intent "正しい収量は不正な頭数を隠さない (yield 12.5, count -2)"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 12.5 :count -2
              :health-status "healthy"}}
   {:id "S29" :phase :phase-2
    :intent "両側とも不正 -- 違反は 1 件に丸めず 2 件積む"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 0 :count 0
              :health-status "healthy"}}
   {:id "S30" :phase :phase-2
    :intent "未登録農場への と畜提案 -- 異なる rule が 2 件同時に積まれる"
    :request {:op :order-slaughter :farm-id unregistered-farm-id}}
   ;; --- phase gate hold (NOT a Governor refusal) ---------------------
   {:id "S31" :phase :phase-unknown
    :intent "未知の phase (probe) -- phase ゲートが保守的に hold。Governor 違反は 0 件"
    :request {:op :log-farm-record :farm-id "farm-001" :yield 12.5 :count 30
              :health-status "healthy"}}])

(def ^:private phase-matrix-ops
  "Three ops run across every phase so the rollout gate is shown as a
  measured matrix, not a described one. The third op is permanently
  blocked, which is how the page shows a Governor refusal is
  phase-independent."
  [{:op :log-farm-record :farm-id "farm-001" :yield 12.5 :count 30
    :health-status "healthy"}
   {:op :flag-health-concern :farm-id "farm-001"
    :concern-domain "crop" :concern "疫病の可能性"}
   {:op :order-slaughter :farm-id "farm-001"}])

(def ^:private phase-matrix-phases
  [:phase-0 :phase-1 :phase-2 :phase-3 :phase-unknown])

;; ------------------------------ run -------------------------------

(defn- invoke!
  "One real actor run. `operation/build` is this repo's actor entry
  point (the StateGraph wiring is documented as deferred in
  `operation.cljc`)."
  [st request phase overrides]
  (let [actor (operation/build st {:advisor (advisor-for overrides)})]
    (actor request (assoc operator-context :phase phase))))

(defn run-demo!
  "Drives every scenario and the phase matrix through the real actor
  against one freshly seeded store. Returns
  {:store :runs :matrix :ledger} where `:ledger` is the ordered
  concatenation of every `:audit` vector the runs produced -- this repo
  has no in-store ledger to read back (see ns docstring)."
  ([] (run-demo! scenarios))
  ([scs]
   (let [st (store/mem-store {:initial-farms seed-farms})
         runs (mapv (fn [{:keys [request phase overrides] :as sc}]
                      (assoc sc :result (invoke! st request phase overrides)))
                    scs)
         matrix (vec (for [request phase-matrix-ops
                           phase phase-matrix-phases]
                       {:request request :phase phase
                        :result (invoke! st request phase nil)}))]
     {:store st
      :runs runs
      :matrix matrix
      :ledger (vec (mapcat (comp :audit :result) (concat runs matrix)))})))

;; --------------------------- derivations ---------------------------

(defn- disposition-of [run] (get-in run [:result :disposition]))

(defn- disposition-fact
  "The second audit fact of a run -- the disposition fact. `:audit` is
  always `[advisor-trace disposition-fact]` (`operation/run-operation`)."
  [run]
  (second (get-in run [:result :audit])))

(defn hold-class
  "Classify a disposition fact. Fact type FIRST, then -- because
  `operation.cljc` emits the same `:t :governor-hold` fact for the phase
  gate's unknown-phase branch as for a real refusal -- on whether the
  fact carries Governor `:violations`.

  A fact that carries BOTH `:violations` and `:phase-reason` is a
  Governor refusal: the Governor rejected the proposal on its own
  authority and the phase gate merely re-stamped it."
  [fact]
  (cond
    (nil? fact)                     :no-fact
    (not= :governor-hold (:t fact)) :not-a-hold
    (seq (:violations fact))        :governor-refusal
    (:phase-reason fact)            :phase-gate
    :else                           :unclassified-hold))

(defn- hold-classes
  "hold-class for every run that actually held, in run order."
  [{:keys [runs matrix]}]
  (->> (concat runs matrix)
       (filter #(= :hold (disposition-of %)))
       (map (fn [run] [run (hold-class (disposition-fact run))]))))

(defn- governor-refusals [state]
  (filter #(= :governor-refusal (second %)) (hold-classes state)))

(defn- phase-gate-holds [state]
  (filter #(= :phase-gate (second %)) (hold-classes state)))

(defn- escalation-class
  "Escalations split the same way. `operation.cljc` prefers the phase
  gate's reason when there is one (`(or reason ...)`), so a
  `:phase-1-always-escalate` fact can hide the fact that the Governor
  independently escalated too. The verdict is therefore read alongside
  the fact instead of trusting the reason string."
  [run]
  (let [fact (disposition-fact run)
        verdict (get-in run [:result :verdict])
        gov? (boolean (:escalate? verdict))
        phase? (contains? #{:phase-0-simulation-only :phase-1-always-escalate}
                          (:reason fact))]
    (cond
      (and gov? phase?) :both
      gov?              :governor
      phase?            :phase-gate
      :else             :unclassified-escalation)))

(defn- fired-rules
  "The set of Governor hard-rule keywords that actually fired."
  [{:keys [runs matrix]}]
  (into (sorted-set)
        (mapcat (fn [r] (map :rule (get-in r [:result :verdict :violations])))
                (concat runs matrix))))

(defn- op-class [op]
  (cond
    (contains? governor/blocked-ops op)         :permanently-blocked
    (contains? governor/always-escalate-ops op) :always-escalates
    (contains? governor/known-ops op)           :allowlisted
    :else                                       :unknown-op))

(defn- farm-ids-seen [runs]
  (->> runs
       (map (comp :farm-id :request))
       distinct
       (sort-by #(or % ""))
       vec))

(defn- commit-records [{:keys [runs matrix]}]
  (keep (fn [run] (when-let [r (get-in run [:result :record])]
                    (assoc run :rec r)))
        (concat runs matrix)))

;; ------------------- approver attribution (measured) -------------------

(def ^:private approver-keys
  "Key names an approver identity could plausibly land under. Checked at
  every level of a commit record and of every audit fact rather than
  assumed. `:actor` is deliberately NOT in this set -- see
  `actor-is-executing-actor?`."
  #{:approved-by :approver :approved_by :signed-off-by :decided-by
    :authorized-by :reviewed-by})

(defn- approver-in? [m]
  (boolean (and (map? m) (some #(contains? m %) approver-keys))))

(defn- record-has-approver? [record]
  (or (approver-in? record)
      (approver-in? (:value record))
      (approver-in? (:payload record))))

(defn- store-protocol-methods []
  (->> (:sigs store/Store) vals (map (comp name :name)) sort vec))

(defn- actor-values
  "Every distinct `:actor` value in the ledger."
  [ledger]
  (->> ledger (keep :actor) distinct sort vec))

(defn actor-is-executing-actor?
  "MEASURES whether the ledger's `:actor` field is the EXECUTING actor
  rather than an approver: it is, iff every `:actor` value the runs
  produced equals the `:actor-id` this build passed in on the context.
  Reading `:actor` as an approver is the error this check exists to
  prevent -- on data where only one actor ever runs, the wrong reading
  and the right reading agree, so the page states which one it measured."
  [ledger]
  (let [vals* (actor-values ledger)]
    {:values vals*
     :context-actor-id (:actor-id operator-context)
     :all-equal-context? (= vals* [(:actor-id operator-context)])}))

(defn approval-measurement
  "MEASURES this repo's approval/approver plumbing instead of asserting
  it. Everything here is read off the live protocol and the records the
  real runs produced, so the disclosure the page prints re-derives
  itself the day a write path is added."
  [{:keys [ledger] :as state}]
  (let [records (map :rec (commit-records state))
        write-methods (remove #{"registered-farm"} (store-protocol-methods))]
    {:store-methods         (store-protocol-methods)
     :write-methods         (vec write-methods)
     :commit-records        (count records)
     :records-with-value    (count (filter #(contains? % :value) records))
     :records-with-payload  (count (filter #(contains? % :payload) records))
     :payload-equals-value  (count (filter #(= (:payload %) (:value %)) records))
     :records-with-approver (count (filter record-has-approver? records))
     :escalations           (count (filter #(= :approval-requested (:t %)) ledger))
     :approval-facts        (count (filter #(contains? #{:approval-granted
                                                         :approval-denied}
                                                       (:t %))
                                           ledger))
     :facts-with-approver   (count (filter approver-in? ledger))
     :actor-field           (actor-is-executing-actor? ledger)}))

;; ---------------------------- html utils ----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- span [cls v] (str "<span class=\"" cls "\">" (esc v) "</span>"))
(defn- muted [v] (span "muted" v))
(defn- dash [] (muted "—"))
(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- disposition-cell [d]
  (case d
    :commit   (span "ok" "commit")
    :escalate (span "warn" "escalate")
    :hold     (span "critical" "HOLD")
    (muted (nm d))))

(defn- hold-class-cell [cls]
  (case cls
    :governor-refusal (span "critical" "Governor 拒否")
    :phase-gate       (span "warn" "phase ゲート")
    (span "critical" (str "未分類 (" (nm cls) ")"))))

(defn- kv-str
  "Deterministic rendering of a record/value map: nils dropped, keys
  sorted by name."
  [m]
  (if (seq m)
    (->> m
         (remove (comp nil? val))
         (sort-by (comp name key))
         (map (fn [[k v]] (str (name k) "=" (nm v))))
         (str/join ", "))
    ""))

(defn- row [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [{:keys [title lead headers rows]}]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" (map row rows)) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- run-label [run]
  (or (:id run)
      (str (nm (get-in run [:request :op])) " @ " (nm (:phase run)))))

;; ----------------------------- sections -----------------------------

(defn- farms-section [{:keys [store runs]}]
  (let [ids (farm-ids-seen runs)]
    {:title "農場レジストリ (Farm registry)"
     :lead (str "登録状態の列は " (code "mixedfarmops.store/registered-farm")
                " を実際に呼んだ結果。未登録の id は Governor の "
                (code ":farm-not-registered")
                " ハード違反を踏むための probe で、seed には存在しない。"
                "ISIC 0150 は作物と家畜の両方を持つ経営体なので、1 農場が両方の列を持つ。")
     :headers ["farm-id" "名称" "作物" "家畜" "登録状態" "この実行での操作数"
               "commit" "escalate" "HOLD"]
     :rows (for [id ids
                 :let [f (store/registered-farm store id)
                       cs (seq (sort (:crops f)))
                       sp (seq (sort (:species f)))
                       mine (filter #(= id (get-in % [:request :farm-id])) runs)
                       cnt (fn [d] (count (filter #(= d (disposition-of %)) mine)))]]
             [(if id (code id) (muted "(farm-id 無し)"))
              (if f (esc (:name f)) (dash))
              (if cs (str/join ", " (map #(str (esc (:name (facts/crop-by-id %)))
                                               " " (code %)) cs))
                  (dash))
              (if sp (str/join ", " (map #(str (esc (:name (facts/species-by-id %)))
                                               " " (code %)) sp))
                  (dash))
              (if f (span "ok" "登録済み")
                  (span "critical" "未登録 (store が nil を返す)"))
              (esc (count mine))
              (esc (cnt :commit))
              (esc (cnt :escalate))
              (esc (cnt :hold))])}))

(defn- op-gate-section [{:keys [runs matrix]}]
  (let [all (concat runs matrix)
        seen-ops (map (comp :op :request) all)
        known (sort-by name governor/all-recognized-ops)
        extra (sort-by name (remove #(contains? governor/all-recognized-ops %)
                                    (distinct seen-ops)))
        ops (concat known extra)]
    {:title "操作ゲート (Mixed-Farming Operations Governor)"
     :lead (str "行は " (code "mixedfarmops.governor") " の " (code "known-ops")
                " / " (code "blocked-ops") " / " (code "always-escalate-ops")
                " を実行時に読んで生成しており、手書きの表ではない。"
                "ハード違反は上書き不可 — 確信度がいくら高くても通らない。")
     :headers ["op" "分類" "この実行での回数" "commit" "escalate" "HOLD"]
     :rows (for [op ops
                 :let [mine (filter #(= op (get-in % [:request :op])) all)
                       cnt (fn [d] (count (filter #(= d (disposition-of %)) mine)))]]
             [(code op)
              (case (op-class op)
                :permanently-blocked (span "critical" "恒久ブロック (blocked-ops)")
                :always-escalates    (span "warn" "常時エスカレート (always-escalate-ops)")
                :allowlisted         (span "ok" "allowlist 内 (known-ops)")
                (span "critical" "allowlist 外 (未知 op)"))
              (esc (count mine))
              (esc (cnt :commit))
              (esc (cnt :escalate))
              (esc (cnt :hold))])}))

(defn- crops-section [{:keys [store runs]}]
  (let [facs (keep #(store/registered-farm store %) (farm-ids-seen runs))]
    {:title "作物リファレンス (ISIC 0150 の作物側)"
     :lead (str (code "mixedfarmops.facts/crops")
                " から読み出し、seed した農場の実データと突き合わせている。"
                "対応する農場が無い行は「—」— 表は農場ではなく facts から生成されている。")
     :headers ["crop-id" "名称" "この実行の登録農場"]
     :rows (for [cid (sort (keys facts/crops))
                 :let [c (facts/crop-by-id cid)
                       matching (sort (map :id (filter #(some #{cid} (:crops %))
                                                       facs)))]]
             [(code cid)
              (esc (:name c))
              (if (seq matching) (str/join ", " (map code matching)) (dash))])}))

(defn- species-section [{:keys [store runs]}]
  (let [facs (keep #(store/registered-farm store %) (farm-ids-seen runs))]
    {:title "家畜リファレンス (ISIC 0150 の家畜側)"
     :lead (str (code "mixedfarmops.facts/species")
                " から読み出し、seed した農場の実データと突き合わせている。")
     :headers ["species-id" "名称" "この実行の登録農場"]
     :rows (for [sid (sort (keys facts/species))
                 :let [s (facts/species-by-id sid)
                       matching (sort (map :id (filter #(some #{sid} (:species %))
                                                       facs)))]]
             [(code sid)
              (esc (:name s))
              (if (seq matching) (str/join ", " (map code matching)) (dash))])}))

(defn- cost-threshold-section [{:keys [runs]}]
  (let [orders (filter #(= :order-supplies (get-in % [:request :op])) runs)
        cat-of (fn [r] (get-in r [:request :category]))
        unknown (remove #(facts/supply-category-by-id (cat-of %)) orders)]
    {:title "調達カテゴリとエスカレーションしきい値"
     :lead (str "しきい値は " (code "mixedfarmops.facts/supply-categories")
                " から読み出したもの。判定は "
                (code "mixedfarmops.registry/cost-exceeds-threshold?")
                " で、境界は通過 (しきい値ちょうどはエスカレートしない)。"
                "カテゴリは提案の " (code "[:value :category]")
                " から解決され、未知なら既定にフォールバックする。")
     :headers ["category" "名称" "しきい値" "この実行での発注" "金額" "結果"]
     :rows (concat
            (for [cid (sort (keys facts/supply-categories))
                  :let [c (facts/supply-category-by-id cid)
                        mine (filter #(= cid (cat-of %)) orders)]]
              [(code cid)
               (esc (:name c))
               (esc (:cost-threshold c))
               (esc (count mine))
               (if (seq mine)
                 (esc (str/join ", " (map #(get-in % [:request :cost]) mine)))
                 (dash))
               (if (seq mine)
                 (str/join " " (map #(disposition-cell (disposition-of %)) mine))
                 (dash))])
            [[(code "(未知カテゴリ)")
              (muted "既定フォールバック default-cost-threshold")
              (esc facts/default-cost-threshold)
              (esc (count unknown))
              (if (seq unknown)
                (esc (str/join ", " (map #(get-in % [:request :cost]) unknown)))
                (dash))
              (if (seq unknown)
                (str/join " " (map #(disposition-cell (disposition-of %)) unknown))
                (dash))]])}))

(defn- basis-cell [run]
  (let [fact (disposition-fact run)
        basis (:basis fact)]
    (cond
      (= :governor-hold (:t fact))
      (let [rules (seq (remove nil? basis))]
        (if rules
          (str/join ", " (map code rules))
          (str (span "warn" (str "phase ゲート: " (nm (:phase-reason fact))))
               " " (muted "(Governor 違反 0 件)"))))

      (= :approval-requested (:t fact))
      (code (:reason fact))

      (= :committed (:t fact))
      (if (seq basis) (esc (str/join ", " basis)) (dash))

      :else (dash))))

(defn- scenario-section [{:keys [runs]}]
  {:title "シナリオ台帳 (実行結果)"
   :lead (str "各行は " (code "mixedfarmops.operation/build")
              " で組んだ実アクターを 1 回まわした結果。"
              "disposition・根拠・コミット記録はすべて実行の戻り値であり、"
              "期待値を書いたものではない。")
   :headers ["#" "op" "農場" "phase" "確信度" "disposition" "根拠 / 理由"
             "コミット記録"]
   :rows (for [{:keys [id phase request result intent] :as run} runs
               :let [conf (get-in result [:verdict :confidence])]]
           [(str (esc id) "<br>" (muted intent))
            (code (:op request))
            (if (:farm-id request) (code (:farm-id request)) (dash))
            (code phase)
            (if (and conf (< conf governor/confidence-floor))
              (span "warn" conf)
              (esc conf))
            (disposition-cell (disposition-of run))
            (basis-cell run)
            (if-let [r (:record result)]
              (code (kv-str (:value r)))
              (dash))])})

(defn- hold-classification-section [state]
  (let [classified (hold-classes state)
        refusals (count (filter #(= :governor-refusal (second %)) classified))
        gates (count (filter #(= :phase-gate (second %)) classified))]
    {:title "HOLD の分類 — Governor 拒否 と phase ゲートを混同しない"
     :lead (str "この実行の HOLD は " (esc (count classified)) " 件で、うち "
                (span "critical" (str "Governor 拒否 " refusals " 件"))
                " と " (span "warn" (str "phase ゲート " gates " 件"))
                " に分かれる。両方とも " (code ":t :governor-hold")
                " という同じ事実型で出てくる (" (code "operation.cljc")
                " が phase ゲートの保守 hold にも "
                (code "governor/hold-fact") " を使い回すため) ので、"
                "判別子は事実型のあと " (code ":violations")
                " が空かどうかである。"
                "違反と phase 理由の両方を持つ行は Governor 拒否に数える — "
                "Governor が単独で拒否しており、phase は後から押印しただけだからである。")
     :headers ["シナリオ" "op" "phase" "分類" "Governor 違反数" "phase-reason"]
     :rows (for [[run cls] classified
                 :let [fact (disposition-fact run)
                       vs (get-in run [:result :verdict :violations])]]
             [(esc (run-label run))
              (code (get-in run [:request :op]))
              (code (:phase run))
              (hold-class-cell cls)
              (if (seq vs) (span "critical" (count vs)) (esc 0))
              (if-let [pr (:phase-reason fact)] (code pr) (dash))])}))

(defn- hard-hold-section [state]
  (let [rows (mapcat
              (fn [[run _cls]]
                (let [vs (get-in run [:result :verdict :violations])]
                  (for [v vs]
                    [(esc (run-label run))
                     (span "critical" (nm (:rule v)))
                     (code (get-in run [:request :op]))
                     (if-let [f (get-in run [:request :farm-id])] (code f) (dash))
                     (esc (:detail v))])))
              (governor-refusals state))]
    {:title "実際に発火したハード違反 (Governor 拒否の内訳)"
     :lead (str "1 行 = 実行が返した違反 1 件。detail 文は "
                (code "mixedfarmops.governor")
                " が生成した本文そのままで、ここで書き起こしたものではない。"
                "HOLD は人間に到達しない — 承認で覆せない。"
                "1 つの提案が複数の違反を積むことがある (両側の記録が不正、"
                "未登録農場への恒久ブロック op など)。")
     :headers ["シナリオ" "rule" "op" "農場" "Governor の判定理由"]
     :rows rows}))

(defn- escalation-section [{:keys [runs matrix]}]
  (let [esc-runs (filter #(= :escalate (disposition-of %)) (concat runs matrix))]
    {:title "エスカレーションの分類 — 誰が人間を呼んだか"
     :lead (str "HOLD と同じ問題がエスカレーション側にもある: "
                (code "operation.cljc") " は "
                (code "(or reason ...)")
                " で phase ゲートの理由を優先するので、事実の "
                (code ":reason")
                " だけを読むと Governor 側の判断が隠れる。"
                "この表は事実と一緒に " (code ":verdict")
                " の " (code ":escalate?") " も読んで分類している。")
     :headers ["シナリオ" "op" "phase" "事実の reason" "Governor も escalate?"
               "分類"]
     :rows (for [run esc-runs
                 :let [fact (disposition-fact run)
                       verdict (get-in run [:result :verdict])
                       cls (escalation-class run)]]
             [(esc (run-label run))
              (code (get-in run [:request :op]))
              (code (:phase run))
              (code (:reason fact))
              (if (:escalate? verdict) (span "warn" "yes") (muted "no"))
              (case cls
                :both       (span "warn" "Governor と phase ゲートの両方")
                :governor   (span "warn" "Governor のみ")
                :phase-gate (muted "phase ゲートのみ")
                (span "critical" "未分類"))])}))

(defn- phase-matrix-section [{:keys [matrix]}]
  {:title "Phase ゲート実測マトリクス"
   :lead (str "同じ要求を phase を変えて実際に流した結果。既定 phase は "
              (code phase/default-phase) "。未知 phase は "
              (code "mixedfarmops.phase/gate")
              " の保守的な default 分岐を踏むための probe。"
              "恒久ブロック op はどの phase でも HOLD になる — "
              "Governor の拒否は rollout 段階に依存しない。")
   :headers ["op" "phase" "disposition" "分類" "理由"]
   :rows (for [{:keys [request phase result] :as run} matrix
               :let [fact (second (:audit result))]]
           [(code (:op request))
            (code phase)
            (disposition-cell (:disposition result))
            (case (:disposition result)
              :hold     (hold-class-cell (hold-class fact))
              :escalate (case (escalation-class run)
                          :both       (muted "Governor + phase")
                          :governor   (muted "Governor")
                          :phase-gate (muted "phase ゲート")
                          (span "critical" "未分類"))
              (dash))
            (cond
              (:phase-reason fact) (code (:phase-reason fact))
              (:reason fact)       (code (:reason fact))
              :else                (dash))])})

(defn- commit-register-section [state]
  (let [recs (commit-records state)]
    {:title "コミット記録レジスタ (実行が生成した書き込み)"
     :lead (str "この repo の Store は読み取り専用なので、コミットは store へ"
                "書き戻されず " (code "operation/commit-record")
                " が返す記録として観測するしかない。"
                "以下は実行が実際に返した記録そのもので、"
                (code ":path") " は " (code "[farm-id]") " である。")
     :headers ["シナリオ" "effect" "path" "value" "payload が value と一致"
               "承認者キー"]
     :rows (for [{:keys [rec] :as run} recs]
             [(esc (run-label run))
              (code (:effect rec))
              (code (pr-str (:path rec)))
              (code (kv-str (:value rec)))
              (if (= (:payload rec) (:value rec))
                (span "ok" "一致")
                (span "warn" "不一致"))
              (if (record-has-approver? rec)
                (span "ok" "あり")
                (span "warn" "無し"))])}))

(defn- approval-section [state]
  (let [m (approval-measurement state)
        no-write? (empty? (:write-methods m))
        af (:actor-field m)]
    {:title "承認と承認者の帰属 (実測)"
     :lead (str "この節は主張ではなく計測である。"
                (code "(:sigs mixedfarmops.store/Store)")
                " と実行が返した commit 記録・監査事実を render 時に走査して"
                "導出しており、書き込み経路が追加されれば文面は自動的に変わる。")
     :headers ["観測項目" "実測値"]
     :rows [["Store プロトコルのメソッド"
             (str/join ", " (map code (:store-methods m)))]
            ["うち書き込み / 承認メソッド"
             (if no-write?
               (span "critical" "0 件 — このリポジトリの Store は読み取り専用")
               (str/join ", " (map code (:write-methods m))))]
            ["commit 記録の生成数" (esc (:commit-records m))]
            [(str "うち " (code ":value") " を持つもの") (esc (:records-with-value m))]
            [(str "うち " (code ":payload") " を持つもの") (esc (:records-with-payload m))]
            [(str (code ":payload") " が " (code ":value") " と一致した記録")
             (str (esc (:payload-equals-value m)) " "
                  (muted (str "(" "commit-record は両方に同じ (:value proposal) を書く"
                              " — 記録側で payload が落ちる欠陥ではない)")))]
            ["うち承認者キーを持つもの"
             (if (zero? (:records-with-approver m))
               (span "warn" "0 件")
               (span "ok" (:records-with-approver m)))]
            ["エスカレーション (approval-requested)" (esc (:escalations m))]
            ["承認/却下の事実 (approval-granted 等)"
             (if (zero? (:approval-facts m))
               (span "critical" "0 件")
               (esc (:approval-facts m)))]
            ["承認者キーを持つ監査事実" (esc (:facts-with-approver m))]
            [(str "監査事実の " (code ":actor") " の実測値")
             (str (str/join ", " (map code (:values af)))
                  " "
                  (if (:all-equal-context? af)
                    (muted (str "— すべて context の :actor-id ("
                                (:context-actor-id af)
                                ") と一致する。つまりこれは実行アクターであって承認者ではない。"
                                "承認者として読むのは誤りなので上の承認者キー集合から除外している。"))
                    (span "warn" "— context の :actor-id と一致しない値がある。要調査。")))]
            ["導出される結論"
             (cond
               (and no-write? (zero? (:approval-facts m)))
               (str (span "critical" "承認経路が存在しない")
                    " "
                    (esc (str "この実行は " (:escalations m)
                              " 件を人間へエスカレートしたが、Store には commit も approve も resume も無く、"
                              "承認を書き戻す先が無い。したがって承認者は「記録が落ちた」のではなく「そもそも記録されない」。"
                              "コンソールが承認者を伏せているのではなく、承認という事実がまだ存在しない。"))
                    " "
                    (esc "実装の穴であって、このページの省略ではない。"))

               (and (pos? (:approval-facts m)) (zero? (:records-with-approver m)))
               (span "warn" (str "承認は記録されているが commit 記録に承認者が残っていない"
                                 " — 監査事実側と突き合わせる必要がある"))

               :else
               (span "ok" "承認者は commit 記録に保持されている"))]]}))

(defn- ledger-section [{:keys [ledger]}]
  {:title "監査台帳 (このビルドの全事実)"
   :lead (str "追記のみの決定事実ログ。" (code ":advisor-proposal")
              " は提案が採用されたかに関わらず必ず記録される — "
              "提案そのものが監査対象だからである。")
   :headers ["#" "fact" "op" "農場" "確信度" "根拠 / 概要"]
   :rows (map-indexed
          (fn [i {:keys [t op farm-id subject confidence basis
                         proposal-summary summary reason disposition]}]
            [(esc (inc i))
             (case t
               :advisor-proposal   (muted "advisor-proposal")
               :committed          (span "ok" "committed")
               :governor-hold      (span "critical" "governor-hold")
               :approval-requested (span "warn" "approval-requested")
               (esc (nm t)))
             (code op)
             (if-let [s (or farm-id subject)] (code s) (dash))
             (if confidence (esc confidence) (dash))
             (or (some-> proposal-summary esc)
                 (some-> summary esc)
                 (some->> (seq basis) (map code) (str/join ", "))
                 (some-> reason code)
                 (some-> disposition code)
                 (dash))])
          ledger)})

;; ---------------------------- invariants ----------------------------

(def ^:private required-hard-rules
  "Every hard rule `mixedfarmops.governor` can emit. The build fails
  unless the run actually exercised all of them -- this makes the page's
  HARD-hold claims a build-time invariant rather than a convention."
  #{:farm-not-registered :no-execution :equipment-pesticide-or-slaughter-blocked
    :op-not-allowed :farm-record-invalid})

(defn invariants
  "Claims the page makes, each paired with what the run measured.
  `-main` throws unless every one holds."
  [{:keys [runs matrix ledger store] :as state}]
  (let [all (concat runs matrix)
        holds (filter #(= :hold (disposition-of %)) all)
        classified (hold-classes state)
        refusals (governor-refusals state)
        gates (phase-gate-holds state)
        unclassified (remove #(contains? #{:governor-refusal :phase-gate}
                                         (second %))
                             classified)
        commits (filter #(= :commit (disposition-of %)) all)
        escalations (filter #(= :escalate (disposition-of %)) all)
        unclassified-esc (filter #(= :unclassified-escalation
                                     (escalation-class %))
                                 escalations)
        rules (fired-rules state)
        phases (into (sorted-set) (map :phase all))
        blocked-not-held (filter #(and (contains? governor/blocked-ops
                                                  (get-in % [:request :op]))
                                       (not= :hold (disposition-of %)))
                                 all)
        hard-but-committed (filter #(and (get-in % [:result :verdict :hard?])
                                         (= :commit (disposition-of %)))
                                   all)
        hard-with-record (filter #(and (get-in % [:result :verdict :hard?])
                                       (get-in % [:result :record]))
                                 all)
        multi-violation (filter #(> (count (get-in % [:result :verdict :violations])) 1)
                                all)
        registered-ok? (every? #(some? (store/registered-farm store %))
                               (sort (keys seed-farms)))]
    [{:id :hard-holds-exist
      :claim "実行が Governor のハード拒否を少なくとも 1 件生成する (phase ゲート hold は数えない)"
      :expected ">= 1"
      :measured (count refusals)
      :ok? (pos? (count refusals))}
     {:id :phase-gate-hold-exists
      :claim "phase ゲート由来の hold も少なくとも 1 件あり、分類が実データ上で分岐する"
      :expected ">= 1"
      :measured (count gates)
      :ok? (pos? (count gates))}
     {:id :hold-classification-total
      :claim "すべての HOLD が Governor 拒否か phase ゲートのどちらかに分類される"
      :expected (count holds)
      :measured (+ (count refusals) (count gates))
      :ok? (and (= (count holds) (+ (count refusals) (count gates)))
                (empty? unclassified))}
     {:id :escalation-classification-total
      :claim "すべてのエスカレーションが Governor / phase ゲート / 両方 のいずれかに分類される"
      :expected 0
      :measured (count unclassified-esc)
      :ok? (empty? unclassified-esc)}
     {:id :all-hard-rules-fired
      :claim "Governor が出しうるハード rule 5 種すべてが実際に発火する"
      :expected (str/join ", " (map name (sort required-hard-rules)))
      :measured (str/join ", " (map name rules))
      :ok? (every? rules required-hard-rules)}
     {:id :multi-violation-observed
      :claim "1 つの提案が複数の違反を積むケースが実際に観測される (違反は 1 件に丸められない)"
      :expected ">= 1"
      :measured (count multi-violation)
      :ok? (pos? (count multi-violation))}
     {:id :blocked-ops-always-hold
      :claim "恒久ブロック op はどの phase でも 1 件残らず HOLD になる"
      :expected 0
      :measured (count blocked-not-held)
      :ok? (zero? (count blocked-not-held))}
     {:id :hard-never-commits
      :claim "ハード違反のある提案は 1 件もコミットしない"
      :expected 0
      :measured (count hard-but-committed)
      :ok? (zero? (count hard-but-committed))}
     {:id :hard-writes-no-record
      :claim "ハード違反のある提案はコミット記録を一切生成しない"
      :expected 0
      :measured (count hard-with-record)
      :ok? (zero? (count hard-with-record))}
     {:id :commits-exist
      :claim "clean な提案は実際にコミットまで到達する"
      :expected ">= 1"
      :measured (count commits)
      :ok? (pos? (count commits))}
     {:id :escalations-exist
      :claim "人間へのエスカレーション経路が実際に踏まれる"
      :expected ">= 1"
      :measured (count escalations)
      :ok? (pos? (count escalations))}
     {:id :all-phases-exercised
      :claim "rollout phase 0-3 をすべて実行する"
      :expected "phase-0, phase-1, phase-2, phase-3"
      :measured (str/join ", " (map name phases))
      :ok? (every? phases [:phase-0 :phase-1 :phase-2 :phase-3])}
     {:id :probe-farm-unregistered
      :claim (str "probe id " unregistered-farm-id " は store に存在しない")
      :expected "nil"
      :measured (pr-str (store/registered-farm store unregistered-farm-id))
      :ok? (nil? (store/registered-farm store unregistered-farm-id))}
     {:id :seed-farms-registered
      :claim "seed した農場はすべて store から引ける"
      :expected (str/join ", " (sort (keys seed-farms)))
      :measured (str/join ", " (sort (keep #(:id (store/registered-farm store %))
                                           (sort (keys seed-farms)))))
      :ok? registered-ok?}
     {:id :ledger-complete
      :claim "監査台帳は 1 実行あたり 2 事実 (提案 + 処分) を持つ"
      :expected (* 2 (count all))
      :measured (count ledger)
      :ok? (= (count ledger) (* 2 (count all)))}]))

(defn- invariant-section [state]
  {:title "ビルド不変条件 (このページが満たしていること)"
   :lead (str "これらは " (code "mixedfarmops.render-html/-main")
              " が実行後に検査し、1 つでも崩れればビルドを " (code "throw")
              " で落とす (ファイルは書かれない)。"
              "ページ上の主張は慣習ではなくビルド時の不変条件である。")
   :headers ["不変条件" "期待" "実測" "判定"]
   :rows (for [{:keys [claim expected measured ok?]} (invariants state)]
           [(esc claim)
            (code expected)
            (code measured)
            (if ok? (span "ok" "OK") (span "critical" "FAIL"))])})

;; ------------------------------ render ------------------------------

(defn- sections [state]
  [(farms-section state)
   (op-gate-section state)
   (crops-section state)
   (species-section state)
   (cost-threshold-section state)
   (scenario-section state)
   (hold-classification-section state)
   (hard-hold-section state)
   (escalation-section state)
   (phase-matrix-section state)
   (commit-register-section state)
   (approval-section state)
   (ledger-section state)
   (invariant-section state)])

(defn render
  "Renders the whole console from a completed `run-demo!` state."
  [state]
  (let [secs (sections state)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-0150 &middot; mixed farming operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>複合農業 (ISIC 0150) — オペレーターコンソール</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · "
     "圃場/家畜取扱設備の直接操作・農薬散布判断の確定・と畜/淘汰は恒久ブロック · "
     "作物と家畜の健康懸念は常に人間へ</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>このページについて</h2>\n"
     "    <p>このコンソールは <code>clojure -M:dev:render-html</code> がビルド時に生成する。"
     "本文の数値・id・判定理由はすべて実アクター "
     "(<code>mixedfarmops.operation</code> → <code>mixedfarmops.advisor</code> → 独立した "
     "<code>mixedfarmops.governor</code> → <code>mixedfarmops.phase</code>) を実際に走らせた戻り値であり、"
     "手書きのサンプルは含まない。タイムスタンプ・乱数を含まないため再生成はバイト単位で同一になる。</p>\n"
     "    <p class=\"muted\">この actor は back-office の調整専用である。圃場・家畜取扱設備の直接操作、"
     "農薬散布判断の確定、と畜/淘汰の判断は農家・アグロノミスト・獣医の専権事項であり、"
     "Governor が構造的に排除する — advisor の確信度では覆せない。"
     "ISIC 0150 は作物栽培と家畜飼養のどちらも標準粗収益の 66% に達しない経営体を指すため、"
     "この actor は両方の側を 1 つの経営体として扱う。</p>\n"
     "  </section>\n"
     (str/join "" (map section secs))
     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">cloud-itonami-isic-0150 · AGPL-3.0-or-later · "
     "生成元: <code>src/mixedfarmops/render_html.clj</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        state (run-demo!)
        checks (invariants state)
        failed (remove :ok? checks)]
    (when (seq failed)
      (throw (ex-info (str "operator console build invariants failed -- refusing "
                           "to write a page whose claims the run did not produce")
                      {:failed (mapv #(select-keys % [:id :claim :expected :measured])
                                     failed)})))
    (let [html (render state)
          rules (fired-rules state)
          missing-on-page (remove #(str/includes? html (name %)) rules)]
      ;; The page must actually SHOW what the run produced -- a silent
      ;; rendering bug would otherwise pass every invariant above.
      (when (seq missing-on-page)
        (throw (ex-info "rendered page omits hard rules the run actually fired"
                        {:missing (mapv name missing-on-page)})))
      (io/make-parents out)
      (spit out html :encoding "UTF-8")
      (println "wrote" out
               (str "(" (count (:runs state)) " scenarios, "
                    (count (:matrix state)) " phase-matrix runs, "
                    (count (:ledger state)) " audit facts, "
                    (count (governor-refusals state)) " governor refusals, "
                    (count (phase-gate-holds state)) " phase-gate holds, "
                    (count rules) " hard rules fired: "
                    (str/join "," (map name rules)) ", "
                    (count checks) " invariants OK)")))))
