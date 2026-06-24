# =============================================================================
# load-tests/run.ps1
#
# PowerShell runner for the Invoice Processing System JMeter load test suite.
# Finds jmeter.bat automatically, creates the results directory, runs the
# test plan, and generates the HTML dashboard report.
#
# Usage (run from project root):
#   .\load-tests\run.ps1
#   .\load-tests\run.ps1 -Profile smoke
#   .\load-tests\run.ps1 -Profile stress
#   .\load-tests\run.ps1 -Profile soak
#   .\load-tests\run.ps1 -JMeterHome "C:\apache-jmeter-5.6.3"
#   .\load-tests\run.ps1 -DryRun
#   .\load-tests\run.ps1 -BaseUrl "rw5n87lye8.execute-api.ap-south-1.amazonaws.com"
#   .\load-tests\run.ps1 -InvoiceIds "INV-001,INV-002,INV-003"
#   .\load-tests\run.ps1 -OpenReport
# =============================================================================

param (
    [ValidateSet('smoke','baseline','stress','soak')]
    [string]$Profile     = 'baseline',

    [string]$JMeterHome  = '',   # e.g. C:\apache-jmeter-5.6.3

    [string]$BaseUrl     = 'rw5n87lye8.execute-api.ap-south-1.amazonaws.com',

    [string]$ReviewerEmail = 'loadtest@zexxity.online',

    # Comma-separated real invoice IDs — written to data/invoice_ids.csv
    [string]$InvoiceIds  = '',

    [switch]$DryRun,

    [switch]$OpenReport
)

$ErrorActionPreference = 'Stop'

# ── Colour helpers ─────────────────────────────────────────────────────────────
function Write-Header ($msg) { Write-Host "`n$('='*64)`n  $msg`n$('='*64)" -ForegroundColor Cyan }
function Write-Step   ($msg) { Write-Host "  >> $msg" -ForegroundColor Yellow }
function Write-Ok     ($msg) { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Write-Warn   ($msg) { Write-Host "  [WARN] $msg" -ForegroundColor DarkYellow }
function Write-Fail   ($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red }
function Write-Info   ($msg) { Write-Host "  $msg" -ForegroundColor White }

# ── Paths ──────────────────────────────────────────────────────────────────────
$LoadTestDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ResultsDir  = Join-Path $LoadTestDir 'results'
$DataDir     = Join-Path $LoadTestDir 'data'
$JmxFile     = Join-Path $LoadTestDir 'invoice_full_suite.jmx'
$PropsFile   = Join-Path $LoadTestDir 'user.properties'
$Timestamp   = Get-Date -Format 'yyyyMMdd_HHmmss'
$JtlFile     = Join-Path $ResultsDir "all_results_${Profile}_${Timestamp}.jtl"
$HtmlDir     = Join-Path $ResultsDir "html_report_${Profile}_${Timestamp}"
$LogFile     = Join-Path $ResultsDir "jmeter_${Profile}_${Timestamp}.log"

# ── Ensure directories exist ───────────────────────────────────────────────────
foreach ($dir in @($ResultsDir, $DataDir)) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
}

# =============================================================================
Write-Header "INVOICE PROCESSING — JMETER LOAD TEST RUNNER"
# =============================================================================
Write-Info "Profile        : $Profile"
Write-Info "Base URL       : $BaseUrl"
Write-Info "Dry Run        : $(if ($DryRun) { 'YES (synthetic invoice IDs, no real SES writes)' } else { 'NO' })"
Write-Info "JTL output     : $JtlFile"
Write-Info "HTML report    : $HtmlDir"
Write-Info "JMeter log     : $LogFile"

# ── Step 1: Locate jmeter.bat ─────────────────────────────────────────────────
Write-Step "Locating jmeter.bat..."

$jmeterBat = $null

if ($JMeterHome -ne '') {
    $candidate = Join-Path $JMeterHome 'bin\jmeter.bat'
    if (Test-Path $candidate) { $jmeterBat = $candidate }
}

if (-not $jmeterBat) {
    # Try PATH
    $found = Get-Command 'jmeter.bat' -ErrorAction SilentlyContinue
    if ($found) { $jmeterBat = $found.Source }
}

if (-not $jmeterBat) {
    # Common install locations
    $candidates = @(
        'C:\apache-jmeter-5.6.3\bin\jmeter.bat',
        'C:\apache-jmeter-5.6\bin\jmeter.bat',
        'C:\apache-jmeter-5.5\bin\jmeter.bat',
        'C:\tools\apache-jmeter\bin\jmeter.bat',
        "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\bin\jmeter.bat",
        "$env:USERPROFILE\tools\apache-jmeter\bin\jmeter.bat"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $jmeterBat = $c; break }
    }
}

