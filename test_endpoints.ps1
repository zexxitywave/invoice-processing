$base = "https://rw5n87lye8.execute-api.ap-south-1.amazonaws.com"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " ENDPOINT HEALTH CHECK" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# TEST 1: GET /invoices
Write-Host ""
Write-Host "[1] GET /invoices (list all)" -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/invoices" -UseBasicParsing -TimeoutSec 20
    $items = $r.Content | ConvertFrom-Json
    Write-Host "    Status : $($r.StatusCode) OK" -ForegroundColor Green
    Write-Host "    Records: $($items.Count)"
} catch {
    Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

# TEST 2: GET /invoices?id=# 22900
Write-Host ""
Write-Host "[2] GET /invoices?id=%23%2022900 (single lookup)" -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/invoices?id=%23%2022900" -UseBasicParsing -TimeoutSec 20
    $j = $r.Content | ConvertFrom-Json
    Write-Host "    Status    : $($r.StatusCode) OK" -ForegroundColor Green
    Write-Host "    invoiceId : $($j.invoiceId)"
    Write-Host "    vendor    : $($j.vendorName)"
    Write-Host "    status    : $($j.validationStatus)"
} catch {
    Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

# TEST 3: POST /invoices/upload-url
Write-Host ""
Write-Host "[3] POST /invoices/upload-url" -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/invoices/upload-url" -Method POST -Body '{"fileName":"test_invoice.pdf"}' -ContentType "application/json" -UseBasicParsing -TimeoutSec 20
    $j = $r.Content | ConvertFrom-Json
    Write-Host "    Status         : $($r.StatusCode) OK" -ForegroundColor Green
    Write-Host "    objectKey      : $($j.objectKey)"
    Write-Host "    expiresInSecs  : $($j.expiresInSeconds)"
    Write-Host "    uploadUrl ok   : $($j.uploadUrl.Length -gt 50)"
} catch {
    Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

# TEST 4: POST /invoices/review
Write-Host ""
Write-Host "[4] POST /invoices/review (approve/reject)" -ForegroundColor Yellow
try {
    $body = '{"invoiceId":"# 22900","decision":"APPROVED","reviewer":"test@zexxity.online","reason":"Health check"}'
    $r = Invoke-WebRequest -Uri "$base/invoices/review" -Method POST -Body $body -ContentType "application/json" -UseBasicParsing -TimeoutSec 30
    $j = $r.Content | ConvertFrom-Json
    Write-Host "    Status         : $($r.StatusCode) OK" -ForegroundColor Green
    Write-Host "    reviewDecision : $($j.reviewDecision)"
    Write-Host "    message        : $($j.message)"
} catch {
    Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

# TEST 5: GET /invoices/approve?token= (valid token)
Write-Host ""
Write-Host "[5] GET /invoices/approve?token= (token approval)" -ForegroundColor Yellow
try {
    $invoiceId = "# 22900"
    $exp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 259200
    $json = "{`"invoiceId`":`"$invoiceId`",`"decision`":`"APPROVED`",`"exp`":$exp}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $token = [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').Replace('=','')
    $r = Invoke-WebRequest -Uri "$base/invoices/approve?token=$token" -UseBasicParsing -TimeoutSec 20
    $isHtml = $r.Content.Contains("html") -or $r.Content.Contains("HTML")
    Write-Host "    Status  : $($r.StatusCode) OK" -ForegroundColor Green
    Write-Host "    HTML    : $isHtml"
    Write-Host "    Contains APPROVED/Already: $($r.Content.Contains('APPROVED') -or $r.Content.Contains('Already'))"
} catch {
    Write-Host "    FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

# TEST 6: GET /invoices/approve with EXPIRED token
Write-Host ""
Write-Host "[6] GET /invoices/approve — expired token (should return 400)" -ForegroundColor Yellow
try {
    $exp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 3600
    $json = "{`"invoiceId`":`"# 22900`",`"decision`":`"APPROVED`",`"exp`":$exp}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $token = [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').Replace('=','')
    $r = Invoke-WebRequest -Uri "$base/invoices/approve?token=$token" -UseBasicParsing -TimeoutSec 20
    Write-Host "    Status  : $($r.StatusCode)" -ForegroundColor $(if ($r.StatusCode -eq 400) { "Green" } else { "Red" })
    Write-Host "    Expected: 400 — Got: $($r.StatusCode)"
} catch [System.Net.WebException] {
    $code = [int]$_.Exception.Response.StatusCode
    Write-Host "    Status  : $code $(if ($code -eq 400) { 'CORRECT' } else { 'UNEXPECTED' })" -ForegroundColor $(if ($code -eq 400) { "Green" } else { "Red" })
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host " HEALTH CHECK COMPLETE" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
