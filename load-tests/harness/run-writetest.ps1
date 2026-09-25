$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$base = "https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com"
$outFile = Join-Path $here "results-write.md"

Add-Type -Path (Join-Path $here "LoadHarness.cs") `
    -ReferencedAssemblies @("System.dll", "System.Core.dll", "System.Net.Http.dll") | Out-Null

# Must be List[ScenarioResult]: [Report]::Markdown takes IList<ScenarioResult> and
# PowerShell cannot convert a List[object] to it at runtime.
$results = New-Object System.Collections.Generic.List[ScenarioResult]

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

$generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss") + " IST"
# Single-quoted so the markdown backticks stay literal; a backtick inside a
# double-quoted PowerShell string is an escape character and gets eaten.
$notes = 'Write path: every request writes a phantom row to DynamoDB and sends a real confirmation email. ' +
         'Thread counts are deliberately capped at 5. Rows are removed by `cleanup-phantoms.ps1` afterwards.'

$md = [Report]::Markdown("Load test - write path", $base, $generatedAt, $results, $notes)
[IO.File]::WriteAllText($outFile, $md, (New-Object Text.UTF8Encoding($false)))
Write-Host "`nSAVED: $outFile" -ForegroundColor Green
