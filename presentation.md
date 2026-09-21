---
marp: true
theme: default
paginate: true
size: 16:9
headingDivider: 2
backgroundColor: #ffffff
style: |
  section {
    font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;
    font-size: 24px;
  }
  section.lead {
    background: linear-gradient(135deg, #0b2e4f 0%, #1f6fb2 100%);
    color: #ffffff;
  }
  section.lead h1 { color: #ffffff; font-size: 56px; }
  section.lead p { color: #dce9f5; }
  h1 { color: #0b2e4f; }
  h2 { color: #1f6fb2; border-bottom: 3px solid #1f6fb2; padding-bottom: 4px; }
  table { font-size: 18px; }
  code { font-size: 16px; }
  blockquote { color: #444; font-style: italic; }
---

# Invoice Processing System

Event-driven, serverless invoice automation with AI-powered extraction and human-in-the-loop review

Version 2.0 | Live: `https://zexxity.online` | Region: `ap-south-1` (Mumbai)


## Agenda

1. Problem & business case
2. Solution & architecture
3. AI extraction, validation & routing
4. Human-in-the-loop review
5. Data model & auditability
6. Automation & scheduled jobs
7. Performance & cold-start tuning
8. Resilience, failures & roadmap
9. Security, cost & DevOps
10. Demo + Q&A


## Problem Statement

| Pain point | Impact |
|---|---|
| Invoices arrive by email as PDFs | Manual data entry, slow turnaround |
| Every invoice layout differs by vendor | OCR alone is not enough |
| No tolerance for errors | Bad data flows into finance systems |
| Duplicates and fraud are hard to catch | Money lost, trust eroded |
| No audit trail of decisions | Compliance risk |

**Goal:** automate intake end-to-end - extract, validate, risk-score, and approve or route to a human reviewer, with every decision recorded.


## Solution Overview

**Three lanes - Ingestion, Processing, Review**

```
INGESTION
  Vendor email / Browser upload
        |
        v
PROCESSING           AI Extraction (Textract)
        |           + Validation & Risk (Bedrock)
        v
    DynamoDB (persist)  ->  AUTO-APPROVE (clean)
                           |
                           +-> REVIEW_REQUIRED (uncertain/incomplete/duplicate)
                                    |
                                    v
REVIEW   Web dashboard  |  one-click email approval (72h token)
```

- Approved invoices: zero manual processing
- Uncertain invoices: routed to a human reviewer with full context
- Every decision recorded for auditability


## Architecture - AWS Services

```
Vendor email -> SES Receipt Rule -> S3 ses-inbound-emails-m3
Browser upload -> UploadUrlLambda -> presigned PUT -> S3 invoice-processing-buckets-m3

S3 Object Created -> EventBridge -> InvoiceExtractionLambda
        |- AWS Textract  (AnalyzeExpense, per-field confidence)
        |- Bedrock Nova-Lite (validation, risk LOW/MED/HIGH, explanation)
        |- DynamoDB PutItem  (persist invoice)
        |- SES v2           (email reviewer when REVIEW_REQUIRED)

Reviewer -> API Gateway -> GetInvoice / ApproveReject / UploadUrl / TokenApproval
Email link -> TokenApprovalLambda -> 72h token -> Decision -> HTML confirmation
```


## AWS Services

| Service | Purpose |
|---|---|
| AWS Lambda (Java 21) | All business logic (11 handlers) |
| API Gateway (HTTP API) | REST endpoints for the frontend |
| S3 | Invoice PDFs, inbound emails, audit JSON, deploy JARs |
| DynamoDB | Invoice records + review decisions (on-demand) |
| AWS Textract | PDF extraction with per-field confidence |
| Amazon Bedrock (Nova-Lite) | AI validation, risk scoring, explanations |
| SES v2 | Confirmation + reviewer notification emails |
| SES Receipt Rules | Inbound email ingestion |
| Secrets Manager | Config: sender, reviewer, model ID, frontend URL |
| EventBridge | S3 event routing + scheduled jobs |
| AWS Amplify | Frontend hosting + CI/CD from GitHub |
| CloudWatch | Logs and metrics for all functions |


## Ingestion Channels

**1. Inbound email** - `invoices@zexxity.online`

| Item | Value |
|---|---|
| Domain | `zexxity.online` |
| Receipt rule set | `invoice-inbound` |
| Action | Deliver PDF to `ses-inbound-emails-m3/emails/` |
| Trigger | `ses-inbound-handler` copies to invoice bucket |
| MX record | `inbound-smtp.ap-south-1.amazonaws.com` |

Requires SES production access (out of sandbox) + verified domain identity.

**2. Browser upload** - `UploadUrlHandler` issues a 5-minute presigned S3 PUT URL.


## Extraction Pipeline (InvoiceExtractionLambda)

```
S3 Object Created event
       |
       v
1. AWS Textract AnalyzeExpense
       | -> invoiceId, vendorName, invoiceDate, total, subtotal
       | -> per-field confidence scores (0-100%)
       v
2. Amazon Bedrock Nova-Lite
       | -> validation of extracted fields
       | -> risk score: LOW / MEDIUM / HIGH
       | -> explanation comments + missing fields
       v
3. DynamoDB PutItem
       | -> persist invoice record with avg confidence
       v
4. SES notification if REVIEW_REQUIRED
```

Every extracted field carries a confidence score; average confidence, risk, and the Bedrock explanation are stored for transparency.


## Routing & Decision Logic

```
totalConfidence = Textract confidence on TOTAL field

if totalConfidence < 95%:
    validationStatus = REVIEW_REQUIRED
    SES email with one-click approve/reject links (72h token)

else:
    Bedrock Nova-Lite validation
    if critical field missing (invoiceId / total / vendorName):
        validationStatus = REVIEW_REQUIRED
    else:
        validationStatus = APPROVED

Duplicate detection:
    if invoiceId already exists -> DUPLICATE (risk = HIGH)
```

Confidence thresholds decide the boundary between automatic approval and human review.


## Human-in-the-Loop - Review

**Two ways to review an invoice**

| Channel | Flow |
|---|---|
| Web dashboard | `/review` queue -> approve / reject + note |
| One-click email | 72h token -> approve/reject -> HTML confirmation |

**Frontend pages (React 19 + Vite, Amplify-hosted)**

- `/` - Dashboard: totals, approved, review queue, duplicates, avg confidence
- `/upload` - drag-and-drop PDF upload with progress
- `/review` - pending-approval queue with decision form
- `/audit` - full history with filters and CSV export
- `/login` - protected reviewer sign-in (client-side route guard)


## Email Approval Flow

```
Invoice flagged REVIEW_REQUIRED
        |
        v
SES email sent with:
  - invoice ID, vendor, amount, confidence scores
  - one-click APPROVE / REJECT links (72h Base64URL token)
  - link to review dashboard: https://zexxity.online/review
        |
        v
Reviewer clicks link -> TokenApprovalLambda
  - validates token expiry (72h) and not-already-decided
  - writes reviewDecision to DynamoDB
  - returns HTML confirmation page

Dashboard review -> ApproveRejectLambda
  - DynamoDB update + SES confirmation run concurrently
  - (CompletableFuture.allOf returns only when both finish)
```


## Data Model - DynamoDB

**Table:** `invoices` - Partition key `invoiceId` - Billing: `PAY_PER_REQUEST`

**Global Secondary Indexes**

| Index | Key |
|---|---|
| `validationStatus-index` | `validationStatus` (HASH) |
| `reviewDecision-index` | `reviewDecision` (HASH) |

**Key attributes**

| Attribute | Description |
|---|---|
| `totalConfidence` | Confidence on TOTAL - drives routing |
| `avgConfidence` | Mean of all field confidence scores |
| `risk` | `LOW` / `MEDIUM` / `HIGH` (Bedrock) |
| `validationStatus` | `APPROVED` / `REVIEW_REQUIRED` / `DUPLICATE` |
| `missingFields` | Comma-separated missing fields (Bedrock) |
| `reviewDecision` | `APPROVED` / `REJECTED` / `ESCALATED` |
| `reviewedBy` / `reviewedAt` / `reviewNote` | Human decision trail |

Every decision - machine or human - is fully auditable.


## Dashboard Metrics

Computed in parallel against the two GSIs:

- Total invoices
- AI approved / rejected
- Review queue size (pending)
- Duplicates flagged
- Average AI confidence

`GetInvoiceHandler` runs the metric counts concurrently for low-latency responses.


## Scheduled Jobs

| Function | Schedule | Action |
|---|---|---|
| `daily-digest-report` | Daily 08:00 IST | Email summary: new invoices, backlog, high-risk pending |
| `expired-review-cleanup` | Daily | Escalate `REVIEW_REQUIRED` undecided after 72h; send fresh links |
| `weekly-s3-cleanup` | Sunday 02:00 UTC | Delete raw PDFs older than 30 days. Audit JSON never deleted. |

Plus `lambda-warm` - every 5 minutes, synchronously invokes the four interactive `live` aliases to keep them hot.


## API Surface

Base: `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`

| Method | Path | Lambda | Description |
|---|---|---|---|
| `GET` | `/invoices` | GetInvoiceLambda | List + dashboard metrics |
| `GET` | `/invoices?id=<id>` | GetInvoiceLambda | Single invoice |
| `POST` | `/invoices/upload-url` | UploadUrlLambda | Presigned S3 PUT URL (5 min) |
| `POST` | `/invoices/review` | ApproveRejectLambda | Submit APPROVED / REJECTED |
| `GET` | `/invoices/approve?token=` | token-approval | One-click approve (email) |
| `GET` | `/invoices/reject?token=` | token-approval | One-click reject (email) |


## Performance - Load Testing

- **Tool:** Apache JMeter suite in `load-tests/`
- **Profiles:**
  - `smoke` - 1 VU, ~30 s sanity check
  - `baseline` - 10-15 VUs, ~2 min
  - `stress` - up to 50 VUs, ~5 min

**Targets / results referenced in the project notes**

| Metric | Value |
|---|---|
| Total requests | 29,000+ |
| Throughput | 77+ requests/sec |
| P95 latency | 237 ms |
| P99 latency | 465 ms |
| Success rate | 100% |

Covers all API endpoints: list, detail, upload-url, review, token approval.


## Performance - Cold-Start Tuning

Interactive functions published with a `live` alias; a `lambda-warm` scheduled job (every 5 min) invokes them synchronously.

Handlers build AWS SDK clients eagerly so class graphs initialize on the first call.

**Measured against live API** (`GET /invoices`, 512 MB, ap-south-1):

| Case | Latency |
|---|---|
| Warm request (steady traffic) | ~200-350 ms |
| First request on hydrated container | ~200-300 ms |
| Cold start after >15 min idle | ~5.5 s |


## Performance - SnapStart Evaluation

Evaluated Lambda **SnapStart** (snapshot of initialized JVM restored on cold start):

| Scenario | Restore / Init | Cold request | Warm request |
|---|---|---|---|
| Before - no SnapStart | Init ~ 2,600 ms | ~ 5.5 s | 200-350 ms |
| After - SnapStart on `live` | Restore ~ 360-570 ms | ~ 0.6-1.0 s | 200-350 ms |

**Findings**
- Restore is ~5x faster than init
- SDK clients must be built eagerly (static final fields) - lazy init after restore caused ~10 s first calls
- Works only on published versions (alias/version, never `$LATEST`)
- Not all state survives snapshot: connections, random seeds, credentials rebuild on restore

**Why not enabled:** SnapStart cannot combine with Provisioned/Reserved Concurrency, and regional concurrency quota is only 10. Warm-up lambda is the zero-quota-change alternative. If quota is raised -> Provisioned Concurrency on `live`.


## Reliability Model

- **S3 -> EventBridge -> Lambda:** retries up to 24 h / 185 attempts; persistent failures are silently dropped (no DLQ today)
- **Lambda async invocations:** retried twice
- **SES inbound receipt rule:** ~2-3 retries ~20 min apart, then the email is lost
- **Browser -> S3 upload:** no client-side retry
- EventBridge is **at-least-once** - extraction must be idempotent (dedupe on `invoiceId` / `objectKey`)

Acknowledged trade-off, with a hardening roadmap.


## Known Failure Modes (13 documented)

| # | Risk | Impact |
|---|---|---|
| 1 | Poison messages (password-protected / corrupt PDFs) | Retries up to 24h, then silently dropped |
| 2 | Large / multi-page invoices over Textract/60s limits | Never processed |
| 3 | Upload network failure / expired presigned URL | Manual re-upload needed |
| 4 | Duplicate processing from S3/EventBridge retries | Double processing cost |
| 5 | Email without legible PDF (HTML-only, docx) | Nothing extracted |
| 6 | Abuse / billing attack on public endpoints | Unbounded spend |
| 7 | Unauthenticated review data (`GET /invoices`) | Data-leak risk |
| 8 | Review race (dashboard + email link simultaneously) | Double decision, last-write-wins |
| 9 | Token replay within 72h window | Repeated approvals |
| 10 | Silent operational failure (no DLQ, no alarms) | Failures unnoticed |
| 11 | Invoice stuck if cleanup job fails | Queue grows silently |
| 12 | Sender/billing deps, SES sandbox verified recipients | Emails rejected |
| 13 | Single region `ap-south-1` | Regional outage = full outage |


## Roadmap - Proposed Hardening

1. **Durable pipeline: SQS + DLQ** - route S3 -> EventBridge -> SQS, consume from queue, DLQ `maxReceiveCount: 5`, alarm on DLQ depth
2. **Frontend upload retry** - re-request URL + exponential backoff (3-5 attempts); client-side size/type limits
3. **Idempotent extraction** - dedupe by `objectKey` before Textract / conditional write
4. **Async Textract** (`StartExpenseAnalysis`) for multi-page / large PDFs
5. **Real authorization & rate limiting** - Cognito / JWT authorizer instead of route guard; per-IP throttling on upload-url; validate content type + size
6. **Operational observability** - CloudWatch alarms (errors, throttles, DLQ, SES bounces), DLQ replay runbook
7. **Security hardening** - SSE-S3/KMS, versioning, DynamoDB at-rest encryption, least-privilege IAM, CloudTrail
8. **Regional DR (optional)** - replicate DynamoDB + S3, DNS failover


## Security

**Implemented**
- Secrets in AWS Secrets Manager (sender, reviewer, model ID, frontend URL)
- SES domain identity + receipt rules
- Presigned URLs with expiry for uploads

**Known gaps (documented, on roadmap)**
- No real authorization - `GET /invoices` behind a client-side route guard only
- Public API endpoints, no rate limiting
- No automated escalation to CloudWatch alarms
- No encryption-at-rest config surfaced (S3 default encryption, DynamoDB KMS)


## Cost Optimization

- **Serverless:** pay-per-use on Lambda, API Gateway, DynamoDB
- **DynamoDB on-demand (`PAY_PER_REQUEST`)** - no idle capacity cost
- **Warm-up lambda:** a few ms of billing every 5 minutes - cheaper than Provisioned Concurrency
- **Textract / Bedrock:** per-call pricing; rationalized by routing only uncertain invoices to review
- **Weekly S3 cleanup** deletes stale raw PDFs (>30 days) to control storage cost - audit JSON kept forever


## CI/CD & DevOps

- **GitHub Actions** (`.github/workflows/maven.yml`) - Maven build on every push/PR + dependency graph for Dependabot (SBOM)
- **AWS Amplify** - builds and hosts React frontend; auto-deploys on push to `main`
- **Build & deploy:**
  ```powershell
  mvn clean package -DskipTests
  .\deploy.ps1
  aws lambda update-function-code --function-name ApproveRejectLambda ...
  ```
- **Migration:** `migration/MIGRATION.md` runbook, account migration scripts, SAM `template-v2.yaml`


## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, AWS Lambda |
| Serverless | SAM / CloudFormation (`template.yaml`) |
| Frontend | React 19 + Vite, AWS Amplify |
| AI | AWS Textract, Amazon Bedrock (Nova-Lite) |
| Storage | S3, DynamoDB |
| Messaging/Events | EventBridge, SQS (roadmap) |
| Email | SES v2, SES receipt rules |
| Testing | JMeter load suites |
| IaC / CICD | SAM, GitHub Actions, Amplify |


## Demo Script

1. **Upload** a normal PDF invoice via the dashboard -> wait for extraction
2. **Auto-approve path:** show the invoice marked `APPROVED` with confidence, risk, and Bedrock explanation on the dashboard
3. **Review path:** upload/trigger a low-confidence or incomplete invoice -> show `REVIEW_REQUIRED`
4. **Email approval:** open the email, click approve -> HTML confirmation -> dashboard reflects decision
5. **Audit:** filter history, export CSV
6. **Scheduled reports:** show today's digest email

Keep it to 2-3 minutes; prepare backups for the failure path.


## Takeaway

> The system automates invoice intake end-to-end, extracting and validating invoices with AI, auto-approving clean invoices, and routing anything uncertain to a human reviewer - with a full audit trail.

- Zero manual processing for auto-approved invoices
- Confidence + AI risk scoring drive the human-in-the-loop boundary
- Load-tested, cold-start-tuned, event-driven serverless design
- Clear roadmap for durable delivery, security, and DR


<!-- _class: lead -->

# Thank You

Questions?

Contact / links
- Live app: https://zexxity.online
- Repository: invoice-processing
- Stack: Java 21 + React 19 + AWS (Lambda, S3, DynamoDB, Textract, Bedrock, SES)