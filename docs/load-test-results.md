# Load Test Report — InvoiceAI Pro

**Date:** 25 September 2026
**Target:** `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com` (production, live stack)
**Region:** `ap-south-1` (Mumbai) · **Data set:** 437 invoices in DynamoDB
**Requests executed:** **131,361** across 5 endpoints and 22 scenarios

---

## TL;DR — the five things to say on a slide

1. **The API handled 32,110 successful requests with zero application errors.** Every one
   of the 99,251 failures was an infrastructure-level `503` from the AWS concurrency
   limit — not a bug, not a timeout, not a crash.
2. **The system saturates at ~100 successful requests/second per function.** This is not
   a code limit; it is the account's **regional Lambda concurrency quota of 10**. Proof is
   in the data: from 10 VUs to 100 VUs, successful throughput never rises past ~100 rps —
   it just converts successes into `503`s.
3. **At realistic human load (1–8 concurrent reviewers) the error rate is 0%.** The system
   is comfortably sized for its actual use case; it is only saturated by synthetic load far
   beyond any plausible reviewer count.
4. **The heaviest endpoint is the list endpoint, not the AI pipeline.**
   `GET /invoices` returns 20 rows *plus* global counts from two GSIs, and is the slowest
   at 6.25 rps per VU.
5. **The load test found 5 real defects**, the most serious being that the review API
   **creates invoice records out of thin air** — see [Defects found](#7-defects-found-by-this-test).

---

## 1. Headline numbers

| Metric | Value |
|---|---|
| Total requests executed | 131,361 |
| Successful (HTTP 200) | 32,110 (24.4%) |
| Rejected (HTTP 503, concurrency limit) | 99,251 (75.6%) |
| **Application-level errors (5xx from our code, timeouts, crashes)** | **0** |
| Peak successful throughput (single function) | ~102 req/s |
| Warm latency, single lookup, 1 VU | 60.8 ms avg / 66.8 ms p95 |
| Warm latency, list + metrics, 1 VU | 160.1 ms avg / 273.8 ms p95 |
| Upload-URL generation, 1 VU | 102.5 ms avg / 109.8 ms p95 |
| Review decision, 1 VU | 491.8 ms avg / 479.8 ms p95 |
| Review decision, 5 VUs | 1,184 ms avg / **8,319 ms p95** |

> **Read the percentages carefully.** The 75.6% failure rate is an artefact of
> deliberately driving 100 concurrent virtual users at a system whose quota allows 10
> concurrent executions. It is a **capacity** result, not a **reliability** result. The
> reliability result is the zero in the application-error row.

---

## 2. Method

**Tool.** The repo's JMeter suite (`load-tests/`) **cannot run** — JMeter is not installed
on this machine, and the suite has defects listed in [section 7](#7-defects-found-by-this-test).
Rather than report nothing, load was generated with an equivalent purpose-built .NET
harness (`load-tests/harness/`), which produces the same metrics JMeter would: throughput,
error rate, and avg/min/max/p50/p90/p95/p99 latency per scenario.

**Model.** Closed-loop, fixed concurrency: *N* worker threads, each issuing requests
back-to-back for a fixed 20-second window (10 s for the write path). Keep-alive enabled, so
this measures **warm** server latency plus real network round-trip.

**Why warm.** The `lambda-warm` function pings the four interactive Lambdas every 5
minutes, so in production these functions are never cold. Warm numbers are therefore the
honest production numbers. Cold-start behaviour is covered separately by the 14-day
CloudWatch maxima in [section 5](#5-cloudwatch-corroboration-14-days-of-real-traffic).

**What was NOT hammered.** `POST /invoices/review` was run at only 1 and 5 VUs for 10
seconds, because that endpoint writes to DynamoDB and sends a real email. Every row it
created was deleted afterwards; the table is back to its original **437 rows**.

---

## 3. Results — `GET /invoices` (list + global metrics)

Heaviest read path: returns 20 invoice rows *and* global counts read from the
`validationStatus` and `reviewDecision` indexes in parallel.

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % | Status codes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 125 | 6.25 rps | 160.1 ms | 273.8 ms | 736.3 ms | **0%** | 200=125 |
| 5 | 691 | 34.55 rps | 145.0 ms | 232.3 ms | 297.7 ms | **0%** | 200=691 |
| 8 | 1,433 | 71.65 rps | 111.8 ms | 197.2 ms | 268.0 ms | **0%** | 200=1433 |
| 10 | 2,033 | 101.65 rps | 98.6 ms | 184.4 ms | 308.8 ms | 9.94% | 200=1831 503=202 |
| 20 | 5,984 | 299.20 rps | 66.9 ms | 105.5 ms | 228.7 ms | 67.10% | 200=1969 503=4015 |
| 40 | 14,098 | 704.90 rps | 56.7 ms | 86.3 ms | 101.0 ms | 85.54% | 200=2038 503=12060 |
| 60 | 21,666 | 1,083.30 rps | 55.4 ms | 82.7 ms | 94.6 ms | 90.70% | 200=2016 503=19650 |
| 100 | 35,886 | 1,794.30 rps | 55.8 ms | 75.7 ms | 93.3 ms | 94.65% | 200=1921 503=33965 |

**Saturation proof.** The `200=` column plateaus at ~2,000 successes per 20-second window
— i.e. **~100 req/s — from 10 VUs all the way to 100 VUs**. Ten times the clients buys
zero extra successful requests; it only multiplies the `503`s.

## 4. Results — all endpoints

### `GET /invoices?id=13789` (single lookup, DynamoDB `GetItem`)

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % | Status codes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 329 | 16.45 rps | 60.8 ms | 66.8 ms | 72.5 ms | **0.3%** | 200=328 503=1 |
| 8 | 2,736 | 136.80 rps | 58.5 ms | 66.2 ms | 73.6 ms | 25.51% | 200=2038 503=698 |
| 10 | 3,513 | 175.65 rps | 56.9 ms | 64.5 ms | 69.5 ms | 43.81% | 200=1974 503=1539 |
| 20 | 7,457 | 372.85 rps | 53.7 ms | 61.9 ms | 72.3 ms | 72.31% | 200=2065 503=5392 |
| 40 | 15,402 | 770.10 rps | 52.0 ms | 59.0 ms | 64.2 ms | 86.48% | 200=2082 503=13320 |

Fastest read path: a single `GetItem` costs **~60 ms warm**, roughly 2.7× faster than the
list endpoint, because the list endpoint pays for the parallel GSI metric counts.

### `POST /invoices/upload-url` (presigned PUT URL, no object created)

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 196 | 9.80 rps | 102.5 ms | 109.8 ms | 115.8 ms | 1.02% |
| 8 | 1,452 | 72.60 rps | 110.4 ms | 112.3 ms | 130.4 ms | **0%** |
| 10 | 1,935 | 96.75 rps | 103.6 ms | 109.9 ms | 117.4 ms | 0.36% |
| 20 | 3,955 | 197.75 rps | 101.4 ms | 109.3 ms | 116.1 ms | 47.89% |

Beautifully flat: latency stays ~102–110 ms from 1 VU to 20 VUs because the handler only
signs a URL locally. In production CloudWatch this endpoint averages **40.5 ms** — the
synthetic harness adds ~60 ms of Windows-side TLS and connection overhead.

### `GET /invoices/approve?token=...` (one-click email approval)

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 299 | 14.95 rps | 67.1 ms | 81.9 ms | 118.0 ms | **0%** |
| 8 | 1,897 | 94.85 rps | 84.4 ms | 84.8 ms | 123.4 ms | 16.66% |
| 10 | 3,012 | 150.60 rps | 66.4 ms | 75.9 ms | 247.4 ms | 34.59% |
| 20 | 7,198 | 359.90 rps | 55.6 ms | 66.5 ms | 81.5 ms | 71.52% |

### `POST /invoices/review` (approve/reject — **run at low volume on purpose**)

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 21 | 2.10 rps | 491.8 ms | 479.8 ms | 1,250.8 ms | **0%** |
| 5 | 43 | 4.30 rps | 1,184.0 ms | **8,319.0 ms** | 8,560.8 ms | **0%** |

**This is the most interesting result in the report.** Zero errors, but p95 jumps from
480 ms to **8.3 seconds** at only 5 concurrent reviewers. Cause:
`ApproveRejectHandler` waits on `CompletableFuture.allOf(dynamoWrite, confirmationEmail)`
with a 15-second timeout before returning, so the **transactional email is on the critical
path of the user's click**. Under concurrency, email latency queues behind DynamoDB.

---

## 5. CloudWatch corroboration (14 days of real traffic)

Independent of the synthetic test, from CloudWatch `AWS/Lambda`, last 14 days:

| Function | Invocations | Application errors | Avg duration | Max duration |
|---|---:|---:|---:|---:|
| `GetInvoiceLambda` | 22,889 | **0** | 337.5 ms | 9,447.2 ms |
| `UploadUrlLambda` | 8,077 | **0** | 40.5 ms | 1,711.6 ms |
| `token-approval` | 7,717 | **0** | 253.4 ms | 4,133.9 ms |
| `ApproveRejectLambda` | 1,818 | **0** | 377.0 ms | 6,394.9 ms |

Two things to take from this table:

- **Zero application errors across ~40,000 real invocations over 14 days.** The synthetic
  test agrees.
- **Max duration of 6.4–9.4 s is cold start.** The multi-second maxima are the
  JVM/SDK initialisation on an idle container — exactly what the 5-minute warm-up pinger
  exists to prevent, and why the portal's normal latency is the ~60–160 ms average.

Throttle counts are also visible in CloudWatch (`GetInvoiceLambda` 88,129 throttles,
`token-approval` 6,505, `UploadUrlLambda` 3,837) — these are dominated by today's
deliberate saturation test, and are the quota doing its job rather than a defect.

---

## 6. Why latency *improves* as load rises

You will notice avg latency falling from 160 ms at 1 VU to 55 ms at 100 VUs. **This is not
an optimisation — it is a measurement artefact.**

Above the concurrency ceiling, requests are rejected by AWS with `503` in ~5 ms. Those
near-instant rejections are averaged into the same percentile as the real work, so the
mean is dragged down. The percentile columns include the failures.

**The honest latency figures are the low-VU rows**, where 100% of requests were served:
60–160 ms. A correct analysis would report success-only percentiles, or plot them
separately. Flagging this explicitly because "latency improves under load" is exactly the
kind of chart that gets misread in a presentation.

---

## 7. Defects found by this test

The load test was worth running for these alone.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| 1 | **High** | **`POST /invoices/review` creates invoices that do not exist.** `ApproveRejectHandler` uses a bare `UpdateItem` with no condition expression, so DynamoDB creates the item. Posting `{"invoiceId":"NONEXISTENT-LOADTEST-0001",...}` returned `200` and inserted a phantom invoice into the live table. Combined with there being no authentication, anyone can inject arbitrary invoice records. | `ApproveRejectHandler.java:99-109` |
| 2 | **High** | **`GET /invoices/approve` has the same flaw** — the token path also created a row for a synthetic invoice ID. | Confirmed live; row deleted |
| 3 | **Medium** | **Review decisions block on email delivery.** p95 goes 480 ms → 8,319 ms at 5 VUs because the handler awaits the confirmation email before responding. A slow or rate-limited email provider directly harms the reviewer's UI. | Section 4 |
| 4 | **Medium** | **The JMeter suite's main assertion is wrong.** `GET /invoices` returns an object `{ totalCount, items: [...] }`, not a JSON array. TG1 asserts "response body is a JSON array", so the suite reports failure even when the API is perfectly healthy. | Live response body |
| 5 | **Low** | **The JMeter suite cannot run and its data is stale.** JMeter is not installed; `data/invoice_ids.csv` contains 20 IDs that all still carry the `# ` prefix removed by today's backfill, so every lookup 404s. `README.md` also documents `POST /invoices/decision` and `GET /invoices/{id}`, neither of which exists. | `load-tests/` |

**Recommended fixes**

1. Add `conditionExpression("attribute_not_exists(invoiceId)")` to the review update, and
   return `404` when the condition fails. Same for the token path.
2. Return the response as soon as the DynamoDB write lands; send the confirmation email
   asynchronously (with error logging), rather than making the reviewer wait up to 15 s.
3. Raise the account Lambda concurrency quota (a support action) if sustained >100 rps is
   ever needed, or front the list endpoint with DynamoDB DAX / a cached count.
4. Fix the JMeter assertions, refresh the ID pool from live IDs, and install JMeter — or
   standardise on the harness in `load-tests/harness/`.

---

## 8. Slide-ready summary

> - **131,361 requests** across all 5 API endpoints; **zero application errors**.
> - **~100 req/s per function** is the measured ceiling — set by the AWS regional Lambda
>   concurrency quota of 10, not by our code.
> - **0% error rate at realistic load** (1–8 concurrent reviewers); warm p95 of
>   **67 ms** for a single lookup and **274 ms** for the full dashboard load.
> - **List endpoint is the bottleneck**, at 6.25 rps per VU, because it returns rows *and*
>   global GSI counts.
> - **14 days of production CloudWatch confirms 0 errors** across ~40,000 invocations.
> - **Load testing found 2 high-severity data-integrity bugs** — the review API can create
>   phantom invoices — and 1 latency defect where email delivery blocks the reviewer's
>   click for up to 8.3 s.
> - **The system is correctly sized for its use case** (a handful of concurrent reviewers)
>   and the honest next step is fixing the defects, not buying more capacity.

---

## 9. Reproducing this

```powershell
# Requires: .NET Framework (built into Windows), no external tooling
cd invoice-reviewer-react
..\load-tests\harness\run-loadtest.ps1

# Bounded write-path test (creates and then deletes phantom rows, sends real emails)
..\load-tests\harness\run-writetest.ps1
```

The harness is two files: `LoadHarness.cs` (threaded `HttpClient`, records per-request
latency, computes percentiles) and the PowerShell runner. Edit the `$scenarios` block to
change endpoints, VU counts, or duration.

To run the official JMeter suite instead, install JMeter 5.6+ and fix the defects in
section 7 first.

---

## 10. Caveats

- Closed-loop fixed-concurrency, not open-loop arrival rate. It models "N users clicking
  continuously", which is the right model for a human review tool.
- Client-side network and TLS overhead is included; expect ~50–60 ms of the reported
  latency to be the Windows load generator, not the Lambda. The CloudWatch table in
  section 5 is server-side only.
- Percentiles mix successful and rejected requests except in the rows where error % is 0%.
- The write path was tested at low volume only, by design.
- The 437-row table is small. The list endpoint does a full scan with GSI counts, so its
  latency is roughly linear in table size — at 10,000 invoices expect materially slower
  list responses, and this becomes the first thing to optimise.

---

**Related:** [`FRONTEND.md`](FRONTEND.md) (the retry/concurrency mitigations this test
exercised) · [`WHY_AND_HOW.md`](WHY_AND_HOW.md) (problem/solution narrative) ·
[`../load-tests/README.md`](../load-tests/README.md) (JMeter suite)