if (-not $jmeterBat) {
    Write-Fail "jmeter.bat not found."
    Write-Host ""
    Write-Host "  Fix options:" -ForegroundColor Cyan
    Write-Host "    1. Add JMeter bin/ to your PATH, then retry." -ForegroundColor White
    Write-Host "    2. Pass the install location explicitly:" -ForegroundColor White
    Write-Host "         .\load-tests\run.ps1 -JMeterHome 'C:\apache-jmeter-5.6.3'" -ForegroundColor White
    Write-Host "    3. Download JMeter: https://jmeter.apache.org/download_jmeter.cgi" -ForegroundColor White
    exit 1
}

Write-Ok "Found: $jmeterBat"

# ── Step 2: Write CSV data file if invoice IDs were supplied ──────────────────
$CsvFile = Join-Path $DataDir 'invoice_ids.csv'
if ($InvoiceIds -ne '') {
    Write-Step "Writing invoice ID pool to $CsvFile..."
    $InvoiceIds.Split(',') | ForEach-Object { $_.Trim() } | Set-Content $CsvFile -Encoding UTF8
    Write-Ok "Wrote $($InvoiceIds.Split(',').Count) invoice ID(s)"
} elseif (-not (Test-Path $CsvFile)) {
    # Create a placeholder so the CSVDataSet element doesn't error
    @('INV-TEST-001','INV-TEST-002','INV-TEST-003') | Set-Content $CsvFile -Encoding UTF8
    Write-Warn "No real invoice IDs supplied — using synthetic placeholders (GET-by-ID will return 404s, which is expected)"
}

# ── Step 3: Build thread-count overrides for the chosen profile ───────────────
Write-Step "Applying profile: $Profile"

$threadOverrides = switch ($Profile) {
    'smoke' {
        '-Jtg1_threads=1 -Jtg1_rampup=5  -Jtg1_duration=30 ' +
        '-Jtg2_threads=1 -Jtg2_rampup=5  -Jtg2_duration=30 ' +
        '-Jtg3_threads=1 -Jtg3_rampup=5  -Jtg3_duration=30 ' +
        '-Jtg4_threads=1 -Jtg4_rampup=5  -Jtg4_duration=30 ' +
        '-Jtg5_threads=1 -Jtg5_rampup=5  -Jtg5_duration=30 ' +
        '-Jtg6_threads=5 -Jtg6_rampup=5  -Jtg6_duration=20'
    }
    'baseline' {
        '-Jtg1_threads=10 -Jtg1_rampup=30 -Jtg1_duration=120 ' +
        '-Jtg2_threads=10 -Jtg2_rampup=30 -Jtg2_duration=120 ' +
        '-Jtg3_threads=15 -Jtg3_rampup=20 -Jtg3_duration=120 ' +
        '-Jtg4_threads=5  -Jtg4_rampup=30 -Jtg4_duration=120 ' +
        '-Jtg5_threads=8  -Jtg5_rampup=30 -Jtg5_duration=120 ' +
        '-Jtg6_threads=30 -Jtg6_rampup=10 -Jtg6_duration=60'
    }
    'stress' {
        '-Jtg1_threads=50 -Jtg1_rampup=60 -Jtg1_duration=300 ' +
        '-Jtg2_threads=50 -Jtg2_rampup=60 -Jtg2_duration=300 ' +
        '-Jtg3_threads=75 -Jtg3_rampup=60 -Jtg3_duration=300 ' +
        '-Jtg4_threads=20 -Jtg4_rampup=60 -Jtg4_duration=300 ' +
        '-Jtg5_threads=35 -Jtg5_rampup=60 -Jtg5_duration=300 ' +
        '-Jtg6_threads=100 -Jtg6_rampup=10 -Jtg6_duration=60'
    }
    'soak' {
        '-Jtg1_threads=8  -Jtg1_rampup=60 -Jtg1_duration=600 ' +
        '-Jtg2_threads=8  -Jtg2_rampup=60 -Jtg2_duration=600 ' +
        '-Jtg3_threads=10 -Jtg3_rampup=60 -Jtg3_duration=600 ' +
        '-Jtg4_threads=4  -Jtg4_rampup=60 -Jtg4_duration=600 ' +
        '-Jtg5_threads=6  -Jtg5_rampup=60 -Jtg5_duration=600 ' +
        '-Jtg6_threads=10 -Jtg6_rampup=30 -Jtg6_duration=60'
    }
}

