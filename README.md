# Invoice Processing System
## Event-Driven Serverless Invoice Automation with AI-Powered Extraction

**Version:** 2.0  
**Live URL:** https://zexxity.online  
**Primary Region:** ap-south-1 (Mumbai)  
**SES Inbound Region:** eu-west-1 (Ireland) — AWS limitation, inbound email receiving not available in Mumbai  
**Runtime:** Java 21 · AWS Lambda  

---

## Overview

A fully serverless, event-driven pipeline that automatically receives invoices by email or manual upload, extracts structured data using AWS Textract, validates and risk-scores them using Amazon Bedrock (Nova-Lite), routes flagged invoices for human approval, and persists all results for audit. Zero manual processing for auto-approved invoices.

The frontend is a React + Vite SPA hosted on AWS Amplify, connected to an API Gateway HTTP API backed by Java Lambda functions.

---

## Architecture

```
INGESTION
─────────
Vendor email → invoices@zexxity.online
      │
      ▼
Amazon SES (eu-west-1) → S3 (ses-inbound-emails-eu)
      │
      ▼
SesInboundHandler Lambda (eu-west-1) → copies PDF to S3 (ap-south-1)

Browser Upload → UploadUrlLambda → presigned S3 PUT URL → S3 (ap-south-1)
                 invoice-processing-buckets / invoices/

PROCESSING
──────────
S3 Object Created → EventBridge → InvoiceExtractionHandler Lambda
      │
      ├── AWS Textract (AnalyzeExpense) → extract fields + confidence scores
      ├── Amazon Bedrock Nova-Lite      → validate + risk score (LOW/MED/HIGH)
      ├── DynamoDB PutItem              → persist invoice record
      └── SES (sesv2, ap-south-1)      → notify reviewer if REVIEW_REQUIRED

REVIEW
──────
Reviewer visits https://zexxity.online
      │
      ├── Dashboard  — metrics, totals, AI approved/rejected counts
      ├── Upload     — manual PDF upload
      ├── Review     — pending approval queue, approve/reject with note
      └── Audit      — full history, CSV export

      OR

Reviewer clicks one-click link in email → TokenApprovalHandler
      └── validates 72h token → writes decision to DynamoDB → HTML confirmation
```

---

## AWS Services Used

| Service | Purpose |
|---|---|
| AWS Lambda (Java 21) | All business logic |
| Amazon S3 | Invoice PDFs, audit JSON, deployment JARs |
| Amazon DynamoDB | Invoice records, review decisions |
| AWS Textract | PDF data extraction (AnalyzeExpense) |
| Amazon Bedrock (Nova-Lite) | AI validation + risk scoring |
| Amazon SES v2 | Outbound notification + approval emails |
| Amazon SES (receipt rules) | Inbound email ingestion (eu-west-1) |
| API Gateway HTTP API | REST endpoints for frontend |
| AWS Amplify | React frontend hosting + CI/CD from GitHub |
| AWS Secrets Manager | Config (sender, reviewer email, model ID, frontend URL) |
| AWS EventBridge | S3 event routing + scheduled jobs |

---

## Lambda Functions

| Function Name | Handler Class | Trigger | Region | Timeout |
|---|---|---|---|---|
| `invoice-extraction-lambda` | `InvoiceExtractionHandler` | EventBridge (S3 event) | ap-south-1 | 60s |
| `ses-inbound-handler` | `SesInboundHandler` | SES Receipt Rule | eu-west-1 | 60s |
| `GetInvoiceLambda` | `GetInvoiceHandler` | API Gateway GET | ap-south-1 | 30s |
| `ApproveRejectLambda` | `ApproveRejectHandler` | API Gateway POST | ap-south-1 | 30s |
| `UploadUrlLambda` | `UploadUrlHandler` | API Gateway POST | ap-south-1 | 15s |
| `token-approval` | `TokenApprovalHandler` | API Gateway GET | ap-south-1 | 30s |
| `daily-digest-report` | `DailyDigestHandler` | EventBridge cron 08:00 IST | ap-south-1 | 120s |
| `expired-review-cleanup` | `ExpiredReviewCleanupHandler` | EventBridge daily | ap-south-1 | 120s |
| `weekly-s3-cleanup` | `S3CleanupHandler` | EventBridge Sunday 02:00 UTC | ap-south-1 | 120s |

---

## API Endpoints

Base URL: `https://rw5n87lye8.execute-api.ap-south-1.amazonaws.com`

| Method | Path | Lambda | Description |
|---|---|---|---|
| `GET` | `/invoices` | GetInvoiceLambda | List all invoices |
| `GET` | `/invoices?id=<id>` | GetInvoiceLambda | Get single invoice by ID |
| `POST` | `/invoices/upload-url` | UploadUrlLambda | Generate presigned S3 PUT URL (5 min) |
| `POST` | `/invoices/review` | ApproveRejectLambda | Submit APPROVED / REJECTED decision |
| `GET` | `/invoices/approve?token=` | token-approval | One-click approve from email link |
| `GET` | `/invoices/reject?token=` | token-approval | One-click reject from email link |

---

## Confidence Scoring & Routing Logic

```
totalConfidence = Textract confidence on the TOTAL field (0–100%)

if totalConfidence < 95%:
    validationStatus = REVIEW_REQUIRED
    SES email sent with one-click approve/reject links (72h token expiry)

else:
    Bedrock Nova-Lite validation runs
    if critical missing field (invoiceId / total / vendorName):
        validationStatus = REVIEW_REQUIRED
    else:
        validationStatus = APPROVED

Duplicate detection:
    if invoiceId already exists in DynamoDB → DUPLICATE (risk = HIGH)
```

---

## DynamoDB Schema

