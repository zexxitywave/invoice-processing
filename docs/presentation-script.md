# Presentation Script — Invoice Processing System

Spoken narration for the demo deck in [`presentation.md`](presentation.md).

**How to use this document**

- Text in **quotes** is what you say out loud.
- `[SHOW]` / `[DO]` lines are stage directions for the screen and your hands.
- Every section has a **Time** budget. Total full run ≈ 24 minutes; a 10-minute
  cut is marked **[10-MIN CUT]**.
- Read the **Say this, not that** table before you present. Several claims in
  the older deck are inaccurate and will be challenged by a technical audience.

---

## Timing plan

| # | Section | Time | 10-min cut |
|---|---|---|---|
| 1 | Opening | 0:45 | keep |
| 2 | The problem | 1:00 | keep |
| 3 | Architecture on one slide | 1:30 | keep |
| 4 | Path A — email ingestion (live demo) | 3:30 | keep |
| 5 | Path B — browser upload (live demo) | 2:00 | keep |
| 6 | The workflow engine (Step Functions) | 1:30 | keep |
| 7 | Extraction with Textract | 1:30 | keep |
| 8 | AI validation with Bedrock | 1:15 | keep |
| 9 | Deterministic validation — the four gates | 2:00 | keep |
| 10 | Persistence and duplicate detection | 1:00 | keep |
| 11 | Human review — two channels (live demo) | 3:00 | keep |
| 12 | Audit trail and the data model | 1:00 | compress |
| 13 | Automation — scheduled jobs | 0:45 | drop |
| 14 | Performance — cold start | 0:45 | drop |
| 15 | Performance — load test results | 1:15 | compress |
| 16 | Reliability and known failure modes | 1:30 | compress |
| 17 | Security — what is and isn't there | 1:00 | compress |
| 18 | Cost | 0:30 | drop |
| 19 | CI/CD | 0:30 | drop |
| 20 | Roadmap | 1:00 | keep |
| 21 | Close and Q&A | 0:45 | keep |

---

## 1. Opening (0:45)

`[SHOW]` Title slide.

> "Good morning. This is an invoice automation platform I built and deployed.
> It removes manual data entry from accounts payable.
>
> The version I'm showing you is live — the URL is on the screen. It's running
> in AWS Mumbai on serverless infrastructure, and the app you're about to see
> is being served from a CDN.
>
> In one sentence: **a PDF invoice goes in by email or by browser upload, and a
> validated, risk-scored, audit-trailed record comes out — automatically, when
> the system is confident, and via a human reviewer when it isn't.**"

---

## 2. The problem (1:00)

`[SHOW]` Problem table.

> "Before I show you the solution, the problem — because it explains every
> design decision.
>
> First: invoices arrive as PDFs, by email, from many different vendors. Every
> vendor has a different layout. So plain OCR is not enough — you need to
> understand structure, not just read characters.
>
> Second: there is zero tolerance for error. A wrong total that gets auto-approved
> becomes a payment error, and a duplicate that gets approved becomes a real
> financial loss.
>
> Third: and this is the one people forget — somebody has to be able to answer
> six months later: *who approved this, and why?*
>
> So the goal was: automate intake end to end, but never at the cost of being
> wrong silently, and never at the cost of losing the audit trail."

---

## 3. Architecture on one slide (1:30)

`[SHOW]` Three-lane diagram. Walk it left to right.

> "Three lanes. **Ingestion**, **processing**, **review**.
>
> **Ingestion** — two doors in: an email address, and an upload button. Both
> converge on one place: an S3 bucket. That's deliberate. Once the PDF is in S3,
> everything downstream is identical regardless of how it arrived, so there's
> exactly one pipeline to reason about.
>
> **Processing** — an S3 object-created event triggers AWS EventBridge, which
> starts a Step Functions workflow. That workflow calls a Lambda that does AI
> extraction, AI validation, deterministic validation, and writes the result to
> DynamoDB.
>
> **Review** — clean invoices are approved automatically. Anything uncertain
> goes to a human, either in the web dashboard or by a one-click link in an
> email.
>
> One design principle worth naming: **we never auto-approve to save effort. We
> auto-approve only when we can prove the invoice is right.** Everything else
> is a review item. That's a deliberate trade of automation rate for accuracy."

