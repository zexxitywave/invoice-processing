# Invoice Processing System
## Event-Driven Serverless Invoice Automation with AI-Powered Extraction

**Version:** 2.0  
**Domain:** zexxity.online  
**Region (primary):** ap-south-1 (Mumbai)  
**Region (SES inbound):** eu-west-1 (Ireland)  
**Region (SES outbound):** eu-north-1 (Stockholm)  
**Runtime:** Java 21 · 512 MB · AWS Lambda  

---

## Overview

A fully serverless, event-driven pipeline that automatically receives invoices by email or manual upload, extracts structured data using AWS Textract, validates and risk-scores them using Amazon Bedrock (Nova-Lite), routes anomalous invoices for human approval, and persists all results for audit. Zero manual processing for auto-approved invoices.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         INGESTION LAYER                                  │
│                                                                          │
│  Vendor sends email to invoices@zexxity.online                           │
│         │                                                                │
│         ▼                                                                │
│  ┌─────────────┐    receipt rule    ┌──────────────────┐                │
│  │  Amazon SES  │ ─────────────────▶│   S3 (eu-west-1) │                │
│  │ (eu-west-1) │                   │ ses-inbound-emails│                │
│  └─────────────┘                   └────────┬─────────┘                │
│                                             │ trigger                   │
│                                             ▼                           │
│                                   ┌──────────────────┐                  │
│                                   │ SesInboundHandler│                  │
│                                   │ Lambda (eu-west-1)│                 │
│                                   └────────┬─────────┘                 │
│                                            │ PutObject                  │
│                                            ▼                            │
│                          ┌─────────────────────────────┐                │
│  Browser Upload ────────▶│   S3 (ap-south-1)           │                │
│  (presigned PUT URL)     │   invoice-processing-buckets │               │
│                          │   invoices/ prefix           │               │
│                          └─────────────┬───────────────┘               │
└────────────────────────────────────────┼────────────────────────────────┘
                                         │ S3 Object Created Event
┌────────────────────────────────────────┼────────────────────────────────┐
│                    ORCHESTRATION LAYER │                                 │
│                                        ▼                                │
│                             ┌──────────────────┐                        │
│                             │   EventBridge    │                        │
│                             └────────┬─────────┘                       │
│                                      ▼                                  │
│                             ┌──────────────────┐                        │
│                             │  Step Functions  │                        │
│                             │  State Machine   │                        │
│                    ┌────────┤  (5 states,      │                        │
│                    │        │  2-retry fault)  │                        │
│                    │        └────────┬─────────┘                       │
│                    │                 │                                  │
│                    │        ┌────────┴─────────┐                       │
│                    │        │  RouteByStatus   │                        │
│                    │        │  (Choice state)  │                        │
│                    │        └──┬───────┬───────┘                       │
│                    │    APPROVED│  REVIEW│  DUPLICATE                   │
│                    │           ▼  REQUIRED▼                             │
│                    │       Succeed  HumanApproval  HandleDuplicate      │
└────────────────────┼────────────────────────────────────────────────────┘
                     │
