# Converts the archived JSON results into the Markdown report format.
# One-off migration helper: run once to regenerate the committed .md files from
# the historical .json captures, then the .json files are no longer needed.
#
#   .\results-to-markdown.ps1
#
$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$base = "https://xei4kla8v8.execute-api.ap-south-1.amazonaws.com"

Add-Type -Path (Join-Path $here "LoadHarness.cs") `
    -ReferencedAssemblies @("System.dll", "System.Core.dll", "System.Net.Http.dll") | Out-Null

function Convert-Results {
    param($jsonName, $mdName, $title, $notes, $generatedAt)

    $jsonPath = Join-Path $here $jsonName
    if (-not (Test-Path $jsonPath)) { Write-Host "  skip $jsonName (not present)"; return }

    $raw = Get-Content -LiteralPath $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json

    $list = New-Object System.Collections.Generic.List[ScenarioResult]
    foreach ($row in $raw) {
        $r = New-Object ScenarioResult
        $r.Label           = $row.label
        $r.Threads         = [int]$row.threads
        $r.DurationSec     = [int]$row.durationSec
        $r.Total           = [int]$row.total
        $r.HttpErrors      = [int]$row.httpErrors
        $r.TransportErrors = [int]$row.transportErrors
        $r.Throughput      = [double]$row.throughput
        $r.Avg             = [double]$row.avg
        $r.Min             = [double]$row.min
        $r.Max             = [double]$row.max
        $r.P50             = [double]$row.p50
        $r.P90             = [double]$row.p90
        $r.P95             = [double]$row.p95
        $r.P99             = [double]$row.p99
        $r.ErrorPct        = [double]$row.errorPct
        $r.FirstError      = $row.firstError
        foreach ($p in $row.statusCodes.PSObject.Properties) {
            $r.StatusCodes[[int]$p.Name] = [int]$p.Value
        }
        $list.Add($r) | Out-Null
    }

    $mdPath = Join-Path $here $mdName
    $md = [Report]::Markdown($title, $base, $generatedAt, $list, $notes)
    [IO.File]::WriteAllText($mdPath, $md, (New-Object Text.UTF8Encoding($false)))
    Write-Host ("  wrote {0} ({1} scenarios, {2} bytes)" -f $mdName, $list.Count, (Get-Item $mdPath).Length) -ForegroundColor Green
}

# The archived captures carry no recorded wall-clock time, so the conversion
# timestamp is stated explicitly rather than implying the run happened now.
$stamp = "archived capture (converted to Markdown $(Get-Date -Format 'yyyy-MM-dd'))"

# Single-quoted so the markdown backticks stay literal; PowerShell treats a
# backtick inside a double-quoted string as an escape character.
$readNotes = 'Read-path only: no invoice rows are created, except T4 which hits the approve endpoint with a ' +
             'synthetic token and writes one phantom row per request. Those rows are deleted by ' +
             '`cleanup-phantoms.ps1` immediately after T5.'

$writeNotes = 'Write path: every request writes a phantom row to DynamoDB and sends a real confirmation email. ' +
              'Thread counts are deliberately capped at 5. Rows are removed by `cleanup-phantoms.ps1` afterwards.'

Write-Host "Converting archived JSON results to Markdown..." -ForegroundColor Cyan

Convert-Results "results-read.json" "results-read.md" "Load test - read path" $readNotes $stamp
Convert-Results "results-write.json" "results-write.md" "Load test - write path" $writeNotes $stamp

Write-Host "done" -ForegroundColor Green