---

## 4. Path A — email ingestion (live demo, 3:30)

`[DO]` Open your mail client. Address is **`invoices@zexxity.online`**.

> "First way in: email. This is a real, dedicated address on a domain I own. A
> vendor's accounts-payable system, or a person, sends a PDF to it. No login,
> no app, no training."

`[DO]` Send the PDF. Wait for it to land.

> "I'm sending an invoice PDF to that address now. Nothing is running on my
> laptop — no server, no cron. Let me show you what happened on the other
> side."

`[SHOW]` CloudWatch Logs → `/aws/lambda/ses-inbound-handler`.

> "AWS SES received the message. An SES **receipt rule** matched the recipient
> address and delivered the raw email into an S3 bucket, and that triggered the
> inbound Lambda.
>
> This Lambda does the MIME parsing — it walks the email structure, finds the
> PDF attachment, and copies it into the main invoice bucket. You can see it in
> the log: the attachment name, the size, and the destination key it wrote to."

`[SHOW]` S3 → `invoice-processing-buckets-m3` → `invoices/`.

> "There's the PDF, in the invoice bucket. This is the single point where both
> ingestion paths converge — the browser upload writes to this same bucket."

> "**One thing I want to flag as a known limitation:** this handler currently
> discards emails that arrive without a readable PDF attachment, and it does so
> quietly — it returns success, so nothing retries and nobody is notified. That's
> a real gap, and it's on my hardening list. I show it here because you should
> know the shape of the failure, not just the happy path."

`[DO]` Pause. Let them register that you're being candid.

---

## 5. Path B — browser upload (live demo, 2:00)

`[SHOW]` `https://zexxity.online/upload`.

> "Second way in: the upload page in the app. Drag and drop, or browse."

`[DO]` Drop a PDF in. Show the progress bar.

> "Here's what's happening under the hood, and the reason I designed it this way.
>
> The browser does **not** upload the file to my server. If it did, every byte
> would pass through a Lambda — that's slow, and it costs money per gigabyte,
> and Lambda request payloads are capped.
>
> Instead: the browser asks my API for a **presigned S3 PUT URL**. That's a
> temporary, signed permission slip — valid for five minutes, scoped to one
> object key. The browser then uploads **directly to S3**, bypassing my
> infrastructure entirely.
>
> So the file goes vendor → S3 on AWS's network. My Lambda only ever handles a
> few kilobytes of JSON. That's why uploads are fast and why the architecture
> scales without me touching it."

> "When the browser finishes, S3 raises the same object-created event as the
> email path — and from that moment the two paths are literally indistinguishable
> to the system."

---

## 6. The workflow engine — Step Functions (1:30)

`[SHOW]` State machine diagram.

> "This is the part I'd like to spend a minute on, because it's where most
> designs like this go wrong.
>
> A common pattern is: S3 event triggers a Lambda, the Lambda calls another
> Lambda, and at some point it needs to **wait for a human**. Waiting is the
> problem. A human might take ten minutes or three days. You cannot hold a
> Lambda open for three days, and you cannot hold a database transaction.
>
> So the orchestration is not in code — it's in **AWS Step Functions**. The
> workflow is a state machine, and it has four meaningful states:
>
> One — **ProcessInvoice**: extract, validate, persist.
> Two — **CheckStatus**: a decision point. Is it approved, does it need review,
> or is it something else like a duplicate?
> Three — **RequestApproval**: and this is the interesting one. The workflow
> hands the system a *task token* and then suspends, for up to 72 hours, costing
> nothing while it waits.
> Four — **DecisionOutcome**: when the human clicks, the token is used to
> resume exactly where it left off.
>
> The execution state lives in Step Functions, not in memory. That's what makes
> a three-day human wait possible on infrastructure that bills per millisecond."