┌────────────────────┼────────────────────────────────────────────────────┐
│               PROCESSING LAYER                                           │
│                    ▼                                                     │
│           ┌──────────────────────┐                                      │
│           │ InvoiceExtractionHandler Lambda (ap-south-1)                │
│           └──┬───┬───┬───┬───┬──┘                                      │
│              │   │   │   │   │                                          │
│   Textract   │   │   │   │   │  Bedrock Nova-Lite                      │
│   Analyze    │   │   │   │   │  Validation + Risk Score                │
│   Expense    ▼   │   │   │   ▼                                          │
│         ┌──────┐ │   │ ┌───────────┐                                   │
│         │Textract│ │   │ Bedrock   │  95% confidence threshold         │
│         └──────┘ │   │ └───────────┘  drives APPROVED vs REVIEW        │
│                  │   │                                                  │
│          DynamoDB│   │ S3 audit JSON                                    │
│          PutItem │   │ (permanent record)                               │
│                  ▼   ▼                                                  │
│            ┌──────┐ ┌──────────┐                                       │
│            │Dynamo│ │S3 audit/ │                                       │
│            │  DB  │ │folder    │                                       │
│            └──────┘ └──────────┘                                       │
│                                                                          │
│           SES notification (REVIEW_REQUIRED) → eu-north-1               │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      REVIEWER INTERFACE LAYER                           │
│                                                                          │
│  Browser → https://www.zexxity.online                                   │
│         ├── login.html        auth guard                                │
│         ├── index.html        dashboard + stats                         │
│         ├── upload.html       manual PDF upload                         │
│         ├── review.html       pending approval queue                    │
│         └── audit.html        full audit report + CSV export            │
│                │                                                         │
│         AWS Amplify (static hosting, GitHub auto-deploy)                │
│                │                                                         │
│         API Gateway HTTP API                                            │
│         rw5n87lye8.execute-api.ap-south-1.amazonaws.com                 │
│                │                                                         │
│  GET  /invoices            → GetInvoiceLambda                           │
│  GET  /invoices?id=XXX     → GetInvoiceLambda                           │
│  POST /invoices/review     → ApproveRejectLambda                        │
│  POST /invoices/upload-url → UploadUrlLambda                            │
│  GET  /invoices/approve    → token-approval                             │
│  GET  /invoices/reject     → token-approval                             │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Lambda Functions

| Deployed Name | Handler Class | Trigger | Region | Timeout |
|---|---|---|---|---|
| `invoice-extraction-lambda` | `InvoiceExtractionHandler` | Step Functions Task | ap-south-1 | 60s |
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

| Method | Path | Lambda | Description |
|---|---|---|---|
| `GET` | `/invoices` | GetInvoiceLambda | List all invoices (DynamoDB scan) |
| `GET` | `/invoices?id=<id>` | GetInvoiceLambda | Get single invoice by ID |
| `POST` | `/invoices/upload-url` | UploadUrlLambda | Generate presigned S3 PUT URL (5 min expiry) |
| `POST` | `/invoices/review` | ApproveRejectLambda | Submit APPROVED / REJECTED decision |
| `GET` | `/invoices/approve?token=` | token-approval | One-click approve from email link |
| `GET` | `/invoices/reject?token=` | token-approval | One-click reject from email link |

---

## Step Functions State Machine

```json
{
  "StartAt": "ExtractAndValidate",
  "States": {
    "ExtractAndValidate": {
      "Type": "Task",
      "Resource": "arn:aws:states:::lambda:invoke",
      "Parameters": {
        "FunctionName": "<invoice-extraction-arn>",
        "Payload.$": "$"
      },
      "ResultSelector": {
        "validationStatus.$": "$.Payload.validationStatus",
        "risk.$":             "$.Payload.risk",
        "invoiceId.$":        "$.Payload.invoiceId",
        "totalConfidence.$":  "$.Payload.totalConfidence",
        "avgConfidence.$":    "$.Payload.avgConfidence",
        "comments.$":         "$.Payload.comments"
      },
      "ResultPath": "$",
      "Retry": [{ "ErrorEquals": ["States.ALL"], "MaxAttempts": 2, "IntervalSeconds": 5 }],
      "Next": "RouteByStatus"
    },
    "RouteByStatus": {
      "Type": "Choice",
      "Choices": [
        { "Variable": "$.validationStatus", "StringEquals": "DUPLICATE",       "Next": "HandleDuplicate" },
        { "Variable": "$.validationStatus", "StringEquals": "REVIEW_REQUIRED", "Next": "HumanApproval" },
        { "Variable": "$.validationStatus", "StringEquals": "APPROVED",        "Next": "InvoiceApproved" }
      ],
      "Default": "HandleUnknown"
    },
    "HumanApproval":   { "Type": "Wait", "Seconds": 1, "Next": "InvoiceApproved" },
    "InvoiceApproved": { "Type": "Succeed" },
    "HandleDuplicate": { "Type": "Fail", "Error": "DuplicateInvoice" },
    "HandleUnknown":   { "Type": "Fail", "Error": "UnknownStatus" }
  }
}
```

