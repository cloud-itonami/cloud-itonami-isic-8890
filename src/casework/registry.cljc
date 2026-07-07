(ns casework.registry
  "Pure-function eligibility-determination + referral record
  construction -- an append-only social-services book-of-record
  draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for an eligibility-determination
  or referral reference number -- every agency/jurisdiction assigns
  its own reference format. This namespace does NOT invent one; it
  builds a jurisdiction-scoped sequence number and validates the
  record's required fields, the same honest, non-fabricating
  discipline `casework.facts` uses.

  `eligibility-criteria-unsatisfied?` reuses `registrar.registry/
  prerequisites-satisfied?`'s SET-CONTAINMENT/subset shape (the FIRST
  check in this fleet to be a universal 'is every member of a
  required set also a member of a satisfied set' test, generalizing
  `clinic.registry/treatment-contraindicated?`'s existential set-
  MEMBERSHIP shape) for a SECOND domain: a case's own required
  eligibility criteria (e.g. residency, income-band, household-size,
  documentation) must ALL appear in its own recorded satisfied-
  criteria set before an eligibility determination can be finalized.
  See its own docstring for the honest simplification this makes vs. a
  full means-testing/benefits-calculation engine.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real case-management system. It builds the RECORD an
  agency would keep, not the act of finalizing the eligibility
  determination or referral itself (that is `casework.operation`'s
  `:eligibility/finalize`/`:referral/finalize`, always human-gated --
  see README `Actuation`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  agency's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn eligibility-criteria-unsatisfied?
  "Does `case-record`'s own `:required-criteria` set contain any item
  NOT present in its own `:satisfied-criteria` set? A pure ground-
  truth check against the case's own permanent fields -- see ns
  docstring for the honest simplification this makes vs. a full means-
  testing/benefits-calculation engine (this R0 does not model partial
  credit, waivers, or benefit-amount calculation -- only whether the
  literal required-criteria labels are all present in the satisfied-
  criteria set)."
  [{:keys [required-criteria satisfied-criteria]}]
  (not (set/subset? (set required-criteria) (set satisfied-criteria))))

(defn register-eligibility-determination
  "Validate + construct the ELIGIBILITY-DETERMINATION registration
  DRAFT -- the agency's own legal act of finalizing a real client's
  benefit-program eligibility. Pure function -- does not touch any
  real case-management system; it builds the RECORD an agency would
  keep. `casework.governor` independently re-verifies the case's own
  eligibility-criteria sufficiency and risk-flag status, and blocks a
  double-finalization of the same case's eligibility determination,
  before this is ever allowed to commit."
  [case-id jurisdiction sequence]
  (when-not (and case-id (not= case-id ""))
    (throw (ex-info "eligibility-determination: case_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "eligibility-determination: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "eligibility-determination: sequence must be >= 0" {})))
  (let [determination-number (str (str/upper-case jurisdiction) "-ELG-" (zero-pad sequence 6))
        record {"record_id" determination-number
                "kind" "eligibility-determination-draft"
                "case_id" case-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "determination_number" determination-number
     "certificate" (unsigned-certificate "EligibilityDetermination" determination-number determination-number)}))

(defn register-referral
  "Validate + construct the REFERRAL registration DRAFT -- the
  agency's own legal act of finalizing a real client's referral to a
  partner service/agency. Pure function -- does not touch any real
  case-management system; it builds the RECORD an agency would keep.
  `casework.governor` independently re-verifies the case's own risk-
  flag status, and blocks a double-finalization of the same case's
  referral, before this is ever allowed to commit."
  [case-id jurisdiction sequence]
  (when-not (and case-id (not= case-id ""))
    (throw (ex-info "referral: case_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "referral: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "referral: sequence must be >= 0" {})))
  (let [referral-number (str (str/upper-case jurisdiction) "-REF-" (zero-pad sequence 6))
        record {"record_id" referral-number
                "kind" "referral-draft"
                "case_id" case-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "referral_number" referral-number
     "certificate" (unsigned-certificate "Referral" referral-number referral-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