> "The other thing worth noting: the timeout is a **real** state. If no human
> decides within 72 hours, the workflow doesn't hang and it doesn't quietly
> succeed — it fails explicitly, with the invoice ID in the error. I hardened
> that recently; previously those two paths reported success, which meant a
> timeout looked identical to an approval in the metrics."

---

## 7. Extraction with Textract (1:30)

`[SHOW]` Extraction pipeline slide.

> "Now the actual intelligence. Step one is **AWS Textract**, specifically the
> `AnalyzeExpense` API.
>
> Textract isn't OCR that gives you a text blob. It's a document-understanding
> service trained on invoices. It understands that a total is a total, that a
> subtotal is a subtotal, and it returns a **confidence score per field**.
>
> That distinction matters enormously. A generic OCR pipeline gives you
> '47,293.16' and you have no idea how much to trust it. Textract gives you
> the value *and* how sure it is, field by field."

> "So for every invoice we now hold a set of numbers alongside every value:
> the confidence on the total, the average confidence across all fields. And
> here's the important part — that confidence is not decoration. It is the
> input to the routing decision you'll see in a few slides."

`[DO]` If you have a live invoice, show the confidence values in the dashboard.

> "Those two numbers — total confidence and average confidence — are computed
> here, and they're what the routing logic actually uses."

---

## 8. AI validation with Bedrock (1:15)

`[SHOW]` Bedrock step.

> "Step two is **Amazon Bedrock**, running Nova Lite.
>
> Here's the distinction I care about: Textract reads, Bedrock *judges*. I give
> it the extracted fields and it returns a validation opinion, a **risk score**
> of low, medium or high, a human-readable explanation, and any fields it
> believes are missing.
>
> Why do I need a model at all when I also have deterministic rules? Because
> rules can't tell you a subtotal is inconsistent with the line items in a way
> that's meaningful, or that a date is implausible, or that a vendor name
> doesn't match who it's billed to. A language model reasons over the whole
> document.
>
> And critically — when Bedrock is unavailable, it degrades. It doesn't crash
> the pipeline. It returns a conservative 'requires review' and the invoice goes
> to a human. The AI is an input, never a single point of failure."

---

## 9. Deterministic validation — the four gates (2:00)

`[SHOW]` Routing logic slide. This is the most important slide in the deck.

> "Now the part I'd defend in a code review. Textract and Bedrock both produce
> probabilistic output. If I auto-approved on their opinion alone, I'd be
> guessing with someone's money.
>
> So there are **four gates, and all four must pass** before anything is
> approved automatically."

`[DO]` Count them on your fingers or point at the slide.

> "**Gate one — confidence.** The confidence on the total field must be at least
> 95 percent. Not the average — the total specifically. I check the total
> because that's the number that becomes a payment.
>
> **Gate two — completeness.** No critical field may be missing. Not just the
> total — the invoice ID, the vendor name, the date, the subtotal. A complete
> invoice is auditable; an incomplete one isn't.
>
> **Gate three — arithmetic.** The line items must add up to the total, within
> half a unit. This is pure arithmetic, and it's the single most effective
> fraud and OCR-error check I have. If the line items don't reconcile, something
> is wrong no matter how confident the OCR was.
>
> **Gate four — AI agreement.** Bedrock must independently say approve.
>
> All four pass → **auto-approved**, zero human involvement.
> Any one fails → **review required**, with the specific reason attached."

> "I want to be explicit about why it's AND, not OR. A high-confidence total
> with line items that don't add up is exactly the shape of a manipulated
> invoice. Any single gate can be fooled. Requiring all four means an attacker
> has to defeat four independent checks simultaneously."

> "**Duplicate detection** runs as well. If that invoice ID has been seen
> before, it's flagged `DUPLICATE` with high risk and never auto-approved —
> regardless of how clean it looks."

