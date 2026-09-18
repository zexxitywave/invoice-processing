# Invoice Processing – AWS Account Migration Runbook (Mumbai, single region)

**Source account:** `977574654100` (old, being suspended) — `profile: old`
**Discarded target:** `662241429223` — `profile: new`  ⛔ Abandoned: Bedrock `NOT_AUTHORIZED` for *all* Nova models in *all* regions, even with AdministratorAccess; agreement API rejected (`create-foundation-model-agreement` → "Agreement not supported for this model"); support plan free/Basic so no Bedrock model access.
**FINAL target:** `891377127368` — `profile: new3` (IAM user `maalik`)
**Region:** `ap-south-1` (Mumbai) — **everything in one region**

> SES inbound email receiving IS available in Mumbai (`inbound-smtp.ap-south-1.amazonaws.com`),
> so the old two-region (eu-west-1) design is gone.

## Status (2026-09-17)

| Step | Status |
|---|---|
| 0. AWS profiles | ✅ `old`, `new3` verified (new3 = 891377127368) |
| 1. New account readiness | ✅ **Textract + Bedrock BOTH work** in 891377127368 (`apac.amazon.nova-lite-v1:0` invokable); SES still **sandbox** → request production access |
| 2. Bucket names | ✅ `invoice-processing-buckets-m3`, `ses-inbound-emails-m3` (EventBridge enabled, SES bucket policy applied) |
| 3. Stack deployed | ✅ `invoice-processing-stack` **UPDATE_COMPLETE** via resource-IMPORT workaround (see Step 3 — CFN's new `EarlyValidation::ResourceExistenceCheck` hook blocks plain CREATE) |
| 4. Data copy | ✅ S3 + DynamoDB **400 items** + secret; API parity verified (400 / 84 / 266 / 45 / 35 / 29) |
| 5. SES verification | ✅ `invydexter@gmail.com` **SUCCESS** (auto-verified); ⏳ `zexxity.online` **PENDING** (3 DKIM CNAMEs at Hostinger pinning new3 tokens — table below) |
| 6. MX cutover | ⏳ not yet (do after Step 5) |
| 7. Amplify | ✅ app `d19y00sxkvzipk` recreated (`main.d19y00sxkvzipk.amplifyapp.com`), `VITE_API_BASE_URL=https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com` baked in, SPA rewrite rule fixed, CORS updated. ⏳ custom domain `zexxity.online` = PENDING_VERIFICATION (Hostinger DNS records required — see Step 7) |
| 8. Cutover checklist | actionable below |

### Verified working end-to-end (2026-09-17)
- `GET /invoices` — all 6 dashboard totals match old account **exactly**: totalCount 400, totalApproved 84, totalReview 266, totalDuplicate 45, totalHumanApproved 35, totalHumanRejected 29
- **Full upload pipeline live**: uploaded real invoice PDF → `incoming/` → EventBridge → Step Functions (`InvoiceProcessingStateMachine-hcaDdc2OYf7l`) → **SUCCEEDED** → Textract → Bedrock Nova → DynamoDB item created (`# 16384`, vendor SuperStore, APPROVED, $6,208.84) → `audit/invoice-16384.json`. Test item + objects deleted afterwards to keep parity at 400.
- Single-invoice lookup / pagination / token-approval / `POST /invoices/review` all previously verified (account-2 work carries over unchanged).
- **Inbound email path (full E2E)**: uploaded raw email (.eml) with invoice PDF to `ses-inbound-emails-m3/emails/` → invoked `ses-inbound-handler` → PDF extracted to `invoice-processing-buckets-m3/invoices/` → EventBridge → SFN → Textract → Bedrock Nova → **DynamoDB item created and SUCCEEDED**. Test artifacts cleaned up (400 items restored).
- **CloudWatch error audit**: 0 runtime errors across all 9 Lambdas in the last 7 days.
- **CORS preflight**: `OPTIONS /invoices` → HTTP 204, `Access-Control-Allow-Origin: https://zexxity.online`
- **All 3 scheduled jobs** (daily-digest-report, expired-review-cleanup, weekly-s3-cleanup) invoked and returned 200 OK with correct summaries.
- **SES send path**: `SendEmail` to `noreply@zexxity.online` → `MessageRejected` (expected — `zexxity.online` domain PENDING until Hostinger CNAMEs added). Confirms wiring is correct.

### Gaps found & fixed during account-3 verification
| # | Gap | Fix |
|---|---|---|
| 1 | CFN `EarlyValidation::ResourceExistenceCheck` blocks stack CREATE when pre-existing DynamoDB table + SES rule set already exist | Adopted both via IMPORT change-set (`import-minimal.yaml`) then `sam deploy` as UPDATE (see Step 3). One-time bootstrap; future deploys are normal UPDATEs. |
| 2 | `SesInboundHandler` hardcoded `s3EuWest` S3 client for reading raw emails — fails with 301 PermanentRedirect (bucket now in ap-south-1) | Replaced with `s3ApSouth`; single-region design. Email path now fully functional. |
| 3 | `totalCount` shows 0 after fresh import — `DescribeTable.itemCount` is eventually-consistent and lags by hours | Switched `getTotalCount()` to paginated `ScanRequest` with `select("COUNT")` for accurate real-time count. |

## Deploy sequence that finally worked (account 3 only, IMPORT workaround)

CFN's managed hook `AWS::EarlyValidation::ResourceExistenceCheck` **blocks creating a NEW stack whose template declares resources that already exist outside CFN** (our DynamoDB table + SES receipt rule set). It does NOT block UPDATEs. Workaround: adopt the pre-existing resources into the stack via an IMPORT change-set first, then `sam deploy` (UPDATE) creates everything else.

1. **Pre-create account-3 resources** (SES identities + receipt rule set, exact order matters — the SES receipt rule fails without the bucket policy):
   ```powershell
   aws sesv2 create-email-identity --email-identity zexxity.online --profile new3 --region ap-south-1
   aws sesv2 create-email-identity --email-identity invydexter@gmail.com --profile new3 --region ap-south-1
   aws ses create-receipt-rule-set --rule-set-name invoice-inbound --profile new3 --region ap-south-1
   aws ses set-active-receipt-rule-set --rule-set-name invoice-inbound --profile new3 --region ap-south-1
   aws s3api put-bucket-policy --bucket ses-inbound-emails-m3 --policy file://ses-bucket-policy-m3.json --profile new3 --region ap-south-1
   ```
2. **Package** the jar (`mvn clean package -DskipTests` first), then:
   ```powershell
   sam package --template-file template-v2.yaml --s3-bucket invoice-deploy-891377127368 `
     --output-template-file packaged.json --region ap-south-1 --profile new3
   ```
3. **IMPORT** the two pre-existing resources (minimal template + tag file). Run via `cmd` to dodge PowerShell quoting (see `import-min.cmd`):
   - `import-minimal.yaml` declares `InvoicesTable` + `ReceiptRuleSet` with `DeletionPolicy: Retain`
   - `import-resources.json` lists `{AWS::DynamoDB::Table, InvoicesTable, {"TableName":"invoices"}}` and `{AWS::SES::ReceiptRuleSet, ReceiptRuleSet, {"RuleSetName":"invoice-inbound"}}`
   ```powershell
   : > import-min.cmd  # contains:
   aws cloudformation create-change-set --stack-name invoice-processing-stack --change-set-name import-min --change-set-type IMPORT --template-body file://import-minimal.yaml --resources-to-import "[...json..." --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM --region ap-south-1 --profile new3
   aws cloudformation execute-change-set --change-set-name import-min --stack-name invoice-processing-stack --profile new3 --region ap-south-1
   ```
4. **Redeploy the FULL stack as an UPDATE** (this is the normal path from now on):
   ```powershell
   sam deploy --template-file template-v2.yaml --stack-name invoice-processing-stack `
     --s3-bucket invoice-deploy-891377127368 `
     --parameter-overrides "InvoiceBucketName=invoice-processing-buckets-m3 SesInboundBucketName=ses-inbound-emails-m3 CorsOrigins=https://zexxity.online,https://www.zexxity.online,http://localhost:5173" `
     --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM --region ap-south-1 --profile new3 --no-confirm-changeset
   ```
> Re-run step 4 after the new Amplify app exists to add `main.<appid>.amplifyapp.com` to `CorsOrigins`.
> Note: `totalCount` is computed by a scanned COUNT (was `DescribeTable.itemCount`, which lagged at 0 after the fresh table — changed in `GetInvoiceHandler.java`, already deployed).

## Step 5 — SES verification (manual; DNS is external at Hostinger)

SES identities don't transfer between accounts. `invydexter@gmail.com` is **SUCCESS** (auto). For `zexxity.online` (PENDING) add these 3 DKIM CNAMEs at Hostinger — **the tokens changed vs account 2** (they're account-specific):

