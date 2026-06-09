# =============================================================================
# deploy.ps1  –  Full deployment script for Invoice Processing System
#
# What this does:
#   1. Builds the Java Lambda JAR
#   2. Deploys all 3 Lambdas + API Gateway via AWS SAM
#   3. Creates the Secrets Manager secret
#   4. Creates an S3 bucket and uploads the reviewer UI
#   5. Prints all URLs you need
#
# Prerequisites:
#   - AWS CLI configured  (aws configure)
#   - SAM CLI installed   (https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
#   - Java 21 + Maven installed
#
# Usage:
#   .\deploy.ps1
# =============================================================================

# ── EDIT THESE ────────────────────────────────────────────────────────────────
$AWS_REGION      = "ap-south-1"
$AWS_ACCOUNT_ID  = (aws sts get-caller-identity --query Account --output text)
$STACK_NAME      = "invoice-processing-stack"
$S3_DEPLOY_BUCKET= "invoice-processing-deploy-$AWS_ACCOUNT_ID"   # SAM staging bucket
$UI_BUCKET       = "invoice-reviewer-ui-$AWS_ACCOUNT_ID"         # Static website bucket
$SES_SENDER      = "noreply@zexxity.online"                       # Your verified SES sender
$SES_REVIEWER    = "invydexter@gmail.com"                         # Reviewer email
$MODEL_ID        = "apac.amazon.nova-lite-v1:0"
# ─────────────────────────────────────────────────────────────────────────────

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Log($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Ok($msg)  { Write-Host "    ✅ $msg"  -ForegroundColor Green }
function Err($msg) { Write-Host "    ❌ $msg"  -ForegroundColor Red; exit 1 }

# ── 1. Build JAR ──────────────────────────────────────────────────────────────
Log "Building Lambda JAR..."
mvn clean package -q
if ($LASTEXITCODE -ne 0) { Err "Maven build failed" }
Ok "JAR built: target/invoice-extraction-lambda-1.0-SNAPSHOT.jar"

# ── 2. Create SAM staging bucket if needed ────────────────────────────────────
Log "Ensuring SAM staging bucket exists..."
$bucketExists = aws s3api head-bucket --bucket $S3_DEPLOY_BUCKET 2>&1
if ($LASTEXITCODE -ne 0) {
    aws s3api create-bucket `
        --bucket $S3_DEPLOY_BUCKET `
        --region $AWS_REGION `
        --create-bucket-configuration LocationConstraint=$AWS_REGION | Out-Null
    Ok "Created bucket: $S3_DEPLOY_BUCKET"
} else {
    Ok "Bucket already exists: $S3_DEPLOY_BUCKET"
}

# ── 3. SAM deploy (Lambda + API Gateway) ─────────────────────────────────────
Log "Deploying SAM stack: $STACK_NAME ..."
sam deploy `
    --template-file template.yaml `
    --stack-name $STACK_NAME `
    --s3-bucket $S3_DEPLOY_BUCKET `
    --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM `
    --region $AWS_REGION `
    --no-confirm-changeset

if ($LASTEXITCODE -ne 0) { Err "SAM deploy failed" }
Ok "SAM stack deployed"

# ── 4. Get API Gateway URL from stack outputs ─────────────────────────────────
Log "Fetching API Gateway URL..."
$API_URL = aws cloudformation describe-stacks `
    --stack-name $STACK_NAME `
    --region $AWS_REGION `
    --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" `
    --output text

if (-not $API_URL) { Err "Could not get API URL from stack outputs" }
Ok "API URL: $API_URL"

# ── 5. Create / update Secrets Manager secret ────────────────────────────────
Log "Writing config to Secrets Manager..."
$SECRET_VALUE = "{`"sesSender`":`"$SES_SENDER`",`"sesReviewer`":`"$SES_REVIEWER`",`"modelId`":`"$MODEL_ID`"}"

$secretExists = aws secretsmanager describe-secret --secret-id "invoice-processing/config" 2>&1
if ($LASTEXITCODE -ne 0) {
    aws secretsmanager create-secret `
        --name "invoice-processing/config" `
        --description "Invoice processing Lambda configuration" `
        --secret-string $SECRET_VALUE `
        --region $AWS_REGION | Out-Null
    Ok "Secret created: invoice-processing/config"
} else {
    aws secretsmanager update-secret `
        --secret-id "invoice-processing/config" `
        --secret-string $SECRET_VALUE `
        --region $AWS_REGION | Out-Null
    Ok "Secret updated: invoice-processing/config"
}

# ── 6. Patch config.js with the real API URL ─────────────────────────────────
Log "Patching invoice-reviewer-ui/config.js..."
$configContent = "const API_BASE_URL = `"$API_URL`";"
Set-Content -Path "invoice-reviewer-ui\config.js" -Value $configContent
Ok "config.js updated with: $API_URL"

# ── 7. Create UI S3 bucket with static website hosting ───────────────────────
Log "Setting up UI S3 bucket: $UI_BUCKET ..."
$uiBucketExists = aws s3api head-bucket --bucket $UI_BUCKET 2>&1
if ($LASTEXITCODE -ne 0) {
    aws s3api create-bucket `
        --bucket $UI_BUCKET `
        --region $AWS_REGION `
        --create-bucket-configuration LocationConstraint=$AWS_REGION | Out-Null
    Ok "Created bucket: $UI_BUCKET"
} else {
    Ok "Bucket already exists: $UI_BUCKET"
}

# Disable block public access
aws s3api put-public-access-block `
    --bucket $UI_BUCKET `
    --public-access-block-configuration "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false" | Out-Null

# Set bucket policy for public read
$BUCKET_POLICY = "{`"Version`":`"2012-10-17`",`"Statement`":[{`"Sid`":`"PublicRead`",`"Effect`":`"Allow`",`"Principal`":`"*`",`"Action`":`"s3:GetObject`",`"Resource`":`"arn:aws:s3:::$UI_BUCKET/*`"}]}"
aws s3api put-bucket-policy --bucket $UI_BUCKET --policy $BUCKET_POLICY | Out-Null

# Enable static website hosting
aws s3 website "s3://$UI_BUCKET" --index-document index.html --error-document index.html | Out-Null
Ok "Static website hosting enabled"

# ── 8. Upload UI files ────────────────────────────────────────────────────────
Log "Uploading UI files to S3..."
aws s3 cp invoice-reviewer-ui\index.html  "s3://$UI_BUCKET/index.html"  --content-type "text/html"
aws s3 cp invoice-reviewer-ui\review.html "s3://$UI_BUCKET/review.html" --content-type "text/html"
aws s3 cp invoice-reviewer-ui\style.css   "s3://$UI_BUCKET/style.css"   --content-type "text/css"
aws s3 cp invoice-reviewer-ui\app.js      "s3://$UI_BUCKET/app.js"      --content-type "application/javascript"
aws s3 cp invoice-reviewer-ui\config.js   "s3://$UI_BUCKET/config.js"   --content-type "application/javascript"
Ok "UI files uploaded"

# ── 9. Print final summary ────────────────────────────────────────────────────
$UI_URL = "http://$UI_BUCKET.s3-website.$AWS_REGION.amazonaws.com"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host "  DEPLOYMENT COMPLETE" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Dashboard URL  : $UI_URL"            -ForegroundColor White
Write-Host "  API Gateway URL: $API_URL"           -ForegroundColor White
Write-Host "  Secret name    : invoice-processing/config" -ForegroundColor White
Write-Host ""
Write-Host "  API Endpoints:"                      -ForegroundColor White
Write-Host "    GET  $API_URL/invoices"
Write-Host "    GET  $API_URL/invoices?id=<id>"
Write-Host "    POST $API_URL/invoices/decision"
Write-Host ""
Write-Host "============================================================" -ForegroundColor Yellow