---

## 10. Persistence and duplicate detection (1:00)

`[SHOW]` DynamoDB schema slide.

> "The record goes to **DynamoDB**, table `invoices`, partitioned on invoice ID,
> billed on demand — so there's no idle capacity cost when nobody's using it.
>
> Two global secondary indexes let the dashboard compute its metrics without a
> scan. That's why the dashboard stays fast as the table grows: it's not
> counting the table, it's querying an index.
>
> Every record keeps the confidence values, the risk score, the model's
> explanation, the missing fields, and — once a human touches it — who decided,
> when, and any note. Machine and human decisions are stored the same way, which
> means the audit trail doesn't care who or what made the call."

---

## 11. Human-in-the-loop — two channels (live demo, 3:00)

`[SHOW]` Review page.

> "This is the review queue. Every invoice that failed a gate is here, with the
> reason it failed — low confidence, missing subtotal, line items don't add up."

> "The reviewer has **two ways to decide**, and I built both on purpose."

**Channel one — the dashboard.**

> "First, right here: open the invoice, see the extracted fields next to the
> actual PDF, hit approve or reject, add a note."

`[DO]` Approve one. Show the dashboard metrics update.

> "Done. And notice the dashboard totals updated — because the index query
> reflects the write immediately."

**Channel two — email, no login required.**

> "Second channel, and this is the one that changes behaviour in practice: an
> email with a one-click approve link.
>
> The point is that the reviewer shouldn't have to open an app, remember a
> password, and navigate a queue — especially if they're at a phone at 6pm on
> a Friday. They get an email, they tap approve, they're done.
>
> That link carries a token that embeds the invoice ID, the decision, and an
> expiry, and it's cryptographically signed. The system can verify that link was
> issued by this system and hasn't been tampered with, and it expires in 72
> hours.
>
> Clicking it doesn't just write to the database — it also hands the task token
> back to Step Functions, so the waiting workflow resumes and closes out
> properly. The invoice leaves the queue, the execution completes, and the
> reviewer gets an HTML confirmation."

`[DO]` Open the email. Click approve. Show the HTML confirmation.

> "One click. No login. And the workflow state machine now knows this invoice is
> resolved."

> "If nobody clicks within 72 hours, the escalation job picks it up and re-sends
> fresh links — and if it still goes untouched, the workflow fails explicitly
> rather than disappearing."

---

## 12. Audit trail and data model (1:00) **[10-MIN CUT: compress]**

`[SHOW]` Audit page.

> "Every invoice, every decision, machine and human, in one place — filterable,
> and exportable to CSV for finance.
>
> This is the compliance answer: for any invoice, I can show you the extracted
> values, the confidence on each, what the model thought, whether it was
> auto-approved or reviewed, and if reviewed, who and when."

---

## 13. Automation — scheduled jobs (0:45)

`[SHOW]` Scheduled jobs table.

> "Three background jobs on EventBridge schedules.
>
> A **daily digest** at 8am summarising new invoices, backlog size, and
> high-risk items still pending.
>
> A **72-hour escalation** job that finds invoices nobody has decided on and
> re-sends fresh approval links.
>
> A **weekly cleanup** that deletes the raw PDFs after seven days — the images
> are the bulky part, and the structured record in DynamoDB is the audit trail,
> so that's what we keep.
>
> Plus a warm-up job every five minutes that keeps the interactive functions
> hot — I'll come back to why that matters."

---

## 14. Performance — cold start (0:45)

`[SHOW]` Latency table.

> "A Lambda cold start is a real cost on a user-facing API, so I tuned for it.
>
> The four interactive functions are published behind a `live` alias, and a
> scheduled function pings them every five minutes so their execution
> environments stay warm.
>
> Warm requests land around **200 to 350 milliseconds**. Genuinely cold, after
> the system has been idle, about **five and a half seconds**. Same code, same
> region — the difference is entirely whether the JVM is already running.
>
> I also evaluated **SnapStart**, which snapshots a started JVM. Restore was
> about five times faster than a cold init. I didn't enable it, because it
> can't be combined with provisioned concurrency and the account's concurrency
> quota here is ten — the warm-up approach achieves the same result without
> touching quota."

