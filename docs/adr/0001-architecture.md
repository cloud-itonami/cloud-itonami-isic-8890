# ADR-0001: cloud-itonami-isic-8890 -- CaseworkOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`
  ADR-0001s (the pattern this ADR ports); ADR-2607071250/
  ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/
  ADR-2607071654/ADR-2607071717/ADR-2607071732/ADR-2607071752/
  ADR-2607071819/ADR-2607071849/ADR-2607071922/ADR-2607072715/
  ADR-2607072730/ADR-2607072745/ADR-2607072800/ADR-2607072815
  (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/
  `9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`, the
  seventeen verticals built outside ADR-2607032000's original
  insurance/real-estate batch -- this is the eighteenth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9000`, this ADR deepens `cloud-itonami-
  isic-8890` (other social work activities without accommodation) from
  `:blueprint` to `:implemented`, the twenty-sixth actor in this fleet
  -- a SECOND social-services vertical (ISIC division 88) alongside
  `8730`'s eldercare, but for non-residential welfare casework rather
  than residential care.

## Problem

A social-services agency's eligibility-determination/referral
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction welfare-eligibility/social-work correctness** -- an
   official spec-basis citation from a real regulator (厚生労働省/
   the U.S. Administration for Children and Families/Social Work
   England/the Bundesagentur für Arbeit), never fabricated.
2. **Eligibility-criteria sufficiency** -- does a case's own required
   eligibility criteria (residency, income-band, household-size,
   documentation) all appear in its own recorded satisfied-criteria
   set? Reuses `registrar.registry/prerequisites-satisfied?`'s SET-
   CONTAINMENT/subset shape (the FIRST universal "every required item
   satisfied" test in this fleet) for a SECOND domain.
3. **Risk-flag resolution verification** -- has a case's own risk
   (fraud/misrepresentation) flag actually been resolved before either
   an eligibility determination or a referral is finalized? The
   casework-specific reuse of the unconditional-evaluation screening
   discipline this fleet's `casualty.governor/sanctions-violations`
   originally established -- a FIFTEENTH distinct grounding.
4. **Real, high-stakes actuation, twice** -- finalizing a real
   client's eligibility determination and finalizing a real client's
   referral are two independently-gated real-world acts on the SAME
   entity (a case).

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a social-services agency with an LLM"
but "seal the LLM inside a trust boundary and layer evidence-
sufficiency, eligibility-criteria verification, risk-flag-resolution
verification, audit and human-approval on top of it, while
structurally fixing both real actuation events as human-only."

## Decision

### 1. CaseworkOps-LLM is sealed into the bottom node; it never finalizes directly

`casework.caseworkopsllm` returns exactly five kinds of proposal:
intake normalization, jurisdiction welfare-eligibility/social-work
checklist, risk screening, eligibility-determination draft, and
referral draft. No proposal writes the SSoT or commits a real
finalization directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 casework operation

`casework.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `eligibility-criteria-unsatisfied?` is the SECOND instance of the set-containment/subset family

`registrar.registry/prerequisites-satisfied?` established the FIRST
check in this fleet to be a universal set-containment/subset test (is
EVERY item of a required set also a member of a satisfied set),
generalizing `clinic.registry/treatment-contraindicated?`'s existential
set-membership shape. `eligibility-criteria-unsatisfied?` is the
SECOND instance, applied to a genuinely different real-world concern:
a case's own required eligibility criteria (not academic prerequisite
courses) must all be satisfied before an eligibility determination can
be finalized.

### 4. Risk-flag-unresolved screening reuses the unconditional-evaluation discipline for a fifteenth distinct grounding

`risk-flag-unresolved-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:risk/screen`, `:eligibility/finalize` AND `:referral/
finalize` -- the FIFTEENTH distinct application of this exact
discipline in this fleet.

### 5. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson already recorded by `parksafety`/`eldercare`/`museum`/`conservation`/`salon`/`entertainment`

`risk-flag-unresolved-is-held-and-unoverridable` calls `:risk/screen`
directly against `case-4` (an unresolved risk flag), NOT
`:eligibility/finalize`/`:referral/finalize` against an un-screened
case -- because a failing screen is itself a HARD hold whose payload
never persists to the store, so the actuation ops alone could never
discover the bad ground-truth flag through this check family without
the screening op having actually been run first. This build applied
that lesson PROACTIVELY for a sixth consecutive vertical (after
`eldercare`, `museum`, `conservation`, `salon` and `entertainment`),
further reinforcing that lessons recorded in this fleet's ADRs
transfer forward reliably.

