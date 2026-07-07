(ns casework.governor-contract-test
  "The governor contract as executable tests -- the social-work/
  casework analog of `cloud-itonami-isic-6512`'s `casualty.governor-
  contract-test`. The single invariant under test:

    CaseworkOps-LLM never finalizes an eligibility determination or
    referral the Social Services Governor would reject, `:eligibility/
    finalize`/`:referral/finalize` NEVER auto-commit at any phase,
    `:case/intake` (no direct capital risk) MAY auto-commit when
    clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [casework.store :as store]
            [casework.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :caseworker :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through risk screening -> approve, leaving a
  screening on file. Only safe to call for a case whose risk flag is
  already resolved -- an unresolved flag HARD-holds the screen itself
  (see `risk-flag-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :risk/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :case/intake :subject "case-1"
                   :patch {:id "case-1" :client-name "Sakura Tanaka"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Tanaka" (:client-name (store/case- db "case-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "case-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "case-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "case-1")) "no assessment written"))))

(deftest eligibility-finalize-without-assessment-is-held
  (testing "eligibility/finalize before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :eligibility/finalize :subject "case-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest eligibility-criteria-unsatisfied-is-held
  (testing "a case whose satisfied-criteria set is missing a required criterion -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "case-3")
          res (exec-op actor "t5" {:op :eligibility/finalize :subject "case-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:eligibility-criteria-unsatisfied} (-> (store/ledger db) last :basis)))
      (is (empty? (store/eligibility-history db))))))

(deftest risk-flag-unresolved-is-held-and-unoverridable
  (testing "an unresolved risk flag on a case -> HOLD, and never reaches request-approval -- exercised via :risk/screen DIRECTLY, not via an actuation op against an unscreened case (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's and salon's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :risk/screen :subject "case-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:risk-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/risk-screening-of db "case-4")) "no clearance written"))))

(deftest eligibility-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, all-criteria-satisfied, risk-clear case still ALWAYS interrupts for human approval -- actuation/finalize-eligibility is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "case-1")
          _ (screen! actor "t7pre2" "case-1")
          r1 (exec-op actor "t7" {:op :eligibility/finalize :subject "case-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, determination record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:eligibility-finalized? (store/case- db "case-1"))))
          (is (= 1 (count (store/eligibility-history db))) "one draft determination record"))))))

(deftest referral-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, risk-clear case still ALWAYS interrupts for human approval -- actuation/finalize-referral is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "case-1")
          _ (screen! actor "t8pre2" "case-1")
          r1 (exec-op actor "t8" {:op :referral/finalize :subject "case-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, referral record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:referral-finalized? (store/case- db "case-1"))))
          (is (= 1 (count (store/referral-history db))) "one draft referral record"))))))

(deftest eligibility-finalize-double-finalization-is-held
  (testing "finalizing the same case's eligibility determination twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "case-1")
          _ (screen! actor "t9pre2" "case-1")
          _ (exec-op actor "t9a" {:op :eligibility/finalize :subject "case-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :eligibility/finalize :subject "case-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-eligibility-finalized} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/eligibility-history db))) "still only the one earlier finalization"))))

(deftest referral-finalize-double-finalization-is-held
  (testing "finalizing the same case's referral twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "case-1")
          _ (screen! actor "t10pre2" "case-1")
          _ (exec-op actor "t10a" {:op :referral/finalize :subject "case-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :referral/finalize :subject "case-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-referral-finalized} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/referral-history db))) "still only the one earlier finalization"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :case/intake :subject "case-1"
                          :patch {:id "case-1" :client-name "Sakura Tanaka"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "case-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
