<#
    CROP-VALIDITY GATE (spec section 5, Rule 3).

    Question: does a 3-burst TOPSAR-Split fixture reproduce the gate values of the full
    scene? If yes, the fast fixture is BLESSED and every later fast number is trustworthy.
    If no, we have caught the failure before the campaign is built on it.

    This exists because a range-cropped Etna fixture once drove classical coherence from
    0.367 to 0.088 - collapsing both pipelines to the estimator floor - while a sane ESD
    residual (~0.03 px) hid the cause, and two days of work were spent on numbers that
    were measuring the crop.

    REFERENCE: ..._Stack_ifg_deb_flt.dim - the DEBURSTED RADAR-GEOMETRY product, which
    carries i_ifg/q_ifg. NOT the _TC.dim, which declares only Phase_ + coh and has an
    undeclared orphan Intensity_*.img in its .data dir.

    CAVEAT: the control stack is 10 S1A bursts with a 9-burst S1C resampled onto it, so
    BURST 10 HAS NO SECONDARY DATA. Any full-scene statistic must exclude it. Bursts 4-6
    are unaffected.
#>
$ErrorActionPreference = 'Continue'
$OUT = 'E:\Output\parity\cropgate'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$verdict = Join-Path $OUT 'verdict.txt'

$TRAD_FULL = 'E:\Output\trad\S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt.dim'

"CROP-VALIDITY GATE" | Set-Content $verdict
"reference (full scene, radar geometry): $TRAD_FULL" | Add-Content $verdict
"burst 10 excluded: the 10-burst S1A stack carries a 9-burst S1C secondary" | Add-Content $verdict
""  | Add-Content $verdict

if (-not (Test-Path $TRAD_FULL)) {
    "ABORT: reference product not found." | Add-Content $verdict
    Write-Host "ABORT: $TRAD_FULL not found"
    exit 1
}
Write-Host "Reference present. Per-source GSLC and 3-burst chains land in Plan 2."
"STATUS: reference verified present; fast/full comparison runs in Plan 2 once the" | Add-Content $verdict
"        Venezuela GSLC chain exists. This driver records the contract and the caveats." | Add-Content $verdict
Get-Content $verdict
