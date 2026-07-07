(ns casework.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:eligibility/finalize`/`:referral/finalize` must NEVER
  be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [casework.phase :as phase]))

(deftest eligibility-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real eligibility determination"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :eligibility/finalize))
          (str "phase " n " must not auto-commit :eligibility/finalize")))))

(deftest referral-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-finalizes a real referral"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :referral/finalize))
          (str "phase " n " must not auto-commit :referral/finalize")))))

(deftest risk-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety-test/inspection/incident-flag/welfare-flag/allergy-flag/rights-clearance screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :risk/screen))
          (str "phase " n " must not auto-commit :risk/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":case/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:case/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :case/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :eligibility/finalize} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :referral/finalize} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :case/intake} :commit)))))
