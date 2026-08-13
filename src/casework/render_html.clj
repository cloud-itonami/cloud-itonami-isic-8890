(ns casework.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo had NO demo page and no generator at all. This namespace
  drives the REAL actor stack (`casework.operation` ->
  `casework.governor` -> `casework.store`, compiled as a langgraph-clj
  StateGraph and resumed through the real
  `interrupt-before #{:request-approval}` human handoff) over the real
  seeded cases `case-1`..`case-4` of `casework.store/demo-data`.

  Every row on the page is real actor output:

    - the case directory is `store/all-cases` AFTER the run, so the
      lifecycle columns are the actor's own committed writes;
    - the scenario log is each run's own `:proposal`/`:verdict`/
      `:disposition` channels, read straight off the returned state;
    - the hold tables are the governor's own `:violations` vectors;
    - the draft determination/referral records are
      `store/eligibility-history`/`store/referral-history`, i.e. what
      `casework.registry` actually built;
    - the audit ledger is `store/ledger` verbatim.

  Nothing is timestamped and no per-run identifier is printed, so the
  output is byte-identical across reruns against the same seed (verify
  by rendering into two scratch files and diffing).

  THREE KINDS OF REFUSAL, RENDERED SEPARATELY — and the difference is
  NOT `:violations`:

    1. `:t :governor-hold` with a NON-EMPTY `:violations` vector — the
       Social Services Governor genuinely refused. Un-overridable.
    2. `:t :governor-hold` with an EMPTY `:violations` vector and a
       `:phase-reason` — the governor CLEARED the proposal and the
       staged-rollout gate (`casework.phase`) refused the write anyway
       because the op is not enabled at that phase. Not a governor
       refusal.
    3. `:t :approval-rejected` — a human approver declined. MEASURED on
       this repo: `casework.operation`'s `:request-approval` node builds
       this fact via `governor/hold-fact` with a SYNTHETIC
       `[{:rule :approver-rejected}]` violation, so it carries a
       non-empty `:violations` vector while the governor itself was
       clean.

  (3) is why classification here keys on the FACT TYPE first and only
  then on `:violations`. A classifier that split on `:violations` alone
  would silently promote every human rejection into the governor-refusal
  count, and the page would claim refusals the censor never made."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [casework.facts :as facts]
            [casework.phase :as phase]
            [casework.governor :as governor]
            [casework.store :as store]
            [casework.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "Phase 3 (supervised-auto) caseworker -- the demo operator. This is
  the EXECUTING actor; it is not the approver."
  {:actor-id "op-1" :actor-role :caseworker :phase 3})

(def ^:private early-phase-operator
  "The SAME executing actor at phase 1 (assisted-intake). Used once, to
  show the rollout-phase gate holding an op the governor itself
  cleared -- a structurally different hold from a governor refusal."
  {:actor-id "op-1" :actor-role :caseworker :phase 1})

(def ^:private approver
  "The human who resumes the graph at `:request-approval`.

  Deliberately DIFFERENT from `operator`'s `:actor-id` (\"op-1\").
  `casework.operation/commit-fact` stamps each ledger fact with
  `:actor`, meaning the actor that EXECUTED the op -- not the human who
  approved it. Had the demo approved as \"op-1\" too, a renderer that
  wrongly read `:actor` as the approver would print exactly the same
  page as one that read it correctly, and the error would be invisible.
  With two distinct ids the two readings disagree on every row."
  "sv-2")

;; ----------------------------- the run -----------------------------

(defn- step-of
  "One scenario row, read straight off a finished run's own channels."
  [tid request ctx run]
  (let [{:keys [proposal verdict disposition]} (:state run)]
    {:tid tid
     :op (:op request)
     :subject (:subject request)
     :phase (:phase ctx)
     :stake (:stake proposal)
     :confidence (:confidence verdict)
     :hard? (boolean (:hard? verdict))
     :escalate? (boolean (:escalate? verdict))
     :high-stakes? (boolean (:high-stakes? verdict))
     :violations (vec (:violations verdict))
     :disposition disposition
     :decision nil}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every
  disposition this actor can reach.

  case-1 (Sakura Tanaka, JPN, all four eligibility criteria satisfied,
  risk flag resolved) clears a full lifecycle: intake (auto-commits
  clean at phase 3 -- directory normalization carries no benefit
  entitlement yet), a jurisdiction assessment (phase-gated, approved), a
  risk screening (approved), an ELIGIBILITY DETERMINATION (always
  escalates -- `:actuation/finalize-eligibility` is permanently
  high-stakes, never auto at any phase -- approved) and a REFERRAL
  (always escalates -- `:actuation/finalize-referral` -- approved).

  Five HARD governor holds, none of which ever reaches a human:
  case-2's jurisdiction assessment cites no official spec-basis for its
  (deliberately unregistered) jurisdiction ATL; case-3 clears its own
  assessment but then HARD-holds an eligibility determination because
  the governor INDEPENDENTLY recomputes its required-criteria set as not
  contained in its satisfied-criteria set; case-4's risk screening
  HARD-holds on its own finding of an unresolved risk flag; case-1 is
  then refused a SECOND eligibility determination and a SECOND referral
  off the dedicated `:eligibility-finalized?`/`:referral-finalized?`
  facts.

  One HUMAN REJECTION: case-4's jurisdiction assessment is clean, so it
  escalates to a human -- who declines. This is the fact that carries a
  synthetic `:violations` entry without the governor having objected.

  One ROLLOUT-PHASE hold: the same clean jurisdiction assessment that
  succeeded at phase 3 is replayed at phase 1, where
  `:jurisdiction/assess` is not yet an enabled write. The governor
  clears it and the phase gate holds it anyway, producing a hold fact
  with EMPTY `:violations`.

  Returns {:db .. :steps .. :grants ..} -- every field read by `render`
  is real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        steps (atom [])
        grants (atom [])
        exec! (fn exec!
                ([tid request] (exec! tid request operator))
                ([tid request ctx]
                 (let [run (g/run* actor {:request request :context ctx}
                                   {:thread-id tid})]
                   (swap! steps conj (step-of tid request ctx run))
                   run)))
        resolve! (fn [tid status]
                   (let [run (g/run* actor {:approval {:status status :by approver}}
                                     {:thread-id tid :resume? true})]
                     (swap! grants into
                            (filterv #(= :approval-granted (:t %))
                                     (get-in run [:state :audit])))
                     (swap! steps
                            (fn [ss]
                              (mapv #(if (= tid (:tid %))
                                       (assoc % :decision {:status status :by approver}
                                              :disposition (get-in run [:state :disposition]))
                                       %)
                                    ss)))
                     run))]

    ;; -- case-1: the full clean lifecycle -------------------------------
    (exec! "t1-intake" {:op :case/intake :subject "case-1"
                        :patch {:id "case-1" :client-name "Sakura Tanaka"}})

    (exec! "t1-assess" {:op :jurisdiction/assess :subject "case-1"})
    (resolve! "t1-assess" :approved)

    (exec! "t1-risk" {:op :risk/screen :subject "case-1"})
    (resolve! "t1-risk" :approved)

    (exec! "t1-eligibility" {:op :eligibility/finalize :subject "case-1"})
    (resolve! "t1-eligibility" :approved)

    (exec! "t1-referral" {:op :referral/finalize :subject "case-1"})
    (resolve! "t1-referral" :approved)

    ;; -- HARD governor refusals ------------------------------------------
    (exec! "t2-assess" {:op :jurisdiction/assess :subject "case-2" :no-spec? true})

    (exec! "t3-assess" {:op :jurisdiction/assess :subject "case-3"})
    (resolve! "t3-assess" :approved)

    (exec! "t3-eligibility" {:op :eligibility/finalize :subject "case-3"})

    (exec! "t4-risk" {:op :risk/screen :subject "case-4"})

    (exec! "t1-eligibility-again" {:op :eligibility/finalize :subject "case-1"})

    (exec! "t1-referral-again" {:op :referral/finalize :subject "case-1"})

    ;; -- a human declines a proposal the governor cleared ----------------
    (exec! "t4-assess" {:op :jurisdiction/assess :subject "case-4"})
    (resolve! "t4-assess" :rejected)

    ;; -- same clean op, earlier phase -> rollout-phase hold --------------
    (exec! "t3-assess-phase1" {:op :jurisdiction/assess :subject "case-3"}
           early-phase-operator)

    {:db db :steps @steps :grants @grants}))

