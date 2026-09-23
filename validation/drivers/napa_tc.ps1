<#
    Terrain-correct the Napa CLASSICAL interferogram so it can be compared with the GSLC one on a
    common map grid (rung R5, scored by validation/gslc_parity/chain_compare.py).

    Goldstein first, matching how the ERS Etna and ASAR Bam classical references were prepared -
    otherwise Napa's number would not sit in the same column as theirs.

    The i/q bands are carried through TC, not the Phase band: terrain correction RESAMPLES, and
    interpolating a WRAPPED phase raster mixes values across the +-pi cut and manufactures
    artefacts at every fringe boundary. Real and imaginary parts interpolate correctly, and the
    phase is recomputed from them afterwards.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\napa'
Set-ParityLog "$D\tc.log"

$src = Join-Path $D 'napa_trad_ifg.dim'
$flt = Join-Path $D 'napa_trad_ifg_flt.dim'
$tc  = Join-Path $D 'napa_trad_ifg_flt_TC.dim'
if (-not (Test-Path $src)) { Log "ABORT: $src missing"; exit 1 }

$ok = Step 'napa-trad-goldstein' $flt {
    # GoldsteinFilterOp declares @SourceProduct with NO alias, so the product is positional -
    # '-Ssource=' is silently not bound and the operator fails on a missing mandatory source.
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('GoldsteinPhaseFiltering',
        '-t', $flt, '-f', 'BEAM-DIMAP', '-q', '8', $src) (Join-Path $D 'napa_trad_flt.log') '16g' }
if (-not $ok) { Log 'ABORT napa-trad-goldstein'; exit 1 }

$pt = New-ParamFile (Join-Path $D 'napa_tc_params.xml') ([ordered]@{
    demName = 'Copernicus 30m Global DEM';
    imgResamplingMethod = 'BILINEAR_INTERPOLATION';
    mapProjection = 'WGS84(DD)';
    nodataValueAtSea = 'false'; saveDEM = 'false' })

$ok = Step 'napa-trad-tc' $tc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('Terrain-Correction', "-Ssource=$flt",
        '-p', $pt, '-t', $tc, '-f', 'BEAM-DIMAP', '-q', '8') (Join-Path $D 'napa_trad_tc.log') '16g' }
if (-not $ok) { Log 'ABORT napa-trad-tc'; exit 1 }
Log "classical reference on a map grid: $tc"
