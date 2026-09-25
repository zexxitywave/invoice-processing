# Load test - read path

| | |
|---|---|
| Target | `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com` |
| Generated | archived capture (converted to Markdown 2026-09-26) |
| Scenarios | 21 (up to 100 concurrent threads) |
| Total requests | 88432 |
| Overall error rate | 70.31% (62174 failed) |

> Read-path only: no invoice rows are created, except T4 which hits the approve endpoint with a synthetic token and writes one phantom row per request. Those rows are deleted by `cleanup-phantoms.ps1` immediately after T5.

## Summary

All latencies in milliseconds. `req/s` is requests per second sustained across the scenario window.

| # | Scenario | Threads | Requests | req/s | avg | min | p50 | p90 | p95 | p99 | max | Errors |
|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 1 | GET /invoices (list) t=1 | 1 | 142 | 7.10 | 142.8 | 71.5 | 84.8 | 253.8 | 636.6 | 846.7 | 1320.4 | 0 (0.00%)
| 2 | GET /invoices (list) t=5 | 5 | 308 | 14.67 | 330.9 | 69.3 | 164.8 | 638.1 | 853.9 | 5487.9 | 6388.0 | 0 (0.00%)
| 3 | GET /invoices (list) t=8 | 8 | 855 | 42.75 | 187.4 | 67.9 | 105.6 | 251.3 | 511.5 | 1432.3 | 5623.5 | 0 (0.00%)
| 4 | GET /invoices (list) t=10 | 10 | 1154 | 57.70 | 176.1 | 48.3 | 92.0 | 292.4 | 777.5 | 1359.8 | 5874.2 | 14 (1.21%)
| 5 | GET /invoices (list) t=20 | 20 | 4176 | 208.80 | 93.9 | 47.2 | 60.0 | 108.0 | 157.3 | 955.8 | 1888.8 | 2599 (62.24%)
| 6 | GET /invoices (list) t=40 | 40 | 8114 | 386.38 | 102.3 | 47.7 | 57.8 | 95.2 | 173.8 | 1349.3 | 2202.8 | 6683 (82.36%)
| 7 | GET /invoices?id= (lookup) t=1 | 1 | 236 | 11.80 | 85.3 | 56.9 | 59.7 | 72.9 | 179.0 | 779.2 | 926.8 | 0 (0.00%)
| 8 | GET /invoices?id= (lookup) t=8 | 8 | 2563 | 128.15 | 62.5 | 48.4 | 59.4 | 72.0 | 80.9 | 110.7 | 343.4 | 531 (20.72%)
| 9 | GET /invoices?id= (lookup) t=10 | 10 | 3155 | 157.75 | 63.2 | 48.5 | 60.5 | 75.9 | 85.1 | 108.3 | 277.5 | 1160 (36.77%)
| 10 | GET /invoices?id= (lookup) t=20 | 20 | 7096 | 354.80 | 56.4 | 48.3 | 54.5 | 62.8 | 65.8 | 75.5 | 234.7 | 5033 (70.93%)
| 11 | GET /invoices?id= (lookup) t=40 | 40 | 13783 | 689.15 | 58.1 | 47.9 | 54.5 | 65.1 | 72.2 | 128.4 | 260.7 | 11724 (85.06%)
| 12 | POST /invoices/upload-url t=1 | 1 | 182 | 9.10 | 109.8 | 100.0 | 107.0 | 116.6 | 119.8 | 144.6 | 244.7 | 0 (0.00%)
| 13 | POST /invoices/upload-url t=8 | 8 | 1430 | 71.50 | 112.2 | 96.1 | 104.8 | 112.2 | 115.8 | 132.2 | 1907.0 | 0 (0.00%)
| 14 | POST /invoices/upload-url t=10 | 10 | 1812 | 90.60 | 110.6 | 94.6 | 104.5 | 114.8 | 122.7 | 204.9 | 1748.3 | 4 (0.22%)
| 15 | POST /invoices/upload-url t=20 | 20 | 3793 | 189.65 | 105.4 | 90.7 | 102.8 | 115.3 | 122.2 | 144.9 | 532.8 | 1785 (47.06%)
| 16 | GET /invoices/approve (token) t=1 | 1 | 308 | 15.40 | 65.0 | 57.6 | 62.5 | 70.8 | 76.9 | 99.4 | 271.0 | 0 (0.00%)
| 17 | GET /invoices/approve (token) t=8 | 8 | 1056 | 52.80 | 151.7 | 48.6 | 67.9 | 165.3 | 399.7 | 1967.4 | 5647.9 | 62 (5.87%)
| 18 | GET /invoices/approve (token) t=10 | 10 | 2064 | 103.20 | 97.4 | 47.9 | 61.4 | 87.0 | 175.4 | 1251.0 | 5568.4 | 593 (28.73%)
| 19 | GET /invoices/approve (token) t=20 | 20 | 4154 | 207.70 | 96.7 | 47.5 | 57.9 | 75.4 | 161.2 | 1375.6 | 2350.3 | 2773 (66.75%)
| 20 | GET /invoices (list) t=60 | 60 | 12666 | 633.30 | 92.8 | 46.4 | 56.6 | 91.8 | 198.5 | 1351.4 | 1912.7 | 11196 (88.39%)
| 21 | GET /invoices (list) t=100 | 100 | 19385 | 923.10 | 106.2 | 46.5 | 60.8 | 101.2 | 199.3 | 1397.8 | 2051.7 | 18017 (92.94%)