---

## 15. Performance — load test results (1:15) **[10-MIN CUT: compress]**

`[SHOW]` Load test results.

> "I wrote a load harness and ran it twice — around **220,000 requests** across
> both runs, against the live API.
>
> Three numbers matter.
>
> **Zero application errors.** Not one 500, not one timeout, not one crash, in
> 220,000 requests. Every failure was a 503 from AWS telling us we'd exceeded
> the account's concurrency quota.
>
> **The ceiling is a hard plateau, between roughly 68 and 104 requests per
> second**, and it doesn't move regardless of how hard you push. That's not a
> code limit — it's the regional Lambda concurrency quota of ten. Push harder and
> you don't get slower, you just get more 503s.
>
> **At realistic load — one to eight concurrent reviewers — the error rate is
> zero.** Which is the honest conclusion: the system is correctly sized for its
> actual use case. It's saturated only by synthetic load far beyond any
> plausible number of reviewers.
>
> I'd rather show you that ceiling than hide it. It's a capacity limit I can
> raise with a quota request — not a design flaw."

---

## 16. Reliability and known failure modes (1:30) **[10-MIN CUT: compress]**

`[SHOW]` Failure modes table.

> "I've documented thirteen failure modes deliberately, because a system you
> can't reason about is a system you can't operate.
>
> Let me pick the ones that matter most.
>
> **Poison messages** — a corrupt or password-protected PDF. Today the workflow
> retries and then fails, and that failure is visible in the execution history,
> but there's no dead-letter queue, so nobody is *pushed*. I'm adding SQS with a
> DLQ and an alarm on depth. That's the first item on my roadmap.
>
> **Large multi-page invoices** beyond Textract's synchronous limits are never
> processed. The fix is Textract's asynchronous API. Also on the list.
>
> **Race conditions** — if a reviewer clicks the email link at the same moment
> someone uses the dashboard, both writes are accepted and the last one wins.
> The fix is a conditional write in DynamoDB so the second write is rejected
> rather than silently overwriting. That's a small change and it's next.
>
> **Approval tokens** — previously the token was just Base64-encoded JSON, which
> anyone could edit to approve an arbitrary invoice. I've replaced it with an
> HMAC-signed token, so a modified link is now rejected.
>
> **Regional concentration** — everything runs in Mumbai. That's an accepted
> trade-off for a single-region deployment, and replicating the data layer is
> the real fix, not a code change.
>
> And one I'm currently fixing: at one point the approval-timeout path reported
> *success*. If a reviewer never responded, the metrics looked identical to an
> approval. That's now a distinct, explicit failure."

---

## 17. Security — what is and isn't there (1:00) **[10-MIN CUT: compress]**

`[SHOW]` Security slide.

> "Being straight about this.
>
> **What's done:** all credentials live in AWS Secrets Manager, never in code or
> environment files. The upload path uses short-lived presigned URLs, so there's
> no long-lived upload credential anywhere. The email domain is a verified
> identity, so we can't be used as an open relay.
>
> **What's not done, and I'd fix next:** the API currently has no real
> authorization. The dashboard's route guard is client-side, which means it's a
> user-interface convenience, not a security control. Anyone who knows the API
> URL can read invoice data.
>
> The fix is an API Gateway authorizer — Cognito or JWT — plus throttling on the
> upload endpoint, which is currently public and would let someone run up a
> Textract bill. And I need to rotate a reviewer credential that's currently
> embedded in the frontend bundle.
>
> I'd rather put that on the slide than have you find it."

---

## 18. Cost (0:30)

