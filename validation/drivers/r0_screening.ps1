<#
    R0 screening sweep. MEASUREMENT ONLY - no gates, no pass/fail.

    Purpose: establish where we actually stand before building anything on a number.
    The campaign has never been run on an easy pair, and the acceptance harness has been
    executed against real data exactly once, on a product already known to be broken.

    Runs ONE heavy job at a time. Each source is torn down before the next begins:
    ~294 GB free against a ~360 GB campaign (spec section 5, Rule 5).

    ASCII only. Every -P argument quoted (an unquoted -Pfoo=1.25E-4 gets token-split).
#>
$ErrorActionPreference = 'Continue'
$ROOT = 'E:\ESA\microwave-toolbox'
$OUT  = 'E:\Output\parity\r0'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$summary = Join-Path $OUT 'summary.csv'
'source,mode,stage,metric,value,notes' | Set-Content $summary

function Log($m) {
    $l = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $l
    $l | Add-Content (Join-Path $OUT 'r0.log')
}

# Sources are listed in Tier D order. PAZ and NISAR are Tier L and are NOT in this sweep.
$sources = @(
    @{ name='venezuela'; mode='TOPS';     note='IW3 bursts 4-6, burstId-matched' },
    @{ name='napa';      mode='stripmap'; note='per-leg azimuth windows, 16809-line offset' },
    @{ name='bam';       mode='stripmap'; note='auto-path stack mandatory, ~8px cross-PAC' }
)

foreach ($s in $sources) {
    Log "=== $($s.name) ($($s.mode)) - $($s.note)"
    # notes contain commas -> quote the field (RFC 4180) or the row has too many columns
    $note = '"' + ($s.note -replace '"', '""') + '"'
    "$($s.name),$($s.mode),screening,status,pending,$note" | Add-Content $summary
}
Log "R0 scaffold written to $summary. Per-source chains are added in Plan 2."
