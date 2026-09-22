<div align="center">

# Invoice Processing System

**Serverless, AI-powered invoice automation with human-in-the-loop review.**

[![CI](https://github.com/zexxitywave/invoice-processing/actions/workflows/maven.yml/badge.svg)](https://github.com/zexxitywave/invoice-processing/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.java.com)
[![Runtime](https://img.shields.io/badge/Runtime-AWS%20Lambda-FF9900)](https://aws.amazon.com/lambda/)
[![Frontend](https://img.shields.io/badge/Frontend-React%2019-blue)](https://react.dev)
[![Build](https://img.shields.io/badge/Build-Maven-C71A36)](https://maven.apache.org)
[![License](https://img.shields.io/badge/License-Proprietary-red)](LICENSE)

[Live App](https://zexxity.online) · [Architecture](#architecture) · [API Reference](#api-endpoints) · [Deployment](#local-build--deploy)

</div>

---

## Overview

Vendors email PDF invoices to `invoices@zexxity.online` — or staff upload them through
the web app. Each invoice is extracted with **AWS Textract**, validated and risk-scored
by **Amazon Bedrock (Nova-Lite)**, and stored in **Amazon DynamoDB**. Cleanly extracted
invoices are **auto-approved**; anything uncertain, incomplete, or duplicated is routed
to a human reviewer through a dashboard or a one-click email link. Every decision is
recorded for full auditability.

### Highlights

- **Fully serverless** — Java 21 + AWS Lambda, single region (`ap-south-1`, Mumbai)
- **AI extraction** — Textract `AnalyzeExpense` with per-field confidence scores
- **Deterministic validation** — auto-approve only when every field is present, math
  checks out, confidence is high, *and* Bedrock agrees
- **Human-in-the-loop** — intuitive review dashboard + one-click email approve/reject (72 h tokens)
- **Audit-ready** — full history, filters, and CSV export
- **Notifications via Brevo** — review, confirmation, daily digest, and escalation emails

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
- [Inbound Email](#inbound-email)
- [Scheduled Jobs](#scheduled-jobs)
- [Frontend](#frontend)
- [Repository Layout](#repository-layout)
- [Local Build & Deploy](#local-build--deploy)
- [CI/CD](#cicd)
- [Load Testing](#load-testing)
- [Resilience & Roadmap](#resilience--roadmap)
- [License](#license)

---

## Architecture

```
INGESTION
─────────
Vendor email → invoices@zexxity.online
      │
      ▼
SES Receipt Rule (ap-south-1) → S3 ses-inbound-emails-m3/emails/
      │
      ▼
SesInboundHandler → copies PDF to invoice bucket
                                   │
Browser Upload → UploadUrlHandler → presigned S3 PUT URL
                                   ▼
                    S3 invoice-processing-buckets-m3/invoices/

PROCESSING
──────────
S3 Object Created → EventBridge → InvoiceExtractionHandler
      │
      ├─ AWS Textract (AnalyzeExpense)      → fields + confidence (retries on rate limits)
      ├─ Amazon Bedrock Nova-Lite           → validation + risk score (LOW/MED/HIGH)
      ├─ Deterministic rules check          → missing fields / line-item math / confidence
      ├─ DynamoDB PutItem                   → persist invoice record
      └─ Brevo transactional email          → reviewer notified when REVIEW_REQUIRED

REVIEW
──────
Reviewer → https://zexxity.online
      ├─ Dashboard — live metrics (totals, approved, review queue, duplicates)
      ├─ Upload    — drag-and-drop PDF upload
      ├─ Review    — pending-approval queue with approve / reject + note
      └─ Audit     — full history with filters and CSV export

      OR one-click approval from email
      └── TokenApprovalHandler → validates 72 h token → writes decision → HTML confirmation
```

---

## AWS Services

| Service | Purpose |
|---|---|
| AWS Lambda (Java 21) | All business logic (10 handlers) |
| Amazon API Gateway (HTTP API) | REST endpoints for the frontend (`https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`) |
| Amazon S3 | Invoice PDFs, inbound emails, audit JSON, deployment JARs |
| Amazon DynamoDB | Invoice records + review decisions (on-demand billing) |
| Amazon Textract | PDF extraction (`AnalyzeExpense`) with per-field confidence |
| Amazon Bedrock (Nova-Lite) | AI validation, risk scoring, explanations |
| Brevo (Sendinblue) | Review, confirmation, digest, and escalation emails |
| Amazon SES | Inbound email ingestion via receipt rules (`ap-south-1`) |
| AWS Secrets Manager | Runtime config: sender, reviewer, model ID, Brevo API key/sender, frontend URL |
| AWS EventBridge | S3 event routing + scheduled jobs |
| AWS Amplify | Frontend hosting + CI/CD from GitHub |
| Amazon CloudWatch | Logs and metrics for all functions |

---

## Lambda Functions

All functions run in `ap-south-1` — the extraction pipeline's capacity is handled with
a jittered backoff retry on Textract rate limits, so bursty uploads do not drop invoices.

| Function | Handler | Trigger | Timeout |
|---|---|---|---|
| `invoice-extraction-lambda` | `InvoiceExtractionHandler` | EventBridge (S3 event) | 120 s |
| `ses-inbound-handler` | `SesInboundHandler` | SES receipt rule | 60 s |
| `GetInvoiceLambda` | `GetInvoiceHandler` | API Gateway `GET` | 30 s |
| `ApproveRejectLambda` | `ApproveRejectHandler` | API Gateway `POST` | 30 s |
| `UploadUrlLambda` | `UploadUrlHandler` | API Gateway `POST` | 15 s |
| `token-approval` | `TokenApprovalHandler` | API Gateway `GET` | 30 s |
| `daily-digest-report` | `DailyDigestHandler` | EventBridge cron 08:00 IST | 120 s |
| `expired-review-cleanup` | `ExpiredReviewCleanupHandler` | EventBridge daily | 120 s |
| `weekly-s3-cleanup` | `S3CleanupHandler` | EventBridge Sunday 02:00 UTC | 120 s |
| `lambda-warm` | `WarmUpHandler` | EventBridge every 5 min | 60 s |

---

## API Endpoints

**Base URL:** `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`

| Method | Path | Lambda | Description |
|---|---|---|---|
| `GET` | `/invoices` | `GetInvoiceLambda` | List invoices + dashboard metrics (parallel GSI reads) |
| `GET` | `/invoices?id=<id>` | `GetInvoiceLambda` | Single invoice by ID |
| `POST` | `/invoices/upload-url` | `UploadUrlLambda` | Presigned S3 PUT URL (5 min expiry) |
| `POST` | `/invoices/review` | `ApproveRejectLambda` | Submit `APPROVED` / `REJECTED` decision |
| `GET` | `/invoices/approve?token=` | `token-approval` | One-click approve (from email link) |
| `GET` | `/invoices/reject?token=` | `token-approval` | One-click reject (from email link) |

---

## Extraction & Routing Logic

```
totalConfidence = Textract confidence on the TOTAL field (0–100%)
missingFields   = computed from the extracted values themselves (vendorName,
                  invoiceDate, invoiceId, subtotal, total) merged with Bedrock
lineItemsSum    = sum of all line-item PRICE/UNIT_PRICE amounts

An invoice is AUTO-APPROVED only when ALL of these hold:
    • totalConfidence >= 95%
    • no field is missing  (missingFields is empty — incl. subtotal)
    • line items add up to the total  (|lineItemsSum − total| <= 0.5)
    • Bedrock says APPROVED

Otherwise:
    validationStatus = REVIEW_REQUIRED
    Brevo email sent with one-click approve/reject links (72 h token)

Duplicate detection:
    if invoiceId already exists → DUPLICATE (risk = HIGH)
```

Extraction failures are handled defensively:

- **Textract rate limits** — `AnalyzeExpense` retried up to 5× with exponential backoff
  + jitter before the failure propagates to EventBridge's built-in retries.
- **Every extraction is idempotent** — retries never double-write to DynamoDB.

---

## DynamoDB Schema

**Table:** `invoices` · **Partition key:** `invoiceId` (String) · **Billing:** `PAY_PER_REQUEST`

**Global Secondary Indexes**

| Index | Key |
|---|---|
| `validationStatus-index` | `validationStatus` (HASH) |
| `reviewDecision-index` | `reviewDecision` (HASH) |

**Attributes**

| Attribute | Type | Description |
|---|---|---|
| `invoiceId` | S | Extracted by Textract |
| `vendorName` | S | Extracted by Textract |
| `invoiceDate` | S | Extracted by Textract |
| `total` | S | Extracted by Textract |
| `subtotal` | S | Extracted by Textract (may be null) |
| `lineItemsSum` | N | Sum of line-item amounts — drives the math check |
| `lineItemCount` | N | Number of line items detected |
| `totalConfidence` | N | Confidence on the TOTAL field — drives routing |
| `avgConfidence` | N | Mean of all field confidence scores |
| `risk` | S | Bedrock — `LOW` / `MEDIUM` / `HIGH` |
| `validationStatus` | S | `APPROVED` / `REVIEW_REQUIRED` / `DUPLICATE` |
| `comments` | S | Bedrock explanation |
| `missingFields` | S | Comma-separated missing fields (computed from extraction + Bedrock) |
| `reviewDecision` | S | Human decision — `APPROVED` / `REJECTED` / `ESCALATED` |
| `reviewedBy` | S | Reviewer email or `email-link` |
| `reviewedAt` | S | ISO 8601 timestamp |
| `reviewNote` | S | Free-text reviewer note |

---

## Email Approval Flow

Outbound emails are sent through **Brevo (Sendinblue)** — reviewer notifications,
confirmations, the daily digest, and 72 h escalation summaries.

```
Invoice flagged REVIEW_REQUIRED
        │
        ▼
InvoiceExtractionHandler sends a Brevo email with:
  • invoice ID, vendor, amount, confidence scores
  • one-click APPROVE / REJECT links (72 h Base64URL token)
  • link to the review dashboard
        │
        ▼
Reviewer clicks link → TokenApprovalHandler
  • validates token expiry (72 h) and not-already-decided
  • writes reviewDecision to DynamoDB
  • returns an HTML confirmation page with a link back to the dashboard

Reviewer uses UI → ApproveRejectLambda
  • DynamoDB update + Brevo confirmation email run concurrently
    (CompletableFuture.allOf — returns only when both complete)
  • confirmation email sent to the reviewer with a decision summary
```

The sender address is configured by `brevoSender` in the Secrets Manager secret.
A Brevo-validated address is required; for branded sending from `noreply@zexxity.online`,
authenticate the `zexxity.online` domain in Brevo.

---

## Inbound Email

Invoices arrive at `invoices@zexxity.online` via an Amazon SES receipt rule.

| Item | Value |
|---|---|
| Domain | `zexxity.online` |
| Receipt rule set | `invoice-inbound` |
| Rule | `save-and-process-invoices` (enabled) |
| Action | Deliver to S3 `ses-inbound-emails-m3/emails/` |
| Trigger | Invokes `ses-inbound-handler` |
| MX record | `inbound-smtp.ap-south-1.amazonaws.com` |

---

## Scheduled Jobs

| Function | Schedule | Action |
|---|---|---|
| `daily-digest-report` | Daily 08:00 IST | Emails a summary: new invoices, backlog, high-risk pending |
| `expired-review-cleanup` | Daily | Escalates undecided `REVIEW_REQUIRED` items after 72 h; sends fresh links |
| `weekly-s3-cleanup` | Sunday 02:00 UTC | Deletes raw PDFs older than 30 days (audit JSON is never deleted) |

---

## Frontend

React 19 + Vite SPA hosted on **AWS Amplify** (auto-deploys on push to `main`).
Connects to API Gateway via the `VITE_API_BASE_URL` build-time environment variable.

**Pages**

| Route | Purpose |
|---|---|
| `/` | Dashboard — totals, approved/rejected, review queue, duplicates, average confidence |
| `/upload` | Drag-and-drop PDF upload with progress tracking |
| `/review` | Pending-approval queue with decision form |
| `/audit` | Full history, filters, and CSV export |
| `/login` | Protected-route reviewer sign-in |

**Local development**

```bash
cd invoice-reviewer-react
npm install
npm run dev     # http://localhost:5173
```

---

## Repository Layout

```
invoice-processing/
├── src/main/java/com/invoice/processing/
│   ├── InvoiceExtractionHandler.java        core pipeline (Textract + Bedrock + rules → ops)
│   ├── ApproveRejectHandler.java            POST /invoices/review + concurrent confirmation email
│   ├── TokenApprovalHandler.java            one-click email approval
│   ├── GetInvoiceHandler.java               GET /invoices (parallel GSI metric counts)
│   ├── UploadUrlHandler.java                presigned S3 URL generator
│   ├── SesInboundHandler.java               inbound email ingestion
│   ├── DailyDigestHandler.java              scheduled digest email
│   ├── ExpiredReviewCleanupHandler.java     daily 72 h escalation
│   ├── S3CleanupHandler.java                weekly PDF cleanup
│   ├── WarmUpHandler.java                   keeps interactive lambdas warm
│   ├── BrevoMailer.java                     Brevo transactional email client
│   ├── SecretsManagerConfig.java            singleton config from Secrets Manager
│   └── InvoiceData.java                     Textract data model
├── invoice-reviewer-react/                  React + Vite frontend (Amplify hosted)
│   ├── src/{components,pages,services,hooks,styles}
│   ├── package.json
│   └── vite.config.js
├── load-tests/                              JMeter suite (all API endpoints)
├── migration/                               account-migration runbook + SAM template v2
├── docs/                                    presentation & interview notes
├── .github/workflows/maven.yml              CI: Maven build + dependency graph
├── amplify.yml                              Amplify build config
├── deploy.ps1                               one-command AWS deployment
├── template.yaml                            SAM / CloudFormation (backend)
└── pom.xml
```

---

## Local Build & Deploy

**Prerequisites:** Java 21, Maven 3.9+, AWS CLI v2, SAM CLI.

```bash
# Build the Lambda JAR
mvn clean package -DskipTests

# Update a single Lambda (example)
aws lambda update-function-code \
  --function-name ApproveRejectLambda \
  --zip-file fileb://target/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  --region ap-south-1
```

Or deploy everything (SM stack, API Gateway, Secrets Manager secret, S3 bucket + UI):

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

## Resilience & Roadmap

### Reliability model

| Delivery path | Recovery |
|---|---|
| S3 → EventBridge → Lambda | Retries up to 24 h / 185 attempts; a persistent failure is dropped (no DLQ yet) |
| Lambda async invocations | Retried twice; a DLQ is used only if configured |
| Textract extraction | In-function retry (5 attempts, jittered backoff) on rate limits |
| SES inbound receipt rule | Retried ~2–3 times ~20 min apart, then the email is lost |

EventBridge is **at-least-once**: retries can re-process the same object, so extraction
is idempotent (dedupe on `invoiceId` / `objectKey`).

### Cold-start tuning

The four interactive functions (`GetInvoiceLambda`, `ApproveRejectLambda`,
`UploadUrlLambda`, `token-approval`) are published with a `live` alias. A scheduled
`lambda-warm` (every 5 min) synchronously invokes the four `live` aliases, keeping their
execution environments warm. Handlers build AWS SDK clients eagerly so class graphs are
ready on first call. Latency measured against the live API (`GET /invoices`):

| Case | Latency |
|---|---|
| Warm request | ~200–350 ms |
| First request on a hydrated container | ~200–300 ms |
| Cold start after >15 min idle | ~5.5 s |

### Known failure modes & edge cases

| # | Risk | Failure scenario | Impact |
|---|---|---|---|
| 1 | Poison messages | Password-protected, corrupt, or non-PDF document | Function retries up to 24 h, then drops silently |
| 2 | Large / multi-page invoices | Over Textract synchronous limits (≈5 MB) | Invoice never processed |
| 3 | Upload network failure | Browser PUT fails mid-flight; 5-min URL expires | Manual re-upload required |
| 4 | Duplicate processing | Same object re-delivered by an S3/EventBridge retry | Deduped on `invoiceId` / `objectKey` |
| 5 | Email without a legible PDF | HTML-only email or scanned/photo PDF below quality bar | Nothing extracted; email lost after SES retries |
| 6 | Abuse / billing attack | Public `/invoices/upload-url` + presigned URLs | Unbounded Textract/Bedrock spend |
| 7 | Unauthenticated review data | Dashboard data behind only a client-side guard | Data exposure risk |
| 8 | Review race condition | Dashboard + email-link decisions at the same time | Last-write-wins (no conditional update) |
| 9 | Token replay | 72 h approval tokens | Repeated decisions possible without idempotency |
| 10 | Silent operational failure | No DLQ, no CloudWatch alarms, no bounce handling | Failures unnoticed until a user complains |
| 11 | Invoice stuck forever | Escalation job fails → items stay `REVIEW_REQUIRED` | Review queue grows silently |
| 12 | Email sender/billing deps | Brevo sender unvalidated / model quota exhausted | Emails rejected until sender verified |
| 13 | Regional single point of failure | Everything in `ap-south-1` | Regional outage = system offline (accepted trade-off) |

### Roadmap

1. **Durable pipeline with SQS + DLQ** — route `S3 → EventBridge → SQS`, consume from the
   queue, dead-letter with `maxReceiveCount: 5` and a depth alarm. Nothing is lost.
2. **Frontend upload retry** — re-request `/upload-url` and retry with backoff on PUT failure.
3. **Idempotent extraction by `objectKey`** — check before Textract to never double-process.
4. **Async Textract for large documents** — `StartExpenseAnalysis` + completion event.
5. **Real authorization & rate limiting** — API Gateway authorizer (Cognito/JWT), per-IP
   throttling, content-type/size validation before presigning.
6. **Operational observability** — CloudWatch alarms on errors/throttles, DLQ depth,
   SES bounces + complaints, DynamoDB throttles.
7. **Security hardening** — S3 default encryption + versioning, DynamoDB encryption at rest,
   least-privilege IAM, CloudTrail.
8. **Regional DR (optional)** — replicate DynamoDB + S3 and fail over DNS.

---

## License

Proprietary — all rights reserved. Reuse requires written permission from the repository
owner. See [LICENSE](LICENSE).