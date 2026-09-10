(ns casework.facts
  "Per-jurisdiction social-work/welfare-eligibility regulatory catalog
  -- the G2-style spec-basis table the Social Services Governor checks
  every jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's welfare-eligibility/
  social-work requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official social-
  welfare/social-work regulator (see `:provenance`); they are a
  STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.

  `:required-eligibility-criteria` mirrors the generic residency/
  income-band/household-size/documentation criteria set a benefit
  program commonly requires -- the ground truth `casework.registry/
  eligibility-criteria-unsatisfied?` independently re-verifies against
  a case's own recorded satisfied-criteria set.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  identity-verification/income-documentation/household-composition/
  needs-assessment evidence set submitted in some form;
  `:required-eligibility-criteria` is the eligibility-criteria set
  `casework.registry/eligibility-criteria-unsatisfied?` checks against;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare)"
          :legal-basis "社会福祉法 (Social Welfare Act)"
          :national-spec "生活保護・福祉サービス受給資格認定基準"
          :provenance "https://www.mhlw.go.jp/"
          :required-evidence ["本人確認書類 (identity verification)"
                              "所得/資産申告書類 (income/means documentation)"
                              "世帯構成記録 (household composition record)"
                              "ニーズアセスメント記録 (needs-assessment record)"]
          :required-eligibility-criteria #{"residency" "income-band" "household-size" "documentation"}}
   "USA" {:name "United States"
          :owner-authority "U.S. Department of Health and Human Services, Administration for Children and Families (ACF)"
          :legal-basis "Social Security Act, Title IV-A (TANF) + Title XX (Social Services Block Grant)"
          :national-spec "Welfare/benefit-eligibility casework and needs-assessment requirements"
          :provenance "https://www.acf.hhs.gov/"
          :required-evidence ["Identity verification"
                              "Income/means documentation"
                              "Household composition record"
                              "Needs-assessment record"]
          :required-eligibility-criteria #{"residency" "income-band" "household-size" "documentation"}}
   "GBR" {:name "United Kingdom"
          :owner-authority "Social Work England"
          :legal-basis "Children and Social Work Act 2017"
          :national-spec "Social work practice and casework-conduct standards"
          :provenance "https://www.socialworkengland.org.uk/"
          :required-evidence ["Identity verification"
                              "Income/means documentation"
                              "Household composition record"
                              "Needs-assessment record"]
          :required-eligibility-criteria #{"residency" "income-band" "household-size" "documentation"}}
   "DEU" {:name "Germany"
          :owner-authority "Bundesagentur für Arbeit (Federal Employment Agency)"
          :legal-basis "Sozialgesetzbuch II (SGB II, Bürgergeld)"
          :national-spec "Leistungsberechtigung und Bedarfsermittlung"
          :provenance "https://www.arbeitsagentur.de/"
          :required-evidence ["Identitätsnachweis (identity verification)"
                              "Einkommens-/Vermögensnachweis (income/means documentation)"
                              "Haushaltszusammensetzungsnachweis (household composition record)"
                              "Bedarfsermittlungsprotokoll (needs-assessment record)"]
          :required-eligibility-criteria #{"residency" "income-band" "household-size" "documentation"}}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize an
  eligibility determination or referral on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8890 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `casework.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn required-eligibility-criteria [iso3]
  (:required-eligibility-criteria (spec-basis iso3) #{}))
