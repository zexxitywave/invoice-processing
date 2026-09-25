# InvoiceAI Pro — Frontend Guide

React single-page application for the **Invoice Processing System**: the reviewer-facing
console where invoices that AWS Textract + Amazon Bedrock could not auto-approve are
queued, inspected, approved or rejected, and audited.

This document is written for teammates who already know the Java/Lambda backend. It
covers *why the product exists*, *what made it hard*, and *how the frontend is wired*, so
you can change it without breaking the API contract.

---

## Table of Contents

- [1. Why this project exists](#1-why-this-project-exists)
- [2. The problem it solves](#2-the-problem-it-solves)
- [3. Challenges we faced](#3-challenges-we-faced)
- [4. Tech stack](#4-tech-stack)
- [5. Repository layout](#5-repository-layout)
- [6. Routing and page guards](#6-routing-and-page-guards)
- [7. The API layer (`services/`)](#7-the-api-layer-services)
- [8. State management (`hooks/`)](#8-state-management-hooks)
- [9. Component inventory](#9-component-inventory)
- [10. The four user journeys](#10-the-four-user-journeys)
- [11. Data contract with the backend](#11-data-contract-with-the-backend)
- [12. Styling](#12-styling)
- [13. Local development](#13-local-development)
- [14. Known bugs and gotchas](#14-known-bugs-and-gotchas)
- [15. Recommended hardening](#15-recommended-hardening)

---

## 1. Why this project exists

Finance and operations teams receive supplier invoices as **PDFs attached to email** or
handed over at a desk. The work that follows is identical every time and completely
manual:

1. Open the PDF, read off the vendor, invoice number, date and totals.
2. Type those values into an accounting or ERP system.
3. Check that the arithmetic on the invoice is internally consistent.
4. Check whether the same invoice has already been paid.
5. Chase an approver, then record the approval somewhere auditable.

The pain is not the reading — it is the volume, the inconsistency, and the audit trail.
A single invoice takes 3–10 minutes by hand, ~30% of invoices contain a genuine
discrepancy (wrong total, missing subtotal, tampered amount), and a re-submitted invoice
is often paid twice because nothing links the two PDFs.

We built a system that does steps 1–2 automatically, does step 3–4 programmatically, and
pushes only the ambiguous remainder to a human. The human never starts from scratch —
they open a pre-filled record, see *why* the AI was unsure, and click approve or reject.

## 2. The problem it solves

| Problem | How the system solves it |
|---|---|
| Manual data entry from PDFs | Textract `AnalyzeExpense` extracts vendor, invoice ID, date, subtotal, tax, shipping, total, and line items **with per-field confidence scores** |
| Silent arithmetic fraud | Deterministic tie-out: line items must sum to the total within $0.50, and `subtotal + shipping + tax - discount` must reconcile. Mismatches are caught even when the AI call fails |
| Uncertain extractions auto-approved | Auto-approval requires **all** of: TOTAL confidence >= 95%, zero missing fields, math reconciled, **and** the AI verdict is APPROVED. Anything else becomes `REVIEW_REQUIRED` |
| Double payment | `invoiceId` is the DynamoDB partition key, so a re-sent invoice is flagged `DUPLICATE` (risk `HIGH`) instead of being processed again |
| No audit trail | Every extraction, AI verdict, risk score, and human decision is persisted; the Audit page exposes filters and CSV export |
| Reviewers context-switching | Reviewer gets an email with one-click approve/reject links, **or** works the queue in this UI |
| Nobody notices a stalled queue | Daily 08:00 IST digest (new invoices, backlog, high-risk pending) plus a 72 h escalation job |

**The frontend's role in that table** is the last mile: it is the only surface where a
human decision is made, so it has to make the AI's reasoning legible. That is why the
invoice detail view shows the per-field confidence bars, the missing-field list, the
Bedrock comment explaining the verdict, and the reviewer identity/timestamp — a reviewer
should never have to download the PDF to find out why something was flagged.

**Live app:** <https://zexxity.online>

## 3. Challenges we faced

Backend challenges are documented in the root `README.md`; these are the ones that shaped
the frontend code.

**Authentication has no backend.** There is no user table, no API Gateway authorizer, no
token issuer. `services/authService.js` compares the typed credentials against a literal
in the bundle and sets `sessionStorage.ips_auth`. `ProtectedRoute` reads that flag. This
is a UI affordance, not a security boundary — anyone who knows the string can read every
invoice. It is the single biggest gap in the product (see [Hardening](#15-recommended-hardening)).

**The API is cold.** Lambdas are kept warm by a 5-minute pinger, but the first request
after an idle period still costs ~5.5 s versus ~200–350 ms warm. So `dashboardService`
retries twice on `[429, 500, 502, 503, 504]` with 300 ms x attempt backoff, and
`uploadService` retries five times with **full jitter** so a classroom full of reviewers
does not stampede the Lambda concurrency quota of 10 on the same tick.

**Uploads must not throttle the account.** `MAX_CONCURRENT_UPLOADS = 2` and
`MAX_FILE_SIZE = 10 MB` are enforced client-side; the real guard is `REQUEST_TIMEOUT`
(30 s) and a presigned PUT URL that expires in 5 minutes. Progress bars use
`XMLHttpRequest.upload.onprogress` because `fetch` has no upload-progress event.

**Totals must be global, not page-local.** A naive implementation counts statuses on the
current 20-row page and shows "2 approved" while 295 exist. The backend returns
`totalCount` / `totalApproved` / `totalReview` / `totalDuplicate` / `totalHumanApproved` /
`totalHumanRejected` / `totalPages` alongside the page, and `useDashboard` prefers those;
`useAudit` does the same for its summary strip.

**Pagination is token-stack, not page-index.** API Gateway + DynamoDB expose
`nextToken` only, so `prevTokens[]` / `currentToken` are tracked manually in
`useDashboard` and `useAudit` to make the Previous button work.

**The review queue is filtered client-side.** `GET /invoices` has no
"status + no decision" query, so `reviewService.getReviewQueue()` walks **every** page
at `pageSize=100` and then keeps rows where
`validationStatus === "REVIEW_REQUIRED"` and the decision is empty / `PENDING`. It also
has to filter the literal string `"undefined"`, which older rows were written with.

**Timestamps arrive in two different shapes.** `createdAt` is not consistently typed in
DynamoDB: older rows store it as a **Number** of epoch **seconds** (`1790278909`), newer
rows store an **ISO 8601 string** (`2026-09-22T19:36:45Z`). `reviewedAt` is always an
ISO string. `new Date(1790278909)` is January 1970, so two components render the wrong
date depending on which row they are handed (see
[Known bugs](#14-known-bugs-and-gotchas)).

**AI verification is unavailable in this account.** Amazon Bedrock model access is
support-gated and returns `Operation not allowed (Status Code: 400)`, so every invoice is
currently flagged with an AI-verification warning and most land in `REVIEW_REQUIRED`. The
deterministic engine still routes correctly, so the UI behaves sensibly — but the
"AI Approved" metric on the Dashboard currently means "passed deterministic checks",
and the copy is misleading.

## 4. Tech stack

| Concern | Choice | Version |
|---|---|---|
| UI library | React / React DOM | 19.2.7 |
| Routing | react-router-dom | 7.18.0 |
| Build tool | Vite | 8.1.0 |
| React plugin | @vitejs/plugin-react | 6.0.2 |
| Linter | oxlint | 1.69.0 |
| Class helper | clsx | 2.1.1 |
| Hosting | AWS Amplify | auto-deploy on push to `main` |

No TypeScript, no state library, no component framework, no test runner. Routing is
declarative, server state lives in custom hooks built on `useState` + `useEffect`, and
styling is plain CSS with CSS custom properties. Deliberate: zero runtime dependencies
beyond React and the router keeps the bundle small and the Amplify build trivial.

Current lint state: **0 errors, 0 warnings** across 55 files with 91 rules enabled.

## 5. Repository layout

```
invoice-reviewer-react/
├── index.html
├── package.json
├── vite.config.js          react() plugin only - no proxy, no env validation
├── .env                    VITE_API_BASE_URL (local, not committed)
├── public/
└── src/
    ├── main.jsx            StrictMode > BrowserRouter > App, imports styles/style.css
    ├── App.jsx             all routes + ProtectedRoute
    ├── pages/              one folder per route
    │   ├── Login/          Login.jsx + .css
    │   ├── Dashboard/      Dashboard.jsx + .css
    │   ├── Upload/         Upload.jsx + .css
    │   ├── Review/         Review.jsx + .css
    │   └── Audit/          Audit.jsx + .css
    ├── components/         one folder per component, colocated .css
    │   ├── Navbar/ MetricCard/ InvoiceTable/ StatusBadge/ ConfidenceBar/
    │   ├── ReviewTable/ InvoiceDetail/ DecisionForm/ AuditTable/ AuditSummary/
    │   ├── DropZone/ FileQueue/ ResultBanner/ ErrorBanner/ LoadingSpinner/
    │   └── ProtectedRoute/
    ├── services/           the only place that talks to the network
    │   ├── authService.js      login/logout/isAuthenticated (client-side only)
    │   ├── dashboardService.js GET /invoices + retry wrapper
    │   ├── auditService.js     GET /invoices with filters + CSV export
    │   ├── reviewService.js    review-queue fetch + POST /invoices/review
    │   └── uploadService.js    presigned URL + direct S3 PUT + validation
    ├── hooks/              one hook per page, owns state + effects
    │   ├── useDashboard.js useReview.js useAudit.js useUpload.js
    ├── utils/
    │   └── humanReview.js  human-decision display semantics
    └── styles/
        ├── style.css       resets, typography, shared layout, buttons, cards, tables
        └── variables.css   colour/spacing/shadow custom properties
```

**Convention:** pages are thin — they call one hook and render. All fetching, paging and
error state lives in the hook; all `fetch` calls live in a service. Components never call
`fetch` directly, and hooks never render. Each component owns its own CSS file next to
the JSX, with shared primitives in `styles/style.css`.

## 6. Routing and page guards

`src/App.jsx`:

| Route | Component | Guard |
|---|---|---|
| `/` | redirect to `/dashboard` | none |
| `/login` | `pages/Login/Login` | none (redirects to `/upload` if already authenticated) |
| `/dashboard` | `pages/Dashboard/Dashboard` | `ProtectedRoute` |
| `/upload` | `pages/Upload/Upload` | `ProtectedRoute` |
| `/review` | `pages/Review/Review` | `ProtectedRoute` |
| `/audit` | `pages/Audit/Audit` | `ProtectedRoute` |
| `*` | redirect to `/dashboard` | none |

`components/ProtectedRoute/ProtectedRoute.jsx` is a layout route: it renders an
`<Outlet />` when `isAuthenticated()` is true, otherwise `<Navigate to="/login" replace />`.

Every page sets `document.title` on mount and restores the base title on unmount.

## 7. The API layer (`services/`)

Base URL comes from the build-time env var in every service:

```js
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";
```

Empty string means same-origin, which is what you want behind an Amplify proxy or a Vite
dev proxy.

### `dashboardService.js`

- `getInvoices({ pageSize = 20, nextToken })` -> `GET /invoices?pageSize=20&nextToken=...`
- `RETRYABLE_STATUS_CODES = [429, 500, 502, 503, 504]`
- `request()` retries twice with 300 ms x attempt linear backoff and attaches
  `error.status` so the UI can distinguish "not found" from "server blew up".
- Non-JSON error bodies are wrapped as `{ message: text }`.

### `auditService.js`

- `getAuditInvoices({ pageSize = 20, nextToken, search })` ->
  `GET /invoices?pageSize&nextToken&search`
- `exportAuditCSV(invoices)` — builds a CSV client-side from the rows currently loaded
  and triggers a browser download via a `Blob` + object URL. Columns include
  `humanReviewValue`, so the export matches what the reviewer sees rather than the raw
  `reviewDecision` field.

### `reviewService.js`

- `getReviewQueue()` — paginates `GET /invoices` at `pageSize=100` through **all**
  `nextToken`s, then filters client-side to
  `validationStatus === "REVIEW_REQUIRED"` and a decision that is empty, `PENDING`, or the
  legacy string `"undefined"`. Returns the flat queue array.
- `getInvoices()` — raw list, no filtering.
- `submitReview({ invoiceId, decision, reviewer, reason })` ->
  `POST /invoices/review` with `decision` in `APPROVED | REJECTED`.

### `uploadService.js`

| Export | Value / behaviour |
|---|---|
| `MAX_FILE_SIZE` | `10 * 1024 * 1024` |
| `MAX_CONCURRENT_UPLOADS` | `2` (Lambda concurrency quota is 10) |
| `REQUEST_TIMEOUT` | `30000` ms via `AbortController` |
| `ALLOWED_FILE_TYPES` | `["application/pdf"]` |
| `getUploadURL(fileName)` | `POST /invoices/upload-url` -> presigned PUT (5 min expiry), retried |
| `uploadFileToS3(url, file, onProgress)` | `XMLHttpRequest` PUT with progress + `Content-Type: application/pdf`, retried |
| `validatePDF(file)` | extension-or-MIME check + 10 MB ceiling, throws a user-facing message |
| `isRetryableError(err)` | status in retry list, `AbortError`, or message matching `/network\|timed out\|timeout\|unavailable\|failed to fetch/i` |
| `withRetry(op, { retries = 5, baseDelay = 1000 })` | full jitter: `random(0, base * 2^(attempt-1))` |
| `formatFileSize(bytes)` | B / KB / MB display helper |

`isRetryableError` treating `AbortError` as retryable is deliberate — it means a slow
response (timeout) is retried rather than shown as a hard failure.

### `authService.js`

`login(username, password)` compares against literals in the bundle, and on success
writes `sessionStorage.ips_auth`. `logout()` removes it. `isAuthenticated()` reads it.
`components/Navbar/Navbar.jsx` also clears the key directly on Logout.

## 8. State management (`hooks/`)

There is no global store. Each page owns one hook; state does not cross page boundaries,
and navigation to a page always refetches. That is fine at this data volume and avoids
cache-invalidation bugs between the dashboard and the audit report.

### `useDashboard.js`

`PAGE_SIZE = 20`. Holds `invoices`, `loading`, `error`, `nextToken`, `prevTokens[]`,
`currentToken`, `pageNumber`. `calculateStats` prefers the backend counts
(`totalCount`, `totalApproved`, `totalReview`, `totalDuplicate`, `totalPages`) and falls
back to counting the current page if they are absent. Exposes
`refresh`, `goNext`, `goPrev`, `hasNext`, `hasPrev`, `stats`.

### `useReview.js`

Holds `queue`, `selectedInvoice`, `loading`, `submitting`, `error`. `refresh()` re-fetches
the queue. `openInvoice(invoice)` sets the selection, `closeInvoice()` clears it.
`submitDecision({ decision, reviewer, reason })` posts the decision, then refreshes the
queue and closes the detail panel so the item visibly leaves the queue.

### `useAudit.js`

`PAGE_SIZE = 20`. Same token-stack paging as the dashboard, plus
`filters = { status, risk, search }` and a memoised `filteredInvoices`. `globalStats` is
built from the backend totals. Changing `search` resets to the first page; changing
`status` or `risk` filters the loaded page client-side. `exportAuditCSV(filteredInvoices)`
is called directly by the page.

### `useUpload.js`

Holds the file queue as objects `{ id, file, status, progress, message }` where `status`
is `pending | uploading | completed | error`. `addFiles` validates each file and registers
it. `uploadAll` runs a small worker pool (`runWithConcurrency`) that, per file, calls
`getUploadURL` then `uploadFileToS3`, updating `progress` as XHR events arrive. `removeFile`
only works while a row is `pending`; `retryUpload` re-runs one errored file; `clearAll`
resets the queue. `queueStats` derives `pending / uploading / completed / failed` counts
used to enable the Upload All button and render the summary line.

### `utils/humanReview.js`

```js
humanReviewValue(invoice)
// -> reviewDecision when set
// -> "PENDING"       when validationStatus === "REVIEW_REQUIRED"
// -> "NOT_REQUIRED"  otherwise
```

Every table and the detail view render the human column through this helper, so the
dashboard, review queue, and audit report always agree on what "human review" means.

## 9. Component inventory

| Component | Responsibility |
|---|---|
| `Navbar` | Brand, `NavLink`s for the four pages, active-class handling, Logout (clears `ips_auth`) |
| `ProtectedRoute` | `isAuthenticated()` -> `<Outlet />` or redirect to `/login` |
| `MetricCard` | Title, value, icon, variant; subtitle text is looked up per variant |
| `InvoiceTable` | Dashboard table: ID, vendor, uploaded, total, risk, AI status, human review, confidence bar, Review action; previous/next pagination |
| `ReviewTable` | Queue table: ID, vendor, total, confidence, risk, comments tooltip, Open |
| `AuditTable` | Read-only history: ID, vendor, total, AI status, human review, risk, confidence |
| `InvoiceDetail` | Full record: every extracted field, `missingFields` list, `comments`, AI verdict + risk, human decision + `reviewedBy` + `reviewedAt`; renders `children` (the decision form) |
| `DecisionForm` | Reviewer email (required, `type="email"`, hint that the confirmation goes there) and an optional note; Approve / Reject buttons |
| `DropZone` | Drag-and-drop + file picker, `accept=".pdf,application/pdf"`, `multiple`, resets `input.value` so the same file can be re-picked |
| `FileQueue` | Per-file row: name, size, progress bar, status message, remove / retry buttons |
| `ResultBanner` | Success or error summary after an upload batch |
| `ErrorBanner` | Renders a hook's `error` string, hidden when empty |
| `LoadingSpinner` | Spinner + label, used by all four pages |
| `StatusBadge` | `type="ai" | "risk" | "human"`; maps `APPROVED / REVIEW_REQUIRED / DUPLICATE / LOW / MEDIUM / HIGH / REJECTED / PENDING / NOT_REQUIRED` to a coloured dot and a title-cased label |
| `ConfidenceBar` | Clamps 0–100, colour thresholds `>= 95` high, `>= 80` medium, else low; renders `toFixed(1)%` |
| `AuditSummary` | Seven metric cards: total, AI approved, review required, duplicates, human approved, human rejected, average confidence |

## 10. The four user journeys

### Sign in

`/login` -> `login(username, password)` -> on success `sessionStorage.ips_auth` is set and
the app navigates to `/upload`. Already-authenticated visitors are bounced straight to
`/upload`. **Credentials are compiled into the bundle** — see
[Hardening](#15-recommended-hardening).

### Upload

1. `DropZone` -> `addFiles` validates type and the 10 MB limit per file; rejects go into the
   row's `message`, valid ones become `pending` rows.
2. "Upload All" -> `runWithConcurrency` with `MAX_CONCURRENT_UPLOADS = 2`.
3. Per file: `POST /invoices/upload-url` (retried) -> `PUT` the bytes straight to S3
   (retried, with progress) -> row becomes `completed`.
4. A failed row offers **Retry**; `ResultBanner` summarises the batch.
5. The page explains the pipeline (S3 -> EventBridge -> Step Functions -> Textract ->
   Bedrock -> DynamoDB -> SES) and tells the reviewer to expect results on the Dashboard
   in ~30 seconds. The UI does **not** poll or subscribe — the reviewer refreshes the
   Dashboard themselves.

### Review

1. `useReview.refresh()` -> `getReviewQueue()` -> every `REVIEW_REQUIRED` row with no decision.
2. **Open** sets `selectedInvoice`; `InvoiceDetail` renders the full extracted record plus
   `DecisionForm`.
3. Approve/Reject requires a reviewer email, then posts
   `{ invoiceId, decision, reviewer, reason }`.
4. On success the hook refreshes the queue and closes the detail; the backend also emails
   a styled confirmation to that address. If the invoice already has a decision, the form
   is replaced by an "already approved/rejected" banner.

### Audit

1. `useAudit` loads page 1 at 20 rows and the global totals.
2. Search box (ID or vendor) is sent to the backend as `?search=`; status and risk selects
   filter the loaded page.
3. "Export CSV" serialises exactly the rows currently displayed.

### Dashboard

Metric cards (AI approved / review required / duplicates / total) over a 20-row recent
table with previous/next paging. The **Review** button navigates to `/review` with
`state: { invoice }`.

## 11. Data contract with the backend

Base URL: `https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`

| Endpoint | Used by | Request | Notes |
|---|---|---|---|
| `GET /invoices` | dashboard, review, audit | `pageSize`, `nextToken`, `search` | Returns `{ invoices, nextToken, totalCount, totalApproved, totalReview, totalDuplicate, totalHumanApproved, totalHumanRejected, totalPages }` |
| `GET /invoices?id=` | not used by the SPA | — | The UI reads single invoices out of the list payload it already has |
| `POST /invoices/upload-url` | upload | `{ fileName }` | Returns a 5-minute presigned PUT URL |
| `POST /invoices/review` | review | `{ invoiceId, decision, reviewer, reason }` | `decision` is `APPROVED` or `REJECTED` |

Invoice fields the UI reads: `invoiceId`, `vendorName`, `invoiceDate`, `subtotal`,
`shipping`, `discount`, `tax`, `total`, `createdAt`, `totalConfidence`, `avgConfidence`,
`missingFields`, `comments`, `validationStatus`, `risk`, `reviewDecision`, `reviewedBy`,
`reviewedAt`, `reviewNote`, `lineItemsSum`, `lineItemCount`.

All amounts arrive as **pre-formatted strings** (for example `"26596.55"`), not numbers —
they are rendered directly, so do not run arithmetic on them client-side.

All errors come back as JSON `{ error }` or `{ message }`; the shared `request()` wrapper
prefers `data.error`, falls back to `data.message`, then to `HTTP <status>`.

## 12. Styling

No CSS framework. `styles/variables.css` holds the design tokens (colours, spacing,
radii, shadows) as custom properties; `styles/style.css` is imported once in `main.jsx`
and holds the reset, typography, `.container`, `.card`, `.btn` variants, `.table-wrapper`
with horizontal scroll, and the status/confidence colour classes. Every component and page
imports its own colocated CSS file. `clsx` is used only where a class list is conditional
(`StatusBadge`, `DropZone`). Layout is CSS grid and flexbox; the design is a clean
dashboard look with emoji glyphs as status icons rather than an icon font.

## 13. Local development

```bash
cd invoice-reviewer-react
npm install
```

Create `.env` in this folder:

```
VITE_API_BASE_URL=https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com
```

Then:

```bash
npm run dev      # http://localhost:5173
npm run build    # production bundle in dist/
npm run preview  # serve the production bundle locally
npm run lint     # oxlint
```

`vite.config.js` contains only the React plugin — there is no dev proxy, so
`VITE_API_BASE_URL` must point at the real API. Add
`http://localhost:5173` to the API's CORS origins (it is already in `template.yaml`).
Production builds are served by Amplify, which runs `npm ci && npm run build` on every
push to `main`; the deployed bundle bakes in the Amplify-provided `VITE_API_BASE_URL`.

There is **no test script and no test runner**. Do not add one casually — if you need
coverage, install Vitest and add a `test` script in the same change.

## 14. Known bugs and gotchas

Ordered by how likely you are to hit them.

1. **Uploaded timestamps render as 1970 on older rows.** `createdAt` is typed as a Number
   of epoch seconds on older invoices and as an ISO string on newer ones, but
   `InvoiceTable.formatUploaded()` (`src/components/InvoiceTable/InvoiceTable.jsx:6`) and
   `InvoiceDetail` (`src/components/InvoiceDetail/InvoiceDetail.jsx:59`) both call
   `new Date(invoice.createdAt)` unconditionally — the numeric rows become Jan 1970. Fix
   client-side with `typeof createdAt === "number" ? createdAt * 1000 : createdAt`, or
   better, normalise the attribute server-side (see
   [Hardening](#15-recommended-hardening) item 7).
2. **The Dashboard "Review" button does not open the invoice.** It navigates to `/review`
   with `state: { invoice }` (`src/pages/Dashboard/Dashboard.jsx:70`), but `useReview` never
   reads `location.state` or a `?id=` query param, so the reviewer lands on the queue with
   nothing selected. Either consume the state in `useReview` or add a `selectedInvoiceId`
   URL param.
3. **Hardcoded credentials in the client bundle.** `admin` / `Zexxity@2024` in
   `src/services/authService.js` ships to every visitor. This must not ship to production.
4. **`ESCALATED` invoices stay out of the queue forever.** `getReviewQueue()` only excludes
   rows that already have a decision; the 72 h escalation job writes `reviewDecision =
   ESCALATED`, which the filter does not treat as resolved, and `InvoiceDetail` renders the
   "already decided" banner for it. Confirm the intended behaviour.
5. **Legacy `"undefined"` strings.** Old rows were written with the literal string
   `"undefined"` in `reviewDecision`; the review filter special-cases it. New code must
   never write that.
6. **Login lands on `/upload`, the router root lands on `/dashboard`.** Intentional but
   inconsistent; the post-login target is hardcoded in `Login.jsx` twice.
7. **The review queue refetches the entire table.** With a few hundred rows this is fine;
   past a few thousand it should become a server-side filtered query.
8. **Search resets paging; status/risk do not.** `search` is server-side (resets to page 1)
   while `status` and `risk` filter only the loaded page, so the two filters can disagree
   with the displayed totals.
9. **"AI Approved" is currently a misnomer.** Bedrock is unavailable in this account, so
   the deterministic engine is doing all the approving. The metric copy should be
   reworded until model access is restored.
10. **No request cancellation.** Hooks do not abort in-flight fetches on unmount, so a
    slow response can set state on an unmounted page and a fast navigation can race.

## 15. Recommended hardening

Ordered by value per unit of effort.

1. **Real authentication.** Amazon Cognito user pool + JWT, an API Gateway HTTP API JWT
   authorizer, and send `Authorization: Bearer` from `request()`. Delete
   `authService`'s credential comparison; keep `isAuthenticated()` as a thin wrapper over
   the token's presence. This closes the biggest hole in the product.
2. **Server-side filtering and paging.** Add `status`, `risk`, and `hasDecision` query
   parameters to `GET /invoices` so the review queue, audit filters, and totals stop
   being computed in the browser.
3. **Conditionals and idempotency.** `POST /invoices/review` and the email token endpoint
   should use a DynamoDB conditional expression on `reviewDecision` so a dashboard click
   racing an email link cannot both win, and a replayed 72 h token cannot flip a decision.
4. **Throttle the public upload endpoint.** `/invoices/upload-url` currently hands out
   presigned URLs to anyone; add per-IP throttling and validate content type and size
   server-side before presigning.
5. **Tighten CORS.** Replace the wildcard-ish origin list with the deployed Amplify domain
   only.
6. **Add observability.** `AbortController` cancellation in every hook, a global error
   boundary, and Sentry-style reporting for `error.status` spikes.
7. **Normalize timestamps at the source.** `createdAt` is currently written both as a
   Number of epoch seconds and as an ISO string. Return ISO 8601 for `createdAt` and
   `reviewedAt` from `GetInvoiceHandler` (and backfill the numeric rows) so the client
   never has to guess the type; that removes bug #1 permanently.
8. **Add a test runner.** At minimum, unit-test `humanReviewValue`, `withRetry`,
   `isRetryableError`, and `exportAuditCSV` — the pure functions where regressions are
   cheap to find and expensive to notice.

---

The backend architecture, DynamoDB schema, extraction/routing rules, and deployment
steps live in the repository root `README.md` and `docs/ARCHITECTURE.md`.
