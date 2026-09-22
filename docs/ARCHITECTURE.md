# Architecture — Invoice Processing System

Serverless invoice-processing pipeline on AWS. A PDF/email lands in S3, an
event triggers a Step Functions state machine, one Lambda extracts + validates
the invoice with Amazon Bedrock (Nova), and every decision is persisted to
DynamoDB and an immutable audit trail in S3. When the model is not confident,
the state machine pauses and asks a human reviewer to approve or reject —
all inside a `WaitForTaskToken` callback so the execution never abandons.

## Live deployment

| Item | Value |
|---|---|
| AWS account | `891377127368` (SoumyajitRout), region `ap-south-1` |
| Stack | `invoice-processing-stack` (SAM) |
| Ingest buckets | `invoice-processing-buckets-m3` (incoming/ + invoices/) |
| Raw e-mail bucket | `ses-inbound-emails-m3` (emails/) |
| Table | `invoices` (HASH key `invoiceId`) |
| State machine | `InvoiceProcessingStateMachine-hcaDdc2OYf7l` |
| HTTP API | `xei4kla8v8` (`https://…invoke-url`) — upload, approve/reject, list, export |
| Frontend | Amplify → `https://zexxity.online`, custom domain via CloudFront |
| Secrets | `invoice-processing/config` (Secrets Manager) |
| Classification model | `apac.amazon.nova-lite-v1:0` |

## Components

```
                        ┌─────────────────────────────────────────────┐
                        │                 REVIEWER UI                 │
                        │   https://zexxity.online  (Amplify/React)   │
                        └──────┬──────────────────────────▲───────────┘
                               │ list /audit              │ approve / reject
                               ▼                          │ (token click → GET)
                     ┌──────────────────┐        ┌────────────┴─────────┐
                     │   HTTP API       │        │  TokenApprovalLambda │
                     │ Get/Approve/     │        │  decodes token,      │
                     │ Reject/UploadUrl │        │  DynamoDB update,    │
                     └────────┬─────────┘        │  SendTaskSuccess     │
                              │                  └────────────┬─────────┘
   ┌──────────────┐           │                               │ resumes
   │  invoices@   │           │                               ▼
   │  zexxity.online          │            ┌─────────────────────────────────┐
   │  SES rule    │           │            │  STEP FUNCTIONS state machine   │
   │  (MX ap-south│           │            └───────────────┬─────────────────┘
   └──────┬───────┘           │                            │
          ▼                   │                            │
   ┌──────────────┐   ┌───────┴────────┐     ┌─────────────┴───────────────┐
   │ SES handler  │──▶│ S3 bucket      │────▶│ EventBridge on ObjectCreated│
   │ (raw mail →  │   │ incoming/      │  r  │ incoming/ + invoices/        │
   │ emails/)     │   │ invoices/      │────▶│ → StartExecution             │
   └──────────────┘   └────────────────┘     └─────────────────────────────┘
                        ┌─────────────────────────────────────────────────┐
                        │          invoice-extraction-lambda             │
                        │  Textract AnalyzeExpense → schema → Bedrock     │
                        │  Nova validation → decision (APPROVED/…)        │
                        └──────────────────┬──────────────────────────────┘
                                           │ writes / validates
                                  ┌────────┴────────┐   ┌───────────────┐
                                  │ DynamoDB:       │   │ S3: audit/invoice-*.json
                                  │ invoices table  │   │ (TST-2026-0001.json)
                                  └─────────────────┘   └───────────────┘
```

## Data flow

1. **Ingest.** Two paths:
   - *Web upload* — frontend calls `UploadUrlLambda` for a presigned PUT into
     `s3://invoice-processing-buckets-m3/incoming/`.
   - *E-mail* — SES inbound (`invoices@zexxity.online`, verified identity
     `zexxity.online`, active rule set `invoice-inbound`) delivers raw mail to
     `s3://ses-inbound-emails-m3/emails/` and fires `ses-inbound-handler`,
     which stores the mail and drops any attachment PDF into the inbox bucket.
2. **Trigger.** EventBridge rule `InvoiceUploadRule` matches `ObjectCreated` on
   prefixes `incoming/` and `invoices/` and starts the state machine.