Table: `invoices` · Partition key: `invoiceId` (String) · Billing: PAY_PER_REQUEST

| Attribute | Type | Description |
|---|---|---|
| `invoiceId` | S | Extracted by Textract |
| `vendorName` | S | Extracted by Textract |
| `invoiceDate` | S | Extracted by Textract |
| `total` | S | Extracted by Textract |
| `subtotal` | S | Extracted by Textract (may be null) |
| `totalConfidence` | N | Textract confidence on TOTAL field — drives routing |
| `avgConfidence` | N | Average of all field confidence scores |
| `risk` | S | Bedrock — `LOW` / `MEDIUM` / `HIGH` |
| `validationStatus` | S | `APPROVED` / `REVIEW_REQUIRED` / `DUPLICATE` |
| `comments` | S | Bedrock explanation |
| `missingFields` | S | Comma-separated missing fields from Bedrock |
| `reviewDecision` | S | Human decision — `APPROVED` / `REJECTED` / `ESCALATED` |
| `reviewedBy` | S | Reviewer email or `email-link` |
| `reviewedAt` | S | ISO 8601 timestamp |
| `reviewNote` | S | Free-text reviewer note |

---

## Email Approval Flow

```
Invoice flagged REVIEW_REQUIRED
        ↓
InvoiceExtractionHandler sends SES email containing:
  - Invoice ID, vendor, amount, confidence scores
  - One-click APPROVE link  (72h Base64URL token)
  - One-click REJECT link   (72h Base64URL token)
  - Link to reviewer dashboard: https://zexxity.online/review
        ↓
Reviewer clicks link → TokenApprovalHandler
  - Validates token expiry (72h)
  - Checks not already decided
  - Writes reviewDecision to DynamoDB
  - Returns HTML confirmation page with link back to dashboard

Reviewer uses UI → ApproveRejectLambda
  - DynamoDB update + SES confirmation email run concurrently
  - Lambda waits for both before returning (CompletableFuture.allOf)
  - Confirmation email sent to reviewer with decision summary
```

---

## Scheduled Jobs

| Function | Schedule | Action |
|---|---|---|
| `daily-digest-report` | Daily 08:00 IST | Emails summary of new invoices, backlog, high-risk pending |
| `expired-review-cleanup` | Daily | Escalates REVIEW_REQUIRED invoices with no decision after 72h, sends fresh links |
| `weekly-s3-cleanup` | Every Sunday 02:00 UTC | Deletes raw PDFs older than 30 days. Audit JSON is never deleted. |

---

## Frontend (React)

- Built with **React 19 + Vite**
- Hosted on **AWS Amplify** — auto-deploys on push to `main`
- Connects to API Gateway via `VITE_API_BASE_URL` environment variable

**Pages:**
- `/` — Dashboard with metrics (total, AI approved, review required, duplicates, human approved/rejected, avg confidence)
- `/upload` — Drag-and-drop PDF upload with progress tracking
- `/review` — Pending approval queue with approve/reject decision form
- `/audit` — Full invoice history with filters and CSV export

**Local development:**
```bash
cd invoice-reviewer-react
npm install
npm run dev
# opens at http://localhost:5173
```

---

## Project Structure

```
invoice-processing/
├── src/main/java/com/invoice/processing/
│   ├── ApproveRejectHandler.java        POST /invoices/review + SES confirmation
│   ├── DailyDigestHandler.java          scheduled digest email
│   ├── ExpiredReviewCleanupHandler.java daily 72h escalation
│   ├── GetInvoiceHandler.java           GET /invoices
│   ├── InvoiceData.java                 Textract data model
│   ├── InvoiceExtractionHandler.java    core pipeline (Textract + Bedrock + SES)
│   ├── S3CleanupHandler.java            weekly PDF cleanup
│   ├── SecretsManagerConfig.java        singleton config from Secrets Manager
│   ├── SesInboundHandler.java           email ingestion (eu-west-1)
│   ├── TokenApprovalHandler.java        one-click email approval
│   └── UploadUrlHandler.java            S3 presigned URL generator
├── invoice-reviewer-react/              React + Vite frontend (Amplify hosted)
│   ├── src/
│   │   ├── components/                  Navbar, MetricCard, FileQueue, etc.
│   │   ├── pages/                       Dashboard, Upload, Review, Audit, Login
│   │   ├── services/                    authService, uploadService, reviewService, etc.
│   │   └── hooks/                       useDashboard, useReview, useUpload, useAudit
│   ├── package.json
│   └── vite.config.js
├── load-tests/                          JMeter test suite (6 thread groups)
├── amplify.yml                          Amplify build config
├── template.yaml                        SAM / CloudFormation
└── pom.xml
```

---

## Local Build & Deploy

**Prerequisites:** Java 21, Maven 3.9+, AWS CLI v2

```bash
# Build the JAR
mvn clean package -DskipTests

# Upload to S3 (26 MB — exceeds direct upload limit)
aws s3 cp target/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  s3://invoice-processing-deploy-977574654100/lambda/invoice-lambda.jar \
  --region ap-south-1

# Deploy a Lambda (example — ApproveRejectLambda)
aws lambda update-function-code \
  --function-name ApproveRejectLambda \
  --s3-bucket invoice-processing-deploy-977574654100 \
  --s3-key lambda/invoice-lambda.jar \
  --region ap-south-1
```

**Frontend** deploys automatically via Amplify on every push to `main`.

---

## Load Tests (JMeter)

Located in `load-tests/`. Covers all 6 API endpoints.

```bash
.\load-tests\run.ps1 -Profile smoke      # 1 VU, ~30s sanity check
.\load-tests\run.ps1 -Profile baseline   # 10-15 VUs, ~2 min
.\load-tests\run.ps1 -Profile stress     # up to 50 VUs, ~5 min
```
