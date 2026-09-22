# Sample Batch Audit Report

Generated from the live S3 audit trail
(`s3://invoice-processing-buckets-m3/audit/invoice-*.json`, example subset).
Every processed invoice is persisted here by the state machine with the fields
as extracted and the confidences as reported by the pipeline. A batch (e.g.
daily) report aggregates these files and adds the pipeline verdict.

## Summary (example batch: 6 invoices)

| Invoice ID | Vendor | Date | Total | Vendor conf | Total conf | Verdict | Notes |
|---|---|---|---|---|---|---|---|
| TST-2026-0001 | AppleMega | Nov 11 2012 | $925.25 | 99.83% | 99.9996% | APPROVED | clean, automated |
| TST-2026-0002 | AppleMega | Dec 05 2012 | $925.00 | 97.57% | 99.9997% | APPROVED | tax present, formula ties out |
| TST-2026-0003 | AppleMega | Jan 15 2013 | $999.99 | 99.77% | 99.9998% | APPROVED (human) | mismatch → REVIEW_REQUIRED → approved by reviewer via e-mail link |
| # 25322 | SuperStore | Feb 28 2012 | $1,452.24 | 76.91% | 99.9975% | REVIEW_REQUIRED | low vendor confidence, no tax |
| # 13707 | — | May 04 2012 | $4,280.48 | — | 99.9985% | REVIEW_REQUIRED | missing vendor name |
| UNKNOWN-…-9100 | — | Jun 5, 2023 | $0.00 | — | 99.9998% | REVIEW_REQUIRED | unreadable document (no ID/subtotal) |

## Per-invoice audit records (raw)

### TST-2026-0001 — APPROVED
```json
{
  "vendorName" : "AppleMega",
  "invoiceDate" : "Nov 11 2012",
  "invoiceId" : "TST-2026-0001",
  "subtotal" : "$1,015.68",
  "shipping" : "$112.71",
  "tax" : null,
  "discount" : "$203.14",
  "total" : "$925.25",
  "vendorConfidence" : 99.826256,
  "totalConfidence" : 99.99961,
  "invoiceIdConfidence" : 99.962425,
  "dateConfidence" : 99.791756,
  "lineItemsSum" : 1015.68,
  "lineItemCount" : 1
}
```

### TST-2026-0002 — APPROVED
```json
{
  "vendorName" : "AppleMega",
  "invoiceDate" : "Dec 05 2012",
  "invoiceId" : "TST-2026-0002",
  "subtotal" : "$1,000.00",
  "shipping" : "$50.00",
  "tax" : "$75.00",
  "discount" : "$200.00",
  "total" : "$925.00",
  "vendorConfidence" : 97.569855,
  "totalConfidence" : 99.999725,
  "invoiceIdConfidence" : 99.97338,
  "dateConfidence" : 99.97117,
  "lineItemsSum" : 1000.0,
  "lineItemCount" : 1
}
```

### TST-2026-0003 — REVIEW_REQUIRED → human APPROVED
Model flagged the totals mismatch: `subtotal − discount + shipping + tax`
(1000.00 − 200.00 + 50.00 + 75.00 = 925.00) does not tie to the printed
`$999.99`. State machine paused in `RequestApproval`, the reviewer approved
via the e-mail one-click link, and `SendTaskSuccess` resumed the execution
(`reviewedBy: "email-link"`, `reviewDecision: "APPROVED"`).

```json
{
  "vendorName" : "AppleMega",
  "invoiceDate" : "Jan 15 2013",
  "invoiceId" : "TST-2026-0003",
  "subtotal" : "$1,000.00",
  "shipping" : "$50.00",
  "tax" : "$75.00",
  "discount" : "$200.00",
  "total" : "$999.99",
  "vendorConfidence" : 99.77439,
  "totalConfidence" : 99.99975,
  "invoiceIdConfidence" : 99.96736,
  "dateConfidence" : 99.776596,
  "lineItemsSum" : 1000.0,
  "lineItemCount" : 1
}
```

### # 25322 — REVIEW_REQUIRED (low vendor confidence)
```json
{
  "vendorName" : "SuperStore",
  "invoiceDate" : "Feb 28 2012",
  "invoiceId" : "# 25322",
  "subtotal" : "$2,432.16",
  "shipping" : "$236.16",
  "discount" : "$1,216.08",
  "total" : "$1,452.24",
  "vendorConfidence" : 76.909874,
  "totalConfidence" : 99.9975,
  "invoiceIdConfidence" : 85.585976,
  "dateConfidence" : 99.910614,
  "lineItemsSum" : 2432.16,
  "lineItemCount" : 1
}
```

### # 13707 — REVIEW_REQUIRED (missing vendor name)
```json
{
  "vendorName" : null,
  "invoiceDate" : "May 04 2012",
  "invoiceId" : "# 13707",
  "subtotal" : "$4,189.92",
  "shipping" : "$90.56",
  "tax" : null,
  "discount" : null,
  "total" : "$4,280.48",
  "vendorConfidence" : null,
  "totalConfidence" : 99.99845,
  "invoiceIdConfidence" : 79.263245,
  "dateConfidence" : 99.75458,
  "lineItemsSum" : 4189.92,
  "lineItemCount" : 1
}
```

### UNKNOWN-1790094135796-9100 — REVIEW_REQUIRED (unreadable document)
```json
{
  "vendorName" : null,
  "invoiceDate" : "Jun 5, 2023",
  "invoiceId" : null,
  "subtotal" : null,
  "total" : "$0.00",
  "vendorConfidence" : null,
  "totalConfidence" : 99.99976,
  "invoiceIdConfidence" : null,
  "dateConfidence" : 99.99381,
  "lineItemsSum" : null,
  "lineItemCount" : 0
}
```

## How to regenerate

- Audit files: `aws s3 sync s3://invoice-processing-buckets-m3/audit/ ./audit/`
- Verdicts: from DynamoDB `invoices.validationStatus` / `reviewDecision`.
- This report is a static example; a scheduled writer could render the same
  table from the `audit/` prefix plus `daily-digest-report` reflects its state.