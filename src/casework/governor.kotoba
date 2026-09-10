(ns casework.governor
  "Social Services Governor -- the independent compliance layer that
  earns the CaseworkOps-LLM the right to commit. The LLM has no notion
  of jurisdictional welfare-eligibility/social-work law, whether a
  case's own required eligibility criteria are all actually satisfied,
  whether a case's own risk (fraud/misrepresentation) flag is still
  unresolved, or when an act stops being a draft and becomes a real-
  world eligibility determination or referral, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the social-work/casework analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete casework evidence, an
  unsatisfied eligibility criterion, an unresolved risk flag, or a
  double finalization). The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `casework.phase`: for `:stake :actuation/finalize-
  eligibility`/`:actuation/finalize-referral` (a real eligibility
  determination or referral) NO phase ever allows auto-commit either.
  Two independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`casework.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:eligibility/finalize`/
                                       `:referral/finalize`, has the
                                       jurisdiction actually been
                                       assessed with a full casework-
                                       evidence checklist on file?
    3. Eligibility criteria
       unsatisfied                   -- for `:eligibility/finalize`,
                                       INDEPENDENTLY recompute whether
                                       the case's own `:required-
                                       criteria` set is fully contained
                                       in its own `:satisfied-criteria`
                                       set (`casework.registry/
                                       eligibility-criteria-
                                       unsatisfied?`) -- needs no
                                       proposal inspection or stored-
                                       verdict lookup at all. Reuses
                                       `registrar.governor/
                                       prerequisites-not-satisfied-
                                       violations`'s SET-CONTAINMENT/
                                       subset shape for a SECOND
                                       domain.
    4. Risk flag unresolved        -- for `:eligibility/finalize`/
                                       `:referral/finalize`, reported
                                       by THIS proposal itself (a
                                       `:risk/screen` that just found
                                       an unresolved flag), or already
                                       on file for the case (`:risk/
                                       screen`/either actuation op).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       `marketadmin.governor/
                                       surveillance-flag-unresolved-
                                       violations`/`testlab.governor/
                                       calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations`/`parksafety.
                                       governor/inspection-not-passed-
                                       violations`/`eldercare.governor/
                                       incident-flag-unresolved-
                                       violations`/`museum.governor/
                                       incident-flag-unresolved-
                                       violations`/`conservation.
                                       governor/welfare-flag-
                                       unresolved-violations`/`salon.
                                       governor/allergy-flag-
                                       unresolved-violations`/
                                       `entertainment.governor/rights-
                                       clearance-unresolved-
                                       violations` established -- the
                                       FIFTEENTH distinct application
                                       of this exact discipline. Like
                                       the twelve most recent siblings'
                                       equivalent checks, this is
                                       exercised in tests/demo via
                                       `:risk/screen` DIRECTLY, not via
                                       an actuation op against an
                                       unscreened case -- see this ns's
                                       own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:eligibility/
                                       finalize`/`:referral/finalize`
                                       (REAL acts) -> escalate.

  Two more guards, double-finalization prevention, are enforced but
  NOT listed as numbered HARD checks above because they need no
  upstream comparison at all -- `already-eligibility-finalized-
  violations`/`already-referral-finalized-violations` refuse to
  finalize the SAME case's eligibility determination/referral twice,
  off dedicated `:eligibility-finalized?`/`:referral-finalized?` facts
  (never a `:status` value) -- the SAME 'check a dedicated boolean,
  not status' discipline `accounting.governor`'s/`marketadmin.
  governor`'s/`testlab.governor`'s/`clinic.governor`'s/`registrar.
  governor`'s/`wagering.governor`'s/`veterinary.governor`'s/`funeral.
  governor`'s/`repairshop.governor`'s/`parksafety.governor`'s/
  `eldercare.governor`'s/`museum.governor`'s/`conservation.
  governor`'s/`salon.governor`'s guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [casework.facts :as facts]
            [casework.registry :as registry]
            [casework.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Finalizing a real eligibility determination and finalizing a real
  referral are the two real-world actuation events this actor
  performs -- a two-member set, matching `cloud-itonami-isic-6512`'s/
  `6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/
  `9200`'s/`9521`'s/`8730`'s/`9102`'s/`9103`'s dual-actuation shape."
  #{:actuation/finalize-eligibility :actuation/finalize-referral})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:eligibility/finalize`/`:referral/
  finalize`) proposal with no spec-basis citation is a HARD violation
  -- never invent a jurisdiction's welfare-eligibility/social-work
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :eligibility/finalize :referral/finalize} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:eligibility/finalize`/`:referral/finalize`, the jurisdiction's
  required identity/income/household/needs-assessment evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:eligibility/finalize :referral/finalize} op)
    (let [c (store/case- st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction c) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(本人確認書類/所得申告書類/世帯構成記録等)が充足していない状態での提案"}]))))

(defn- eligibility-criteria-unsatisfied-violations
  "For `:eligibility/finalize`, INDEPENDENTLY recompute whether the
  case's own required-criteria set is fully satisfied via `casework.
  registry/eligibility-criteria-unsatisfied?` -- needs no proposal
  inspection or stored-verdict lookup at all, since its inputs are
  permanent ground-truth fields already on the case."
  [{:keys [op subject]} st]
  (when (= op :eligibility/finalize)
    (let [c (store/case- st subject)]
      (when (registry/eligibility-criteria-unsatisfied? c)
        [{:rule :eligibility-criteria-unsatisfied
          :detail (str subject " の必須基準" (:required-criteria c)
                      "のうち充足基準" (:satisfied-criteria c) "に含まれない項目がある")}]))))

(defn- risk-flag-unresolved-violations
  "An unresolved risk (fraud/misrepresentation) flag -- reported by
  THIS proposal (e.g. a `:risk/screen` that itself just found an
  unresolved flag), or already on file in the store for the case
  (`:risk/screen`/either actuation op) -- is a HARD, un-overridable
  hold. Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        case-id (when (contains? #{:risk/screen :eligibility/finalize :referral/finalize} op) subject)
        hit-on-file? (and case-id (= :unresolved (:verdict (store/risk-screening-of st case-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :risk-flag-unresolved
        :detail "未解決のリスク(不正/申告内容相違)フラグが残っているケースに対する提案は進められない"}])))

(defn- already-eligibility-finalized-violations
  "For `:eligibility/finalize`, refuses to finalize the SAME case's
  eligibility determination twice, off a dedicated `:eligibility-
  finalized?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :eligibility/finalize)
    (when (store/case-already-eligibility-finalized? st subject)
      [{:rule :already-eligibility-finalized
        :detail (str subject " は既に受給資格認定確定済み")}])))

(defn- already-referral-finalized-violations
  "For `:referral/finalize`, refuses to finalize the SAME case's
  referral twice, off a dedicated `:referral-finalized?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :referral/finalize)
    (when (store/case-already-referral-finalized? st subject)
      [{:rule :already-referral-finalized
        :detail (str subject " は既に紹介確定済み")}])))

(defn check
  "Censors a CaseworkOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (eligibility-criteria-unsatisfied-violations request st)
                           (risk-flag-unresolved-violations request proposal st)
                           (already-eligibility-finalized-violations request st)
                           (already-referral-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
