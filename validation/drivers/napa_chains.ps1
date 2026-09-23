<#
    S1 Napa stripmap: both chains on the fixtures from napa_fixture.ps1.

    CLASSICAL CONTROL IS NAMED EXPLICITLY (spec C3: the two controls are not interchangeable):
    Napa uses CreateStack -> Cross-Correlation -> Warp -> Interferogram, i.e. CC+Warp, the same
    control as validation/graphs/trad_ers_control.xml. NOT DEM-Assisted-Coregistration.

    Both chains use the SAME DEM (Copernicus 30 m, auto) and the same ground-corrected coherence
    window, so neither can differ by elevation source or by multilook support.

    Fixture is the EPICENTRE BOX (26.8 x 21.8 km), a deliberate deviation from spec Rule 2 - see
    napa_fixture.ps1. R5 numbers from it are less representative in range than Venezuela's.

    GSLC chain   A(GSLC) + raw B through the CreateStack AUTO path (the stripmap bias estimate is
                 the thing under test here, so the both-GSLC path is not used).
    subtractSeamSteps stays OFF - stripmap has no burst seams, and every headline result is
    taken with it off.

    Products in E:\Output\parity\napa:
      napa_gslc.dim        napa_gslc_stack.dim   napa_gslc_ifg.dim
      napa_trad_warp.dim   napa_trad_ifg.dim
#>
param([switch]$SkipClassical, [switch]$SkipGslc)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\napa'
Set-ParityLog "$D\chains.log"

$A = (Get-ChildItem $D -Filter 'S1A_S1_SLC*20140807*_epi_orb.dim' | Select-Object -First 1).FullName
$B = (Get-ChildItem $D -Filter 'S1A_S1_SLC*20140831*_epi_orb.dim' | Select-Object -First 1).FullName
if (-not $A -or -not $B) { Log 'ABORT: fixtures missing - run napa_fixture.ps1'; exit 1 }
Log "leg A $A"
Log "leg B $B"

$DEMNAME = 'Copernicus 30m Global DEM'

# ---------------------------------------------------------------- GSLC chain
if (-not $SkipGslc) {
    $gslc  = Join-Path $D 'napa_gslc.dim'
    $stack = Join-Path $D 'napa_gslc_stack.dim'
    $ifg   = Join-Path $D 'napa_gslc_ifg.dim'

    $pg = New-ParamFile (Join-Path $D 'napa_gslc_params.xml') ([ordered]@{
        demName = $DEMNAME; imgResamplingMethod = 'BISINC_5_POINT_INTERPOLATION';
        gridSpacing = 'NATIVE_ANISOTROPIC'; mapProjection = 'WGS84(DD)';
        outputFlattened = 'false'; outputAzimuthCarrier = 'false'; outputPhaseTerms = 'true';
        nodataValueAtSea = 'false' })

    $logGslc = Join-Path $D 'napa_gslc.log'
    $ok = Step 'napa-gslc' $gslc {
        Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('GSLC-Terrain-Correction', "-Ssource=$A",
            '-p', $pg, '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8') $logGslc '24g' }
    if (-not $ok) { Log 'ABORT napa-gslc'; exit 1 }
    # Rule 2 / G4: below 1539 lines the data-driven Doppler estimator silently disengages and the
    # fixture stops being the same experiment as the full scene. Prove which table was used.
    if (-not (Assert-Log $logGslc @('residual Doppler centroid built from \d+ coefficient') `
            @('OutOfMemoryError|NullPointerException'))) {
        Log 'FAIL napa-gslc: no stripmap Doppler-centroid line - not the deramp path under test'; exit 1
    }

    $logStack = Join-Path $D 'napa_gslc_stack.log'
    $ok = Step 'napa-gslc-stack' $stack {
        Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('CreateStack', '-Pextent=Master', '-t', $stack,
            '-f', 'BEAM-DIMAP', '-q', '8', $gslc, $B) $logStack '24g' }
    if (-not $ok) { Log 'ABORT napa-gslc-stack'; exit 1 }
    # Bias-estimation failure logs a warning and KEEPS ZERO BIAS, so a run can complete having
    # done nothing (spec, Bam constraint - same code path here). Assert the estimate exists.
    if (-not (Assert-Log $logStack @('bias for slave') @('OutOfMemoryError|NullPointerException'))) {
        Log 'FAIL napa-gslc-stack: no CC bias estimate - the stack may carry zero bias'; exit 1
    }

    $pi = New-ParamFile (Join-Path $D 'napa_ifg_params.xml') ([ordered]@{
        subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = $DEMNAME;
        includeCoherence = 'true'; cohWinSizeMeters = '100' })
    $logIfg = Join-Path $D 'napa_gslc_ifg.log'
    $ok = Step 'napa-gslc-ifg' $ifg {
        Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi, '-t', $ifg,
            '-f', 'BEAM-DIMAP', '-q', '8', $stack) $logIfg }
    if (-not $ok) { Log 'ABORT napa-gslc-ifg'; exit 1 }
    if (-not (Assert-Log $logIfg @('cohWinSizeMeters=100\.0 m -> cohWinAz=\d+, cohWinRg=\d+') `
            @('OutOfMemoryError|NullPointerException'))) {
        Log 'FAIL napa-gslc-ifg: metre-based coherence window did not engage'; exit 1
    }
    Log "GSLC chain complete: $ifg"
}

# ------------------------------------------------------------ classical CC+Warp control
if (-not $SkipClassical) {
    $twarp = Join-Path $D 'napa_trad_warp.dim'
    $tifg  = Join-Path $D 'napa_trad_ifg.dim'
    $logWarp = Join-Path $D 'napa_trad_warp.log'

    # CreateStack + Cross-Correlation + Warp MUST be ONE gpt invocation: GCPManager is an
    # in-memory singleton, so writing the stack to disk and re-reading it for Cross-Correlation
    # loses the GCPs silently and Warp then writes a ZEROED product. See the graph's own header.
    $ok = Step 'napa-trad-warp' $twarp {
        Invoke-MvnGpt 'sar-op-insar' 'compile' @('validation/graphs/trad_sm_ccwarp.xml',
            "-Pinput1=$A", "-Pinput2=$B", "-Poutput=$twarp") $logWarp '24g' }
    if (-not $ok) { Log 'ABORT napa-trad-warp'; exit 1 }
    # A Warp that found no GCPs still writes a product, so prove the registration happened.
    if (-not (Assert-Log $logWarp @('RMS|GCP') @('OutOfMemoryError|NullPointerException'))) {
        Log 'FAIL napa-trad-warp: no GCP/RMS evidence - the warp may be degenerate'; exit 1
    }

    $pi2 = New-ParamFile (Join-Path $D 'napa_trad_ifg_params.xml') ([ordered]@{
        subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = $DEMNAME;
        includeCoherence = 'true'; cohWinSizeMeters = '100' })
    $logTifg = Join-Path $D 'napa_trad_ifg.log'
    $ok = Step 'napa-trad-ifg' $tifg {
        Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi2, '-t', $tifg,
            '-f', 'BEAM-DIMAP', '-q', '8', $twarp) $logTifg }
    if (-not $ok) { Log 'ABORT napa-trad-ifg'; exit 1 }
    Log "classical CC+Warp control complete: $tifg"
}
Log 'napa chains complete'
