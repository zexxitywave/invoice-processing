# Invoice Processing System

> Event-driven, serverless invoice automation with AI-powered extraction and human-in-the-loop review.

**Version:** 2.0
**Live URL:** https://zexxity.online
**Default Region:** `ap-south-1` (Mumbai) — single-region deployment
**Runtime:** Java 21 · AWS Lambda
**Frontend:** React 19 + Vite · AWS Amplify

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [AWS Services](#aws-services)
- [Lambda Functions](#lambda-functions)
- [API Endpoints](#api-endpoints)
- [Extraction & Routing Logic](#extraction--routing-logic)
- [DynamoDB Schema](#dynamodb-schema)
- [Email Approval Flow](#email-approval-flow)
- [Inbound Email (SES)](#inbound-email-ses)
- [Scheduled Jobs](#scheduled-jobs)
- [Frontend](#frontend)
- [Project Structure](#project-structure)
- [Local Build & Deploy](#local-build--deploy)
- [CI/CD](#cicd)
- [Load Testing](#load-testing)

---

## Overview

The system automates invoice intake end-to-end. Vendors email PDF invoices to
`invoices@zexxity.online` (or staff upload them through the web UI). Each invoice
is extracted with **AWS Textract**, validated and risk-scored by **Amazon Bedrock
(Nova-Lite)**, and persisted to **DynamoDB**. Invoices that extract cleanly are
auto-approved; anything uncertain, incomplete, or duplicated is routed to a human
reviewer through a dashboard or a one-click email approval link. Every decision is
recorded for full auditability.

Zero manual processing for auto-approved invoices.

---

## Architecture

```
INGESTION
─────────
Vendor email → invoices@zexxity.online
      │
      ▼
Amazon SES Receipt Rule (ap-south-1) → S3 ses-inbound-emails-m3/emails/
      │
      ▼
SesInboundHandler → copies PDF to invoice bucket (ap-south-1)

Browser Upload → UploadUrlHandler → presigned S3 PUT URL → S3 invoice-processing-buckets-m3/invoices/

PROCESSING
──────────
S3 Object Created → EventBridge → InvoiceExtractionHandler
      │
      ├─ AWS Textract (AnalyzeExpense) → fields + confidence scores
      ├─ Amazon Bedrock Nova-Lite       → validation + risk score (LOW/MED/HIGH)
      ├─ DynamoDB PutItem               → persist invoice record
      └─ Amazon SES (sesv2)             → email reviewer when REVIEW_REQUIRED

REVIEW
──────
Reviewer → https://zexxity.online
      ├─ Dashboard — live metrics (totals, approved, review queue, duplicates)
      ├─ Upload    — drag-and-drop PDF upload
      ├─ Review    — pending-approval queue with approve / reject + note
      └─ Audit     — full history with filters and CSV export

      OR one-click approval from email
      └── TokenApprovalHandler → validates 72h token → writes decision → HTML confirmation
```

---

## AWS Services

| Service | Purpose |
|---|---|
| AWS Lambda (Java 21) | All business logic (11 handlers) |
| Amazon API Gateway (HTTP API) | REST endpoints for the frontend |
| Amazon S3 | Invoice PDFs, inbound emails, audit JSON, deployment JARs |
| Amazon DynamoDB | Invoice records + review decisions (on-demand billing) |
| AWS Textract | PDF extraction (AnalyzeExpense) with per-field confidence |
| Amazon Bedrock (Nova-Lite) | AI validation, risk scoring, explanations |
| Amazon SES v2 | Confirmation + reviewer notification emails |
| Amazon SES Receipt Rules | Inbound email ingestion (`ap-south-1`) |
| AWS Secrets Manager | Config: sender, reviewer, model ID, frontend URL |
| AWS EventBridge | S3 event routing + scheduled jobs |
| AWS Amplify | Frontend hosting + CI/CD from GitHub |
| Amazon CloudWatch | Logs and metrics for all functions |

---

## Lambda Functions

| Function | Handler | Trigger | Timeout |
|---|---|---|---|
| `invoice-extraction-lambda` | `InvoiceExtractionHandler` | EventBridge (S3 event) | 60 s |
| `ses-inbound-handler` | `SesInboundHandler` | SES receipt rule | 60 s |
| `GetInvoiceLambda` | `GetInvoiceHandler` | API Gateway `GET` | 30 s |
| `ApproveRejectLambda` | `ApproveRejectHandler` | API Gateway `POST` | 30 s |
| `UploadUrlLambda` | `UploadUrlHandler` | API Gateway `POST` | 15 s |
| `token-approval` | `TokenApprovalHandler` | API Gateway `GET` | 30 s |
| `daily-digest-report` | `DailyDigestHandler` | EventBridge cron 08:00 IST | 120 s |
| `expired-review-cleanup` | `ExpiredReviewCleanupHandler` | EventBridge daily | 120 s |
| `weekly-s3-cleanup` | `S3CleanupHandler` | EventBridge Sunday 02:00 UTC | 120 s |

All run in `ap-south-1`.

---

## API Endpoints

**Base URL:** `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`

| Method | Path | Lambda | Description |
|---|---|---|---|
| `GET` | `/invoices` | GetInvoiceLambda | List invoices + dashboard metrics |
| `GET` | `/invoices?id=<id>` | GetInvoiceLambda | Single invoice by ID |
| `POST` | `/invoices/upload-url` | UploadUrlLambda | Presigned S3 PUT URL (5 min) |
| `POST` | `/invoices/review` | ApproveRejectLambda | Submit APPROVED / REJECTED decision |
| `GET` | `/invoices/approve?token=` | token-approval | One-click approve (from email) |
| `GET` | `/invoices/reject?token=` | token-approval | One-click reject (from email) |

Dashboard metrics are computed in parallel against the `validationStatus-index`
and `reviewDecision-index` GSIs.

---

## Extraction & Routing Logic

```
totalConfidence = Textract confidence on the TOTAL field (0–100%)

if totalConfidence < 95%:
    validationStatus = REVIEW_REQUIRED
    SES email sent with one-click approve/reject links (72h token)

else:
    Bedrock Nova-Lite validation runs
    if a critical field is missing (invoiceId / total / vendorName):
        validationStatus = REVIEW_REQUIRED
    else:
        validationStatus = APPROVED

Duplicate detection:
    if invoiceId already exists → DUPLICATE (risk = HIGH)
```

Each extracted field carries a confidence score; average confidence is recorded
alongside the risk level and the Bedrock explanation for transparency.

---

## DynamoDB Schema

**Table:** `invoices` · **Partition key:** `invoiceId` (String) · **Billing:** `PAY_PER_REQUEST`

**Global Secondary Indexes:**

| Index | Key |
|---|---|
| `validationStatus-index` | `validationStatus` (HASH) |
| `reviewDecision-index` | `reviewDecision` (HASH) |

**Attributes:**

| Attribute | Type | Description |
|---|---|---|
| `invoiceId` | S | Extracted by Textract |
| `vendorName` | S | Extracted by Textract |
| `invoiceDate` | S | Extracted by Textract |
| `total` | S | Extracted by Textract |
| `subtotal` | S | Extracted by Textract (may be null) |
| `totalConfidence` | N | Confidence on TOTAL field — drives routing |
| `avgConfidence` | N | Mean of all field confidence scores |
| `risk` | S | Bedrock — `LOW` / `MEDIUM` / `HIGH` |
| `validationStatus` | S | `APPROVED` / `REVIEW_REQUIRED` / `DUPLICATE` |
| `comments` | S | Bedrock explanation |
| `missingFields` | S | Comma-separated missing fields (Bedrock) |
| `reviewDecision` | S | Human decision — `APPROVED` / `REJECTED` / `ESCALATED` |
| `reviewedBy` | S | Reviewer email or `email-link` |
| `reviewedAt` | S | ISO 8601 timestamp |
| `reviewNote` | S | Free-text reviewer note |

---

## Email Approval Flow

```
Invoice flagged REVIEW_REQUIRED
        │
        ▼
InvoiceExtractionHandler sends SES email with:
  • invoice ID, vendor, amount, confidence scores
  • one-click APPROVE / REJECT links (72h Base64URL token)
  • link to the review dashboard: https://zexxity.online/review
        │
        ▼
Reviewer clicks link → TokenApprovalHandler
  • validates token expiry (72h) and not-already-decided
  • writes reviewDecision to DynamoDB
  • returns HTML confirmation page with link back to dashboard

Reviewer uses UI → ApproveRejectLambda
  • DynamoDB update + SES confirmation email run concurrently
    (CompletableFuture.allOf — returns only when both complete)
  • confirmation email sent to reviewer with decision summary
```

---

## Inbound Email (SES)

Inbound invoices arrive at `invoices@zexxity.online` via Amazon SES receipt rules.

| Item | Value |
|---|---|
| Domain | `zexxity.online` |
| Receipt rule set | `invoice-inbound` |
| Rule | `save-and-process-invoices` (enabled) |
| Action | Deliver to S3 bucket `ses-inbound-emails-m3/emails/` |
| Trigger | Invokes `ses-inbound-handler` |
| MX record | `inbound-smtp.ap-south-1.amazonaws.com` |

Receiving mail requires SES production access (out of the sandbox) and the domain
identity verified.

---

## Scheduled Jobs

| Function | Schedule | Action |
|---|---|---|
| `daily-digest-report` | Daily 08:00 IST | Emails summary: new invoices, backlog, high-risk pending |
| `expired-review-cleanup` | Daily | Escalates `REVIEW_REQUIRED` items undecided after 72 hours; sends fresh links |
| `weekly-s3-cleanup` | Sunday 02:00 UTC | Deletes raw PDFs older than 30 days. Audit JSON is never deleted. |

---

## Frontend

React 19 + Vite SPA hosted on AWS Amplify (auto-deploys on push to `main`).
Connects to API Gateway via the `VITE_API_BASE_URL` build-time environment variable.

**Pages:**

- `/` — Dashboard: totals, AI approved/rejected, review queue, duplicates, average confidence
- `/upload` — Drag-and-drop PDF upload with progress tracking
- `/review` — Pending-approval queue with decision form
- `/audit` — Full history, filters, and CSV export
- `/login` — ProtectedRoute-gated reviewer sign-in

**Local development:**

```bash
cd invoice-reviewer-react
npm install
npm run dev        # http://localhost:5173
```

---

## Project Structure

```
invoice-processing/
├── src/main/java/com/invoice/processing/
│   ├── ApproveRejectHandler.java         POST /invoices/review + concurrent SES confirmation
│   ├── DailyDigestHandler.java           scheduled digest email
│   ├── ExpiredReviewCleanupHandler.java  daily 72h escalation
│   ├── GetInvoiceHandler.java            GET /invoices (parallel GSI metric counts)
│   ├── InvoiceData.java                  Textract data model
│   ├── InvoiceExtractionHandler.java     core pipeline (Textract + Bedrock + SES)
│   ├── S3CleanupHandler.java             weekly PDF cleanup
│   ├── SecretsManagerConfig.java         singleton config from Secrets Manager
│   ├── SesInboundHandler.java            inbound email ingestion
│   ├── TokenApprovalHandler.java         one-click email approval
│   └── UploadUrlHandler.java             presigned S3 URL generator
├── invoice-reviewer-react/               React + Vite frontend (Amplify hosted)
│   ├── src/
│   │   ├── components/                   Navbar, MetricCard, FileQueue, DecisionForm, ...
│   │   ├── pages/                        Dashboard, Upload, Review, Audit, Login
│   │   ├── services/                     auth, dashboard, review, upload, audit
│   │   └── hooks/                        useDashboard, useReview, useUpload, useAudit
│   ├── package.json
│   └── vite.config.js
├── load-tests/                           JMeter suite (all API endpoints)
├── migration/                            account-migration runbook + SAM template v2
├── .github/workflows/maven.yml           CI: Maven build + dependency graph
├── amplify.yml                           Amplify build config
├── deploy.ps1                            one-command AWS deployment
├── template.yaml                         SAM / CloudFormation (backend)
└── pom.xml
```

---

## Local Build & Deploy

**Prerequisites:** Java 21, Maven 3.9+, AWS CLI v2, SAM CLI.

```bash
# Build the Lambda JAR
mvn clean package -DskipTests

# Upload to the SAM staging bucket
aws s3 cp target/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  s3://invoice-processing-deploy-<account-id>/lambda/invoice-lambda.jar \
  --region ap-south-1

# Update a single Lambda (example)
aws lambda update-function-code \
  --function-name ApproveRejectLambda \
  --s3-bucket invoice-processing-deploy-<account-id> \
  --s3-key lambda/invoice-lambda.jar \
  --region ap-south-1
```

Or deploy everything with the helper script:

```powershell
.\deploy.ps1
```

The frontend deploys automatically through Amplify on every push to `main`.

---

## CI/CD

- **GitHub Actions** (`.github/workflows/maven.yml`) builds the project with Maven on
  every push/PR and submits the dependency graph for Dependabot.
- **AWS Amplify** builds and hosts the React frontend, auto-deploying on push to `main`.

---

## Load Testing

JMeter suites live in `load-tests/` and cover all API endpoints.

```powershell
.\load-tests\run.ps1 -Profile smoke      # 1 VU, ~30 s sanity check
.\load-tests\run.ps1 -Profile baseline   # 10–15 VUs, ~2 min
.\load-tests\run.ps1 -Profile stress     # up to 50 VUs, ~5 min
```

---

## Resilience, Known Limitations & Future Roadmap

### Current reliability model

Failure recovery relies on **built-in retries only**:

- **S3 → EventBridge → Lambda:** EventBridge retries up to **24 h / 185 attempts**.
  On persistent failure the event is **silently dropped** — no dead-letter queue.
- **Lambda async invocations:** retried twice; a DLQ is only used *if configured* (not today).
- **SES inbound receipt rule:** retried ~2–3 times ~20 min apart, then **the email is lost**.
- **Browser → S3 upload:** **no client-side retry** — a failed PUT requires a manual retry.

Notably, EventBridge is **at-least-once**: retries can re-process the same object, so
extraction must be idempotent (dedupe on `invoiceId` / `objectKey`).

### Cold-start tuning (warm-up lambda)

The four interactive functions (`GetInvoiceLambda`, `ApproveRejectLambda`,
`UploadUrlLambda`, `token-approval`) are published with a `live` alias. A
scheduled `lambda-warm` (every 5 minutes) synchronously invokes all four `live`
aliases, keeping their execution environments warm. Handlers build AWS SDK
clients eagerly so their class graphs are fully initialized on the first call.

Measured against the live API (`GET /invoices`, 512 MB, ap-south-1):

| Case | Latency |
|---|---|
| Warm request (steady traffic) | ~200–350 ms |
| First request on a hydrated container | ~200–300 ms |
| Cold start after >15 min idle (rare) | ~5.5 s |

Notes: Lambda **SnapStart** was evaluated but rejected in production because this
account's regional concurrency quota is only **10**, and AWS does not allow
Provisioned Concurrency on SnapStart functions (nor PC at all once it would drop
unreserved concurrency below the 10 minimum). The warm-up lambda costs a few
milliseconds of billing every 5 minutes and is the zero-quota-change way to keep
the interactive path hot. If traffic grows, raise quota `L-B99A9384` and switch
to Provisioned Concurrency on the `live` alias instead.

### SnapStart evaluation — before & after

Before settling on the warm-up lambda we evaluated **Lambda SnapStart** on the four
interactive functions (the published `live` versions, 512 MB, ap-south-1). SnapStart
takes a snapshot of the fully-initialized JVM — class graph, eager SDK clients, and
JIT state — and **restores that snapshot on cold start instead of re-running init**.

| Scenario | `Init` / `Restore` duration | Cold request (end-to-end) | Warm request |
|---|---|---|---|
| **Before** — no SnapStart | `Init Duration ≈ 2,600 ms` | ≈ 5.5 s | ≈ 200–350 ms |
| **After** — SnapStart on `live` | `Restore Duration ≈ 360–570 ms` (`RESTORE_REPORT`) | ≈ 0.6–1.0 s | ≈ 200–350 ms |

What the evaluation showed:

- **Restore is ~5× faster than init.** The first request after a cold start pays
  ~0.4 s of snapshot restore instead of ~2.6 s of JVM + SDK initialization.
- **SDK clients must be built eagerly.** The four handlers construct their AWS SDK
  clients in `static final` fields so they exist *before* the snapshot is taken. When
  we tried lazy construction (creating clients on first use, i.e. *after* restore), the
  very first call ballooned to ~10 s because the entire SDK graph had to initialize
  post-restore. Eager init is what makes the snapshot pay off — and it also helps the
  warm-up path described above.
- **SnapStart works only on published versions.** The functions use
  `AutoPublishAlias: live`, and both API Gateway and the warm-up lambda target the
  alias/version (never `$LATEST`).
- **Not all state survives the snapshot.** Open network connections, random seeds, and
  credentials are not preserved; a restored environment rebuilds them, so the first
  outbound call still pays normal connection setup.

**Why it is not enabled in production:** SnapStart cannot be combined with Provisioned
Concurrency, and this account's regional concurrency quota is only **10** (`L-B99A9384`;
the default is 1000 and it is still ramping up). Reserving any concurrency would drop
unreserved concurrency below the required minimum of 10, so neither Provisioned nor
Reserved Concurrency is possible today. The warm-up lambda was chosen as the
zero-quota-change alternative. If the quota is raised, the plan is to enable Provisioned
Concurrency on the `live` alias, at which point SnapStart becomes optional (the two are
mutually exclusive).

### Known failure modes & edge cases

| # | Risk | Failure scenario | Impact |
|---|---|---|---|
| 1 | Poison messages | A PDF is password-protected, corrupt, or a non-supported format | Function retries for up to 24 h, then silently drops the invoice |
| 2 | Large / multi-page invoices | PDFs over the synchronous Textract limits (≈5 MB) or very long documents time out the 60 s Lambda | Invoice never processed or is dropped |
| 3 | Upload network failure | Browser PUT to the presigned URL fails mid-flight; the 5-min URL expires during retry | User must re-upload manually; no queue/retry |
| 4 | Duplicate processing | Same object re-delivered by S3/EventBridge retry | Duplicate record or double Textract/Bedrock cost |
| 5 | Email without a legible PDF | HTML-only email, no attachment, `docx`/`xlsx` attachment, scanned/photo PDF below quality bar | Nothing extracted; email lost after SES retries |
| 6 | Abuse / billing attack | Public API endpoints: anyone can call `/invoices/upload-url` and upload arbitrary files via presigned URLs | Unbounded Textract/Bedrock spend; storage abuse |
| 7 | Unauthenticated review data | `GET /invoices` exposes vendor names + amounts behind only a client-side route guard | Data leak risk; no real authorization |
| 8 | Review race condition | Reviewer approves via dashboard and the one-click email link simultaneously | Double decision; last-write-wins without a conditional update |
| 9 | Token replay | One-click approval token valid for 72 h | Repeated approvals possible if no idempotency check |
| 10 | Silent operational failure | No dead-letter queue, no CloudWatch alarms, no SES bounce/complaint handling | Failures go unnoticed until a user complains |
| 11 | Invoice stuck forever | `expired-review-cleanup` escalates undecided items; if that scheduled job itself fails, items remain `REVIEW_REQUIRED` indefinitely | Review queue grows silently |
| 12 | Sender/billing dependencies | Backend default config points to a stale API URL; sandbox-mode SES limits verified recipients | Emails rejected (`Email address is not verified`) |
| 13 | Regional single point of failure | Everything lives in `ap-south-1` | Regional outage takes the whole system offline (accepted cost trade-off) |

### Roadmap — proposed hardening

1. **Durable pipeline with SQS + DLQ**
   Route `S3 → EventBridge → SQS` and let `invoice-extraction-lambda` consume from the
   queue. Add a dead-letter queue with `maxReceiveCount: 5` and a CloudWatch alarm on its
   depth. Nothing is silently lost; poison messages can be inspected and replayed.

2. **Frontend upload retry**
   On PUT failure, re-request a fresh `/upload-url` and retry with exponential backoff
   (3–5 attempts). Set upload size/type limits client-side as a first cheap guard.

3. **Idempotent extraction**
   Dedupe by `objectKey` (check before Textract, or use a conditional write on
   `invoiceId` + `sourceFile`) so retries never double-process an invoice.

4. **Async extraction for large documents**
   Switch to asynchronous Textract (`StartExpenseAnalysis`) for multi-page files and
   process the completion event; keeps within Lambda limits and handles big PDFs.

5. **Real authorization & rate limiting**
   Add API Gateway authorizer (e.g. Amazon Cognito or a JWT) instead of a client-side
   route guard; enforce per-IP throttling on `/invoices/upload-url`; validate content
   type and size before issuing the presigned URL.

6. **Operational observability**
   CloudWatch alarms on Lambda errors/throttles, DLQ depth, SES bounce + complaint
   notifications, DynamoDB throttle events, and daily-digest failure. Add a runbook for
   DLQ replay.

7. **Security hardening**
   Enable S3 default encryption (SSE-S3/KMS) and bucket versioning; encrypt DynamoDB at
   rest; restrict IAM to least privilege; turn on CloudTrail for audit.

8. **Regional DR (optional / costly)**
   If needed later, replicate DynamoDB + S3 to a second region and fail over DNS. Single
   region is the deliberate cost trade-off today.

---

## License

This is a private project. Reuse requires permission from the repository owner.