$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$base = "https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com"
$outFile = Join-Path $here "results-read.json"

Add-Type -Path (Join-Path $here "LoadHarness.cs") `
    -ReferencedAssemblies @("System.dll", "System.Core.dll", "System.Net.Http.dll") | Out-Null

$results = New-Object System.Collections.Generic.List[object]

function Run-Scenario {
    param($label, $method, $url, $body, $threads, $dur, $keepAlive = $true)
    Write-Host ("  running {0,-46} threads={1,-3} dur={2}s" -f $label, $threads, $dur)
    $r = [Harness]::Run($label, $method, $url, $body, $threads, $dur, $keepAlive, $null)
    $results.Add($r) | Out-Null
    Write-Host ("    -> {0,6} req | {1,8} req/s | avg {2,7} ms | p95 {3,7} ms | p99 {4,7} ms | err {5,6}% | {6}" -f `
        $r.Total, $r.Throughput, $r.Avg, $r.P95, $r.P99, $r.ErrorPct, $r.StatusSummary())
    if ($r.FirstError) { Write-Host "       FIRST ERROR: $($r.FirstError)" -ForegroundColor Red }
}

Write-Host "`n=== T1: GET /invoices?pageSize=20 (list + global metrics) ===" -ForegroundColor Cyan
foreach ($t in @(1, 5, 8, 10, 20, 40)) {
    Run-Scenario "GET /invoices (list) t=$t" "GET" "$base/invoices?pageSize=20" $null $t 20
}

Write-Host "`n=== T2: GET /invoices?id=13789 (single lookup) ===" -ForegroundColor Cyan
foreach ($t in @(1, 8, 10, 20, 40)) {
    Run-Scenario "GET /invoices?id= (lookup) t=$t" "GET" "$base/invoices?id=13789" $null $t 20
}

Write-Host "`n=== T3: POST /invoices/upload-url (presign, no object created) ===" -ForegroundColor Cyan
$upBody = '{"fileName":"loadtest-presentation.pdf"}'
foreach ($t in @(1, 8, 10, 20)) {
    Run-Scenario "POST /invoices/upload-url t=$t" "POST" "$base/invoices/upload-url" $upBody $t 20
}

Write-Host "`n=== T4: GET /invoices/approve?token=... (synthetic token, non-existent invoice) ===" -ForegroundColor Cyan
$exp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 255600
$json = '{"invoiceId":"NONEXISTENT-LOADTEST-TOKEN","decision":"APPROVED","exp":' + $exp + '}'
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
Write-Host "  WARNING: this endpoint creates a phantom row. run-writetest-style cleanup follows."
foreach ($t in @(1, 8, 10, 20)) {
    Run-Scenario "GET /invoices/approve (token) t=$t" "GET" "$base/invoices/approve?token=$b64" $null $t 20
}

Write-Host "`n=== T5: concurrency ceiling probe (GET /invoices) ===" -ForegroundColor Cyan
foreach ($t in @(60, 100)) {
    Run-Scenario "GET /invoices (list) t=$t" "GET" "$base/invoices?pageSize=20" $null $t 20
}

Write-Host "`n=== Cleanup: removing phantom rows created by T4 ===" -ForegroundColor Yellow
& (Join-Path $here "cleanup-phantoms.ps1")

$rows = @()
foreach ($r in $results) {
    $codes = [ordered]@{}
    foreach ($k in ($r.StatusCodes.Keys | Sort-Object)) { $codes["$k"] = $r.StatusCodes[$k] }
    $rows += [ordered]@{
        label = $r.Label; threads = $r.Threads; durationSec = $r.DurationSec
        total = $r.Total; httpErrors = $r.HttpErrors; transportErrors = $r.TransportErrors
        throughput = $r.Throughput; avg = $r.Avg; min = $r.Min; max = $r.Max
        p50 = $r.P50; p90 = $r.P90; p95 = $r.P95; p99 = $r.P99
        errorPct = $r.ErrorPct; statusCodes = $codes; firstError = $r.FirstError
    }
}
$rows | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outFile -Encoding UTF8
Write-Host "`nSAVED: $outFile" -ForegroundColor Green
