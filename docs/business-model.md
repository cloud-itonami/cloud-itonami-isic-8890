# Business Model: Other social work activities without accommodation

## Classification

- Repository: `cloud-itonami-isic-8890`
- ISIC Rev.5: `8890`
- Activity: other social work activities without accommodation not elsewhere classified (e.g. counseling, welfare/benefit-eligibility casework, community-outreach services)
- Social impact: care quality, data sovereignty, transparent audit

## Customer

- independent social-service agencies
- cooperative case-management collectives
- community welfare-access programs

## Offer

- client intake
- needs-assessment/eligibility proposal
- service/referral proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per agency
- support: monthly retainer with SLA
- migration: import from an incumbent case-management system
- per-case fee

## Trust Controls

- no eligibility determination or referral is finalized without human
  sign-off (a caseworker)
- a fabricated jurisdiction citation, incomplete casework evidence, an
  unsatisfied eligibility criterion, or an unresolved risk (fraud/
  misrepresentation) flag -- each forces a hold, not an override
- a case's eligibility determination/referral cannot each be finalized
  twice: a double-finalization attempt is held off this actor's own
  case facts alone, with no upstream comparison needed
- every intake, assessment, screening and finalization path is
  auditable
- client data stays outside Git
- emergency manual override paths remain outside LLM control
