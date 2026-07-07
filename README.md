# cloud-itonami-isic-8890

Open Business Blueprint for **ISIC Rev.5 8890**: Other social work
activities without accommodation. This repository publishes a social-
work/casework actor -- client-case intake, jurisdiction assessment,
risk screening, eligibility-determination finalization and referral
finalization -- as an OSS business that any qualified social-services
agency can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103),
[`9602`](https://github.com/cloud-itonami/cloud-itonami-isic-9602),
[`9000`](https://github.com/cloud-itonami/cloud-itonami-isic-9000)) --
a second social-services vertical (ISIC division 88) in this fleet,
alongside `8730`'s eldercare, but for non-residential welfare
casework rather than residential care. Here it is **CaseworkOps-LLM ⊣
Social Services Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a client-
> case intake summary, normalizing records, and checking whether every
> required eligibility criterion actually appears in a case's own
> recorded satisfied-criteria set -- but it has **no notion of which
> jurisdiction's welfare-eligibility/social-work requirements are
> official, no license to finalize a real client's eligibility
> determination or referral, and no way to know on its own whether a
> case's own risk (fraud/misrepresentation) flag is still
> unresolved**. Letting it finalize an eligibility determination or
> referral directly invites fabricated jurisdiction citations, a
> benefit determination finalized without every eligibility criterion
> actually satisfied, and an unresolved risk concern being quietly
> signed off -- and liability, and client-welfare risk, for whoever
> runs it. This project seals the CaseworkOps-LLM into a single node
> and wraps it with an independent **Social Services Governor**, a
> human **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers client-case intake through jurisdiction assessment,
risk screening, eligibility-determination finalization and referral
finalization. It does **not**, by itself, hold any license required to
operate a social-services agency in a given jurisdiction, and it does
not claim to. It also does **not** model a full means-testing/
benefits-calculation engine -- no partial-credit rules, no waiver
processing, no benefit-amount calculation (see `casework.registry/
eligibility-criteria-unsatisfied?`'s own docstring for the honest
simplification this makes: a literal required-criteria-vs-satisfied-
criteria membership check, not a full means-testing engine). Whoever
deploys and operates a live instance (a licensed social-services
agency) supplies any jurisdiction-specific license, the real social-
work/casework expertise and the real case-management-system
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**Finalizing a real client's eligibility determination or referral is
never autonomous, at any phase, by construction.** Two independent
layers enforce this (`casework.governor`'s `:actuation/finalize-
eligibility`/`:actuation/finalize-referral` high-stakes gate and
`casework.phase`'s phase table, which never puts `:eligibility/
finalize`/`:referral/finalize` in any phase's `:auto` set) -- see
`casework.phase`'s docstring and `test/casework/phase_test.clj`'s
`eligibility-finalize-never-auto-at-any-phase`/`referral-finalize-
never-auto-at-any-phase`. The actor may draft, check and recommend; a
human caseworker is always the one who actually finalizes an
eligibility determination or referral. Like `6512`/`6622`/`6520`/
`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`,
this actor has TWO actuation events.

## The core contract

```
case intake + jurisdiction facts (casework.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ CaseworkOps- │ ─────────────▶ │ Social Services              │  (independent system)
   │ LLM (sealed) │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ eligibility-criteria-
                                 │             │           │ unsatisfied (set-
                           record + ledger  escalate ─▶ human   containment) ·
                                             (ALWAYS for         risk-flag-unresolved
                                              :eligibility/          (unconditional) ·
                                              finalize /              already-finalized
                                              :referral/finalize)
```

**The CaseworkOps-LLM never finalizes an eligibility determination or
referral the Social Services Governor would reject, and never does so
without a human sign-off.** Hard violations (fabricated jurisdiction
requirements; unsupported casework evidence; an unsatisfied
eligibility criterion; an unresolved risk flag; a double finalization)
force **hold** and *cannot* be approved past; a clean finalization
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (eligibility determination, referral) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a document-courier robot
handles physical casework-file handoff where used, under the actor,
gated by the independent **Social Services Governor**. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions
require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Social Services Governor, eligibility-determination + referral draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8890`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`/`9102`/`9103`/`9602`/`9000`, this vertical's case
records are practice-specific rather than a shared cross-operator data
contract, so `casework.*` runs on the generic identity/forms/dmn/bpmn/
audit-ledger stack only -- no bespoke domain capability lib to
reference at all.

## Layout

| File | Role |
|---|---|
| `src/casework/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate eligibility-determination/referral history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded case, and the double-finalization guards check dedicated `:eligibility-finalized?`/`:referral-finalized?` booleans rather than a `:status` value |
| `src/casework/registry.cljc` | Eligibility-determination + referral draft records, plus `eligibility-criteria-unsatisfied?` -- reuses `registrar.registry/prerequisites-satisfied?`'s SET-CONTAINMENT/subset shape (the FIRST universal 'every required item is satisfied' test in this fleet) for a SECOND domain |
| `src/casework/facts.cljc` | Per-jurisdiction welfare-eligibility/social-work catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/casework/caseworkopsllm.cljc` | **CaseworkOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/risk-screening/eligibility-determination/referral proposals |
| `src/casework/governor.cljc` | **Social Services Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · eligibility-criteria-unsatisfied, pure ground-truth set-containment recompute · risk-flag-unresolved, unconditional evaluation, the FIFTEENTH grounding of this discipline) + already-eligibility-finalized/already-referral-finalized guards + 1 soft (confidence/actuation gate) |
| `src/casework/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (both finalizations always human; case intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/casework/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/casework/sim.cljc` | demo driver |
| `test/casework/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers client-case intake through jurisdiction assessment,
risk screening, eligibility-determination finalization and referral
finalization -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Case intake + per-jurisdiction welfare-eligibility/social-work checklisting, HARD-gated on an official spec-basis citation (`:case/intake`/`:jurisdiction/assess`) | A full means-testing/benefits-calculation engine (partial-credit rules, waiver processing, benefit-amount calculation -- see `eligibility-criteria-unsatisfied?`'s docstring) |
| Risk screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:risk/screen`) | Real case-management-system integration, counseling/community-outreach workflows |
| Eligibility-determination finalization, HARD-gated on every required eligibility criterion being satisfied and a double-finalization guard (`:eligibility/finalize`) | Ongoing benefit-disbursement workflows themselves |
| Referral finalization, HARD-gated on the case's risk flag being resolved and a double-finalization guard (`:referral/finalize`) | |
| Immutable audit ledger for every intake/assessment/screening/finalization decision | |

Extending coverage is additive: add the next gate (e.g. a benefit-
overlap check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`casework.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `casework.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `casework.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `CaseworkOps-LLM` + `Social Services Governor` run
as real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the twenty-
five prior actors' architecture. See `docs/adr/0001-architecture.md`
for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
