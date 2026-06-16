# Architecture Document
## Event-Driven Invoice Processing System with Intelligent Document Understanding

**Version:** 1.0  
**Domain:** zexxity.online  
**Region (primary):** ap-south-1 (Mumbai)  
**Region (SES inbound):** eu-west-1 (Ireland)  
**Region (SES outbound):** eu-north-1 (Stockholm)

---

## 1. System Overview

An event-driven, serverless pipeline that automatically receives invoices by email,
extracts structured data using AWS Textract, validates them using Amazon Bedrock AI,
routes anomalous invoices for human approval, and persists all results for audit.

---

## 2. Architecture Diagram (Text)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         INGESTION LAYER                                  │
│                                                                          │
│  Vendor sends email                                                      │
│  to invoices@zexxity.online                                              │
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
│                                   ┌──────────────────┐                  │
│                                   │   S3 (ap-south-1)│                  │
│                                   │ invoice-processing│                 │
│                                   │  -buckets        │                  │
│                                   │  invoices/ prefix│                  │
│                                   └────────┬─────────┘                 │
└────────────────────────────────────────────┼────────────────────────────┘
                                             │ S3 Object Created Event
┌────────────────────────────────────────────┼────────────────────────────┐
│                       ORCHESTRATION LAYER  │                            │
│                                            ▼                            │
│                                   ┌──────────────────┐                  │
│                                   │  EventBridge     │                  │
│                                   │  Rule            │                  │
│                                   └────────┬─────────┘                 │
│                                            │                            │
│                                            ▼                            │
│                                   ┌──────────────────┐                  │
│                                   │  Step Functions  │                  │
│                                   │  State Machine   │                  │
│                                   │                  │                  │
│                          ┌────────┤  ProcessInvoice  │                  │
│                          │        │  (Task state)    │                  │
│                          │        └────────┬─────────┘                 │
│                          │                 │                            │
│                          │        ┌────────┴─────────┐                 │
│                          │        │  RouteByStatus   │                  │
│                          │        │  (Choice state)  │                  │
│                          │        └──┬──────┬────────┘                 │
│                          │    APPROVED│  REVIEW│  DUPLICATE             │
│                          │           ▼   REQUIRED ▼                    │
│                          │       Succeed  HumanApproval  HandleDuplicate│
└──────────────────────────┼──────────────────────────────────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────────────────┐
│                    PROCESSING LAYER                                      │
│                           │                                             │
│                           ▼                                             │
│                  ┌──────────────────────┐                               │
│                  │ InvoiceExtraction    │                               │
│                  │ Handler Lambda       │                               │
│                  │ (ap-south-1)         │                               │
│                  └──┬───┬───┬───┬───┬──┘                               │
│                     │   │   │   │   │                                   │
│          Textract   │   │   │   │   │  Bedrock                         │
│          Analyze    │   │   │   │   │  Nova-Lite                       │
│          Expense    │   │   │   │   │  Validation                      │
│             ▼       │   │   │   │   ▼                                   │
│         ┌───────┐   │   │   │ ┌─────────┐                              │
│         │Amazon │   │   │   │ │ Amazon  │                              │
│         │Textract│  │   │   │ │ Bedrock │                              │
│         └───────┘   │   │   │ └─────────┘                              │
│                     │   │   │                                           │
│              DynamoDB│   │  S3 Audit                                    │
│              PutItem │   │  JSON                                        │
│                  ▼   │   ▼                                              │
│            ┌──────┐  │ ┌──────────┐                                    │
│            │Dynamo│  │ │S3 audit/ │                                    │
│            │  DB  │  │ │folder    │                                    │
│            └──────┘  │ └──────────┘                                    │
│                      │                                                  │
│               SES Email (REVIEW_REQUIRED)                               │
│                      │                                                  │
│                      ▼                                                  │
│              ┌──────────────────┐                                       │
│              │ Amazon SES       │  → Email with Approve/Reject links    │
│              │ (eu-north-1)     │    to invydexter@gmail.com            │
│              └──────────────────┘                                       │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      REVIEWER INTERFACE LAYER                           │
│                                                                          │
│  Browser → https://www.zexxity.online                                   │
│         │                                                                │
│         ├── login.html       (auth guard)                               │
│         ├── index.html       (dashboard + stats)                        │
│         ├── upload.html      (manual PDF upload)                        │
│         └── review.html      (approve/reject queue)                     │
│                │                                                         │
│                ▼                                                         │
│         AWS Amplify (static hosting)                                    │
│                │                                                         │
│                ▼                                                         │
│         API Gateway (HTTP API)                                          │
│         rw5n87lye8.execute-api.ap-south-1.amazonaws.com                 │
│                │                                                         │
│         ┌──────┴──────────────────────────────────┐                    │
│         │              LAMBDA ROUTES               │                    │
│         │                                          │                    │
│  GET  /invoices           → GetInvoiceHandler      │                    │
│  GET  /invoices?id=XXX    → GetInvoiceHandler      │                    │
│  POST /invoices/review    → ApproveRejectHandler   │                    │
│  POST /invoices/upload-url→ UploadUrlHandler       │                    │
│  GET  /invoices/approve   → TokenApprovalHandler   │                    │
│  GET  /invoices/reject    → TokenApprovalHandler   │                    │
│         └──────────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                        SECURITY LAYER                                   │
│                                                                          │
│  Credentials & config → AWS Secrets Manager                             │
│  Secret: invoice-processing/config                                      │
│  Fields: sesSender, sesReviewer, modelId, frontendUrl                  │
│                                                                          │
│  UI auth → sessionStorage token (login.html + auth.js)                  │
│  API auth → CORS restricted to https://www.zexxity.online               │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Lambda Functions

