(ns casework.store
  "SSoT for the social-work/casework actor, behind a `Store` protocol
  so the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/casework/store_contract_test.clj), which is the whole point:
  the actor, the Social Services Governor and the audit ledger never
  know which SSoT they run on.

  Like `marketadmin.store`'s dual admission/halt-lift history,
  `registrar.store`'s dual grade/degree history, `wagering.store`'s
  dual acceptance/settlement history, `repairshop.store`'s dual
  completion/return history, `eldercare.store`'s dual care-plan/
  incident-response-finalization history, `museum.store`'s dual loan/
  deaccession history and `conservation.store`'s dual transfer/
  release history, this actor has TWO actuation events (finalizing an
  eligibility determination, finalizing a referral) acting on the SAME
  entity (a case), each with its OWN history collection, sequence
  counter and dedicated double-actuation-guard boolean
  (`:eligibility-finalized?`/`:referral-finalized?`, never a `:status`
  value) -- the same discipline `accounting.governor`'s/`marketadmin.
  governor`'s/`testlab.governor`'s/`clinic.governor`'s/`registrar.
  governor`'s/`wagering.governor`'s/`veterinary.governor`'s/`funeral.
  governor`'s/`repairshop.governor`'s/`parksafety.governor`'s/
  `eldercare.governor`'s/`museum.governor`'s/`conservation.
  governor`'s guards establish.

  The ledger stays append-only on every backend: 'which case was
  screened for an unresolved risk flag, which eligibility
  determination was finalized, which referral was finalized, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a client trusting an agency needs,
  and the evidence an operator needs if a determination or referral is
  later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [casework.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (case- [s id])
  (all-cases [s])
  (risk-screening-of [s case-id] "committed risk screening verdict for a case, or nil")
  (assessment-of [s case-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (eligibility-history [s] "the append-only eligibility-determination history (casework.registry drafts)")
  (referral-history [s] "the append-only referral history (casework.registry drafts)")
  (next-eligibility-sequence [s jurisdiction] "next eligibility-determination-number sequence for a jurisdiction")
  (next-referral-sequence [s jurisdiction] "next referral-number sequence for a jurisdiction")
  (case-already-eligibility-finalized? [s case-id] "has this case's eligibility determination already been finalized?")
  (case-already-referral-finalized? [s case-id] "has this case's referral already been finalized?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-cases [s cases] "replace/seed the case directory (map id->case)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained case set covering both actuation lifecycles
  (finalizing an eligibility determination, finalizing a referral) so
  the actor + tests run offline."
  []
  {:cases
   {"case-1" {:id "case-1" :client-name "Sakura Tanaka"
              :required-criteria #{"residency" "income-band" "household-size" "documentation"}
              :satisfied-criteria #{"residency" "income-band" "household-size" "documentation"}
              :risk-flag-resolved? true
              :eligibility-finalized? false :referral-finalized? false
              :jurisdiction "JPN" :status :intake}
    "case-2" {:id "case-2" :client-name "Atlantis Doe"
              :required-criteria #{"residency" "income-band" "household-size" "documentation"}
              :satisfied-criteria #{"residency" "income-band" "household-size" "documentation"}
              :risk-flag-resolved? true
              :eligibility-finalized? false :referral-finalized? false
              :jurisdiction "ATL" :status :intake}
    "case-3" {:id "case-3" :client-name "鈴木一郎"
              :required-criteria #{"residency" "income-band" "household-size" "documentation"}
              :satisfied-criteria #{"residency" "income-band"}
              :risk-flag-resolved? true
              :eligibility-finalized? false :referral-finalized? false
              :jurisdiction "JPN" :status :intake}
    "case-4" {:id "case-4" :client-name "田中花子"
              :required-criteria #{"residency" "income-band" "household-size" "documentation"}
              :satisfied-criteria #{"residency" "income-band" "household-size" "documentation"}
              :risk-flag-resolved? false
              :eligibility-finalized? false :referral-finalized? false
              :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-eligibility!
  "Backend-agnostic `:case/mark-eligibility-finalized` -- looks up the
  case via the protocol and drafts the eligibility-determination
  record, and returns {:result .. :case-patch ..} for the caller to
  persist."
  [s case-id]
  (let [c (case- s case-id)
        seq-n (next-eligibility-sequence s (:jurisdiction c))
        result (registry/register-eligibility-determination case-id (:jurisdiction c) seq-n)]
    {:result result
     :case-patch {:eligibility-finalized? true
                  :determination-number (get result "determination_number")}}))

(defn- finalize-referral!
  "Backend-agnostic `:case/mark-referral-finalized` -- looks up the
  case via the protocol and drafts the referral record, and returns
  {:result .. :case-patch ..} for the caller to persist."
  [s case-id]
  (let [c (case- s case-id)
        seq-n (next-referral-sequence s (:jurisdiction c))
        result (registry/register-referral case-id (:jurisdiction c) seq-n)]
    {:result result
     :case-patch {:referral-finalized? true
                  :referral-number (get result "referral_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (case- [_ id] (get-in @a [:cases id]))
  (all-cases [_] (sort-by :id (vals (:cases @a))))
  (risk-screening-of [_ id] (get-in @a [:risk-screenings id]))
  (assessment-of [_ case-id] (get-in @a [:assessments case-id]))
  (ledger [_] (:ledger @a))
  (eligibility-history [_] (:eligibility-determinations @a))
  (referral-history [_] (:referrals @a))
  (next-eligibility-sequence [_ jurisdiction] (get-in @a [:eligibility-sequences jurisdiction] 0))
  (next-referral-sequence [_ jurisdiction] (get-in @a [:referral-sequences jurisdiction] 0))
  (case-already-eligibility-finalized? [_ case-id] (boolean (get-in @a [:cases case-id :eligibility-finalized?])))
  (case-already-referral-finalized? [_ case-id] (boolean (get-in @a [:cases case-id :referral-finalized?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (swap! a update-in [:cases (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :risk-screening/set
      (swap! a assoc-in [:risk-screenings (first path)] payload)

      :case/mark-eligibility-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize-eligibility! s case-id)
            jurisdiction (:jurisdiction (case- s case-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:eligibility-sequences jurisdiction] (fnil inc 0))
                       (update-in [:cases case-id] merge case-patch)
                       (update :eligibility-determinations registry/append result))))
        result)

      :case/mark-referral-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize-referral! s case-id)
            jurisdiction (:jurisdiction (case- s case-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:referral-sequences jurisdiction] (fnil inc 0))
                       (update-in [:cases case-id] merge case-patch)
                       (update :referrals registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-cases [s cases] (when (seq cases) (swap! a assoc :cases cases)) s))

(defn seed-db
  "A MemStore seeded with the demo case set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :risk-screenings {} :ledger [] :eligibility-sequences {}
                           :eligibility-determinations [] :referral-sequences {} :referrals []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/risk-screening payloads, ledger
  facts, eligibility-determination/referral records) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses."
  {:case/id                          {:db/unique :db.unique/identity}
   :assessment/case-id               {:db/unique :db.unique/identity}
   :risk-screening/case-id           {:db/unique :db.unique/identity}
   :ledger/seq                       {:db/unique :db.unique/identity}
   :eligibility/seq                  {:db/unique :db.unique/identity}
   :referral/seq                     {:db/unique :db.unique/identity}
   :eligibility-sequence/jurisdiction {:db/unique :db.unique/identity}
   :referral-sequence/jurisdiction    {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- case->tx [{:keys [id client-name required-criteria satisfied-criteria
                        risk-flag-resolved? eligibility-finalized? referral-finalized?
                        jurisdiction status determination-number referral-number]}]
  (cond-> {:case/id id}
    client-name                        (assoc :case/client-name client-name)
    required-criteria                  (assoc :case/required-criteria (enc required-criteria))
    satisfied-criteria                 (assoc :case/satisfied-criteria (enc satisfied-criteria))
    (some? risk-flag-resolved?)        (assoc :case/risk-flag-resolved? risk-flag-resolved?)
    (some? eligibility-finalized?)     (assoc :case/eligibility-finalized? eligibility-finalized?)
    (some? referral-finalized?)        (assoc :case/referral-finalized? referral-finalized?)
    jurisdiction                       (assoc :case/jurisdiction jurisdiction)
    status                             (assoc :case/status status)
    determination-number               (assoc :case/determination-number determination-number)
    referral-number                    (assoc :case/referral-number referral-number)))

(def ^:private case-pull
  [:case/id :case/client-name :case/required-criteria :case/satisfied-criteria
   :case/risk-flag-resolved? :case/eligibility-finalized? :case/referral-finalized?
   :case/jurisdiction :case/status :case/determination-number :case/referral-number])

(defn- pull->case [m]
  (when (:case/id m)
    {:id (:case/id m) :client-name (:case/client-name m)
     :required-criteria (or (dec* (:case/required-criteria m)) #{})
     :satisfied-criteria (or (dec* (:case/satisfied-criteria m)) #{})
     :risk-flag-resolved? (boolean (:case/risk-flag-resolved? m))
     :eligibility-finalized? (boolean (:case/eligibility-finalized? m))
     :referral-finalized? (boolean (:case/referral-finalized? m))
     :jurisdiction (:case/jurisdiction m) :status (:case/status m)
     :determination-number (:case/determination-number m) :referral-number (:case/referral-number m)}))

(defrecord DatomicStore [conn]
  Store
  (case- [_ id]
    (pull->case (d/pull (d/db conn) case-pull [:case/id id])))
  (all-cases [_]
    (->> (d/q '[:find [?id ...] :where [?e :case/id ?id]] (d/db conn))
         (map #(pull->case (d/pull (d/db conn) case-pull [:case/id %])))
         (sort-by :id)))
  (risk-screening-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?cid
                :where [?k :risk-screening/case-id ?cid] [?k :risk-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ case-id]
    (dec* (d/q '[:find ?p . :in $ ?cid
                :where [?a :assessment/case-id ?cid] [?a :assessment/payload ?p]]
              (d/db conn) case-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (eligibility-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :eligibility/seq ?s] [?e :eligibility/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (referral-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :referral/seq ?s] [?e :referral/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-eligibility-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :eligibility-sequence/jurisdiction ?j] [?e :eligibility-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-referral-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :referral-sequence/jurisdiction ?j] [?e :referral-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (case-already-eligibility-finalized? [s case-id]
    (boolean (:eligibility-finalized? (case- s case-id))))
  (case-already-referral-finalized? [s case-id]
    (boolean (:referral-finalized? (case- s case-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (d/transact! conn [(case->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/case-id (first path) :assessment/payload (enc payload)}])

      :risk-screening/set
      (d/transact! conn [{:risk-screening/case-id (first path) :risk-screening/payload (enc payload)}])

      :case/mark-eligibility-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize-eligibility! s case-id)
            jurisdiction (:jurisdiction (case- s case-id))
            next-n (inc (next-eligibility-sequence s jurisdiction))]
        (d/transact! conn
                     [(case->tx (assoc case-patch :id case-id))
                      {:eligibility-sequence/jurisdiction jurisdiction :eligibility-sequence/next next-n}
                      {:eligibility/seq (count (eligibility-history s)) :eligibility/record (enc (get result "record"))}])
        result)

      :case/mark-referral-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize-referral! s case-id)
            jurisdiction (:jurisdiction (case- s case-id))
            next-n (inc (next-referral-sequence s jurisdiction))]
        (d/transact! conn
                     [(case->tx (assoc case-patch :id case-id))
                      {:referral-sequence/jurisdiction jurisdiction :referral-sequence/next next-n}
                      {:referral/seq (count (referral-history s)) :referral/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-cases [s cases]
    (when (seq cases) (d/transact! conn (mapv case->tx (vals cases)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:cases ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [cases]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-cases s cases))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo case set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
