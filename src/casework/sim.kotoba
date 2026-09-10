(ns casework.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean case through
  intake -> jurisdiction assessment -> risk screening -> eligibility-
  determination proposal (always escalates) -> human approval ->
  commit, then through referral proposal (always escalates) -> human
  approval -> commit, then shows five HARD holds (a jurisdiction with
  no spec-basis, an unsatisfied eligibility criterion, an unresolved
  risk flag screened directly via `:risk/screen` [never via an
  actuation op against an unscreened case -- see this actor's own
  governor ns docstring / the lesson `parksafety`'s ADR-2607071922
  Decision 5, `eldercare`'s, `museum`'s, `conservation`'s and
  `salon`'s ADR-0001s already recorded], and a double finalization of
  each actuation op) that never reach a human at all, and prints the
  audit ledger + the draft eligibility-determination and referral
  records."
  (:require [langgraph.graph :as g]
            [casework.store :as store]
            [casework.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :caseworker :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== case/intake case-1 (JPN, clean; all eligibility criteria satisfied, risk-flag resolved) ==")
    (println (exec! actor "t1" {:op :case/intake :subject "case-1"
                                :patch {:id "case-1" :client-name "Sakura Tanaka"}} operator))

    (println "== jurisdiction/assess case-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator))
    (println (approve! actor "t2"))

    (println "== risk/screen case-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :risk/screen :subject "case-1"} operator))
    (println (approve! actor "t3"))

    (println "== eligibility/finalize case-1 (always escalates -- actuation/finalize-eligibility) ==")
    (let [r (exec! actor "t4" {:op :eligibility/finalize :subject "case-1"} operator)]
      (println r)
      (println "-- human caseworker approves --")
      (println (approve! actor "t4")))

    (println "== referral/finalize case-1 (always escalates -- actuation/finalize-referral) ==")
    (let [r (exec! actor "t5" {:op :referral/finalize :subject "case-1"} operator)]
      (println r)
      (println "-- human caseworker approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess case-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "case-2" :no-spec? true} operator))

    (println "== jurisdiction/assess case-3 (escalates -- human approves; sets up the unsatisfied-criteria test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "case-3"} operator))
    (println (approve! actor "t7"))

    (println "== eligibility/finalize case-3 (missing household-size/documentation criteria -> HARD hold) ==")
    (println (exec! actor "t8" {:op :eligibility/finalize :subject "case-3"} operator))

    (println "== risk/screen case-4 (unresolved risk flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :risk/screen :subject "case-4"} operator))

    (println "== eligibility/finalize case-1 AGAIN (double-finalization -> HARD hold) ==")
    (println (exec! actor "t10" {:op :eligibility/finalize :subject "case-1"} operator))

    (println "== referral/finalize case-1 AGAIN (double-finalization -> HARD hold) ==")
    (println (exec! actor "t11" {:op :referral/finalize :subject "case-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft eligibility-determination records ==")
    (doseq [r (store/eligibility-history db)] (println r))

    (println "== draft referral records ==")
    (doseq [r (store/referral-history db)] (println r))))
