<#
    Campi Flegrei: GSLC and classical chains for one pair, like for like (same fixtures, same auto
    Copernicus 30 m DEM, same 100 m coherence window, no filtering, no ETAD on either side).

      -Pair 72d   20230809 -> 20231020   (P-SBAS peak LOS ~5.2 cm)
      -Pair 1yr   20221013 -> 20231020   (P-SBAS peak LOS ~17.7 cm; the Olibano-Accademia anomaly)

    GSLC:       CreateStack AUTO path (reference GSLC + RAW secondary: grid lock, burst-ID lock, CC bias),
                with -Dgslc.diagGeometry=true so the resample-free radar-cell binning of
                esa_chain_comparison.py is available, as on Venezuela.
    Classical:  Back-Geocoding + ESD -> Interferogram + Deburst (radar) -> Terrain-Correction (map), the
                last carrying i/q as complex (never the wrapped Phase band).

    Products in E:\Output\parity\cf:  cf_<pair>_gslc / _stack / _ifg,  cf_<pair>_trad_stack / _ifg_deb / _ifg_TC
#>
param([Parameter(Mandatory = $true)][ValidateSet('72d', '1yr')][string]$Pair)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\cf'
Set-ParityLog "$D\chains_$Pair.log"

$refDate = @{ '72d' = '20230809'; '1yr' = '20221013' }[$Pair]
$Ref = (Get-ChildItem $D -Filter "S1A_IW_SLC__1SDV_$refDate*_cf_orb.dim" | Select-Object -First 1).FullName
$Sec = (Get-ChildItem $D -Filter 'S1A_IW_SLC__1SDV_20231020*_cf_orb.dim' | Select-Object -First 1).FullName
if (-not $Ref -or -not $Sec) { Log 'ABORT: fixtures missing - run cf_fixture.ps1'; exit 1 }
Log "pair $Pair  ref $Ref"
Log "pair $Pair  sec $Sec"
$T = "cf_$Pair"
$DEMNAME = 'Copernicus 30m Global DEM'

# ---------------------------------------------------------------- GSLC chain
$gslc  = "$D\$T`_gslc.dim"
$stack = "$D\$T`_stack.dim"
$ifg   = "$D\$T`_ifg.dim"
$pg = New-ParamFile "$D\$T`_gslc_params.xml" ([ordered]@{
    demName = $DEMNAME; imgResamplingMethod = 'BISINC_5_POINT_INTERPOLATION'; gridSpacing = 'NATIVE_ANISOTROPIC';
    mapProjection = 'WGS84(DD)'; outputFlattened = 'false'; outputAzimuthCarrier = 'false';
    outputPhaseTerms = 'true'; nodataValueAtSea = 'false' })
$pi = New-ParamFile "$D\$T`_ifg_params.xml" ([ordered]@{
    subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = $DEMNAME;
    includeCoherence = 'true'; cohWinSizeMeters = '100' })
$diag = '-Dgslc.diagGeometry=true'

$ok = Step "$T-gslc" $gslc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('GSLC-Terrain-Correction', "-Ssource=$Ref", '-p', $pg,
        '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8') "$D\$T`_gslc.log" '12g' $diag }
if (-not $ok) { Log "ABORT $T-gslc"; exit 1 }

$ok = Step "$T-stack" $stack {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('CreateStack', '-Pextent=Master', '-t', $stack,
        '-f', 'BEAM-DIMAP', '-q', '8', $gslc, $Sec) "$D\$T`_stack.log" '12g' $diag }
if (-not $ok) { Log "ABORT $T-stack"; exit 1 }
# every seam matched: "(0 of 1 seam(s)" is a degraded lock and must not pass
if (-not (Assert-Log "$D\$T`_stack.log" @('locked to the reference \(([1-9]\d*) of \1 seam') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $T`: burst-overlap lock did not engage"; exit 1
}

$ok = Step "$T-ifg" $ifg {
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi, '-t', $ifg,
        '-f', 'BEAM-DIMAP', '-q', '8', $stack) "$D\$T`_ifg.log" '12g' }
if (-not $ok) { Log "ABORT $T-ifg"; exit 1 }
# the window's REAL ground footprint, from the product's own grid step (a regex on cohWinRg=\d+ passed
# the ~76 m GSLC window before the 2026-09-26 range_spacing fix)
& python "$PSScriptRoot\check_cohwin.py" "$D\$T`_ifg.log" --dim $ifg | Out-Host
if ($LASTEXITCODE -ne 0) { Log "FAIL $T`: GSLC coherence window is not ~100 m on the ground"; exit 1 }
Log "GSLC chain $T complete: $ifg"

# ---------------------------------------------------------------- classical chain
$tstack = "$D\$T`_trad_stack.dim"
$tifg   = "$D\$T`_trad_ifg_deb.dim"
$ttc    = "$D\$T`_trad_ifg_TC.dim"
$ok = Step "$T-trad-coreg" $tstack {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_coreg_cf.xml', "-Pinput1=$Ref",
        "-Pinput2=$Sec", '-Presampling=BISINC_5_POINT_INTERPOLATION', "-Poutput=$tstack") "$D\$T`_trad_coreg.log" '12g' }
if (-not $ok) { Log "ABORT $T-trad-coreg"; exit 1 }

$ok = Step "$T-trad-ifg" $tifg {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifgdeb_cf.xml', "-Pinput1=$tstack",
        "-Poutput=$tifg") "$D\$T`_trad_ifg.log" '12g' }
if (-not $ok) { Log "ABORT $T-trad-ifg"; exit 1 }
& python "$PSScriptRoot\check_cohwin.py" "$D\$T`_trad_ifg.log" | Out-Host
if ($LASTEXITCODE -ne 0) { Log "FAIL $T`: classical coherence window is not ~100 m on the ground"; exit 1 }

$pt = New-ParamFile "$D\$T`_tc_params.xml" ([ordered]@{
    demName = $DEMNAME; imgResamplingMethod = 'BILINEAR_INTERPOLATION'; mapProjection = 'WGS84(DD)';
    nodataValueAtSea = 'false'; saveDEM = 'false' })
$ok = Step "$T-trad-tc" $ttc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('Terrain-Correction', "-Ssource=$tifg", '-p', $pt,
        '-t', $ttc, '-f', 'BEAM-DIMAP', '-q', '8') "$D\$T`_trad_tc.log" '12g' }
if (-not $ok) { Log "ABORT $T-trad-tc"; exit 1 }
# TC must have carried the interferogram as COMPLEX i/q (never bilinearly resampled wrapped phase)
if (-not (Assert-Log "$D\$T`_trad_tc.log" @('geocoding complex data as complex') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $T`: Terrain-Correction did not geocode the interferogram as complex"; exit 1
}
Log "classical chain $T complete: $tifg | $ttc"