| Name | Type | Value |
|---|---|---|
| `sgbeznusa2gwgmq4iqufbxcexjdcdrom._domainkey.zexxity.online` | CNAME | `sgbeznusa2gwgmq4iqufbxcexjdcdrom.dkim.amazonses.com` |
| `taphhysz53ruw6rjmq2e2aszuis77uuo._domainkey.zexxity.online` | CNAME | `taphhysz53ruw6rjmq2e2aszuis77uuo.dkim.amazonses.com` |
| `uwt4njb2zbjbond266nz42njq4gyi5ik._domainkey.zexxity.online` | CNAME | `uwt4njb2zbjbond266nz42njq4gyi5ik.dkim.amazonses.com` |

(Optionally also legacy TXT `_amazonses.zexxity.online` TXT `9LmWZQhQ7MuhIC1xjOX3Y0ugGhhO9iZl0/DYkDSL8AY=`.)

Wait until `get-email-identity zexxity.online` → `SUCCESS`, then request **SES production access** (sandbox = 200/day, verified-recipients only) — console support case.

## Step 6 — Point `zexxity.online` mail at Mumbai (after Step 5)

```
Old: 10 inbound-smtp.eu-west-1.amazonaws.com
New: 10 inbound-smtp.ap-south-1.amazonaws.com
```
Mail to `invoices@zexxity.online` then lands in the new account's SES → `ses-inbound-emails-m3/emails/` → Lambda.