### 6. Dual actuation, matching `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`'s shape

`casework.governor`'s `high-stakes` set has exactly two members
(`:actuation/finalize-eligibility`, `:actuation/finalize-referral`),
each acting on the SAME case entity, each with its OWN history
collection (`eligibility-history`/`referral-history`), sequence
counter and dedicated double-actuation-guard boolean.

### 7. Double-finalization guards check dedicated booleans, not `:status`

`already-eligibility-finalized-violations`/`already-referral-
finalized-violations` check `:eligibility-finalized?`/`:referral-
finalized?`, dedicated booleans set once and never cleared, rather
than a `:status` value that could legitimately advance past a checked
state (the exact trap `cloud-itonami-isic-6492`'s ADR-0001 documents
in detail, explicitly avoided BY DESIGN in every sibling actor's
equivalent guard since). This actor's `:status` never needs to encode
"has this actuation already happened" at all -- a deliberate
architectural choice applied here for a sixteenth consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`/`9102`/`9103`/`9602`/`9000`, and unlike most other actors in
this fleet, this vertical's case records are practice-specific rather
than a shared cross-operator data contract -- `casework.*` runs on the
generic identity/forms/dmn/bpmn/audit-ledger stack only, per the
blueprint's own explicit statement.

### 9. Protocol method named `case-`, not `case`, to avoid colliding with the `case` special form

The Store protocol's entity accessor is named `case-` (trailing dash),
not `case` -- naming a protocol method `case` inside `casework.store`
would conflict with `clojure.core/case`, which every `.cljc` namespace
refers in by default. This is a genuinely new naming consideration for
this fleet (no prior sibling's entity noun collided with a Clojure
special form); every reference across `casework.governor`/
`casework.caseworkopsllm`/tests consistently calls `store/case-`.

## Consequences

- (+) Non-residential social-services casework gets the same
  governed, auditable-actor treatment as the twenty-five prior actors,
  and this fleet now has an EIGHTEENTH concrete precedent for
  extending past ADR-2607032000's original scope, deepening social-
  services coverage (ISIC division 88) alongside `8730`'s eldercare
  with a genuinely different service model (non-residential casework
  vs. residential care).
- (+) `eligibility-criteria-unsatisfied?` is a genuine structural
  contribution: the second instance of the set-containment/subset
  family, reused for a domain with no academic-curriculum concept at
  all.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/casework/phase_test.clj`'s `eligibility-
  finalize-never-auto-at-any-phase`/`referral-finalize-never-auto-at-
  any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/casework/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The risk-flag-unresolved test/demo correctly applied the
  established SCREENING-op-directly pattern for a sixth consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `casework.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `eligibility-criteria-unsatisfied?` models only a literal
  required-criteria-vs-satisfied-criteria membership check, not a full
  means-testing/benefits-calculation engine (partial-credit rules,
  waiver processing, benefit-amount calculation are out of scope --
  see that fn's own docstring); real case-management-system
  integration and ongoing counseling/community-outreach workflows are
  all out of scope for this OSS actor -- each operator's
  responsibility (see README's coverage table).
- 38 tests / 178 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All seventeen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (88) overlaps with `8730` |
| Keep `cloud-itonami-isic-8890` at `:blueprint` only | ❌ | The standing direction continues past `9000`; non-residential social-work casework is a natural, well-precedented next domain, deepening this fleet's social-services coverage with a genuinely different service model than `8730`'s residential eldercare |
| Name the protocol accessor `case` (matching most siblings' bare-noun convention) | ❌ | `case` is a Clojure special form referred in by default; defining a protocol method with that name in `casework.store` would conflict with `clojure.core/case`, which the same namespace uses for its own effect dispatch -- `case-` (trailing dash) avoids the collision while staying close to the natural entity noun |
| See `cloud-itonami-isic-8890`'s own registry/governor for the set-containment vs. set-membership framing | -- | (whether eligibility-criteria-unsatisfied? is a new family vs. an extension, unconditional-evaluation test design, capability-lib reference, etc.) |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`, first seventeen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8890/docs/adr/0001-architecture.md` (this ADR)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
