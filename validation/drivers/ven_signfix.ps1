<#
    CONTROLLED EXPERIMENT: rebuild the ETAD-on S1A x S1C GSLC interferogram from the EXISTING stack with the
    sign of the carrier-difference add-back flipped (-Dgslc.carrierDiffSign=-1, an experimental default-off
    switch in InterferogramOp).

      default : residual-ramp OFF (the spec's R5 configuration)  -> ven_etadD_ifg_signfix.dim

    Cell-level analysis of the saved products predicted that the flip makes the GSLC interferogram agree with
    the classical one (phase-only concentration 0.001 -> 0.900, R5b gy ratio 37.9 -> 0.77). This builds it for
    real. Measured: concentration 0.961, gx ratio 0.87, gy ratio 0.86 - all four R5b gates pass.

    Needs E:\Output\parity\ven\ven_etadD_stack.dim (built by ven_gslc.ps1 -Diag).
#>
param([switch]$Legacy)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
$tag = 'signfix'
if ($Legacy) { $tag += '_legacy' }   # -Legacy reproduces the pre-2026-09-21 sign (+1); the default is now -1
Set-ParityLog "$D\$tag.log"
$stack = "$D\ven_etadD_stack.dim"
$out   = "$D\ven_etadD_ifg_$tag.dim"
if (-not (Test-Path $stack)) { Log "ABORT: $stack missing"; exit 1 }
$pi = New-ParamFile "$D\ven_etadD_ifg_$tag`_params.xml" ([ordered]@{
    subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = 'External DEM';
    externalDEMFile = $script:DEM; externalDEMNoDataValue = '0.0'; includeCoherence = 'true';
    cohWinSizeMeters = '100' })
$ok = Step "$tag-ifg" $out {
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi, '-t', $out, '-f', 'BEAM-DIMAP', '-q', '8', $stack) `
        "$D\ven_etadD_ifg_$tag.log" '24g' $(if ($Legacy) { '-Dgslc.carrierDiffSign=+1' } else { '-Dgslc.carrierDiffSign=-1' }) }
if (-not $ok) { Log "ABORT $tag-ifg"; exit 1 }
$need = @('exact deramp-model subtraction active')
if ($Legacy) { $need += 'LEGACY add-back sign in use' }
if (-not (Assert-Log "$D\ven_etadD_ifg_$tag.log" $need @('OutOfMemoryError|NullPointerException'))) {
    Log 'FAIL: the run did not report the expected add-back sign'; exit 1
}
Log "sign-flipped interferogram complete: $out"
