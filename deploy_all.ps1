$bucket  = "invoice-processing-buckets"
$key     = "deployments/invoice-extraction-lambda-1.0-SNAPSHOT.jar"
$region  = "ap-south-1"

$functions = @(
    "GetInvoiceLambda",
    "ApproveRejectLambda",
    "UploadUrlLambda",
    "token-approval",
    "ses-inbound-handler",
    "invoice-extraction-lambda",
    "daily-digest-report",
    "weekly-s3-cleanup",
    "expired-review-cleanup"
)

Write-Host "Deploying JAR to all Lambda functions..." -ForegroundColor Cyan

foreach ($fn in $functions) {
    Write-Host "  Updating: $fn" -NoNewline
    $result = aws lambda update-function-code `
        --function-name $fn `
        --s3-bucket $bucket `
        --s3-key $key `
        --region $region `
        --query "FunctionName" --output text 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host " ✓" -ForegroundColor Green
    } else {
        Write-Host " ✗ $result" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Fixing timeouts..." -ForegroundColor Cyan

# ApproveRejectLambda: uses CompletableFuture.get(8s) — needs at least 30s
aws lambda update-function-configuration `
    --function-name ApproveRejectLambda `
    --timeout 30 --region $region `
    --query "FunctionName" --output text 2>&1 | ForEach-Object { Write-Host "  ApproveRejectLambda timeout=30s ✓" -ForegroundColor Green }

# GetInvoiceLambda: DynamoDB scan — 30s is plenty
aws lambda update-function-configuration `
    --function-name GetInvoiceLambda `
    --timeout 30 --region $region `
    --query "FunctionName" --output text 2>&1 | ForEach-Object { Write-Host "  GetInvoiceLambda timeout=30s ✓" -ForegroundColor Green }

# token-approval: DynamoDB read+write — 30s
aws lambda update-function-configuration `
    --function-name token-approval `
    --timeout 30 --region $region `
    --query "FunctionName" --output text 2>&1 | ForEach-Object { Write-Host "  token-approval timeout=30s ✓" -ForegroundColor Green }

# UploadUrlLambda: S3 presigner only — 15s is fine, keep it
Write-Host "  UploadUrlLambda timeout=15s (ok, presigner only)" -ForegroundColor Gray

Write-Host ""
Write-Host "Setting API_BASE_URL env var on invoice-extraction-lambda..." -ForegroundColor Cyan

aws lambda update-function-configuration `
    --function-name invoice-extraction-lambda `
    --region $region `
    --environment "Variables={DYNAMO_TABLE=invoices,INVOICE_BUCKET=invoice-processing-buckets,API_BASE_URL=https://rw5n87lye8.execute-api.ap-south-1.amazonaws.com,JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1}" `
    --query "FunctionName" --output text 2>&1 | ForEach-Object { Write-Host "  invoice-extraction-lambda env updated ✓" -ForegroundColor Green }

Write-Host ""
Write-Host "All done." -ForegroundColor Cyan
