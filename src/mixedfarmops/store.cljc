(ns mixedfarmops.store
  "Store abstraction for mixed-farming farm/crop/herd records. Current
  implementation is an in-memory map; production should migrate to
  Datomic/kotoba-server (the same seam point all cloud-itonami actors
  use). Mirrors `cattleops.store` (cloud-itonami-isic-0141) /
  `cerealops.store` (cloud-itonami-isic-0111) in shape.

  A registered farm is the minimal unit of authority: a mixed-farming
  operation (crop fields AND herd, combined) must be registered before
  ANY proposal referencing it can be considered by the Governor (see
  `mixedfarmops.governor`'s `farm-not-registered` invariant). Farm data is
  opaque to this namespace -- callers/backends decide what a farm record
  contains (name, location, crop fields, herd roster, etc); this Store
  only answers \"is this farm-id registered, and if so what's on file\".")

;; Protocol for swappable store implementations
(defprotocol Store
  (registered-farm [store farm-id]
    "Retrieve a registered farm record by ID. Returns nil if the farm-id
    is nil or not registered."))

;; In-memory implementation (MemStore) for development/testing
(defrecord MemStore [farms]
  Store
  (registered-farm [_store farm-id]
    (when farm-id
      (get @farms farm-id))))

(defn mem-store
  "Create an in-memory store. `initial-farms` is an optional map of
  farm-id -> farm-record."
  [& [{:keys [initial-farms] :or {initial-farms {}}}]]
  (MemStore. (atom initial-farms)))

(defn add-farm
  "Register or update a farm in the store. Used by tests and simulation."
  [^MemStore store farm-id farm-data]
  (swap! (:farms store) assoc farm-id farm-data)
  farm-data)