---

## DynamoDB Schema

Table: `invoices` · Partition key: `invoiceId` (String) · Billing: PAY_PER_REQUEST

| Attribute | Type | Source |
|---|---|---|
| `invoiceId` | S | Textract (`INVOICE_RECEIPT_ID`) |
| `vendorName` | S | Textract |
| `invoiceDate` | S | Textract |
| `subtotal` | S | Textract |
| `total` | S | Textract |
| `vendorConfidence` | N | Textract confidence score |
| `totalConfidence` | N | Textract — drives routing threshold |
| `invoiceIdConfidence` | N | Textract confidence score |
| `dateConfidence` | N | Textract confidence score |
| `avgConfidence` | N | Computed average of all field confidences |
| `risk` | S | Bedrock — `LOW` / `MEDIUM` / `HIGH` |
| `validationStatus` | S | AI result — `APPROVED` / `REVIEW_REQUIRED` / `DUPLICATE` |
| `comments` | S | Bedrock explanation |
| `missingFields` | S | Bedrock — comma-separated missing field names |
| `reviewDecision` | S | Human — `APPROVED` / `REJECTED` / `ESCALATED` |
| `reviewedBy` | S | Reviewer email or `email-link` |
| `reviewedAt` | S | ISO 8601 timestamp |
| `reviewNote` | S | Reviewer free-text note |

---

## Confidence Scoring & Routing Logic

```
totalConfidence = Textract confidence on the TOTAL field (0–100%)

if totalConfidence < 95%:
    validationStatus = "REVIEW_REQUIRED"
    SES email sent with one-click approve/reject links (72h expiry)

else:
    run Bedrock Nova-Lite validation
    if Bedrock flags critical missing field (invoiceId / total / vendorName):
        validationStatus = "REVIEW_REQUIRED"
    else:
        validationStatus = "APPROVED"

Duplicate detection:
    if invoiceId already exists in DynamoDB → DUPLICATE (risk = HIGH)
```

---

## Email Approval Flow

```
Invoice flagged REVIEW_REQUIRED
        ↓
SES sends email (eu-north-1) containing:
  - Invoice ID, vendor, amount, confidence scores
  - ✅ One-click APPROVE link  (72-hour Base64URL token)
  - ❌ One-click REJECT link   (72-hour Base64URL token)
  - Link to reviewer dashboard
        ↓
Reviewer clicks link → TokenApprovalHandler
  - Validates token expiry
  - Checks not already decided
  - Writes reviewDecision to DynamoDB
  - Returns HTML confirmation page
        ↓
ApproveRejectLambda (UI path):
  - DynamoDB UpdateItem + SES confirmation email run in parallel
    via CompletableFuture — total latency ≈ max(DynamoDB, SES)
  - Lambda waits for both before returning (8s timeout)
```

---

## Security

| Mechanism | Detail |
|---|---|
| Secrets Manager | `invoice-processing/config` — sesSender, sesReviewer, modelId, frontendUrl. Loaded once at cold start, cached for lifetime of execution environment. |
| IAM | Each Lambda has its own role with only required permissions (DynamoDB, S3, SES, Textract, Bedrock, Secrets Manager). |
| CORS | API Gateway allows `*` origin (public API). UI restricted to `https://www.zexxity.online`. |
| UI Auth | `sessionStorage` token checked on every page load. Session cleared on browser close. |
| Token approval | Base64URL JSON `{invoiceId, decision, exp}` — 72-hour expiry enforced server-side by `TokenApprovalHandler`. |
| Presigned S3 | Browser uploads directly to S3 via 5-minute PUT URL. No AWS credentials exposed to browser. |

---

## Scheduled Jobs