## Detail

### 1. GET /invoices (list) t=1

- **Load** 1 thread(s) for 20s, 142 requests at 7.10 req/s
- **Latency** min 71.5 / p50 84.8 / p90 253.8 / p95 636.6 / p99 846.7 / max 1320.4 / avg 142.8
- **Status codes** `200` x142
- **Errors** 0 http, 0 transport (0.00%)

### 2. GET /invoices (list) t=5

- **Load** 5 thread(s) for 21s, 308 requests at 14.67 req/s
- **Latency** min 69.3 / p50 164.8 / p90 638.1 / p95 853.9 / p99 5487.9 / max 6388.0 / avg 330.9
- **Status codes** `200` x308
- **Errors** 0 http, 0 transport (0.00%)

### 3. GET /invoices (list) t=8

- **Load** 8 thread(s) for 20s, 855 requests at 42.75 req/s
- **Latency** min 67.9 / p50 105.6 / p90 251.3 / p95 511.5 / p99 1432.3 / max 5623.5 / avg 187.4
- **Status codes** `200` x855
- **Errors** 0 http, 0 transport (0.00%)

### 4. GET /invoices (list) t=10

- **Load** 10 thread(s) for 20s, 1154 requests at 57.70 req/s
- **Latency** min 48.3 / p50 92.0 / p90 292.4 / p95 777.5 / p99 1359.8 / max 5874.2 / avg 176.1
- **Status codes** `200` x1140, `503` x14
- **Errors** 14 http, 0 transport (1.21%)

### 5. GET /invoices (list) t=20

- **Load** 20 thread(s) for 20s, 4176 requests at 208.80 req/s
- **Latency** min 47.2 / p50 60.0 / p90 108.0 / p95 157.3 / p99 955.8 / max 1888.8 / avg 93.9
- **Status codes** `200` x1577, `503` x2599
- **Errors** 2599 http, 0 transport (62.24%)

### 6. GET /invoices (list) t=40

- **Load** 40 thread(s) for 21s, 8114 requests at 386.38 req/s
- **Latency** min 47.7 / p50 57.8 / p90 95.2 / p95 173.8 / p99 1349.3 / max 2202.8 / avg 102.3
- **Status codes** `200` x1431, `503` x6683
- **Errors** 6683 http, 0 transport (82.36%)

### 7. GET /invoices?id= (lookup) t=1

- **Load** 1 thread(s) for 20s, 236 requests at 11.80 req/s
- **Latency** min 56.9 / p50 59.7 / p90 72.9 / p95 179.0 / p99 779.2 / max 926.8 / avg 85.3
- **Status codes** `200` x236
- **Errors** 0 http, 0 transport (0.00%)

### 8. GET /invoices?id= (lookup) t=8

- **Load** 8 thread(s) for 20s, 2563 requests at 128.15 req/s
- **Latency** min 48.4 / p50 59.4 / p90 72.0 / p95 80.9 / p99 110.7 / max 343.4 / avg 62.5
- **Status codes** `200` x2032, `503` x531
- **Errors** 531 http, 0 transport (20.72%)

### 9. GET /invoices?id= (lookup) t=10

- **Load** 10 thread(s) for 20s, 3155 requests at 157.75 req/s
- **Latency** min 48.5 / p50 60.5 / p90 75.9 / p95 85.1 / p99 108.3 / max 277.5 / avg 63.2
- **Status codes** `200` x1995, `503` x1160
- **Errors** 1160 http, 0 transport (36.77%)

### 10. GET /invoices?id= (lookup) t=20

