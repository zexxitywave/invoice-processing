# Why We Built This Portal — The Problems We Hit and How We Solved Them

**Project:** InvoiceAI Pro (Invoice Processing System)
**Live app:** <https://zexxity.online>
**Stack:** Java 21 on AWS Lambda + Step Functions + Textract + Bedrock + DynamoDB, React 19 SPA on Amplify, region `ap-south-1` (Mumbai)

This is the "why" document. It explains what the portal is for, every real problem we
ran into while building it, and the specific fix we shipped for each one. It is written
for teammates, reviewers, and anyone evaluating the project.

---

## Table of Contents

- [1. The problem we were hired to solve](#1-the-problem-we-were-hired-to-solve)
- [2. What the portal actually is](#2-what-the-portal-actually-is)
- [3. The two ways an invoice gets in](#3-the-two-ways-an-invoice-gets-in)
- [4. Problems and solutions — extraction and data quality](#4-problems-and-solutions--extraction-and-data-quality)
- [5. Problems and solutions — AI validation](#5-problems-and-solutions--ai-validation)
- [6. Problems and solutions — performance and scale](#6-problems-and-solutions--performance-and-scale)
- [7. Problems and solutions — reviewer trust and workflow](#7-problems-and-solutions--reviewer-trust-and-workflow)
- [8. Problems and solutions — data hygiene](#8-problems-and-solutions--data-hygiene)
- [9. Problems we have NOT solved yet](#9-problems-we-have-not-solved-yet)
- [10. How we know it works](#10-how-we-know-it-works)

---

## 1. The problem we were hired to solve

Invoices arrive as PDFs — attached to email, or dropped on someone's desk. What happens
next is identical for every single invoice and entirely manual:

1. Open the PDF, read off vendor, invoice number, date and totals.
2. Retype those values into an accounting system.
3. Check that the invoice's own arithmetic is consistent.
4. Check whether that invoice has already been paid.
5. Chase an approver, then record the decision somewhere auditable.

Each invoice costs a human 3–10 minutes. Around 30% of real invoices contain a genuine
discrepancy — a wrong total, a missing subtotal, a tampered amount. A re-sent invoice is
frequently paid twice because nothing links the two PDFs. And because the work is
repetitive, reviewers eventually skim: a $20,000 arithmetic error is invisible to a tired
eye at 5pm on a Friday.

**The insight that shaped the build:** automation should not replace the reviewer, it
should *prepare* them. The system should do the reading, the typing, the arithmetic and
the duplicate check, then hand a human a pre-filled record **with an explanation of what
looked wrong** — and only for the cases where a machine genuinely cannot be sure.

That is why this is a **review portal** and not just a script.

## 2. What the portal actually is

```
Vendor emails a PDF  ─┐
                      ├─> S3 ─> EventBridge ─> Step Functions ─┐
Reviewer uploads PDF ─┘                                          │
                                                                 ▼
                                              Textract: read every field
                                                        + confidence score
                                                                 │
                                              Deterministic checks:
                                                • are required fields present?
                                                • do line items = subtotal?
                                                • does subtotal − discount
                                                  + shipping + tax = total?
                                                                 │
                                       ┌─────────────────────────┴──────────────────┐
                                       │                                            │
                              all checks pass                              anything is off
                                       │                                            │
                              AUTO-APPROVED                          REVIEW_REQUIRED
                             (DynamoDB, no human)              • reviewer email with
                                                                one-click approve/reject
                                                                • portal Review queue
                                                                    │
                                                              human decides
                                                                    │
                                                     DynamoDB + confirmation email
```

Five AWS services do the work; the portal is where the human decision happens:

| Service | Role |
|---|---|
| Amazon S3 | Raw PDFs (`invoices/`), inbound emails, permanent audit JSON |
| Amazon Textract | `AnalyzeExpense` — extracts every field **with a per-field confidence score** |
| Amazon Bedrock (Nova-Lite) | Second opinion: risk rating (LOW/MED/HIGH) and a plain-English explanation |
| Deterministic engine (our code) | The arithmetic and completeness gate — the actual decision-maker |
| Amazon DynamoDB | `invoiceId` partition key, so duplicate detection is a single `GetItem` |
| Amazon SES | Inbound email ingestion via a receipt rule |
| Amazon EventBridge | S3 event routing + all scheduled jobs |
| AWS Step Functions | Orchestrates extraction → check → wait-for-human (up to 72 h) → record |
| AWS Lambda (Java 21) | All business logic, 10 handlers |
| AWS Amplify | Hosts the React portal, auto-deploys on every push to `main` |

## 3. The two ways an invoice gets in

Both routes end in the same place, and both are counted by the daily digest:

1. **Email** — a vendor sends a PDF to `invoices@zexxity.online`. An SES receipt rule
   delivers it to S3, `ses-inbound-handler` extracts the PDF attachment and copies it into
   the invoice bucket. **Zero human involvement.**
2. **Web upload** — a reviewer drags PDFs into the portal. The browser requests a
   presigned S3 PUT URL, uploads the bytes **directly to S3** (the file never passes
   through our Lambda), and the same pipeline starts.

The portal exists mainly for route 2's reviewer, but it also serves as the human-in-the-loop
destination for route 1, because the email notification links point at it.

---

## 4. Problems and solutions — extraction and data quality

### 4.1 Textract kept the `# ` in invoice numbers, so invoices became unfindable

**Problem.** Textract often reads an invoice number as an untyped `OTHER` token — `# 13789`
rather than `13789`. We were storing that raw string as the DynamoDB **partition key**. The
consequences were quiet and nasty: a search for `13789` returned nothing, the audit JSON
was written to `audit/invoice-13789.json` while the database row said `# 13789`, and
duplicate detection compared `# 13789` against `13789` and concluded they were different
invoices — so the same invoice could be approved twice.

**Solution (three layers).**
1. `normalizeInvoiceId()` strips the leading `#` and whitespace, so `# 13789` and `#13789`
   and `13789` all collapse to one key.
2. `filenameInvoiceId()` recovers the number from the PDF name when extraction finds none
   at all (`invoice_Ralph_Arnett_17190.pdf` → `17190`).
3. A synthetic `UNKNOWN-<millis>-<random>` id as last resort, so the record is still
   auditable rather than dropped.

Duplicate detection now queries the *normalized* id.

**Outcome.** Invoice `13789` is reachable by typing `13789`. A one-time backfill migrated
**226 of 227** legacy `# `-prefixed rows to clean keys with zero failures (a full NDJSON
backup of every original item was taken first, and the table row count was unchanged at
437 — each row moved, none was lost or duplicated).

The single exception is instructive: `# 11014` and `11014` turned out to be **two different
invoices from two different vendors** (SuperStore / Aug 2012 / $9,482.30 and LEON PETROU /
Jul 2023 / 10,009) that happen to share the number 11014. Migrating it would have destroyed
one record, so it was deliberately left alone. **Invoice numbers are only unique per
vendor, not globally** — a real design lesson we now carry into the roadmap.

### 4.2 The invoice number was sometimes not extracted at all

**Problem.** On some layouts Textract returned no `INVOICE_RECEIPT_ID` field whatsoever. The
record was then stored under a synthetic `UNKNOWN-...` id, which no human could ever
reconcile against the PDF in front of them.

**Solution.** Fall back to the trailing number in the source file name, which invoice
generators reliably embed, and log which strategy produced the id so a bad id is visible
in CloudWatch rather than mysterious.

### 4.3 The vendor name disappeared from some invoices

**Problem.** Textract sometimes skips the vendor banner entirely, producing a blank
`vendorName`. We initially treated a missing vendor as a review blocker — which meant a
cosmetic OCR miss on the one field that does not affect the money was parking a perfectly
clean invoice in a human's queue.

**Solution.** Recover the vendor from the `NAME` token Textract emits, and exclude
`vendorName` from the review-blocking set — it is still recorded in `missingFields` for the
audit trail, but it no longer forces human review on its own when the arithmetic verifies.

### 4.4 Amounts arrived as text with currency symbols and commas

**Problem.** Textract returns `"$26,596.55"`, `"10009"`, `"0"` and sometimes `null`. Naive
comparison produced false mismatches and false approvals.

**Solution.** A single `parseMoney()` helper strips symbols and separators, treats absent
tax/discount as `0.0` (an OCR absence, not an invoice error), and only compares numbers
once both sides are doubles. All amounts stay pre-formatted strings for display.

---

## 5. Problems and solutions — AI validation

### 5.1 Amazon Bedrock is not enabled on this account

**Problem.** Every Bedrock call fails with
`Operation not allowed (Service: BedrockRuntime, Status Code: 400)`. Model access is
support-gated per account and cannot be self-enabled. This meant the "AI verification"
leg of the pipeline was dead — and had it been load-bearing, the whole system would have
been dead with it.

**Solution — make the deterministic engine authoritative, the AI advisory.** The routing
decision now rests on three checks we control in code:

```
AUTO-APPROVE only when ALL hold:
  • TOTAL confidence >= 95%
  • every required field present (vendor excluded, see 4.3)
  • line items == subtotal, and
    subtotal − discount + shipping + tax == total      (tolerance ±$0.50)
```

Bedrock contributes a **risk rating and an explanation**, and its verdict can *escalate* to
review — but it can no longer block an invoice whose arithmetic verifies exactly. The
comment field records the override explicitly, e.g.
`Math verifies deterministically; AI advisory (...) overridden by precise check`, so the
audit trail is honest about who decided what.

**Outcome.** The system routes correctly with or without an LLM. The agreed next step is to
add an external OpenAI-compatible provider (Groq) as the AI leg via Secrets Manager, with
Bedrock kept as fallback — not yet wired, it needs a provider key.

### 5.2 The AI kept disagreeing with the arithmetic

**Problem.** The model would report "totals do not tie out" on invoices whose numbers were
provably correct, and would ask for review merely because a tax line was missing. Those
false positives were flooding the human queue with work that had no actual risk in it.

**Solution.** Same as 5.1 — arithmetic is a fact, not an opinion. Missing tax/discount is
scored as `0` in the check, and only a check that *actually* fails escalates.

### 5.3 A real $20,000 discrepancy got flagged — and that was the point

**Problem we were happy to have.** Invoice `13789` (SuperStore, Nov 2012) reads
`subtotal 6,474.00 + shipping 122.55 = 6,596.55` but declares `total 26,596.55` — off by
exactly $20,000.

**How the system handled it.** The tie-out failed by $19,999.45, far outside the ±$0.50
tolerance, so the invoice was routed to `REVIEW_REQUIRED` at `MEDIUM` risk with the exact
arithmetic written into the reviewer's email and the portal. **A human made the call.** This
is the product working as designed, and it is the clearest single argument for why the
deterministic gate exists.

---

## 6. Problems and solutions — performance and scale

### 6.1 Every API call felt broken (5.5-second cold starts)

**Problem.** A reviewer clicking "Refresh" on an idle dashboard waited ~5.5 seconds and
reasonably assumed the site was down.

**Solution.** The four interactive Lambdas are published behind a `live` alias and pinged
every 5 minutes by a scheduled `lambda-warm` function that invokes each alias with a
`{"warmup": true}` payload; every handler short-circuits that payload after touching its
DynamoDB connection, which primes the AWS SDK connection pool and the JVM class graph.

**Outcome.**

| Case | Latency |
|---|---|
| Warm request | ~200–350 ms |
| First request on a hydrated container | ~200–300 ms |
| Cold start after >15 min idle | ~5.5 s |

We deliberately did **not** use Provisioned Concurrency or SnapStart: the account's regional
Lambda concurrency quota is only 10, and the pinger costs milliseconds of billing every five
minutes instead of reserving capacity.

### 6.2 Textract throttled us under bursty uploads

**Problem.** Uploading a folder of PDFs produced simultaneous `AnalyzeExpense` calls and
Textract returned throttling errors. Invoices were at risk of being dropped.

**Solution.** Two layers. In-function, `TEXTRACT_MAX_ATTEMPTS = 5` retries with jittered
exponential backoff. Client-side, `MAX_CONCURRENT_UPLOADS = 2` in the portal — deliberately
low so parallel uploads stay well under the concurrency quota of 10 instead of self-throttling.
The portal also retries on `[429, 500, 502, 503, 504]` with **full jitter**, so many
reviewers never retry in lockstep.

### 6.3 Dashboard numbers were wrong

**Problem.** The metric cards counted statuses on the current 20-row page, so the dashboard
could report "2 approved" while 295 invoices had been approved.

**Solution.** The backend now computes true totals (in parallel across the
`validationStatus` and `reviewDecision` indexes) and returns them beside the page:
`totalCount`, `totalApproved`, `totalReview`, `totalDuplicate`, `totalHumanApproved`,
`totalHumanRejected`, `totalPages`. The hooks prefer those numbers and only fall back to
page-local counting. **The audit trail and the headline metrics can no longer disagree.**

### 6.4 Pagination had no Previous button

**Problem.** The API returns an opaque `nextToken`, never a page number, so going back a
page was not possible with naive `page - 1` arithmetic.

**Solution.** The hooks maintain a token stack (`prevTokens[]` plus `currentToken`) so
Previous walks back through tokens actually issued by the backend.

---

## 7. Problems and solutions — reviewer trust and workflow

### 7.1 Reviewers did not trust the flags

**Problem.** A queue of "needs review" rows with no explanation is indistinguishable from a
queue of arbitrary noise. Reviewers started clicking Approve on everything just to clear it
— which destroys the entire value of the system.

**Solution.** The portal is built around legibility. Every flagged invoice shows the full
extracted record, per-field confidence bars, the explicit missing-field list, the exact
arithmetic that failed (`subtotal (6474.0) + shipping (122.55) = 6596.55 != total
(26596.55)`), and the model's plain-English comment. The reviewer never has to open the PDF
to find out why something was flagged.

### 7.2 Reviewers were not at their desk when the email arrived

**Problem.** Flagged invoices sat undecided because the notification required the reviewer
to open the portal.

**Solution.** Two paths to the same outcome. The email carries one-click
approve/reject links backed by a 72-hour HMAC-style token, and the portal has a Review
queue. Both write the same DynamoDB decision. The email also shows the source PDF name, the
confidence scores, and the reason, so the decision can be made without opening anything.

### 7.3 Approvals expired silently

**Problem.** A token older than 72 hours simply stopped working, and an invoice could sit in
`REVIEW_REQUIRED` forever with nobody noticing.

**Solution.** A daily `expired-review-cleanup` job marks undecided invoices past 72 hours
as `ESCALATED` and emails fresh approval links, and the 08:00 IST digest reports the
high-risk backlog so it cannot quietly grow.

### 7.4 Confirmation emails went to the wrong person

**Problem.** The decision confirmation was sent to a single configured address, so the
person who actually clicked Approve never got told, and accountability was muddled.

**Solution.** The `DecisionForm` now requires the reviewer's email before it will submit, and
the styled HTML confirmation is sent to **that** address, with `reviewedBy` persisted on the
record. The form states the behaviour inline so nobody wonders where the mail goes.

### 7.5 Searching for a prefixed invoice ID found nothing

**Problem.** A reviewer who could see `# 13789` in an email typed `13789` into Audit search
and got zero results — the backend compared against the raw stored key.

**Solution.** Audit search normalises a leading `#` on both sides of the comparison, and is
case-insensitive across vendor name, invoice ID, status and comments.

### 7.6 The digest miscounted the backlog

**Problem.** The daily digest's buckets were not mutually exclusive, so an invoice could be
counted as both "new" and "backlog", and "since yesterday" was ambiguous.

**Solution.** Buckets are now explicitly exclusive and the window is a precise last-24-hours
comparison (`createdAt >= now − 24h`, with epoch-seconds and ISO inputs both handled). The
digest also makes clear that **both** ingestion routes (email and web upload) are counted,
because the record does not store its source.

---

## 8. Problems and solutions — data hygiene

### 8.1 The bucket grew without bound

**Problem.** 558 raw PDFs were accumulating in S3, and the scheduled cleanup was deleting
nothing because the rule only removed files older than 30 days — and every object was newer
than that. Storage cost grew and the "cleanup exists" assumption was false.

**Diagnosis worth recording.** We suspected the schedule was monthly; it was not. The rule is
`cron(0 2 ? * SUN *)` — weekly, and enabled. The handler's `INVOICE_BUCKET` environment
variable was also correct. The only real bug was the retention window itself.

**Solution.** Retention changed from 30 days to **7 days**, so each Sunday run clears roughly
the previous week's PDFs. Audit JSON under `audit/` is **never** deleted — it is the
permanent record. Confirmed live: the job logs `S3Cleanup: no files older than 7 days found.`

### 8.2 `createdAt` was stored in two different formats

**Problem.** Older rows hold `createdAt` as a Number of epoch **seconds**
(`1790278909`); newer rows hold an ISO string (`2026-09-22T19:36:45Z`). Anything that parses
it naively renders January 1970 for the numeric rows. The portal currently does exactly
that in two components, and searching/filtering by date is unreliable across the table.

**Solved so far.** The digest and the escalation job now handle both shapes, and
`createdAt` is written as epoch seconds going forward.

**Still open.** Two frontend components call `new Date(createdAt)` without a type check, and
the legacy numeric rows have not been converted. The correct fix is at the source: emit ISO
8601 for both `createdAt` and `reviewedAt` and backfill the old rows.

### 8.3 EventBridge is at-least-once, so invoices can be processed twice

**Problem.** S3 → EventBridge → Lambda is at-least-once. A retry re-delivers the same object,
which means Textract runs again, Brevo fires again, and a reviewer gets a second email about
an invoice they are already deciding on.

**Solution.** Every extraction is idempotent on `invoiceId`: the existing record is detected
and marked `DUPLICATE` at `HIGH` risk rather than overwritten, which also preserves the
audit trail of the original decision.

---

## 9. Problems we have NOT solved yet

Being explicit about these is more useful than pretending the project is finished.

| # | Open problem | Why it matters | Planned fix |
|---|---|---|---|
| 1 | **No real authentication.** `admin` / `Zexxity@2024` is compared in the browser bundle and `sessionStorage` is the only guard. | Anyone who reads the JS can see every invoice and submit decisions. The single biggest gap. | Cognito user pool + API Gateway JWT authorizer; send `Authorization: Bearer` from the service layer |
| 2 | **Public upload endpoint.** `/invoices/upload-url` presigns for anyone. | Unbounded Textract/Bedrock spend and S3 abuse. | Per-IP throttling; validate content type and size server-side before presigning |
| 3 | **Review race condition.** A dashboard click and an email link at the same instant are last-write-wins. | Two contradictory decisions, one silently lost. | DynamoDB conditional expression on `reviewDecision` |
| 4 | **Token replay.** 72-hour links can be reused. | An approved invoice could be flipped to rejected later. | Same conditional guard + single-use token consumption |
| 5 | **No dead-letter queue, no CloudWatch alarms.** A permanently failing PDF is retried for 24 hours then dropped silently. | Failures are discovered when a user complains. | SQS between EventBridge and Lambda, DLQ with `maxReceiveCount: 5`, depth + error alarms |
| 6 | **`createdAt` type inconsistency** (section 8.2) | Wrong dates in the UI; unreliable date filtering | Emit ISO 8601 at the source; backfill legacy rows |
| 7 | **Invoice IDs are not globally unique** (section 4.1) | Two vendors legitimately share number 11014; a naive key collides | Composite key `vendorName + invoiceId`, or a resolved internal UUID |
| 8 | **No automated tests.** The project has CI that builds, but no test suite. | Regressions in the validation rules would be invisible. | Unit-test the deterministic engine, `humanReviewValue`, and the retry helpers |
| 9 | **Textract synchronous size ceiling (~5 MB).** | Large multi-page invoices are never processed. | `StartExpenseAnalysis` async path |
| 10 | **Regional single point of failure.** Everything runs in `ap-south-1`. | A regional outage takes the portal offline. | Accepted for now; optional DynamoDB/S3 replication + DNS failover |
| 11 | **`ESCALATED` invoices drop out of the review queue** and never get a second look. | Genuinely stuck invoices become invisible. | Decide the policy: keep escalating in the queue, or add an "escalated" filter |
| 12 | **Bedrock unavailable** (section 5.1) | The AI leg contributes nothing today; the dashboard's "AI Approved" label is misleading. | External OpenAI-compatible provider (Groq) via Secrets Manager; reword the metric either way |

---

## 10. How we know it works

**Live and verifiable.** <https://zexxity.online> runs the same stack described here.

**Ingestion works unattended.** 437 invoice records processed from both entry routes,
without a human touching the pipeline.

**The routing is real, not cosmetic.** A representative spread: 295 auto-approved,
77 routed to human review, 33 duplicates caught, 3 rejected — the ratio we would expect
from a system that auto-approves the clean majority and escalates only the ambiguous tail.

**The arithmetic gate catches genuine fraud.** Invoice `13789` is a live, correctly
rejected-for-review example: a $20,000 declared-total discrepancy, caught by the
deterministic check and explained to the reviewer in plain arithmetic.

**The audit trail is complete.** Every extraction, confidence score, risk rating, AI
comment, reviewer email, decision and timestamp is persisted in DynamoDB and mirrored to
immutable `audit/` JSON in S3. The portal's Audit page can filter and export all of it as
CSV.

**Performance is measured, not assumed.** ~200–350 ms warm against the live API, verified
against the deployed `live` alias rather than a local estimate.

**Automation is verified end-to-end.** All ten Lambda functions are deployed and
scheduled, including the weekly PDF cleanup (confirmed by a live invoke), the 08:00 IST
digest, the 72-hour escalation sweep, and the 5-minute warm-up pinger.

---

## Where to read next

| You want to know about… | Read |
|---|---|
| The frontend in detail — components, hooks, API contract, known bugs | [`FRONTEND.md`](FRONTEND.md) |
| Backend architecture, DynamoDB schema, API endpoints, deployment | [`../README.md`](../README.md) |
| Diagrams, decisions, and the reasoning behind the architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Load-test methodology and results | [`../load-tests/README.md`](../load-tests/README.md) |