# ── Step 4: Delete any stale results/html from a previous run ─────────────────
if (Test-Path $HtmlDir) { Remove-Item -Recurse -Force $HtmlDir }

# ── Step 5: Run JMeter ────────────────────────────────────────────────────────
Write-Header "RUNNING JMETER (NON-GUI MODE)"
Write-Info "Test plan : $JmxFile"
Write-Info "Profile   : $Profile"
Write-Host ""

$dryRunFlag  = if ($DryRun) { '-Jdry_run=true' } else { '-Jdry_run=false' }
$overrideArr = ($threadOverrides -split '\s+') | Where-Object { $_ -ne '' }

$jmeterArgs = @(
    '-n',                              # non-GUI mode
    '-t', $JmxFile,                    # test plan
    '-q', $PropsFile,                  # user.properties
    '-l', $JtlFile,                    # JTL output
    '-j', $LogFile,                    # JMeter log
    "-Jbase_url=$BaseUrl",
    "-Jreviewer_email=$ReviewerEmail",
    $dryRunFlag
) + $overrideArr

$StartTime = Get-Date
Write-Info "Start time : $StartTime"
Write-Host ""

& $jmeterBat @jmeterArgs
$exitCode = $LASTEXITCODE
$EndTime  = Get-Date
$Duration = [math]::Round(($EndTime - $StartTime).TotalSeconds, 1)

Write-Host ""
Write-Header "TEST COMPLETE"
Write-Info "Duration   : ${Duration}s"
Write-Info "Exit code  : $exitCode"

# ── Step 6: Generate HTML dashboard report ────────────────────────────────────
Write-Step "Generating HTML dashboard report..."

if (Test-Path $JtlFile) {
    $reportArgs = @(
        '-g', $JtlFile,           # input JTL
        '-o', $HtmlDir,           # output HTML directory
        '-j', $LogFile,           # append to same log
        '-q', $PropsFile
    )

    & $jmeterBat @reportArgs
    $reportExit = $LASTEXITCODE

    if ($reportExit -eq 0 -and (Test-Path (Join-Path $HtmlDir 'index.html'))) {
        Write-Ok "HTML report: $HtmlDir\index.html"
    } else {
        Write-Warn "HTML report generation returned exit code $reportExit — check $LogFile"
    }
} else {
    Write-Warn "JTL file not found ($JtlFile) — skipping HTML report generation."
}

# ── Step 7: Print quick summary from JTL ─────────────────────────────────────
Write-Step "Parsing JTL for quick summary..."
if (Test-Path $JtlFile) {
    Print-JtlSummary $JtlFile
}

# ── Step 8: Open HTML report ───────────────────────────────────────────────────
$indexHtml = Join-Path $HtmlDir 'index.html'
if ($OpenReport -and (Test-Path $indexHtml)) {
    Write-Step "Opening HTML report in browser..."
    Start-Process $indexHtml
}

Write-Host ""
if ($exitCode -eq 0) {
    Write-Ok "JMeter run finished. Review HTML report: $HtmlDir\index.html"
} else {
    Write-Fail "JMeter exited with code $exitCode. Check log: $LogFile"
}

Write-Host ""
exit $exitCode

# =============================================================================
# FUNCTIONS
# =============================================================================

