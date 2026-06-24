# Load Tests — Invoice Processing System (JMeter)

JMeter-based load test suite covering every Lambda endpoint in the invoice processing pipeline.

---

## Structure

```
load-tests/
├── invoice_full_suite.jmx    JMeter test plan — 6 Thread Groups, all endpoints
├── user.properties           Thread counts, ramp times, durations, API config
├── run.ps1                   PowerShell runner — finds jmeter.bat, runs test, generates report
├── README.md                 This file
├── data/
│   └── invoice_ids.csv       Invoice IDs used by GET-by-ID and decision tests
└── results/                  Auto-created — JTL files + HTML dashboard per run
```

---

## Prerequisites

### Install JMeter

1. Download from https://jmeter.apache.org/download_jmeter.cgi
2. Extract to e.g. `C:\apache-jmeter-5.6.3`
3. Add `C:\apache-jmeter-5.6.3\bin` to your `PATH`, **or** pass `-JMeterHome` to the runner

Verify: open a new terminal and run `jmeter.bat --version`

---

## Quick Start

Run from the project root (`invoice-extraction-lambda/`):

```powershell
# Smoke test — 1 VU per thread group, ~30 seconds, just checks nothing crashes
.\load-tests\run.ps1 -Profile smoke

# Baseline — default config from user.properties (~2 minutes)
.\load-tests\run.ps1

# Open HTML report in browser automatically when done
.\load-tests\run.ps1 -OpenReport

# Avoid real SES emails during testing (uses synthetic invoice IDs)
.\load-tests\run.ps1 -DryRun

# Seed with real invoice IDs from your DynamoDB table
.\load-tests\run.ps1 -InvoiceIds "INV-2025-001,INV-2025-002,INV-2025-003"

# Point at a different API (e.g. staging)
.\load-tests\run.ps1 -BaseUrl "your-api-id.execute-api.ap-south-1.amazonaws.com"

# Stress test — 50–100 VUs, 5 minutes
.\load-tests\run.ps1 -Profile stress -DryRun

# Soak test — 10 VUs sustained for 10 minutes
.\load-tests\run.ps1 -Profile soak

# If jmeter.bat is not on PATH
.\load-tests\run.ps1 -JMeterHome "C:\apache-jmeter-5.6.3"
```

---

## Profiles

| Profile    | VUs (peak) | Duration   | Purpose                               |
|------------|-----------|------------|---------------------------------------|
| `smoke`    | 1–5       | ~30s       | Sanity check — nothing crashes        |
| `baseline` | 5–15      | ~2 min     | Normal expected load **(DEFAULT)**    |
| `stress`   | 20–100    | ~5 min     | Find the breaking point               |
| `soak`     | 4–10      | ~10 min    | Detect slow memory leaks / drift      |

Override individual settings in `user.properties` or with `-J` flags:

```powershell
# Custom: 20 VUs on GET /invoices only, 5-minute run
.\load-tests\run.ps1 -Profile baseline
# then in user.properties: tg1_threads=20, tg1_duration=300
```

---

## Thread Groups

| # | Name                         | Endpoint                        | Lambda              | AWS Services                    |
|---|------------------------------|---------------------------------|---------------------|---------------------------------|
| 1 | GET /invoices (List All)     | `GET /invoices`                 | GetInvoiceHandler   | DynamoDB Scan                   |
| 2 | GET /invoices/{id}           | `GET /invoices/{id}`            | GetInvoiceHandler   | DynamoDB GetItem                |
| 3 | POST /invoices/upload-url    | `POST /invoices/upload-url`     | UploadUrlHandler    | S3 Presigner (no external I/O)  |
| 4 | POST /invoices/decision      | `POST /invoices/decision`       | ApproveRejectHandler| DynamoDB UpdateItem + SES       |
| 5 | Token Approval (email links) | `GET /invoices/approve|reject`  | TokenApprovalHandler| DynamoDB GetItem + UpdateItem   |
| 6 | Cold Start Spike             | All of the above                | All handlers        | All services, fast ramp         |

---

## What Each Thread Group Tests

**TG1 — List All Invoices**
- HTTP 200 assertion
- Response body is a JSON array
- Duration < 3000 ms

**TG2 — Get Invoice by ID**
- HTTP 200 or 404 (both acceptable — 5xx is a failure)
- `invoiceId` field present on 200 responses
- Duration < 2000 ms

**TG3 — Upload URL Generator**
- HTTP 200 assertion
- `uploadUrl` field present
- `expiresInSeconds` = 300
- `objectKey` matches `^invoices/.*\.pdf$`
- Duration < 2000 ms

