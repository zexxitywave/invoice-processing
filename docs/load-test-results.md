# Load Test Report — InvoiceAI Pro

**Date:** 25 September 2026
**Target:** `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com` (production, live stack)
**Region:** `ap-south-1` (Mumbai) · **Data set:** 437 invoices in DynamoDB
**Requests executed:** **219,856 across two independent runs** — 131,361 (Run A) and 88,495 (Run B), 5 endpoints, 22 scenarios each

---

## TL;DR — the five things to say on a slide

1. **The API handled 58,431 successful requests across two runs with zero application
   errors.** Every one of the 161,425 failures was an infrastructure-level `503` from the
   AWS concurrency limit — not a bug, not a timeout, not a crash.
2. **The system saturates, and the ceiling is a hard plateau.** Successful throughput
   stops rising between 8 and 100 VUs and only converts successes into `503`s. This is not
   a code limit; it is the account's **regional Lambda concurrency quota of 10**. The
   plateau is the single most important result in this report.
3. **The plateau's exact height is 68–104 req/s depending on how warm the containers are.**
   Run A measured ~100 rps; Run B measured ~70 rps on the heavier endpoints because it
   caught cold starts. Same code, same quota, different number — which is itself the
   finding: **there is no headroom for a cold start.**
4. **At realistic human load (1–8 concurrent reviewers) the error rate is 0% in both runs.**
   The system is correctly sized for its actual use case and is only saturated by synthetic
   load far beyond any plausible reviewer count.
