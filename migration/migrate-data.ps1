# =============================================================================
# migrate-data.ps1 – Copy data from OLD AWS account to NEW AWS account
#
#   Copies:  S3 buckets (invoice + SES inbound)  via local staging
#            DynamoDB table `invoices` (scan + batch-write)
#            Secrets Manager `invoice-processing/config`
#
# Prerequisites:
#   - AWS CLI with two profiles: `old` (977574654100) and `new`
#   - Run BEFORE the old account is suspended
#
# Usage:
#   .\migrate-data.ps1 -OldProfile old -NewProfile new
#   .\migrate-data.ps1 -OldProfile old -NewProfile new -InvoiceBucket invoice-processing-buckets-new \
#                      -SesBucket ses-inbound-emails-new
#
# Source buckets default to the old account's names (invoice-processing-buckets,
# ses-inbound-emails-eu) — override with -OldInvoiceBucket / -OldSesBucket.
#
# Single-region note: source data lives in ap-south-1 + ses-inbound bucket in
# eu-west-1 (old design). Everything lands in ap-south-1 in the new account —
# SES inbound is now supported in Mumbai (inbound-smtp.ap-south-1.amazonaws.com).
#
# NOTE on bucket names: names in the old account stay globally reserved while
# it is suspended. Use NEW names (suffix -new) unless the old account has been
# fully deleted and you can reuse the originals.
# =============================================================================

param(
    [string]$OldProfile = "old",
    [string]$NewProfile = "new",
    [string]$OldAccount  = "977574654100",
    [string]$OldInvoiceBucket = "invoice-processing-buckets",
    [string]$OldSesBucket     = "ses-inbound-emails-eu",
    [string]$InvoiceBucket   = "invoice-processing-buckets-new",
    [string]$SesBucket       = "ses-inbound-emails-new",
    [string]$InvoiceRegion   = "ap-south-1",
    [string]$SesRegion       = "ap-south-1",
    [string]$SesSourceRegion = "eu-west-1",
    [string]$DynamoTable     = "invoices",
    [string]$StagingDir      = "$env:TEMP\invoice-migration"
)

Set-StrictMode -Version Latest
# "Stop" turns native-command stderr into NativeCommandError even with 2>$null (PS 5.1 quirk).
# Every aws call below checks $LASTEXITCODE explicitly, so "Continue" is both safe and quieter.
$ErrorActionPreference = "Continue"

# aws CLI writes UTF-8 to stdout; PS 5.1 otherwise decodes it using the ANSI code page,
# which corrupts non-ASCII bytes (and then the mangled JSON fails --request-items parsing).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Log($m) { Write-Host "`n>>> $m" -ForegroundColor Cyan }
function Ok($m)  { Write-Host "    OK $m" -ForegroundColor Green }

