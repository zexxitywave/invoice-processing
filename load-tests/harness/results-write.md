# Load test - write path

| | |
|---|---|
| Target | `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com` |
| Generated | archived capture (converted to Markdown 2026-09-26) |
| Scenarios | 2 (up to 5 concurrent threads) |
| Total requests | 63 |
| Overall error rate | 0.00% (0 failed) |

> Write path: every request writes a phantom row to DynamoDB and sends a real confirmation email. Thread counts are deliberately capped at 5. Rows are removed by `cleanup-phantoms.ps1` afterwards.

## Summary

All latencies in milliseconds. `req/s` is requests per second sustained across the scenario window.

| # | Scenario | Threads | Requests | req/s | avg | min | p50 | p90 | p95 | p99 | max | Errors |
|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 1 | POST /invoices/review t=1 | 1 | 16 | 1.60 | 644.3 | 451.6 | 473.1 | 536.8 | 3082.2 | 3082.2 | 3082.2 | 0 (0.00%)
| 2 | POST /invoices/review t=5 | 5 | 47 | 4.70 | 1074.7 | 290.5 | 440.9 | 543.2 | 7969.1 | 8403.3 | 8403.3 | 0 (0.00%)

## Detail

### 1. POST /invoices/review t=1

- **Load** 1 thread(s) for 10s, 16 requests at 1.60 req/s
- **Latency** min 451.6 / p50 473.1 / p90 536.8 / p95 3082.2 / p99 3082.2 / max 3082.2 / avg 644.3
- **Status codes** `200` x16
- **Errors** 0 http, 0 transport (0.00%)

### 2. POST /invoices/review t=5

- **Load** 5 thread(s) for 10s, 47 requests at 4.70 req/s
- **Latency** min 290.5 / p50 440.9 / p90 543.2 / p95 7969.1 / p99 8403.3 / max 8403.3 / avg 1074.7
- **Status codes** `200` x47
- **Errors** 0 http, 0 transport (0.00%)