> "Cost-wise: everything is pay-per-use, and DynamoDB is on-demand, so there's no
> idle capacity cost. The AI calls are the main variable cost, and routing
> uncertain invoices to a human rather than re-running a model to force a
> decision is cheaper *and* safer. The weekly cleanup keeps storage bounded
> without touching the audit record."

---

## 19. CI/CD (0:30)

> "GitHub Actions builds on every push and pull request. The frontend deploys
> automatically through Amplify when `main` moves. The backend is SAM and
> CloudFormation, so the infrastructure is code and reviewable like everything
> else. One PowerShell script deploys the stack."

---

## 20. Roadmap (1:00)

`[SHOW]` Roadmap.

> "In priority order, and honestly ordered:
>
> **First, durable delivery** — SQS and a dead-letter queue between S3 and the
> workflow, so a poison message is parked and alerted rather than lost. Plus
> CloudWatch alarms on errors and failed executions. Right now a failure is
> recorded but not announced.
>
> **Second, real authentication and rate limiting** — the authorizer and upload
> throttling I mentioned.
>
> **Third, conditional writes** to close the review race condition.
>
> **Fourth, asynchronous Textract** for large documents.
>
> **Fifth, observability** — bounce handling, DLQ replay runbook, alarms.
>
> And then optional: regional disaster recovery."

---

## 21. Close and Q&A (0:45)

`[SHOW]` Takeaway slide.

> "To summarise: invoices arrive by email or browser upload, both landing in S3.
> Textract reads the document and tells us how much to trust each field. Bedrock
> judges it. Four deterministic gates decide whether we're confident enough to
> act alone — and any doubt routes to a human, who can decide from a dashboard
> or a single tap in an email. Every decision is recorded.
>
> The principle underneath all of it: **automation where we can prove it, human
> judgement where we can't, and a full record of both.**
>
> Happy to take questions."

`[SHOW]` Thank you slide with the live URL and repository.

---

# Say this, not that

These are claims in the older deck or in common retelling that are **inaccurate
against the actual code**. A technical interviewer who has read the repo will
catch them.

| Do NOT say | Say instead | Why |
|---|---|---|
| "Retries up to 24 hours / 185 attempts, then dropped" | "The workflow retries four times, then fails visibly in ~3.5 minutes" | The 24 h figure is EventBridge retrying `StartExecution`, which returns 200 instantly. The real failure window is minutes. |
| "Dedupe on `invoiceId` / `objectKey`" | "Dedupe is on `invoiceId`" | `objectKey` is read but never persisted. Only the invoice ID dedupes today. |
| "Deletes raw PDFs older than 30 days" | "Deletes raw PDFs after 7 days" | `S3CleanupHandler.RETENTION_DAYS = 7`. The 30-day figure is a *proposed* lifecycle, not current behaviour. |
| "SES inbound retries ~2–3 times, then the email is lost" | "Non-PDF mail is currently discarded without a retry" | The handler returns success, so SES never retries. There are no retries at all. |
| "SES v2 sends the notifications" | "Brevo sends all outbound email" | SES send grants exist in IAM but are unused. All four call sites use Brevo. |
| "29,000 requests, 100% success rate" | "≈220,000 requests, zero application errors, 161,425 × 503 from concurrency quota" | The old numbers predate the real load test and hide the ceiling. |
| "The system scales to X users" | "The ceiling is 68–104 req/s, set by a concurrency quota of 10" | The plateau is a quota limit, not a scaling property. |
| "SnapStart reduced cold start to 0.6 s in production" | "SnapStart was evaluated; it is not enabled" | It was benchmarked, then rejected in favour of the warm-up job. |
| "Authentication is handled" / "the review page is protected" | "There is no API authorization today; the route guard is client-side" | Say this before you're asked. |
| "10 handlers" or "11 handlers" without checking | "Eleven Lambda functions" | Eleven is correct: ten in the README table plus `request-approval`, which only the workflow calls. |
| "Email tokens expire after 72 hours and can't be reused" | Expiry is real; replay protection was only added in the current branch | Until the token-signing change is deployed, links are unsigned Base64. |

