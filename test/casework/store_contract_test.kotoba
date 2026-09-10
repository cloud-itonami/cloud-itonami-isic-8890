(ns casework.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [casework.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tanaka" (:client-name (store/case- s "case-1"))))
      (is (= "JPN" (:jurisdiction (store/case- s "case-1"))))
      (is (= #{"residency" "income-band" "household-size" "documentation"} (:required-criteria (store/case- s "case-1"))))
      (is (= #{"residency" "income-band" "household-size" "documentation"} (:satisfied-criteria (store/case- s "case-1"))))
      (is (true? (:risk-flag-resolved? (store/case- s "case-1"))))
      (is (= #{"residency" "income-band"} (:satisfied-criteria (store/case- s "case-3"))))
      (is (false? (:risk-flag-resolved? (store/case- s "case-4"))))
      (is (false? (:eligibility-finalized? (store/case- s "case-1"))))
      (is (false? (:referral-finalized? (store/case- s "case-1"))))
      (is (= ["case-1" "case-2" "case-3" "case-4"]
             (mapv :id (store/all-cases s))))
      (is (nil? (store/risk-screening-of s "case-1")))
      (is (nil? (store/assessment-of s "case-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/eligibility-history s)))
      (is (= [] (store/referral-history s)))
      (is (zero? (store/next-eligibility-sequence s "JPN")))
      (is (zero? (store/next-referral-sequence s "JPN")))
      (is (false? (store/case-already-eligibility-finalized? s "case-1")))
      (is (false? (store/case-already-referral-finalized? s "case-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :case/upsert
                                 :value {:id "case-1" :client-name "Sakura Tanaka"}})
        (is (= "Sakura Tanaka" (:client-name (store/case- s "case-1"))))
        (is (= #{"residency" "income-band" "household-size" "documentation"}
               (:required-criteria (store/case- s "case-1"))) "unrelated field preserved"))
      (testing "assessment / risk-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["case-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "case-1")))
        (store/commit-record! s {:effect :risk-screening/set :path ["case-1"]
                                 :payload {:case-id "case-1" :verdict :resolved}})
        (is (= {:case-id "case-1" :verdict :resolved} (store/risk-screening-of s "case-1"))))
      (testing "eligibility finalization drafts a determination record and advances the sequence"
        (store/commit-record! s {:effect :case/mark-eligibility-finalized :path ["case-1"]})
        (is (= "JPN-ELG-000000" (get (first (store/eligibility-history s)) "record_id")))
        (is (= "eligibility-determination-draft" (get (first (store/eligibility-history s)) "kind")))
        (is (true? (:eligibility-finalized? (store/case- s "case-1"))))
        (is (= 1 (count (store/eligibility-history s))))
        (is (= 1 (store/next-eligibility-sequence s "JPN")))
        (is (true? (store/case-already-eligibility-finalized? s "case-1")))
        (is (false? (store/case-already-eligibility-finalized? s "case-2"))))
      (testing "referral finalization drafts a referral record and advances the sequence"
        (store/commit-record! s {:effect :case/mark-referral-finalized :path ["case-1"]})
        (is (= "JPN-REF-000000" (get (first (store/referral-history s)) "record_id")))
        (is (= "referral-draft" (get (first (store/referral-history s)) "kind")))
        (is (true? (:referral-finalized? (store/case- s "case-1"))))
        (is (= 1 (count (store/referral-history s))))
        (is (= 1 (store/next-referral-sequence s "JPN")))
        (is (true? (store/case-already-referral-finalized? s "case-1")))
        (is (false? (store/case-already-referral-finalized? s "case-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/case- s "nope")))
    (is (= [] (store/all-cases s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/eligibility-history s)))
    (is (= [] (store/referral-history s)))
    (is (zero? (store/next-eligibility-sequence s "JPN")))
    (is (zero? (store/next-referral-sequence s "JPN")))
    (store/with-cases s {"x" {:id "x" :client-name "n" :required-criteria #{"residency"}
                              :satisfied-criteria #{"residency"} :risk-flag-resolved? true
                              :eligibility-finalized? false :referral-finalized? false
                              :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:client-name (store/case- s "x"))))))