| Function | Schedule | Action |
|---|---|---|
| `daily-digest-report` | Every day 08:00 IST (02:30 UTC) | Scans all invoices, emails summary (new in 24h, backlog, high-risk pending) |
| `expired-review-cleanup` | Every day | Finds `REVIEW_REQUIRED` invoices with no decision older than 72h → marks `ESCALATED`, sends fresh approval links |
| `weekly-s3-cleanup` | Every Sunday 02:00 UTC | Deletes raw PDFs older than 30 days from `invoices/` prefix. Audit JSON in `audit/` is never deleted. |

---

## Load Tests (JMeter)

Located in `load-tests/`. Covers all 6 API endpoints with 6 thread groups.

```
load-tests/
├── invoice_full_suite.jmx   full test plan — 6 thread groups
├── user.properties          thread counts, ramp times, API config
├── run.ps1                  PowerShell runner with HTML report generation
├── data/invoice_ids.csv     real invoice IDs for GET / decision tests
└── README.md                setup guide, threshold table, expected metrics
```

**Quick start:**

```powershell
# Install JMeter: https://jmeter.apache.org/download_jmeter.cgi
# Then run from project root:

.\load-tests\run.ps1 -Profile smoke              # 1 VU, ~30s sanity check
.\load-tests\run.ps1 -Profile baseline           # 10-15 VUs, ~2 min (default)
.\load-tests\run.ps1 -Profile stress             # up to 50 VUs, ~5 min
.\load-tests\run.ps1 -Profile soak               # 10 VUs, 10 min
.\load-tests\run.ps1 -DryRun -OpenReport         # no real SES emails, open HTML report
```

| Thread Group | Endpoint | Lambda |
|---|---|---|
| TG1 | `GET /invoices` | GetInvoiceLambda |
| TG2 | `GET /invoices?id=` | GetInvoiceLambda |
| TG3 | `POST /invoices/upload-url` | UploadUrlLambda |
| TG4 | `POST /invoices/review` | ApproveRejectLambda |
| TG5 | `GET /invoices/approve\|reject?token=` | token-approval |
| TG6 | Cold start spike — all endpoints | All |

---

## Local Build & Deploy

**Prerequisites:** Java 21, Maven 3.9+, AWS CLI v2, JMeter 5.6+ (for load tests)

```bash
# Build
mvn clean package -DskipTests

# Upload JAR to S3 (required — 26 MB exceeds direct upload limit)
aws s3 cp target/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  s3://invoice-processing-buckets/deployments/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  --region ap-south-1

# Deploy a specific function
aws lambda update-function-code \
  --function-name ApproveRejectLambda \
  --s3-bucket invoice-processing-buckets \
  --s3-key deployments/invoice-extraction-lambda-1.0-SNAPSHOT.jar \
  --region ap-south-1
```

**Frontend** deploys automatically via Amplify on every push to `main`.

---

## Project Structure

```
invoice-extraction-lambda/
├── src/main/java/com/invoice/processing/
│   ├── ApproveRejectHandler.java       POST /invoices/review
│   ├── DailyDigestHandler.java         scheduled digest email
│   ├── ExpiredReviewCleanupHandler.java daily 72h escalation
│   ├── GetInvoiceHandler.java          GET /invoices
│   ├── InvoiceData.java                Textract data model
│   ├── InvoiceExtractionHandler.java   core pipeline (Textract + Bedrock)
│   ├── S3CleanupHandler.java           weekly PDF cleanup
│   ├── SecretsManagerConfig.java       singleton config loader
│   ├── SesInboundHandler.java          email ingestion (eu-west-1)
│   ├── TokenApprovalHandler.java       one-click email approval
│   └── UploadUrlHandler.java           S3 presigned URL generator
├── invoice-reviewer-ui/
│   ├── index.html / review.html / upload.html / audit.html / login.html
│   ├── app.js                          SPA logic
│   ├── auth.js                         session auth guard
│   ├── config.js                       API base URL
│   └── style.css
├── load-tests/                         JMeter test suite
├── template.yaml                       SAM / CloudFormation
└── pom.xml
```