**TG4 — Approve / Reject**
- Valid decision: HTTP 200, `reviewDecision` matches request body
- Invalid (missing `invoiceId`): HTTP 400 — runs ~10% of iterations
- Duration < 6000 ms (DynamoDB write + cross-region SES to eu-north-1)

**TG5 — Token Approval**
- Valid approve token: HTTP 200, HTML response, body contains `APPROVED` or `Already Decided`
- Valid reject token: HTTP 200, body contains `REJECTED`
- Expired token: HTTP 400, body mentions "expired" or "72" — runs ~15% of iterations
- Malformed token: HTTP 400 — runs ~8% of iterations
- Tokens are built at runtime using `Base64.getUrlEncoder()` in a BeanShell PreProcessor, exactly matching `TokenApprovalHandler.java`'s format: `{"invoiceId":"...","decision":"...","exp":<epoch+72h>}`

**TG6 — Cold Start Spike**
- 30 VUs ramped in 10 seconds (fast enough to trigger Lambda cold starts)
- Hits all endpoints with `use_keepalive=false` (forces new connections)
- Duration budget: 8000 ms per request (Java 21 cold start overhead)
- Compare TG6 latency vs TG1–TG5 to measure cold start delta

---

## Output Files

After each run, `results/` contains:

| File / Directory                          | Contents                                   |
|-------------------------------------------|--------------------------------------------|
| `all_results_<profile>_<ts>.jtl`          | All samples in CSV format                  |
| `html_report_<profile>_<ts>/index.html`   | JMeter HTML dashboard (open in browser)    |
| `jmeter_<profile>_<ts>.log`               | JMeter engine log (errors, warnings)       |
| `tg1_list_invoices.jtl`                   | TG1 samples only                           |
| `tg2_get_by_id.jtl`                       | TG2 samples only                           |
| `tg3_upload_url.jtl`                      | TG3 samples only                           |
| `tg4_approve_reject.jtl`                  | TG4 samples only                           |
| `tg5_token_approval.jtl`                  | TG5 samples only                           |
| `tg6_cold_start.jtl`                      | TG6 cold start spike samples               |
| `errors_only.jtl`                         | Failed samples only (includes response body for triage) |

---

## HTML Dashboard Sections

The JMeter HTML report (`index.html`) contains:

- **Dashboard** — total requests, error %, avg/p90/p95/p99 latency, throughput (req/s)
- **Charts** — response time over time per label, active threads over time, throughput over time, error % over time
- **Statistics** — per-sampler table: samples, fail%, avg, min, max, p90, p95, p99, throughput, received KB/s
- **Errors** — error type breakdown

Key metrics to check in the Statistics table:

| Sampler label                              | Expected p95   |
|--------------------------------------------|----------------|
| GET /invoices — List All                   | < 2000 ms      |
| GET /invoices/{id} — Single Lookup         | < 1500 ms      |
| POST /invoices/upload-url                  | < 1500 ms      |
| POST /invoices/decision — Valid Decision   | < 4000 ms      |
| GET /invoices/approve — Valid Token        | < 2500 ms      |
| GET /invoices/reject — Valid Token         | < 2500 ms      |
| COLD: GET /invoices                        | < 8000 ms      |
| COLD: POST /invoices/upload-url            | < 7000 ms      |
| COLD: GET /invoices/approve (token)        | < 8000 ms      |

---

## Seeding Real Invoice IDs

For the most realistic test, populate `data/invoice_ids.csv` with real invoice IDs from your DynamoDB table:

```powershell
# One-liner using AWS CLI
aws dynamodb scan --table-name invoices --projection-expression "invoiceId" `
  --query "Items[*].invoiceId.S" --output text | `
  ForEach-Object { $_ -split '\t' } | `
  Set-Content load-tests\data\invoice_ids.csv
```

Or pass them directly to the runner:

```powershell
.\load-tests\run.ps1 -InvoiceIds "# 11502,# 11503,# 11504"
```

---

## Dry Run (No Real SES Emails)

Use `-DryRun` when you don't want real SES emails fired. TG4 (approve/reject) uses synthetic invoice IDs that don't exist in DynamoDB — Lambda still executes end-to-end (measures cold start, IAM handshake, DynamoDB miss latency) but the UpdateItem is a no-op and no SES email is sent.

```powershell
.\load-tests\run.ps1 -Profile stress -DryRun
```

---

## Opening the Test Plan in JMeter GUI

To inspect or modify the test plan visually:

```powershell
jmeter.bat -t load-tests\invoice_full_suite.jmx
```

This opens the full GUI with all Thread Groups, Samplers, Assertions, and Listeners visible.