;; ----------------------------- refusal classification -----------------------------
;;
;; Keyed on `:t` FIRST. `:approval-rejected` facts carry a synthetic
;; `[{:rule :approver-rejected}]` violation vector (see this ns's
;; docstring), so a `:violations`-only split would count every human
;; rejection as a governor refusal.

(defn- governor-hold-facts [ledger]
  (filter #(= :governor-hold (:t %)) ledger))

(defn hard-holds
  "Social Services Governor refusals: fact type `:governor-hold` AND a
  non-empty `:violations` vector. Un-overridable -- no approver can
  approve past these, and the graph never offers them to one."
  [ledger]
  (filterv #(seq (:violations %)) (governor-hold-facts ledger)))

(defn phase-holds
  "Rollout-phase gate holds: fact type `:governor-hold` with an EMPTY
  `:violations` vector -- the governor cleared the proposal and
  `casework.phase` refused the write for this phase anyway."
  [ledger]
  (filterv #(empty? (:violations %)) (governor-hold-facts ledger)))

(defn approval-rejections
  "Human approver refusals. A different fact type entirely, despite
  carrying a violation-shaped entry."
  [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

;; ----------------------------- approver attribution -----------------------------

(def ^:private approver-keys
  "Every key any backend in this repo could plausibly use to retain an
  approver on a committed artifact. Scanned at RENDER time so the page
  self-corrects if the store starts (or stops) retaining one.

  `:actor` is deliberately NOT in this list. It is present on every
  ledger fact and means the EXECUTING actor, not the approver."
  [:approved-by :approver "approved_by" "approver"])

(defn- approver-of [m]
  (when (map? m) (some m approver-keys)))

(defn- attribution
  "Scans the committed registers (jurisdiction assessments, risk
  screenings) and the committed actuation records (eligibility-
  determination / referral drafts) for an approver key.

  Derived from the store at render time -- NOT joined to the approval
  audit facts on [op subject], which is not a unique key here (case-1 is
  the subject of an eligibility determination, a referral AND a refused
  second attempt of each, so such a join would silently attribute one
  op's approver to another op's record).

  Every artifact class scanned here is produced only by an op that is
  absent from EVERY phase's `:auto` set (see `always-human-ops`), so
  each row below did pass through a human -- which is what makes a
  missing approver a real loss rather than an op that never had one.
  The case directory is excluded for exactly that reason: `:case/intake`
  auto-commits at phase 3, so it has no approver to retain."
  [db]
  (concat
   (for [c (store/all-cases db)
         [kind reg] [["jurisdiction assessment" (store/assessment-of db (:id c))]
                     ["risk screening" (store/risk-screening-of db (:id c))]]
         :when reg]
     {:artifact kind :subject (:id c) :approver (approver-of reg)})
   (for [[kind rs] [["eligibility-determination draft" (store/eligibility-history db)]
                    ["referral draft" (store/referral-history db)]]
         r rs]
     {:artifact kind :subject (get r "case_id") :record-id (get r "record_id")
      :approver (approver-of r)})))

;; ----------------------------- derived gate facts -----------------------------

(defn- auto-ops
  "Ops that ANY phase allows to auto-commit -- read out of
  `casework.phase/phases`, not hand-listed."
  []
  (into (sorted-set) (mapcat (comp :auto val) phase/phases)))

(defn- writable-phases
  "The phases at which `op` is an enabled write."
  [op]
  (->> phase/phases
       (filter (fn [[_ p]] (contains? (:writes p) op)))
       (map key)
       sort))

(defn- always-human-ops
  "Write ops absent from EVERY phase's `:auto` set."
  []
  (into (sorted-set) (remove (auto-ops) phase/write-ops)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm
  "Keyword -> its full printed name INCLUDING the namespace.

  `clojure.core/name` would render `:eligibility/finalize` and
  `:referral/finalize` both as \"finalize\" -- two different real-world
  acts collapsed into one label on an audit page."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))

(defn- yn [b yes no]
  (if b
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

;; ----------------------------- rows -----------------------------

(defn- last-fact-for [ledger case-id]
  (last (filter #(= (:subject %) case-id) ledger)))

(defn- status-cell [ledger case-id]
  (let [f (last-fact-for ledger case-id)]
    (cond
      (nil? f) (muted "no activity")
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f))
      "<span class=\"warn\">approver declined</span>"
      (and (= :governor-hold (:t f)) (seq (:violations f)))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (nm (-> f :violations first :rule))) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"warn\">phase hold &middot; "
           (esc (nm (or (:phase-reason f) :phase-gate))) "</span>")
      :else (muted "in progress"))))

(defn- lifecycle-cell [{:keys [eligibility-finalized? referral-finalized?
                               determination-number referral-number]}]
  (cond
    (and eligibility-finalized? referral-finalized?)
    (str "<span class=\"ok\">determined &amp; referred</span> "
         (muted (str (esc determination-number) " / " (esc referral-number))))
    referral-finalized?
    (str "<span class=\"ok\">referred</span> " (muted (esc referral-number)))
    eligibility-finalized?
    (str "<span class=\"warn\">determined, not referred</span> "
         (muted (esc determination-number)))
    :else (muted "open case")))

(defn- criteria-cell [{:keys [required-criteria satisfied-criteria]}]
  (let [missing (remove (set satisfied-criteria) (set required-criteria))]
    (str "<span class=\"num " (if (seq missing) "critical" "ok") "\">"
         (count satisfied-criteria) " / " (count required-criteria) "</span>"
         (when (seq missing)
           (str " " (muted (str "missing: " (str/join ", " (map esc (sort missing))))))))))

(defn- case-row [ledger {:keys [id client-name jurisdiction risk-flag-resolved?] :as c}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (code id) (esc client-name) (code jurisdiction)
          (criteria-cell c)
          (yn risk-flag-resolved? "resolved" "unresolved")
          (lifecycle-cell c)
          (status-cell ledger id)))

(defn- verdict-cell [{:keys [hard? escalate? high-stakes?]}]
  (cond
    hard? "<span class=\"critical\">refused</span>"
    escalate? (str "<span class=\"warn\">escalate</span>"
                   (when high-stakes? (str " " (muted "high-stakes"))))
    :else "<span class=\"ok\">clean</span>"))

(defn- decision-cell [{:keys [decision]}]
  (case (:status decision)
    :approved (str "<span class=\"ok\">approved</span> " (muted (esc (:by decision))))
    :rejected (str "<span class=\"warn\">declined</span> " (muted (esc (:by decision))))
    (muted "no human involved")))

(defn- disposition-cell [d]
  (case d
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (muted (esc (nm d)))))

(defn- step-row [{:keys [op subject phase stake confidence disposition] :as s}]
  (format "        <tr><td>%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (code (nm op)) (code subject) (esc phase)
          (if stake (code (nm stake)) (muted "—"))
          (esc confidence)
          (verdict-cell s)
          (decision-cell s)
          (disposition-cell disposition)))

(defn- hard-hold-row [{:keys [op subject violations confidence]}]
  (let [v (first violations)]
    (format "        <tr><td>%s</td><td>%s</td><td><span class=\"critical\">%s</span></td><td>%s</td><td class=\"num\">%s</td></tr>"
            (code (nm op)) (code subject) (esc (nm (:rule v)))
            (esc (:detail v)) (esc confidence))))

(defn- phase-hold-row [{:keys [op subject phase phase-reason violations]}]
  (format "        <tr><td>%s</td><td>%s</td><td class=\"num\">%s</td><td><span class=\"warn\">%s</span></td><td>%s</td></tr>"
          (code (nm op)) (code subject) (esc phase)
          (esc (nm (or phase-reason :phase-gate)))
          (if (seq violations)
            (esc (str (count violations) " governor violation(s)"))
            (muted "none — the Social Services Governor cleared this proposal"))))

(defn- rejection-row [{:keys [op subject violations confidence]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td></tr>"
          (code (nm op)) (code subject)
          (if (seq violations)
            (str "<span class=\"warn\">" (esc (str/join ", " (map (comp nm :rule) violations))) "</span>")
            (muted "—"))
          (muted "the governor raised no violation of its own")
          (esc confidence)))

(defn- record-row [{:strs [record_id case_id jurisdiction kind immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (code record_id) (code case_id) (esc jurisdiction) (esc kind)
          (if immutable "<span class=\"ok\">immutable</span>" "<span class=\"warn\">mutable</span>")))

(defn- attribution-row [{:keys [artifact subject record-id approver]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc artifact) (code subject)
          (if record-id (code record-id) (muted "—"))
          (if approver
            (str "<span class=\"ok\">" (esc approver) "</span>")
            "<span class=\"warn\">not retained on record</span>")))

(defn- grant-row [{:keys [op subject by]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td></tr>"
          (code (nm op)) (code subject) (esc by)))

(defn- gate-row [op auto always-human]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (code (nm op))
          (let [ph (writable-phases op)]
            (if (seq ph) (esc (str/join ", " ph)) (muted "none")))
          (if (contains? auto op)
            (str "<span class=\"warn\">phase "
                 (esc (str/join ", " (->> phase/phases
                                          (filter (fn [[_ p]] (contains? (:auto p) op)))
                                          (map key) sort)))
                 "</span>")
            "<span class=\"ok\">never — at any phase</span>")
          (if (contains? always-human op)
            "<span class=\"ok\">always a human decision</span>"
            (muted "may auto-commit when the governor is clean"))))

(defn- coverage-row [iso3]
  (let [{:keys [name owner-authority legal-basis provenance required-evidence]}
        (facts/spec-basis iso3)]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td><a href=\"%s\">source</a></td></tr>"
            (code iso3) (esc name) (esc owner-authority) (esc legal-basis)
            (count required-evidence) (esc provenance))))

(defn- ledger-row [{:keys [t op subject actor disposition basis]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (nm t)) (code (nm (or op :n-a))) (code subject) (code actor)
          (esc (or (some->> basis seq (map nm) (str/join ", "))
                   (some-> disposition nm) ""))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the full operator-console.html document from a `run-demo!`
  result ({:db .. :steps .. :grants ..})."
  [{:keys [db steps grants]}]
  (let [ledger (vec (store/ledger db))
        cases (store/all-cases db)
        hard (hard-holds ledger)
        phasey (phase-holds ledger)
        rejected (approval-rejections ledger)
        attrib (attribution db)
        retained (filter :approver attrib)
        dropped (remove :approver attrib)
        ledger-approvers (filter approver-of ledger)
        determinations (store/eligibility-history db)
        referrals (store/referral-history db)
        auto (auto-ops)
        always-human (always-human-ops)
        cov (facts/coverage)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-8890 &middot; other social work activities without accommodation</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Other social work activities without accommodation (ISIC 8890) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · eligibility determination &amp; referral always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     ;; -- cases ---------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Cases — " (count cases) " seeded</h2>\n"
     "    <p class=\"muted\">Demo snapshot, build-time-generated from <code>casework.store</code> via <code>casework.render-html</code> (<code>clojure -M:dev:render-html</code>). The eligibility-criteria, lifecycle and status columns are the actor's own committed writes, not a hand-typed summary. Client names are the fixtures in <code>casework.store/demo-data</code>; no real client record appears on this page.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Case</th><th>Client</th><th>Jurisdiction</th><th>Criteria satisfied</th><th>Risk flag</th><th>Lifecycle</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial case-row ledger) cases)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- scenario log --------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Scenario log — " (count steps) " actor runs</h2>\n"
     "    <p class=\"muted\">One row per <code>langgraph.graph/run*</code> invocation, in order. The stake, confidence and governor columns are read off that run's own <code>:proposal</code> and <code>:verdict</code> channels; the human column is set only where the graph actually paused at <code>interrupt-before #{:request-approval}</code> and was resumed. Approvals are recorded as <code>" (esc approver) "</code>, deliberately a different identity from the executing actor <code>" (esc (:actor-id operator)) "</code> — see the attribution section below for why that distinction is load-bearing.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Case</th><th>Phase</th><th>Stake</th><th>Confidence</th><th>Governor</th><th>Human</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map step-row steps)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- action gate ---------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Action gate (rollout phase × Social Services Governor)</h2>\n"
     "    <p class=\"muted\">Derived from <code>casework.phase/phases</code> and <code>casework.governor/high-stakes</code> at render time, not hand-listed. "
     (count always-human) " of " (count phase/write-ops)
     " write ops are absent from EVERY phase's <code>:auto</code> set — "
     (esc (str/join ", " (map nm always-human)))
     ". The governor enforces the same invariant independently for "
     (esc (str/join ", " (map nm (sort governor/high-stakes))))
     ": two layers, not one, agree that a real eligibility determination or referral is always a human's call.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Writable at phase</th><th>Auto-commit</th><th>Human requirement</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map #(gate-row % auto always-human) (sort phase/write-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- hard holds ----------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>HARD governor holds — " (count hard) " this run</h2>\n"
     "    <p class=\"muted\">Proposals the Social Services Governor genuinely <strong>refused</strong>. Fact type <code>:governor-hold</code> with a non-empty <code>:violations</code> vector. These never reach a human at all — the graph routes them straight to <code>:hold</code>, so there is no approval path past them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Case</th><th>Rule</th><th>Detail</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hard-hold-row hard)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- phase holds ---------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Rollout-phase gate holds — " (count phasey) " this run</h2>\n"
     "    <p class=\"muted\">Structurally different from the table above: here the Social Services Governor <em>cleared</em> the proposal and the staged-rollout gate (<code>casework.phase</code>) refused the write anyway, because the op is not yet enabled at that phase. These facts share the <code>:governor-hold</code> tag but carry an EMPTY <code>:violations</code> vector, so a naive count of <code>:governor-hold</code> facts would report them as governor refusals. They are counted separately, and the build-time invariant in <code>-main</code> counts only the HARD kind.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Case</th><th>Phase</th><th>Gate reason</th><th>Governor violations</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-hold-row phasey)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- human rejections ----------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Human approver rejections — " (count rejected) " this run</h2>\n"
     "    <p class=\"muted\">The third kind of refusal, and the one that makes <code>:violations</code> an unsafe classifier on its own. <code>casework.operation</code>'s <code>:request-approval</code> node builds a declined approval through <code>governor/hold-fact</code> with a <strong>synthetic</strong> <code>[{:rule :approver-rejected}]</code> violation, then overrides the tag to <code>:approval-rejected</code>. So this fact carries a non-empty <code>:violations</code> vector while the governor itself raised nothing: splitting holds on <code>:violations</code> alone would move every row below into the HARD governor count above. This page classifies on the fact type first.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Case</th><th>Violations on the fact</th><th>Governor's own finding</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map rejection-row rejected)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- drafts --------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Draft eligibility-determination / referral records — " (+ (count determinations) (count referrals)) "</h2>\n"
     "    <p class=\"muted\">Built by <code>casework.registry</code> — the record an agency keeps, never the act itself. Every certificate this actor produces is <strong>unsigned</strong>: signature is the agency's own act. Reference numbers are jurisdiction-scoped sequence numbers; this repo does not invent a check-digit standard that does not exist.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Case</th><th>Jurisdiction</th><th>Kind</th><th>Mutability</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map record-row (concat determinations referrals))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- attribution ---------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Approver attribution</h2>\n"
     "    <p class=\"muted\">Derived at render time by scanning the committed registers and actuation records for an approver key ("
     (esc (str/join ", " (map nm approver-keys)))
     ") — not by joining records to approval facts on <code>[op, case]</code>, which is not a unique key here (<code>case-1</code> is the subject of an eligibility determination, a referral, and a refused second attempt of each, so such a join would attribute one op's approver to another op's record). <code>:actor</code> is deliberately excluded from that key list: it appears on every ledger fact and means the actor that <em>executed</em> the op, not the human who approved it. "
     (cond
       (empty? attrib)
       "This run committed no artifact that could carry an approver."

       (empty? dropped)
       (str "This run: <strong>all " (count attrib)
            "</strong> committed artifacts retain the approver on the record itself.")

       (empty? retained)
       "This run: <strong>no</strong> committed artifact retained the approver — it survives only in the run's audit channel, below."

       :else
       (str "This run: <strong>" (count retained) " of " (count attrib)
            "</strong> committed artifacts retain the approver on the record itself; the other "
            (count dropped)
            " do not. That split is a property of the store, not of the approval: the register writes"
            " (<code>:assessment/set</code>, <code>:risk-screening/set</code>) persist the operation's"
            " <code>:payload</code>, which the <code>:request-approval</code> node has already stamped"
            " with <code>:approved-by</code>, while <code>commit-record!</code> re-derives the two"
            " actuation records from <code>casework.registry</code> and never reads <code>:payload</code>"
            " at all. Reported, not averaged — and re-measured on every render, so this page"
            " self-corrects if the store changes."))
     "</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Artifact</th><th>Case</th><th>Record id</th><th>Approver on record</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map attribution-row attrib)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <h3>Approvals granted this run — " (count grants) "</h3>\n"
     "    <p class=\"muted\">The human handoff is real: <code>interrupt-before #{:request-approval}</code> pauses the graph and the operator resumes it. These <code>:approval-granted</code> facts are emitted on the actor's <code>:audit</code> channel. The ledger is a third case again, and the strictest: <strong>"
     (if (seq ledger-approvers)
       (str (count ledger-approvers) " of " (count ledger) " ledger facts carry an approver key")
       (str "none of the " (count ledger) " ledger facts carries an approver key"))
     "</strong> — <code>casework.operation/commit-fact</code> has no approver field, so a <code>:committed</code> fact never records who approved it even when the register it wrote does. Its <code>:actor</code> column below reads <code>" (esc (:actor-id operator)) "</code> on every row, including the five ops this run's human approved as <code>" (esc approver) "</code>. Where the column above reads &ldquo;not retained on record&rdquo;, the approver survives only here, in this run's audit channel.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Case</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map grant-row grants)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- coverage ------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Jurisdiction spec-basis coverage — " (:covered cov) " of " (:requested cov) " seeded</h2>\n"
     "    <p class=\"muted\">" (esc (:note cov)) " A jurisdiction absent from this catalog has NO spec-basis: the advisor must not fabricate one, and the governor HARD-holds if it tries (see <code>case-2</code> above, jurisdiction <code>ATL</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ISO3</th><th>Jurisdiction</th><th>Owner authority</th><th>Legal basis</th><th>Required evidence</th><th>Provenance</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map coverage-row (:covered-jurisdictions cov))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; -- ledger --------------------------------------------------------
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run) — " (count ledger) " facts</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and every refusal this scenario produced, in order. <code>:actor</code> is the executing actor on every row.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Case</th><th>Actor</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>Generated by <code>casework.render-html</code> from a real "
     "<code>casework.operation</code> run — cloud-itonami-isic-8890, AGPL-3.0-or-later.</footer>\n"
     "</body></html>\n")))

(defn -main
  "Regenerates the operator console. BUILD-TIME INVARIANT: refuses to
  write the page if the run produced zero HARD governor holds.

  A console that shows only approvals would be indistinguishable from a
  page whose governor was never wired in at all, and a page is not
  evidence of a censor that never said no.

  Counting only `:governor-hold` facts with a non-empty `:violations`
  vector is what gives the check teeth. Two other fact shapes in this
  repo would otherwise satisfy it on a run in which the governor refused
  nothing: the rollout-phase gate writes the same `:governor-hold` tag
  with EMPTY violations, and a human rejection writes a DIFFERENT tag
  (`:approval-rejected`) carrying a synthetic violation. The first would
  fool a tag-only count; the second would fool a violations-only count."
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db grants] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hard (hard-holds ledger)
        phasey (phase-holds ledger)
        rejected (approval-rejections ledger)]
    (when (empty? hard)
      (throw (ex-info (str "refusing to write " out
                           ": the run produced ZERO HARD governor holds"
                           " (:governor-hold facts carrying a non-empty :violations vector)."
                           " A console with no refusals is not evidence of a governor.")
                      {:out out
                       :ledger-facts (count ledger)
                       :governor-hold-facts (count (governor-hold-facts ledger))
                       :hard-holds 0
                       :phase-holds (count phasey)
                       :approval-rejections (count rejected)})))
    (spit out (render result))
    (println "wrote" out "-" (count ledger) "ledger facts,"
             (count hard) "HARD governor holds"
             (str "(" (str/join ", " (sort (distinct (map #(nm (:rule (first (:violations %)))) hard)))) "),")
             (count phasey) "rollout-phase holds,"
             (count rejected) "approver rejections,"
             (count grants) "approvals,"
             (count (store/eligibility-history db)) "determination drafts,"
             (count (store/referral-history db)) "referral drafts")))