# Write JSON without a UTF-8 BOM (aws CLI's file:// loader chokes on the BOM).
function Write-JsonFile($path, $obj) {
    $json = $obj | ConvertTo-Json -Depth 20 -Compress
    [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
}

# ── 0. Sanity check: are both profiles valid? ──────────────────────────────
Log "Checking profiles..."
$oldId = aws sts get-caller-identity --profile $OldProfile --query Account --output text
if (-not $oldId) { throw "Old profile '$OldProfile' failed. Configure it with: aws configure --profile $OldProfile" }
$newId = aws sts get-caller-identity --profile $NewProfile --query Account --output text
if (-not $newId) { throw "New profile '$NewProfile' failed. Configure it with: aws configure --profile $NewProfile" }
Ok "Old account: $oldId | New account: $newId"

if ($oldId -ne $OldAccount) { Write-Warning "Old profile returns $oldId, expected $OldAccount. Double-check!" }

# ── 1. Create destination buckets (with NEW names) ──────────────────────────
Log "Ensuring destination buckets exist in the new account..."

function Ensure-Bucket($profile, $bucket, $region) {
    $exists = aws s3api head-bucket --bucket $bucket --profile $profile 2>$null
    if ($LASTEXITCODE -ne 0) {
        aws s3api create-bucket --bucket $bucket --region $region `
            --create-bucket-configuration LocationConstraint=$region --profile $profile | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not create bucket $bucket (name may be globally taken). Retry with a different -InvoiceBucket/-SesBucket." }
        Ok "Created $bucket in $region"
    } else {
        Ok "Bucket already exists: $bucket"
    }

    # S3 -> EventBridge notifications: REQUIRED so the InvoiceUploadRule fires on
    # Object Created (without this, uploads never reach Step Functions).
    $ncfg = Join-Path $StagingDir "notif-$bucket.json"
    [System.IO.File]::WriteAllText($ncfg, '{"EventBridgeConfiguration":{}}', (New-Object System.Text.UTF8Encoding($false)))
    aws s3api put-bucket-notification-configuration --bucket $bucket `
        --notification-configuration ("file://" + $ncfg.Replace('\','/')) --profile $profile | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning "Could not enable EventBridge notifications on $bucket" }
    else { Ok "EventBridge notifications enabled: $bucket" }
}

Ensure-Bucket $NewProfile $InvoiceBucket $InvoiceRegion
Ensure-Bucket $NewProfile $SesBucket $SesRegion

# ── 2. S3 copy: old -> local staging -> new ─────────────────────────────────
function Copy-Bucket($sourceBucket, $destBucket, $sourceRegion, $destRegion) {
    $local = Join-Path $StagingDir $sourceBucket
    New-Item -ItemType Directory -Path $local -Force | Out-Null
    Log "Syncing s3://$sourceBucket -> $local"
    aws s3 sync "s3://$sourceBucket" $local --profile $OldProfile --region $sourceRegion
    if ($LASTEXITCODE -ne 0) { throw "S3 download failed for $sourceBucket" }
    Log "Uploading $local -> s3://$destBucket"
    aws s3 sync $local "s3://$destBucket" --profile $NewProfile --region $destRegion
    if ($LASTEXITCODE -ne 0) { throw "S3 upload failed for $destBucket" }
    Ok "Copied s3://$sourceBucket -> s3://$destBucket"
}

Copy-Bucket $OldInvoiceBucket $InvoiceBucket $InvoiceRegion $InvoiceRegion
Copy-Bucket $OldSesBucket     $SesBucket     $SesSourceRegion $SesRegion

# ── 3. DynamoDB copy (scan + batch-write, chunks of 25) ─────────────────────
Log "Ensuring DynamoDB table $DynamoTable exists in new account..."
$ddbExists = aws dynamodb describe-table --table-name $DynamoTable --profile $NewProfile --region $InvoiceRegion 2>$null
if ($LASTEXITCODE -ne 0) {
    # Create with the SAME GSIs as production (validationStatus / reviewDecision) —
    # the dashboard count queries need them. DynamoDB only allows one GSI change
    # per update, so they are created here upfront.
    $tt = @{
        TableName = $DynamoTable
        AttributeDefinitions = @(
            @{ AttributeName = "invoiceId";         AttributeType = "S" },
            @{ AttributeName = "validationStatus";  AttributeType = "S" },
            @{ AttributeName = "reviewDecision";    AttributeType = "S" }
        )
        KeySchema = @(@{ AttributeName = "invoiceId"; KeyType = "HASH" })
        BillingMode = "PAY_PER_REQUEST"
        GlobalSecondaryIndexes = @(
            @{ IndexName = "validationStatus-index"
               KeySchema = @(@{ AttributeName = "validationStatus"; KeyType = "HASH" })
               Projection = @{ ProjectionType = "ALL" } },
            @{ IndexName = "reviewDecision-index"
               KeySchema = @(@{ AttributeName = "reviewDecision"; KeyType = "HASH" })
               Projection = @{ ProjectionType = "ALL" } }
        )
    }
    $ttFile = Join-Path $StagingDir "ddb-create.json"
    [System.IO.File]::WriteAllText($ttFile, ($tt | ConvertTo-Json -Depth 10 -Compress), (New-Object System.Text.UTF8Encoding($false)))
    aws dynamodb create-table --cli-input-json ("file://" + $ttFile.Replace('\','/')) --profile $NewProfile --region $InvoiceRegion | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not create DynamoDB table" }
    Ok "Created table $DynamoTable (waiting for ACTIVE...)"
    do { Start-Sleep -Seconds 2
         $st = aws dynamodb describe-table --table-name $DynamoTable --profile $NewProfile --region $InvoiceRegion --query Table.TableStatus --output text
    } while ($st -ne "ACTIVE")
} else {
    Ok "Table already exists: $DynamoTable"
}

Log "Copying DynamoDB table $DynamoTable ($oldId -> $newId)..."

$chunk = [System.Collections.ArrayList]@()
$total = 0
$batchCount = 0
$nextToken = $null

do {
    $args = @("dynamodb","scan","--table-name",$DynamoTable,"--profile",$OldProfile,"--region",$InvoiceRegion,"--output","json")
    if ($nextToken) { $args += @("--starting-token", $nextToken) }

    $res = aws @args | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "DynamoDB scan failed on old account" }

    foreach ($item in $res.Items) {
        $total++
        [void]$chunk.Add(@{ PutRequest = @{ Item = $item } })
        if ($chunk.Count -eq 25) {
            $body = @{ $DynamoTable = $chunk.ToArray() }
            $bodyFile = Join-Path $StagingDir "ddb-$batchCount.json"
            Write-JsonFile $bodyFile $body
            aws dynamodb batch-write-item --request-items ("file://" + $bodyFile.Replace('\','/')) --profile $NewProfile --region $InvoiceRegion | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "batch-write-item failed on chunk $batchCount" }
            Write-Host "    written $total items..." -NoNewline
            $batchCount++
            $chunk.Clear()
        }
    }
    $nextToken = if ($null -ne $res.PSObject.Properties['NextToken']) { $res.NextToken } else { $null }
} while ($nextToken)

if ($chunk.Count -gt 0) {
    $body = @{ $DynamoTable = $chunk.ToArray() }
    $bodyFile = Join-Path $StagingDir "ddb-$batchCount.json"
    Write-JsonFile $bodyFile $body
    aws dynamodb batch-write-item --request-items ("file://" + $bodyFile.Replace('\','/')) --profile $NewProfile --region $InvoiceRegion | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "batch-write-item failed on last chunk" }
    $batchCount++
}
if ($total -eq 0) { Write-Warning "No items found in DynamoDB table $DynamoTable" }
Ok "DynamoDB: wrote $total items across $batchCount batches"

# ── 4. Secrets Manager copy ─────────────────────────────────────────────────
Log "Recreating Secrets Manager secret in new account..."
$secretStr = aws secretsmanager get-secret-value --secret-id "invoice-processing/config" `
    --profile $OldProfile --region $InvoiceRegion --query SecretString --output text

# Avoid PowerShell's broken native-arg quoting (embedded double quotes get stripped):
# stage the raw JSON to a file and pass it via file://.
$secretFile = Join-Path $StagingDir "invoice-secret.json"
[System.IO.File]::WriteAllText($secretFile, $secretStr, (New-Object System.Text.UTF8Encoding($false)))
$secretArg = "file://" + $secretFile.Replace('\','/')

$exists = aws secretsmanager describe-secret --secret-id "invoice-processing/config" --profile $NewProfile --region $InvoiceRegion 2>$null
if ($LASTEXITCODE -eq 0) {
    aws secretsmanager update-secret --secret-id "invoice-processing/config" `
        --secret-string $secretArg --profile $NewProfile --region $InvoiceRegion | Out-Null
    Ok "Secret updated in new account"
} else {
    aws secretsmanager create-secret --name "invoice-processing/config" `
        --description "Invoice processing Lambda configuration" `
        --secret-string $secretArg --profile $NewProfile --region $InvoiceRegion | Out-Null
    Ok "Secret created in new account"
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host "  DATA MIGRATION COMPLETE" -ForegroundColor Yellow
Write-Host "  Old: $oldId  ->  New: $newId" -ForegroundColor White
Write-Host "  S3 invoice   : $InvoiceBucket" -ForegroundColor White
Write-Host "  S3 ses-inbound: $SesBucket" -ForegroundColor White
Write-Host "  DynamoDB     : $DynamoTable ($total items)" -ForegroundColor White
Write-Host "  All resources : $InvoiceRegion (single region)" -ForegroundColor White
Write-Host ""
Write-Host "  NEXT: see migration/MIGRATION.md Steps 5-8 (SES verify, Amplify, DNS/MX cutover)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Yellow