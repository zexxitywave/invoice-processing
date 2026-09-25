$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$profileName = "new3"
$region = "ap-south-1"
$table = "invoices"
$prefix = "NONEXISTENT-LOADTEST"

$common = @("dynamodb", "scan", "--table-name", $table, "--profile", $profileName, "--region", $region,
    "--projection-expression", "invoiceId", "--output", "json")

$ids = New-Object System.Collections.Generic.List[string]
$startKey = $null
do {
    $a = $common
    if ($startKey) { $a += @("--exclusive-start-key", $startKey) }
    $page = (& aws @a) | ConvertFrom-Json
    foreach ($i in $page.Items) { if ($i.invoiceId.S) { $ids.Add($i.invoiceId.S) } }
    $startKey = $page.LastEvaluatedKey
} while ($startKey)

$phantoms = @($ids | Where-Object { $_ -like "$prefix*" })
Write-Host "TOTAL_ROWS=$($ids.Count)"
Write-Host "PHANTOM_ROWS=$($phantoms.Count)"

if ($phantoms.Count -gt 0) {
    $keyFile = Join-Path $here "cleanup-key.json"
    $deleted = 0
    foreach ($p in $phantoms) {
        @{ invoiceId = @{ S = $p } } | ConvertTo-Json -Compress |
            Set-Content -LiteralPath $keyFile -Encoding ASCII
        $uri = "file://" + $keyFile.Replace('\', '/')
        & aws dynamodb delete-item --table-name $table --profile $profileName --region $region --key $uri --output json | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAILED to delete $p" -ForegroundColor Red
            continue
        }
        $deleted++
        Write-Host "  deleted $p"
    }
    Remove-Item -LiteralPath $keyFile -Force -ErrorAction SilentlyContinue
    Write-Host "CLEANED=$deleted of $($phantoms.Count)" -ForegroundColor $(if ($deleted -eq $phantoms.Count) { "Green" } else { "Red" })

    $remaining = & aws dynamodb scan --table-name $table --profile $profileName --region $region `
        --projection-expression "invoiceId" --output json | ConvertFrom-Json
    $left = @($remaining.Items | Where-Object { $_.invoiceId.S -like "$prefix*" })
    if ($left.Count -gt 0) {
        Write-Host "VERIFY FAILED: $($left.Count) phantom row(s) still present:" -ForegroundColor Red
        $left | ForEach-Object { Write-Host "  $($_.invoiceId.S)" -ForegroundColor Red }
    } else {
        Write-Host "VERIFIED: no phantom rows remain." -ForegroundColor Green
    }
} else {
    Write-Host "CLEANED=0 (nothing to remove)"
}