function Print-JtlSummary($jtlPath) {
    try {
        $lines = Import-Csv $jtlPath

        if ($lines.Count -eq 0) {
            Write-Warn "JTL file is empty."
            return
        }

        $total    = $lines.Count
        $errors   = ($lines | Where-Object { $_.success -eq 'false' }).Count
        $errorPct = [math]::Round(($errors / $total) * 100, 2)

        $durations = $lines | Where-Object { $_.elapsed -ne '' } |
                     ForEach-Object { [int]$_.elapsed } | Sort-Object

        $avg  = [math]::Round(($durations | Measure-Object -Average).Average, 0)
        $min  = ($durations | Measure-Object -Minimum).Minimum
        $max  = ($durations | Measure-Object -Maximum).Maximum
        $p50  = $durations[[math]::Floor($durations.Count * 0.50)]
        $p90  = $durations[[math]::Floor($durations.Count * 0.90)]
        $p95  = $durations[[math]::Floor($durations.Count * 0.95)]
        $p99  = $durations[[math]::Floor($durations.Count * 0.99)]

        # Throughput: requests per second
        $timestamps = $lines | Where-Object { $_.timeStamp -ne '' } |
                      ForEach-Object { [long]$_.timeStamp } | Sort-Object
        $testDurationMs = $timestamps[-1] - $timestamps[0]
        $rps = if ($testDurationMs -gt 0) {
            [math]::Round($total / ($testDurationMs / 1000.0), 2)
        } else { 0 }

        # Per-label breakdown
        $labels = $lines | Group-Object -Property label | Sort-Object Name

        Write-Host ""
        Write-Host "  ┌─────────────────────────────────────────────────────────────┐" -ForegroundColor Cyan
        Write-Host "  │           LOAD TEST QUICK SUMMARY                           │" -ForegroundColor Cyan
        Write-Host "  ├─────────────────────────────────────────────────────────────┤" -ForegroundColor Cyan
        Write-Host ("  │  Total Requests   : {0,-43}│" -f $total) -ForegroundColor White
        Write-Host ("  │  Errors           : {0,-43}│" -f "$errors ($errorPct%)") -ForegroundColor $(if ($errors -gt 0) { 'Red' } else { 'Green' })
        Write-Host ("  │  Throughput       : {0,-43}│" -f "$rps req/s") -ForegroundColor White
        Write-Host ("  │  Avg Latency      : {0,-43}│" -f "${avg} ms") -ForegroundColor White
        Write-Host ("  │  Min Latency      : {0,-43}│" -f "${min} ms") -ForegroundColor White
        Write-Host ("  │  Max Latency      : {0,-43}│" -f "${max} ms") -ForegroundColor White
        Write-Host ("  │  p50 Latency      : {0,-43}│" -f "${p50} ms") -ForegroundColor White
        Write-Host ("  │  p90 Latency      : {0,-43}│" -f "${p90} ms") -ForegroundColor White
        Write-Host ("  │  p95 Latency      : {0,-43}│" -f "${p95} ms") -ForegroundColor $(if ($p95 -gt 3000) { 'Red' } elseif ($p95 -gt 2000) { 'DarkYellow' } else { 'Green' })
        Write-Host ("  │  p99 Latency      : {0,-43}│" -f "${p99} ms") -ForegroundColor $(if ($p99 -gt 6000) { 'Red' } elseif ($p99 -gt 4000) { 'DarkYellow' } else { 'Green' })
        Write-Host "  ├─────────────────────────────────────────────────────────────┤" -ForegroundColor Cyan
        Write-Host "  │  Per-Endpoint Breakdown                                     │" -ForegroundColor Cyan
        Write-Host "  ├────────────────────────────────┬──────┬───────┬──────┬──────┤" -ForegroundColor Cyan
        Write-Host "  │ Label                          │  #   │ Err%  │ Avg  │ p95  │" -ForegroundColor Cyan
        Write-Host "  ├────────────────────────────────┼──────┼───────┼──────┼──────┤" -ForegroundColor Cyan

        foreach ($grp in $labels) {
            $lbl     = $grp.Name.PadRight(30).Substring(0, [Math]::Min(30, $grp.Name.Length)).PadRight(30)
            $cnt     = $grp.Count
            $lErrs   = ($grp.Group | Where-Object { $_.success -eq 'false' }).Count
            $lErrPct = [math]::Round(($lErrs / $cnt) * 100, 1)
            $lDurs   = $grp.Group | Where-Object { $_.elapsed -ne '' } |
                       ForEach-Object { [int]$_.elapsed } | Sort-Object
            $lAvg    = [math]::Round(($lDurs | Measure-Object -Average).Average, 0)
            $lP95Idx = [math]::Floor($lDurs.Count * 0.95)
            $lP95    = if ($lDurs.Count -gt 0) { $lDurs[$lP95Idx] } else { 0 }

            $color = if ($lErrPct -gt 5) { 'Red' } elseif ($lErrPct -gt 0) { 'DarkYellow' } else { 'White' }
            Write-Host ("  │ {0} │{1,5} │{2,6}% │{3,5}ms│{4,5}ms│" -f $lbl, $cnt, $lErrPct, $lAvg, $lP95) -ForegroundColor $color
        }

        Write-Host "  └────────────────────────────────┴──────┴───────┴──────┴──────┘" -ForegroundColor Cyan
        Write-Host ""

    } catch {
        Write-Warn "Could not parse JTL: $_"
    }
}
