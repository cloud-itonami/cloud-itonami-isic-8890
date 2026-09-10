(ns casework.phase
  "Phase 0->3 staged rollout -- the social-work/casework analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- case intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + risk
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:case/intake` (no capital risk yet)
                                 may auto-commit. `:eligibility/
                                 finalize`/`:referral/finalize` NEVER
                                 auto-commit, at any phase.

  `:eligibility/finalize`/`:referral/finalize` are deliberately ABSENT
  from every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Finalizing a
  real eligibility determination and finalizing a real referral are
  the two real-world legal acts this actor performs; both are always a
  human caseworker's call. `casework.governor`'s `:actuation/finalize-
  eligibility`/`:actuation/finalize-referral` high-stakes gate
  enforces the same invariant independently -- two layers, not one,
  agree on this. `:risk/screen` is likewise never auto-eligible, at
  any phase -- the same posture every sibling's KYC/conflict/
  independence/surveillance/calibration/credential/integrity/patron/
  authorization/safety-test/inspection/incident-flag/welfare-flag/
  allergy-flag/rights-clearance screening op has. Like `credit.
  phase`/`accounting.phase`/`marketadmin.phase`/`testlab.phase`/
  `clinic.phase`/`registrar.phase`/`wagering.phase`/`veterinary.
  phase`/`funeral.phase`/`repairshop.phase`/`parksafety.phase`/
  `eldercare.phase`/`museum.phase`/`conservation.phase`, phase 3's
  `:auto` set here has only ONE member (`:case/intake`) -- this domain
  has no separate no-capital-risk 'file' lifecycle distinct from the
  case record itself.")

(def read-ops  #{})
(def write-ops #{:case/intake :jurisdiction/assess :risk/screen
                 :eligibility/finalize :referral/finalize})

;; NOTE the invariant: `:eligibility/finalize`/`:referral/finalize`
;; are members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:case/intake}                                        :auto #{}}
   2 {:label "assisted-assess" :writes #{:case/intake :jurisdiction/assess :risk/screen}       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:case/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:eligibility/finalize`/`:referral/finalize` are never auto-
    eligible at any phase, so they always escalate once the governor
    clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Social Services Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