---

# Q&A preparation

**"What stops a duplicate from being paid twice?"**
Deduplication on the invoice ID happens before auto-approval, and a match sets
the status to `DUPLICATE` with high risk, which is never auto-approved. Note
honestly: if Textract fails to read an ID at all, the system mints a placeholder
ID, and that path is not deduplicated — it's first item on the idempotency work.

**"What if Textract or Bedrock is down?"**
Bedrock degrades to a conservative "requires review" — it never throws into the
pipeline. Textract throttling is retried in-function with jittered backoff, five
attempts, and again at the workflow layer. The system fails toward human review,
not toward silent approval.

**"How do you know the AI isn't hallucinating a total?"**
That's precisely why the total is never taken on the model's word alone. The
model's output has to clear a 95% OCR confidence, survive a line-item
arithmetic reconciliation, and be corroborated by an independent AI check. Any
disagreement routes to review.

**"Is this safe to point at real invoices?"**
The data model and audit trail are. The gap is authorization — the API is
currently open, and that's the first thing I'd change before handling anything
sensitive. I'd rather say that than imply otherwise.

**"Why 72 hours for the review window?"**
It matches the escalation schedule, so an invoice that isn't actioned within the
window gets a fresh set of links and then an explicit failure, rather than
sitting silently forever.

**"What happens if two reviewers click at the same time?"**
Today, last write wins — that's a documented defect. The fix is a DynamoDB
conditional write so the second decision is rejected, and it's a small change
I've already scoped.

**"How do you handle a password-protected PDF?"**
It fails. It's classified as non-retryable, the workflow fails, and the failure
is recorded in the execution history. What's missing is a dead-letter queue and
an alarm, so it's parked and visible rather than merely failed. That's roadmap
item one.

**"Why Bedrock at all if you have deterministic rules?"**
Because the rules can't reason. Arithmetic reconciliation catches a broken total,
but it can't tell you a plausible-looking invoice is billed to the wrong entity,
or that the date is implausible. The model covers what arithmetic can't, and the
rules cover what the model can't be trusted on.

---

# Demo preparation checklist

Run through this **30 minutes before** you present.

- [ ] Send a test invoice to `invoices@zexxity.online` and confirm it lands as `REVIEW_REQUIRED` — you want a review item ready, not an auto-approval.
- [ ] Have one **clean** invoice already auto-approved so you can show a populated dashboard metric.
- [ ] Confirm the reviewer's inbox has a live escalation/approval email whose token has **not** expired.
- [ ] Open the CloudWatch log stream for `ses-inbound-handler` and `invoice-extraction-lambda` in separate tabs, pre-scrolled to the log group list.
- [ ] Open the S3 `invoices/` prefix in a second window.
- [ ] **Clear your browser cache** — a stale bundle could show a cached API base URL.
- [ ] **Do not open View Source on the login page.** The reviewer credential is currently embedded in the frontend bundle. If asked about auth, use the Q&A answer above rather than demoing it.
- [ ] Have the state machine diagram and the failure-modes table ready as screenshots in case the live console is slow.
- [ ] Confirm the demo tab is not logged into anything that would show notifications.
- [ ] Pre-load the PDF you'll upload so you're not hunting for a file live.
- [ ] Have a **backup**: if the email path fails during the demo, jump straight to the browser upload and say *"let me take the other path"* — both converge on the same bucket, so the demo still works.

---

# Files

| File | Purpose |
|---|---|
| `presentation.md` | Marp slide deck (visual layer) |
| `presentation-script.md` | This document (spoken layer) |
| `ARCHITECTURE.md` | Detailed technical architecture |
| `state-machine.md` | Full state machine reference |
| `load-test-results.md` | Load test methodology and results |
| `WHY_AND_HOW.md` | Design rationale, question-by-question |
| `interview-questions.md` | Anticipated interview questions |