- **Load** 20 thread(s) for 20s, 7096 requests at 354.80 req/s
- **Latency** min 48.3 / p50 54.5 / p90 62.8 / p95 65.8 / p99 75.5 / max 234.7 / avg 56.4
- **Status codes** `200` x2063, `503` x5033
- **Errors** 5033 http, 0 transport (70.93%)

### 11. GET /invoices?id= (lookup) t=40

- **Load** 40 thread(s) for 20s, 13783 requests at 689.15 req/s
- **Latency** min 47.9 / p50 54.5 / p90 65.1 / p95 72.2 / p99 128.4 / max 260.7 / avg 58.1
- **Status codes** `200` x2059, `503` x11724
- **Errors** 11724 http, 0 transport (85.06%)

### 12. POST /invoices/upload-url t=1

- **Load** 1 thread(s) for 20s, 182 requests at 9.10 req/s
- **Latency** min 100.0 / p50 107.0 / p90 116.6 / p95 119.8 / p99 144.6 / max 244.7 / avg 109.8
- **Status codes** `200` x182
- **Errors** 0 http, 0 transport (0.00%)

### 13. POST /invoices/upload-url t=8

- **Load** 8 thread(s) for 20s, 1430 requests at 71.50 req/s
- **Latency** min 96.1 / p50 104.8 / p90 112.2 / p95 115.8 / p99 132.2 / max 1907.0 / avg 112.2
- **Status codes** `200` x1430
- **Errors** 0 http, 0 transport (0.00%)

### 14. POST /invoices/upload-url t=10

- **Load** 10 thread(s) for 20s, 1812 requests at 90.60 req/s
- **Latency** min 94.6 / p50 104.5 / p90 114.8 / p95 122.7 / p99 204.9 / max 1748.3 / avg 110.6
- **Status codes** `200` x1808, `503` x4
- **Errors** 4 http, 0 transport (0.22%)

### 15. POST /invoices/upload-url t=20

- **Load** 20 thread(s) for 20s, 3793 requests at 189.65 req/s
- **Latency** min 90.7 / p50 102.8 / p90 115.3 / p95 122.2 / p99 144.9 / max 532.8 / avg 105.4
- **Status codes** `200` x2008, `503` x1785
- **Errors** 1785 http, 0 transport (47.06%)

### 16. GET /invoices/approve (token) t=1

- **Load** 1 thread(s) for 20s, 308 requests at 15.40 req/s
- **Latency** min 57.6 / p50 62.5 / p90 70.8 / p95 76.9 / p99 99.4 / max 271.0 / avg 65.0
- **Status codes** `200` x308
- **Errors** 0 http, 0 transport (0.00%)

### 17. GET /invoices/approve (token) t=8

- **Load** 8 thread(s) for 20s, 1056 requests at 52.80 req/s
- **Latency** min 48.6 / p50 67.9 / p90 165.3 / p95 399.7 / p99 1967.4 / max 5647.9 / avg 151.7
- **Status codes** `200` x994, `503` x62
- **Errors** 62 http, 0 transport (5.87%)

### 18. GET /invoices/approve (token) t=10

- **Load** 10 thread(s) for 20s, 2064 requests at 103.20 req/s
- **Latency** min 47.9 / p50 61.4 / p90 87.0 / p95 175.4 / p99 1251.0 / max 5568.4 / avg 97.4
- **Status codes** `200` x1471, `503` x593
- **Errors** 593 http, 0 transport (28.73%)

### 19. GET /invoices/approve (token) t=20

- **Load** 20 thread(s) for 20s, 4154 requests at 207.70 req/s
- **Latency** min 47.5 / p50 57.9 / p90 75.4 / p95 161.2 / p99 1375.6 / max 2350.3 / avg 96.7
- **Status codes** `200` x1381, `503` x2773
- **Errors** 2773 http, 0 transport (66.75%)

### 20. GET /invoices (list) t=60

- **Load** 60 thread(s) for 20s, 12666 requests at 633.30 req/s
- **Latency** min 46.4 / p50 56.6 / p90 91.8 / p95 198.5 / p99 1351.4 / max 1912.7 / avg 92.8
- **Status codes** `200` x1470, `503` x11196
- **Errors** 11196 http, 0 transport (88.39%)

### 21. GET /invoices (list) t=100

- **Load** 100 thread(s) for 21s, 19385 requests at 923.10 req/s
- **Latency** min 46.5 / p50 60.8 / p90 101.2 / p95 199.3 / p99 1397.8 / max 2051.7 / avg 106.2
- **Status codes** `200` x1368, `503` x18017
- **Errors** 18017 http, 0 transport (92.94%)
