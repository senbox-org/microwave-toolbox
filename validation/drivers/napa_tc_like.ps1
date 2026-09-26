<#
    Like-for-like Napa references for the ESA comparison deck (reviewer comment: the first version
    compared a Goldstein-FILTERED classical against an UNFILTERED single-look GSLC, so the
    difference was floor-limited by the filter, not by the chains).

    Two matched pairs are produced:
      unfiltered : classical napa_trad_ifg -> TC            vs  GSLC napa_gslc_ifg
      filtered   : classical napa_trad_ifg_flt_TC (exists)  vs  GSLC napa_gslc_ifg -> Goldstein

    Same TC parameters as napa_tc.ps1 (i/q carried through TC, never the wrapped Phase band).
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\napa'
Set-ParityLog "$D\tc_like.log"

$src   = Join-Path $D 'napa_trad_ifg.dim'
$tc    = Join-Path $D 'napa_trad_ifg_TC.dim'
$gslc  = Join-Path $D 'napa_gslc_ifg.dim'
$gflt  = Join-Path $D 'napa_gslc_ifg_flt.dim'
$pt    = Join-Path $D 'napa_tc_params.xml'
foreach ($f in @($src, $gslc, $pt)) { if (-not (Test-Path $f)) { Log "ABORT: $f missing"; exit 1 } }

$ok = Step 'napa-trad-tc-unfiltered' $tc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('Terrain-Correction', "-Ssource=$src",
        '-p', $pt, '-t', $tc, '-f', 'BEAM-DIMAP', '-q', '8') (Join-Path $D 'napa_trad_tc_unf.log') '16g' }
if (-not $ok) { Log 'ABORT napa-trad-tc-unfiltered'; exit 1 }

# GoldsteinFilterOp declares @SourceProduct with NO alias: the product is POSITIONAL.
$ok = Step 'napa-gslc-goldstein' $gflt {
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('GoldsteinPhaseFiltering',
        '-t', $gflt, '-f', 'BEAM-DIMAP', '-q', '8', $gslc) (Join-Path $D 'napa_gslc_flt.log') '16g' }
if (-not $ok) { Log 'ABORT napa-gslc-goldstein'; exit 1 }
Log "like-for-like references ready: $tc | $gflt"
