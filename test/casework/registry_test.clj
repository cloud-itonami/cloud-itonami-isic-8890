(ns casework.registry-test
  (:require [clojure.test :refer [deftest is]]
            [casework.registry :as r]))

;; ----------------------------- eligibility-criteria-unsatisfied? -----------------------------

(deftest satisfied-when-satisfied-is-a-superset
  (is (not (r/eligibility-criteria-unsatisfied? {:required-criteria #{"residency"} :satisfied-criteria #{"residency" "income-band"}})))
  (is (not (r/eligibility-criteria-unsatisfied? {:required-criteria #{} :satisfied-criteria #{}})))
  (is (not (r/eligibility-criteria-unsatisfied? {:required-criteria #{"residency" "income-band"} :satisfied-criteria #{"residency" "income-band"}}))))

(deftest unsatisfied-when-a-required-criterion-is-missing
  (is (r/eligibility-criteria-unsatisfied? {:required-criteria #{"residency"} :satisfied-criteria #{"income-band"}}))
  (is (r/eligibility-criteria-unsatisfied? {:required-criteria #{"residency" "income-band"} :satisfied-criteria #{"residency"}})))

(deftest unsatisfied-when-fields-missing
  (is (not (r/eligibility-criteria-unsatisfied? {}))))

;; ----------------------------- register-eligibility-determination -----------------------------

(deftest eligibility-determination-is-a-draft-not-a-real-determination
  (let [result (r/register-eligibility-determination "case-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest eligibility-determination-assigns-determination-number
  (let [result (r/register-eligibility-determination "case-1" "JPN" 7)]
    (is (= (get result "determination_number") "JPN-ELG-000007"))
    (is (= (get-in result ["record" "case_id"]) "case-1"))
    (is (= (get-in result ["record" "kind"]) "eligibility-determination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest eligibility-determination-validation-rules
  (is (thrown? Exception (r/register-eligibility-determination "" "JPN" 0)))
  (is (thrown? Exception (r/register-eligibility-determination "case-1" "" 0)))
  (is (thrown? Exception (r/register-eligibility-determination "case-1" "JPN" -1))))

(deftest eligibility-history-is-append-only
  (let [c1 (r/register-eligibility-determination "case-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-eligibility-determination "case-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-ELG-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-ELG-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-referral -----------------------------

(deftest referral-is-a-draft-not-a-real-referral
  (let [result (r/register-referral "case-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest referral-assigns-referral-number
  (let [result (r/register-referral "case-1" "JPN" 7)]
    (is (= (get result "referral_number") "JPN-REF-000007"))
    (is (= (get-in result ["record" "case_id"]) "case-1"))
    (is (= (get-in result ["record" "kind"]) "referral-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest referral-validation-rules
  (is (thrown? Exception (r/register-referral "" "JPN" 0)))
  (is (thrown? Exception (r/register-referral "case-1" "" 0)))
  (is (thrown? Exception (r/register-referral "case-1" "JPN" -1))))

(deftest referral-history-is-append-only
  (let [d1 (r/register-referral "case-1" "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-referral "case-2" "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-REF-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-REF-000001" (get-in hist2 [1 "record_id"])))))