| Function | Handler | Trigger | Region |
|---|---|---|---|
| `invoice-extraction` | `InvoiceExtractionHandler` | Step Functions Task | ap-south-1 |
| `ses-inbound-handler` | `SesInboundHandler` | SES Receipt Rule | eu-west-1 |
| `get-invoice` | `GetInvoiceHandler` | API Gateway GET | ap-south-1 |
| `approve-reject-invoice` | `ApproveRejectHandler` | API Gateway POST | ap-south-1 |
| `upload-url-generator` | `UploadUrlHandler` | API Gateway POST | ap-south-1 |
| `token-approval` | `TokenApprovalHandler` | API Gateway GET | ap-south-1 |

---

## 4. Step Functions State Machine

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

## 5. DynamoDB Schema

Table: `invoices`  
Partition key: `invoiceId` (String)

| Attribute | Type | Source |
|---|---|---|
| `invoiceId` | S | Textract |
| `vendorName` | S | Textract |
| `invoiceDate` | S | Textract |
| `subtotal` | S | Textract |
| `total` | S | Textract |
| `vendorConfidence` | N | Textract |
| `totalConfidence` | N | Textract |
| `invoiceIdConfidence` | N | Textract |
| `dateConfidence` | N | Textract |
| `avgConfidence` | N | Computed |
| `risk` | S | Bedrock (LOW/MEDIUM/HIGH) |
| `validationStatus` | S | AI result (APPROVED/REVIEW_REQUIRED/DUPLICATE) |
| `comments` | S | Bedrock |
| `missingFields` | S | Bedrock |
| `reviewDecision` | S | Human (APPROVED/REJECTED) |
| `reviewedBy` | S | Reviewer email |
| `reviewedAt` | S | ISO timestamp |
| `reviewNote` | S | Reviewer note |

---

## 6. Security Design

- **Secrets Manager** — `invoice-processing/config` stores SES sender, reviewer email, model ID, frontend URL. Loaded once at Lambda cold start.
- **IAM least privilege** — each Lambda has only the permissions it needs.
- **CORS** — API Gateway restricts `Access-Control-Allow-Origin` to `https://www.zexxity.online`.
- **UI Auth** — `sessionStorage` token checked on every page load. Session cleared on browser close.
- **Token-based email approval** — Base64URL-encoded JSON token with 72-hour expiry. No credentials needed to approve/reject from email.
- **Presigned S3 URLs** — browser uploads go directly to S3 with a 5-minute expiring PUT URL. No AWS credentials exposed to browser.

---

## 7. Confidence Scoring Logic

```
totalConfidence = Textract confidence on the TOTAL field

if totalConfidence < 95%:
    validationStatus = "REVIEW_REQUIRED"
    SES email sent with approve/reject links

else:
    validationStatus = Bedrock result (APPROVED or REVIEW_REQUIRED)
```

Average confidence (all fields) is stored in DynamoDB for audit purposes but does not drive routing.

---

## 8. Email Approval Flow

```
Invoice flagged → REVIEW_REQUIRED
        ↓
SES sends email to invydexter@gmail.com containing:
  - Invoice details (ID, vendor, amount, confidence)
  - ✅ One-click APPROVE link  (expires 72 hours)
  - ❌ One-click REJECT link   (expires 72 hours)
  - Dashboard link for full review
        ↓
Reviewer clicks link
        ↓
TokenApprovalHandler validates token, records decision
in DynamoDB (reviewDecision field), returns HTML confirmation page
```