5. **The load test found 6 real defects**, the most serious being that the review API
   **creates invoice records out of thin air** — see [Defects found](#7-defects-found-by-this-test).

---

## 1. Headline numbers

| Metric | Run A | Run B | Combined |
|---|---:|---:|---:|
| Total requests executed | 131,361 | 88,495 | **219,856** |
| Successful (HTTP 200) | 32,110 | 26,321 | **58,431** |
| Rejected (HTTP 503, concurrency limit) | 99,251 | 62,174 | **161,425** |
| **Application-level errors (5xx from our code, timeouts, crashes)** | **0** | **0** | **0** |
| Peak successful throughput, single lookup | ~104 rps | ~103 rps | ~104 rps |
| Peak successful throughput, list + metrics | ~102 rps | ~79 rps | — |
| Warm latency, single lookup, 1 VU | 60.8 ms avg | 85.3 ms avg | — |
| Upload-URL generation, 1 VU | 102.5 ms avg | 109.8 ms avg | — |
| Review decision, 5 VUs | 1,184 ms avg / **8,319 ms p95** | 1,074 ms avg / **7,969 ms p95** | — |

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

**Two runs, deliberately.** The suite was executed twice against the same unchanged
production stack. Run A measured ~100 rps ceilings; Run B measured ~70 rps on the heavier
endpoints. Both are reported throughout rather than picking the flattering one. The
difference is not random noise — it is cold starts, analysed in
[section 6](#6-why-the-two-runs-differ-cold-start-is-the-whole-story), and it is the most
actionable result in the report.

**Model.** Closed-loop, fixed concurrency: *N* worker threads, each issuing requests
back-to-back for a fixed 20-second window (10 s for the write path). Keep-alive enabled, so
this measures **warm** server latency plus real network round-trip.

**Why warm.** The `lambda-warm` function pings the four interactive Lambdas every 5
minutes, so in normal operation these functions are never cold. Warm numbers are therefore
the honest production numbers. Cold-start behaviour is covered separately by the 14-day
CloudWatch maxima in [section 5](#5-cloudwatch-corroboration-14-days-of-real-traffic).

**What was NOT hammered.** `POST /invoices/review` was run at only 1 and 5 VUs for 10
seconds, because that endpoint writes to DynamoDB and sends a real email. Every row it
created was deleted afterwards; the table is back to its original **437 rows**.

---

## 3. Results — `GET /invoices` (list + global metrics)

Heaviest read path: returns 20 invoice rows *and* global counts read from the
`validationStatus` and `reviewDecision` indexes in parallel.

**Run A**

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

**Run B**

| VUs | Requests | Throughput | Avg | p95 | p99 | Error % | Status codes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 142 | 7.10 rps | 142.8 ms | 636.6 ms | 846.7 ms | **0%** | 200=142 |
| 5 | 308 | 14.67 rps | 330.9 ms | 853.9 ms | 5,487.9 ms | **0%** | 200=308 |
| 8 | 855 | 42.75 rps | 187.4 ms | 511.5 ms | 1,432.3 ms | **0%** | 200=855 |
| 10 | 1,154 | 57.70 rps | 176.1 ms | 777.5 ms | 1,359.8 ms | 1.21% | 200=1140 503=14 |
| 20 | 4,176 | 208.80 rps | 93.9 ms | 157.3 ms | 955.8 ms | 62.24% | 200=1577 503=2599 |
| 40 | 8,114 | 386.38 rps | 102.3 ms | 173.8 ms | 1,349.3 ms | 82.36% | 200=1431 503=6683 |
| 60 | 12,666 | 633.30 rps | 92.8 ms | 198.5 ms | 1,351.4 ms | 88.39% | 200=1470 503=11196 |
| 100 | 19,385 | 923.10 rps | 106.2 ms | 199.3 ms | 1,397.8 ms | 92.94% | 200=1368 503=18017 |

**Saturation proof.** In both runs the `200=` column flattens out and stops tracking the VU
count. Run A plateaus near 2,000 successes per 20-second window (~100 req/s); Run B
plateaus near 1,450 (~72 req/s). In both cases, going from 10 VUs to 100 VUs — ten times
the clients — buys **zero** extra successful requests and only multiplies the `503`s.

The two plateaus differ because Run B caught cold starts; see
[section 6](#6-why-the-two-runs-differ-cold-start-is-the-whole-story).

## 4. Results — all endpoints

### `GET /invoices?id=13789` (single lookup, DynamoDB `GetItem`)

| VUs | Run A rps | Run A p95 | Run A err | Run B rps | Run B p95 | Run B err |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 16.45 | 66.8 ms | 0.3% | 11.80 | 179.0 ms | 0% |
| 8 | 136.80 | 66.2 ms | 25.51% | 128.15 | 80.9 ms | 20.72% |
| 10 | 175.65 | 64.5 ms | 43.81% | 157.75 | 85.1 ms | 36.77% |
| 20 | 372.85 | 61.9 ms | 72.31% | 354.80 | 65.8 ms | 70.93% |
| 40 | 770.10 | 59.0 ms | 86.48% | 689.15 | 72.2 ms | 85.06% |

The most consistent endpoint across both runs: ~100 successful req/s ceiling, ~56–85 ms
served latency. A single `GetItem` is roughly 2.7× faster than the list endpoint, which
pays for two parallel GSI metric queries.

### `POST /invoices/upload-url` (presigned PUT URL, no object created)

| VUs | Run A rps | Run A p95 | Run A err | Run B rps | Run B p95 | Run B err |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 9.80 | 109.8 ms | 1.02% | 9.10 | 119.8 ms | 0% |
| 8 | 72.60 | 112.3 ms | **0%** | 71.50 | 115.8 ms | **0%** |
| 10 | 96.75 | 109.9 ms | 0.36% | 90.60 | 122.7 ms | 0.22% |
| 20 | 197.75 | 109.3 ms | 47.89% | 189.65 | 122.2 ms | 47.06% |

Beautifully flat and nearly identical across runs: latency stays ~102–122 ms from 1 VU to
20 VUs because the handler only signs a URL locally. In production CloudWatch this
endpoint averages **35.8 ms** — the synthetic harness adds ~60 ms of Windows-side TLS and
connection overhead.

### `GET /invoices/approve?token=...` (one-click email approval)

| VUs | Run A rps | Run A p95 | Run A err | Run B rps | Run B p95 | Run B err |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 14.95 | 81.9 ms | **0%** | 15.40 | 76.9 ms | **0%** |
| 8 | 94.85 | 84.8 ms | 16.66% | 52.80 | 399.7 ms | 5.87% |
| 10 | 150.60 | 75.9 ms | 34.59% | 103.20 | 175.4 ms | 28.73% |
| 20 | 359.90 | 66.5 ms | 71.52% | 207.70 | 161.2 ms | 66.75% |

Ceiling ~103 rps in Run A, ~69 rps in Run B — the same cold-start effect, and the widest
gap between the two runs of any endpoint.

### `POST /invoices/review` (approve/reject — **run at low volume on purpose**)

| VUs | Run A avg | Run A p95 | Run B avg | Run B p95 | Error % (both) |
|---:|---:|---:|---:|---:|---:|
| 1 | 491.8 ms | 479.8 ms | 644.3 ms | 3,082.2 ms | **0%** |
| 5 | 1,184.0 ms | **8,319.0 ms** | 1,074.7 ms | **7,969.1 ms** | **0%** |

**This is the most important result in the report, and it reproduced.** Both runs show the
same pathology at 5 VUs: mean under 1.2 seconds, but **p95 of 7,969–8,319 ms**. The
distribution is bimodal — most requests finish in ~440–490 ms, and a minority block for
eight seconds.

Cause: `ApproveRejectHandler` waits on `CompletableFuture.allOf(dynamoWrite, confirmationEmail)`
with a 15-second timeout before returning, so the **transactional email is on the critical
path of the user's click**. Under concurrency, email latency queues behind DynamoDB. The
reviewer sees the button hang for eight seconds, for a request that only needs a
DynamoDB write.

---

## 5. CloudWatch corroboration (14 days of real traffic)

Independent of the synthetic test, from CloudWatch `AWS/Lambda`. The window below ends
**before** the load tests began, so it contains organic traffic only:

| Function | Invocations | Application errors | Avg duration | Max duration |
|---|---:|---:|---:|---:|
| `GetInvoiceLambda` | 2,263 | **0** | 344.8 ms | 9,447.2 ms |
| `UploadUrlLambda` | 2,276 | **0** | 35.8 ms | 1,711.6 ms |
| `token-approval` | 1,650 | **0** | 258.2 ms | 4,133.9 ms |
| `ApproveRejectLambda` | 1,651 | **0** | 389.3 ms | 6,394.9 ms |

Three things to take from this table:

- **Zero application errors across ~7,840 organic invocations over 14 days.** The
  synthetic tests agree.
- **Max duration of 1.7–9.4 s is cold start.** The multi-second maxima are JVM/SDK
  initialisation on an idle container. These are real, and they are what the 5-minute
  warm-up pinger exists to suppress.
- **Real usage is low-volume and bursty** — roughly 560 invocations/day across four
  functions, with a 1.6-hour gap between typical invocations. This is a human review tool,
  and the load profile should be modelled accordingly.

Throttle counts in CloudWatch are dominated by the two deliberate saturation runs and
represent the quota working as designed, not a defect.

---

## 6. Why the two runs differ — cold start is the whole story

Run A measured ~100 req/s ceilings. Run B measured ~70 rps on the list and token
endpoints. Same code, same quota, same region, no deploy in between. The difference is
entirely **how many containers were already warm when each scenario started**.

**The mechanism.** The account has a regional concurrency quota of 10 and the stack uses
no Provisioned or Reserved Concurrency — confirmed by inspecting both the templates and the
live account. Every execution, warm or cold, occupies one of those 10 slots. A cold start
holds its slot for the **4–6 seconds** it takes to boot a JVM, while doing no useful work.

So one cold start does not cost one request. It costs a slot for 5 seconds, which at
~100 req/s is roughly **500 requests of lost capacity**.

**The evidence is in the tails.** Run B's list endpoint at 5 VUs recorded
`p99 = 5,487.9 ms` against Run A's `p99 = 297.7 ms` — a factor of 18. Those 5-second
outliers are cold starts being served while the rest of the fleet is saturated. Run B's
60-VU and 100-VU p99 values of ~1,350 ms and ~1,398 ms are the same effect at smaller
sample ratios, versus ~95 ms in Run A.

**The ceiling difference follows directly.** List endpoint plateau:

| | 20 VUs | 40 VUs | 60 VUs | 100 VUs |
|---|---:|---:|---:|---:|
| Run A successes/20 s | 1,969 | 2,038 | 2,016 | 1,921 |
| Run B successes/20 s | 1,577 | 1,431 | 1,470 | 1,368 |
| Run B as % of Run A | 80% | 70% | 73% | 71% |

Run B sustained 70–80% of Run A's throughput — consistent with roughly 2–3 of the 10
concurrency slots being absorbed by cold starts during each 20-second window.

**Why the warm-up pinger did not prevent this.** `lambda-warm` fires on `rate(5 minutes)`
and invokes all four targets **sequentially** from a 256 MB function. Two weaknesses:

1. It keeps **one** container per target warm, not ten. Any load above 1 concurrent
   execution still forces cold starts, and the pinger has no effect on them.
2. It runs sequentially, so with four targets the last one is pinged up to several seconds
   after the first, and the pinger itself can be cold.

**What this means for the presentation.** Quote the ceiling as a **range, 68–104 req/s**,
not a single number. The defensible claim is not "the system does exactly 100 req/s" — it
is "**the system saturates at a hard plateau determined by the concurrency quota, and cold
starts cost 20–30% of available capacity.**" That second half is the more valuable insight
and it only emerged because the suite was run twice.

### Why latency also *improves* as load rises

A second artefact worth flagging: avg latency falls from ~143–160 ms at 1 VU to ~56 ms at
100 VUs. **This is not an optimisation.** Above the ceiling, requests are rejected with
`503` in ~5 ms, and those near-instant rejections are averaged into the same percentile as
real work. The percentile columns include the failures.

**The honest latency figures are the low-VU rows**, where 100% of requests were served:
60–160 ms. A rigorous analysis would report success-only percentiles. Flagging this
explicitly because "latency improves under load" is exactly the kind of chart that gets
misread in a presentation.

---

## 7. Defects found by this test

The load test was worth running for these alone.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| 1 | **High** | **`POST /invoices/review` creates invoices that do not exist.** `ApproveRejectHandler` uses a bare `UpdateItem` with no condition expression, so DynamoDB creates the item. Posting `{"invoiceId":"NONEXISTENT-LOADTEST-0001",...}` returned `200` and inserted a phantom invoice into the live table. Combined with there being no authentication, anyone can inject arbitrary invoice records. | `ApproveRejectHandler.java:99-109` |
| 2 | **High** | **`GET /invoices/approve` has the same flaw** — the token path also created a row for a synthetic invoice ID. | Confirmed live; row deleted |
| 3 | **Medium** | **Review decisions block on email delivery.** p95 goes ~480 ms → **~8 s** at 5 VUs in both runs because the handler awaits the confirmation email before responding. A slow or rate-limited email provider directly harms the reviewer's UI. | Section 4 |
| 4 | **Medium** | **Cold starts cost 20–30% of total capacity.** A 4–6 s JVM boot occupies a concurrency slot from a quota of only 10. Run B sustained 70–80% of Run A's throughput purely because more containers were booting. The 5-minute pinger keeps one container warm, not ten, so it cannot prevent this. | Section 6 |
| 5 | **Medium** | **The JMeter suite's main assertion is wrong.** `GET /invoices` returns an object `{ totalCount, items: [...] }`, not a JSON array. TG1 asserts "response body is a JSON array", so the suite reports failure even when the API is perfectly healthy. | Live response body |
| 6 | **Low** | **The JMeter suite cannot run and its data is stale.** JMeter is not installed; `data/invoice_ids.csv` contains 20 IDs that all still carry the `# ` prefix removed by today's backfill, so every lookup 404s. `README.md` also documents `POST /invoices/decision` and `GET /invoices/{id}`, neither of which exists. | `load-tests/` |

**Recommended fixes**

1. Add `conditionExpression("attribute_not_exists(invoiceId)")` to the review update, and
   return `404` when the condition fails. Same for the token path.
2. Return the response as soon as the DynamoDB write lands; send the confirmation email
   asynchronously (with error logging), rather than making the reviewer wait up to 15 s.
3. Make the warm-up pinger invoke its four targets **in parallel** rather than
   sequentially, so all four are warm at the same moment instead of drifting seconds apart.
4. Raise the account Lambda concurrency quota (an AWS support action) if sustained >100 rps
   is ever needed, or front the list endpoint with DynamoDB DAX / a cached count.
5. Fix the JMeter assertions, refresh the ID pool from live IDs, and install JMeter — or
   standardise on the harness in `load-tests/harness/`.

---

## 8. Slide-ready summary

> - **219,856 requests** across all 5 API endpoints in two independent runs;
>   **zero application errors**.
> - **The API saturates at a hard plateau of 68–104 successful req/s per function** — set by
>   the AWS regional Lambda concurrency quota of 10, not by our code. From 10 VUs to 100 VUs
>   the success count does not rise at all; it only converts into `503`s.
> - **Cold starts cost 20–30% of available capacity**, because a booting container holds a
>   concurrency slot for 4–6 seconds. Running the suite twice is what exposed this.
> - **0% error rate at realistic load** (1–8 concurrent reviewers) in both runs; warm p95 of
>   **67–85 ms** for a single lookup.
> - **Review decisions are the worst real-world path**: mean ~1.1 s but **p95 ~8 s** at only
>   5 concurrent reviewers, because confirmation email sits on the critical path.
> - **14 days of production CloudWatch confirms 0 errors** across ~7,840 organic
>   invocations.
> - **Load testing found 2 high-severity data-integrity bugs** — the review API can create
>   phantom invoices — plus a cold-start capacity bug and a latency defect.
> - **The system is correctly sized for its use case** (a handful of concurrent reviewers,
>   ~560 invocations/day). The honest next step is fixing the defects, not buying capacity.

---

## 9. Reproducing this

```powershell
# From the repository root. Requires only Windows PowerShell — no JMeter, no Python.
.\load-tests\harness\run-loadtest.ps1     # read paths, ~8 min
.\load-tests\harness\run-writetest.ps1   # bounded write path, ~20 s
```

Results are written as Markdown to `load-tests/harness/results-read.md` and
`results-write.md`, so a run can be read directly on GitHub or in any Markdown
viewer without a JSON tool. Phantom rows created by the token and write scenarios
are removed automatically by `cleanup-phantoms.ps1`, which verifies the delete and
re-scans to confirm.

The harness is: `LoadHarness.cs` (threaded `HttpClient`, records per-request
latency, computes percentiles, and renders the Markdown report), the two runners,
the cleanup script, and `results-to-markdown.ps1` (one-off migration that converted
the archived JSON captures to the Markdown format). Edit the `foreach ($t in @(...))`
blocks to change VU counts, or `$dur` to change duration.

> **Expect different numbers on your machine.** The ceiling moves between 68 and 104 req/s
> depending on how many containers are warm when you start. That variance is real and is
> discussed in [section 6](#6-why-the-two-runs-differ-cold-start-is-the-whole-story).


To run the official JMeter suite instead, install JMeter 5.6+ and fix the defects in
section 7 first.

---

## 10. Caveats

- **Two runs, not one.** Both are reported. Run-to-run variance is driven by container
  warmth and is analysed in [section 6](#6-why-the-two-runs-differ-cold-start-is-the-whole-story)
  rather than averaged away. Any single figure quoted from this report should be given as
  a range.
- Closed-loop fixed-concurrency, not open-loop arrival rate. It models "N users clicking
  continuously", which is the right model for a human review tool.
- Client-side network and TLS overhead is included; expect ~50–60 ms of the reported
  latency to be the Windows load generator, not the Lambda. The CloudWatch table in
  section 5 is server-side only.
- Percentiles mix successful and rejected requests except in the rows where error % is 0%.
- The write path was tested at low volume only, by design.
- Load was generated from a single Windows client. At 100 VUs the client itself may be
  part of the bottleneck, so the highest `req/s` figures in the saturated rows should be
  read as "requests offered", not "requests served".
- The 437-row table is small. The list endpoint does a full scan with GSI counts, so its
  latency is roughly linear in table size — at 10,000 invoices expect materially slower
  list responses, and this becomes the first thing to optimise.

---

**Related:** [`FRONTEND.md`](FRONTEND.md) (the retry/concurrency mitigations this test
exercised) · [`WHY_AND_HOW.md`](WHY_AND_HOW.md) (problem/solution narrative) ·
[`../load-tests/README.md`](../load-tests/README.md) (JMeter suite)
