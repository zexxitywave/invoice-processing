$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$base = "https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com"
$outFile = Join-Path $here "results-write.json"

Add-Type -Path (Join-Path $here "LoadHarness.cs") `
    -ReferencedAssemblies @("System.dll", "System.Core.dll", "System.Net.Http.dll") | Out-Null

$results = New-Object System.Collections.Generic.List[object]

function Run-Scenario {
    param($label, $body, $threads, $dur)
    Write-Host ("  running {0,-40} threads={1,-3} dur={2}s" -f $label, $threads, $dur)
    $r = [Harness]::Run($label, "POST", "$base/invoices/review", $body, $threads, $dur, $true, $null)
    $results.Add($r) | Out-Null
    Write-Host ("    -> {0,5} req | {1,8} req/s | avg {2,7} ms | p95 {3,7} ms | p99 {4,7} ms | err {5,6}% | {6}" -f `
        $r.Total, $r.Throughput, $r.Avg, $r.P95, $r.P99, $r.ErrorPct, $r.StatusSummary())
    if ($r.FirstError) { Write-Host "       FIRST ERROR: $($r.FirstError)" -ForegroundColor Red }
}

Write-Host "WARNING: this endpoint writes to DynamoDB and sends a real confirmation email." -ForegroundColor Yellow
Write-Host "Rows are removed afterwards by cleanup-phantoms.ps1. Keep the VU counts low." -ForegroundColor Yellow

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$body = '{"invoiceId":"NONEXISTENT-LOADTEST-W-' + $stamp +
    '","decision":"APPROVED","reviewer":"loadtest@zexxity.online","reason":"load test - safe phantom row, will be deleted"}'

Write-Host "`n=== T6: POST /invoices/review (bounded) ===" -ForegroundColor Cyan
Run-Scenario "POST /invoices/review t=1" $body 1 10
Run-Scenario "POST /invoices/review t=5" $body 5 10

Write-Host "`n=== Cleanup ===" -ForegroundColor Yellow
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