3. **Extract.** `ProcessInvoice` (55 s timeout, 3 retries) runs
   `invoice-extraction-lambda`: Textract `AnalyzeExpense`, field mapping,
   discount/tax derivation, confidence aggregation, then Amazon Bedrock
   `apac.amazon.nova-lite-v1:0` (temperature 0.1) with a strict output
   contract: `validationStatus`, `risk`, `totalConfidence`, `avgConfidence`,
   `comments`, `missingFields`.
   - Bedrock is optional: if `InvokeModel` fails the result degrades to a
     conservative REVIEW_REQUIRED, never a crash.
4. **Decide.**
   - Validation string is `APPROVED` → `CheckStatus` routes to **Approved**.
   - Anything else → **ReviewOrDuplicate** → **RequestApproval**.
5. **Human approval.** `RequestApproval` uses `Resource:
   arn:aws:states:::lambda:invoke.waitForTaskToken`. It:
   - sends the reviewer a Brevo e-mail with one-click Approve/Reject links
     (72 h TTL);
   - waits (state timeout 3 days) until the token is answered.
   The reviewer click hits `TokenApprovalLambda` via the API (`/invoices/approve`
   or `/invoices/reject`), which writes `reviewDecision`, `reviewedBy`,
   `reviewedAt`, `reviewNote` to DynamoDB and calls `SendTaskSuccess` carrying
   the same decision; the state machine transitions to **Approved / Rejected**.
   Unanswered tokens expire and the execution catches into **ApprovalTimedOut**
   (exception surfaced by `expired-review-cleanup` lifetime timer).

## State machine

States: `ProcessInvoice` → `CheckStatus` → (`Approved` | `ReviewOrDuplicate` →
`RequestApproval`) → `DecisionOutcome` → `Approved` / `Rejected`, plus the
catches `RequestFailed` (ProcessInvoice dead-letter) and `ApprovalTimedOut`
(RequestApproval TTL).

- `ProcessInvoice`: TimeoutSeconds 55, interval 3 s, backoff 1.5, 3 retries,
  catches `Lambda.ServiceException`, `Lambda.AWSLambdaException`,
  `States.TaskFailed` → `RequestFailed` (state succeeds, workflow ends).
- `RequestApproval`: `waitForTaskToken`, TimeoutSeconds 259200,
  `Catch` → `ApprovalTimedOut` (also succeeds; does not leave dangling
  RUNNING executions).

## Persistence

- **DynamoDB `invoices`** — one item per invoice keyed by `invoiceId`
  (HASH). Holds extracted fields, confidences, `validationStatus`, `risk`,
  `comments`, `missingFields`, then `reviewDecision`/`reviewedBy`/
  `reviewedAt`/`reviewNote` once a human or the model decides.
- **S3 audit trail** — every processed invoice writes
  `s3://invoice-processing-buckets-m3/audit/invoice-<id>.json` with the
  extracted, confidence-tagged fields (see `docs/sample-audit-report.md`);
  immovable and exportable. The reviewer dashboard and `export` API read it.

## Security

- Secrets (`brevoApiKey`, `brevoSender`, `sesReviewer`, `frontendUrl`,
  `modelId`) only in Secrets Manager; Lambdas read at runtime
  (`secretsmanager:GetSecretValue` scoped to `invoice-processing/config*`).
- Lambda role grants least-privilege: DynamoDB item CRUD on `invoices`,
  Textract, Bedrock, SES send, `states:SendTaskSuccess`, S3 on the two
  buckets only.
- Approval links are single-click (no login) but expire after 72 h and are
  recorded with the authority `email-link`.
- API enables CORS for `zexxity.online` origins only.

## IaC / deploy

Singular SAM template `template.yaml` (promoted from `migration/template-v2.yaml`)
defines every Lambda, the bucket policy, DynamoDB, the API, the state
machine + role, and the EventBridge rule. Jar is pre-built with Maven and
deployed without a SAM build pass:

```
mvn clean package
sam package --template-file template.yaml --s3-bucket invoice-deploy-891377127368 \
  --output-template-file packaged-template.yaml --region ap-south-1 --profile new3
aws cloudformation deploy --stack-name invoice-processing-stack \
  --template-file packaged-template.yaml --region ap-south-1 --profile new3 \
  --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND \
  --parameter-overrides "InvoiceBucketName=invoice-processing-buckets-m3" \
      "SesInboundBucketName=ses-inbound-emails-m3" "DynamoTableName=invoices" \
      "SesReceiptRuleSetName=invoice-inbound"
```

Pass each override as its own `"K=V"` argument — a single quoted string makes
the AWS CLI swallow the rest as one value (observed: bucket name became the
entire override line and the pipeline briefly guarded against the wrong S3
bucket).