## Step 7 — Recreate Amplify

1. Amplify → New app → GitHub → repo → `amplify.yml`. Branch `main`.
2. Env var `VITE_API_BASE_URL` = **`https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com`**
3. Custom domain `zexxity.online` → DNS CNAME to the new `main.<appid>.amplifyapp.com`.
4. Re-run Step 4 `sam deploy` to add that domain to `CorsOrigins`.

### Done (app `d19y00sxkvzipk`, account 891377127368)

- App recreated and deployed (`main` branch, build SUCCEED); default URL
  **`https://main.d19y00sxkvzipk.amplifyapp.com`** serves the correct bundle.
- The old app's account held `zexxity.online` (cross-account lock). Old app had no
  domain associations left; the failed association in the new app was deleted and
  re-created → now **PENDING_VERIFICATION**.
- CORS on `xei4kla8v8` updated to also allow `https://main.d19y00sxkvzipk.amplifyapp.com`.
- SPA rewrite rule corrected to the standard catch-all (deep links now return 200).

**Remaining (manual, at Hostinger hPanel → DNS zone editor):** add these records:

| Name | Type | Target |
|---|---|---|
| `_b8a7f47a955d7de8ca280253931e28a5` | CNAME | `_71660ba40d8ab85589368aa1f171918f.jkddzztszm.acm-validations.aws` |
| `@` (root) | CNAME (hPanel shows it as ALIAS) | `d13v49uikfgp89.cloudfront.net` |
| `www` | CNAME | `d13v49uikfgp89.cloudfront.net` |

> Replace the existing `www` CNAME (currently pointing at the dead
> `d2ksbypjcrl5m7.cloudfront.net`). Once Amplify validates the ACM record the status
> becomes AVAILABLE and `https://zexxity.online` serves the new app.

## Step 8 — Cutover checklist

- [x] `GET https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com/invoices` → 400 records, totals match old
- [x] Upload test PDF → SFN → Textract → Bedrock (Nova) → new DynamoDB item (verified, item removed)
- [x] Inbound email path: SES bucket → `ses-inbound-handler` → PDF → SFN → DynamoDB (simulated SES event; real email waits on MX)
- [x] All Lambdas invoked: 0 errors (CloudWatch); scheduled jobs return 200
- [x] CORS preflight 204; IAM policies + secret readable
- [ ] `zexxity.online` SES domain SUCCESS + production access
- [ ] MX switched → live email to `invoices@zexxity.online` → S3 `ses-inbound-emails-m3/emails/`
- [ ] Amplify new app on `zexxity.online` (CORS updated)
- [ ] `daily-digest-report` SES send succeeds (needs verified domain)
- [ ] Old-account cleanup after cutover (old receipt rule, eu-west-1 handler)

## Rollback / safety

- Old account still alive (API `rw5n87lye8`) until suspension — safe to test against new account.
- `migrate-data.ps1` is idempotent (S3 `sync` deltas, DynamoDB batch-write overwrites by key).
- Deploying again after account-3 resources are stack-owned: plain `sam deploy` (Step 4) — no more IMPORT needed.

## Files in `migration/`

| File | Purpose |
|---|---|
| `template-v2.yaml` | Full ap-south-1 stack (parameterized buckets; mirrors live prod + SES inbound) |
| `packaged.json` | `sam package` output (jar referenced from S3) — regenerated per deploy |
| `import-minimal.yaml` / `import-resources.json` / `import-min.cmd` | IMPORT bootstrap for pre-existing `InvoicesTable` + `ReceiptRuleSet` behind the EarlyValidation hook |
| `ses-bucket-policy-m3.json` | SES-can-write policy for `ses-inbound-emails-m3` (bucket `-m3`, account 891377127368) |
| `migrate-data.ps1` | S3 + DynamoDB + Secrets copy (old → new3) |

> ROTATE: the `maalik` access key (`AKIA47CRXR7EN5QIO57G` from `C:\Users\jagdish\Downloads\maalik_accessKeys.csv`) was shared in chat — after setup completes, delete it and issue a new one.