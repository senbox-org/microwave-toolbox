<#
    2x2 experiment: {no ETAD, ETAD} x {SQUARE_COARSEST, NATIVE_ANISOTROPIC}
    Venezuela IW3 VV, S1A/S1C, BURSTS 4-6 only.

    Bursts 4-6 in BOTH products cover identical ground: the two IW3 stacks align 1:1 for bursts 1-8
    (measured mid-swath centre latitudes agree to 0.0 km); S1A merely extends one burst further north.
    So burst indices can be used directly -- no AOI needed -- and 3 bursts cuts the work to ~30%
    while still containing 2 burst seams.

    Only ONE variable changes between arms. Everything else -- scenes, bursts, DEM, CRS, resampling,
    conventions -- is identical by construction, so a difference between two arms is attributable.

    Standalone rather than driven through cases/run.ps1: this is a controlled experiment, and an
    explicit linear script is auditable at a glance. ASCII only, no Python here-strings, every gpt
    call through Invoke-Gpt, sources positional -- all lessons from earlier failures in this work.
#>
$ErrorActionPreference = 'Continue'
$gpt  = 'C:\Program Files\esa-snap\bin\gpt.exe'
$OUT  = 'E:\Output\exp2x2'
$DEM  = 'E:\TestData\dem\copernicus30_venezuela_orbit106.tif'
$A    = 'E:\Data\Venezuela\S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE.zip'
$C    = 'E:\Data\Venezuela\S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE.zip'
$FB   = 4
$LB   = 6
# ETAD products passed EXPLICITLY rather than auto-downloaded.
#
# Auto-download cannot work on a burst SUBSET: ETADSearch queries by TIME, using the source product's
# own start/end +/- 5 s. For bursts 4-6 that window is 22:50:59-22:51:08 (8.9 s), while the ETAD
# product spans the full ~30 s frame, so the search returns zero results and the operator reports
# "ETAD product not found". That is NOT an authentication failure: the query ran and matched nothing.
# These are the products SNAP itself downloaded for the FULL frames, taken from its own cache.
$ETAD_A = 'C:\Users\luis_\.snap\var\cache\etad\S1A_IW_ETA__AXDV_20260623T225050_20260623T225120_065103_0834C8_D7B7.SAFE.zip'
$ETAD_C = 'C:\Users\luis_\.snap\var\cache\etad\S1C_IW_ETA__AXDV_20260624T224958_20260624T225025_008254_010515_8B0D.SAFE.zip'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$log = Join-Path $OUT 'experiment.log'
"=== 2x2 experiment: bursts $FB-$LB, IW3 VV ===" | Set-Content $log

function Log($m) {
    $l = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    $l | Add-Content $log; Write-Host $l
}
# gpt writes INFO to stderr; under 'Stop' that becomes terminating. Judge by EXIT CODE. Out-Host so
# the function returns the code, not the log lines (Tee-Object passes its input through).
function Invoke-Gpt {
    param([string[]]$GptArgs)
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $gpt @GptArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Host; return [int]$LASTEXITCODE }
    finally { $ErrorActionPreference = $prev }
}
function Step {
    param([string]$Name, [string]$Target, [string[]]$GptArgs)
    if (Test-Path $Target) { Log "SKIP $Name (exists)"; return $true }
    Log "RUN  $Name"
    "gpt $($GptArgs -join ' ')" | Add-Content $log
    $rc = Invoke-Gpt $GptArgs
    if ($rc -ne 0) { Log "FAIL $Name exit $rc"; return $false }
    # exit 0 with no product is a FALSE success and has misled this work before
    if (-not (Test-Path $Target)) { Log "FAIL $Name exit 0 but '$Target' absent"; return $false }
    Log "OK   $Name"
    return $true
}

# Split to bursts 4-6 once per date, then reuse for all four arms: the split is identical across
# arms, so repeating it would only add time and a chance of divergence.
$splits = @{}
foreach ($p in @(@{N='A'; F=$A}, @{N='C'; F=$C})) {
    # ETADUtils.getProductIndex parses sensing start/stop from the PRODUCT NAME, so intermediates
    # must keep the original Sentinel-1 stem ("a suffix is fine" per the operator's own error).
    # Short names like A_iw3_b4-6 made the ETAD step fail even with -PetadFile set.
    $stem = [IO.Path]::GetFileNameWithoutExtension($p.F) -replace '\.SAFE$', ''
    $t = Join-Path $OUT "$stem`_b$FB-$LB.dim"
    $okS = Step "split-$($p.N)" $t @('TOPSAR-Split', "-Ssource=$($p.F)", '-Psubswath=IW3',
        '-PselectedPolarisations=VV', "-PfirstBurstIndex=$FB", "-PlastBurstIndex=$LB",
        '-t', $t, '-f', 'BEAM-DIMAP', '-q', '8')
    if (-not $okS) { Log "ABORT: split-$($p.N) failed"; exit 1 }
    # Precise orbits before anything geometric, so neither arm is handicapped by predicted orbits.
    $o = Join-Path $OUT "$stem`_b$FB-$LB`_orb.dim"
    $okO = Step "orbit-$($p.N)" $o @('Apply-Orbit-File', "-Ssource=$t",
        '-PorbitType=Sentinel Precise (Auto Download)', '-PcontinueOnFail=false',
        '-t', $o, '-f', 'BEAM-DIMAP', '-q', '8')
    if (-not $okO) { Log "ABORT: orbit-$($p.N) failed"; exit 1 }
    $splits[$p.N] = $o
}

# ETAD once per date (shared by both grid arms): the correction is applied in radar geometry, before
# geocoding, so it is independent of the output grid. Computing it once also guarantees the two grid
# arms see a bit-identical correction.
# outputETADPhaseBand writes the applied range-delay phase so it can be inspected directly instead of
# inferred from fringes. The individual range terms default to FALSE -- enabling none would give a
# ZERO correction and an ablation indistinguishable from an honest null.
$etads = @{}
foreach ($k in @('A', 'C')) {
    $e = Join-Path $OUT "$k`_iw3_b$FB-$LB`_etad.dim"
    $ef = if ($k -eq 'A') { $ETAD_A } else { $ETAD_C }
    if (-not (Test-Path $ef)) { Log "SKIP etad-$k : ETAD product missing at $ef"; $etads[$k] = $null; continue }
    # resamplingImage=TRUE is REQUIRED for the GSLC chain: option 2 (false) only attaches the
    # correction as etadPhaseCorrection tie-point grids, which are consumed by InterferogramOp's
    # CLASSICAL radar-geometry path alone -- a geocoded stack can never subtract them, so for GSLC
    # it is a guaranteed null (measured: arm C came out bit-identical to arm A, RMS 0.0000).
    # Option 1 (true) + outputPhaseCorrections bakes the range-delay phase into the complex data,
    # which survives geocoding. See TOPSCorrector.computePartialTileForOption1/2.
    $okE = Step "etad-$k" $e @('S1-ETAD-Correction', "-Ssource=$($splits[$k])",
        "-PetadFile=$ef",
        '-PresamplingImage=true', '-PoutputPhaseCorrections=true',
        '-PsumOfRangeCorrections=true', '-PtroposphericCorrectionRg=true',
        '-PionosphericCorrectionRg=true', '-PgeodeticCorrectionRg=true',
        '-PoutputETADPhaseBand=true',
        '-t', $e, '-f', 'BEAM-DIMAP', '-q', '8')
    $etads[$k] = if ($okE) { $e } else { $null }
}

# --- the four arms ---------------------------------------------------------------------------
$arms = @(
    @{ Id = 'A_sq_noetad';   Grid = 'SQUARE_COARSEST';     Etad = $false; Ramp = $false },
    @{ Id = 'B_nat_noetad';  Grid = 'NATIVE_ANISOTROPIC';  Etad = $false; Ramp = $false },
    @{ Id = 'C_sq_etad';     Grid = 'SQUARE_COARSEST';     Etad = $true ; Ramp = $false },
    @{ Id = 'D_nat_etad';    Grid = 'NATIVE_ANISOTROPIC';  Etad = $true ; Ramp = $false },
    # E differs from B by ONE thing: residual-ramp removal. B vs E therefore isolates exactly how much
    # of the GSLC excess fringing is the known deramp-mismatch ramp rather than deformation.
    # StackFrom: E's GSLCs and stack would be bit-identical to B's (same sources, same params), so it
    # reuses B's stack -- saves ~40 min and makes the one-variable isolation exact by construction.
    @{ Id = 'E_nat_noetad_ramp'; Grid = 'NATIVE_ANISOTROPIC'; Etad = $false; Ramp = $true; StackFrom = 'B_nat_noetad' },
    # F: built on the USER's TRAD_TC lattice -- geographic WGS84, SQUARE in degrees at TRAD_TC's own
    # step (1.2566604374770714E-4 deg), ETAD ON to match it. TRAD_TC is geographic while the other
    # arms are UTM 19N, so F is the only arm that can be differenced against it without reprojecting
    # one of them -- and reprojection would inject exactly the interpolation error being measured.
    @{ Id = 'F_trad_lattice'; Grid = ''; Etad = $true; Ramp = $true; DegSpacing = '1.2566604374770714E-4'; Crs = 'WGS84(DD)' },
    @{ Id = 'G_trad_lattice_noetad'; Grid = ''; Etad = $false; Ramp = $true; DegSpacing = '1.2566604374770714E-4'; Crs = 'WGS84(DD)' }
)
foreach ($arm in $arms) {
    Log "================ arm $($arm.Id): grid=$($arm.Grid) etad=$($arm.Etad) ================"
    if ($arm.Etad -and (-not $etads['A'] -or -not $etads['C'])) {
        Log "SKIP arm $($arm.Id): ETAD stage did not produce both dates"
        continue
    }
    $st = Join-Path $OUT "$($arm.Id)_stack.dim"
    if ($arm.StackFrom) {
        $st = Join-Path $OUT "$($arm.StackFrom)_stack.dim"
        if (-not (Test-Path $st)) { Log "SKIP arm $($arm.Id): donor stack '$st' absent"; continue }
        Log "REUSE stack of $($arm.StackFrom) for $($arm.Id)"
    } else {
    $gs = @{}
    $armOk = $true
    foreach ($k in @('A', 'C')) {
        $src = if ($arm.Etad) { $etads[$k] } else { $splits[$k] }
        $g = Join-Path $OUT "$($arm.Id)_$k`_GSLC.dim"
        # gridSpacing only applies when NO explicit pixel spacing is given, so none is passed.
        # outputPhaseTerms writes the carrier and flattening grids for term-by-term inspection.
        $okG = Step "$($arm.Id)-gslc-$k" $g @('GSLC-Terrain-Correction', "-Ssource=$src",
            "-PexternalDEMFile=$DEM", '-PexternalDEMNoDataValue=0.0',
            '-PimgResamplingMethod=BISINC_5_POINT_INTERPOLATION',
            $(if ($arm.DegSpacing) {
                  # An explicit spacing deliberately OVERRIDES gridSpacing: matching an existing
                  # product's lattice is the whole point of this arm.
                  "-PpixelSpacingInDegree=$($arm.DegSpacing)"
              } else { "-PgridSpacing=$($arm.Grid)" }),
            "-PmapProjection=$(if ($arm.Crs) { $arm.Crs } else { 'EPSG:32619' })",
            '-PoutputFlattened=false', '-PoutputAzimuthCarrier=false',
            '-PoutputPhaseTerms=true', '-PnodataValueAtSea=false',
            '-t', $g, '-f', 'BEAM-DIMAP', '-q', '8')
        if (-not $okG) { $armOk = $false; break }
        $gs[$k] = $g
    }
    if (-not $armOk) { Log "SKIP rest of arm $($arm.Id)"; continue }

    # CreateStack: sources are POSITIONAL (the operator declares a @SourceProducts array; the
    # -SsourceProducts= form is rejected). resamplingType NONE because both are already on one lattice.
    if (-not (Step "$($arm.Id)-stack" $st @('CreateStack', '-PresamplingType=NONE', '-Pextent=Master',
            '-t', $st, '-f', 'BEAM-DIMAP', '-q', '8', $gs['A'], $gs['C']))) { continue }
    }

    # Interferogram: source POSITIONAL (its field is 'sourceProduct', so -Ssource= does not bind).
    # subtractFlatEarthPhase=false keeps the (R_ref - R_sec) term, which IS the fringe signal.
    $if = Join-Path $OUT "$($arm.Id)_ifg.dim"
    # subtractFlatEarthPhase=TRUE. This is NOT the same knob as GSLC's outputFlattened:
    #   outputFlattened removes 4*pi*R/lambda from EACH SLC independently -> destroys the
    #     interferometric phase. Correctly false.
    #   subtractFlatEarthPhase removes the reference-ELLIPSOID phase from the INTERFEROGRAM ->
    #     standard practice, and it does NOT remove deformation.
    # Setting it false left a steep flat-earth ramp which, on the 14 m square grid, aliased into
    # something indistinguishable from noise (measured 1.4842 rad/px against a 1.571 noise floor) and
    # was not comparable to any conventionally-processed interferogram. Both reference runs used true.
    # A DIFFERENTIAL interferogram: both the reference-ellipsoid term and the topographic term are
    # removed, leaving deformation + atmosphere. That is what the published DInSAR products show, so
    # it is the only basis on which this can be compared to them or to the classical chain.
    # The SAME staged DEM as the geocoding step, so the topographic term cannot differ by elevation source.
    $ifArgs = @('Interferogram', '-PsubtractFlatEarthPhase=true',
        '-PsubtractTopographicPhase=true', '-PdemName=External DEM',
        "-PexternalDEMFile=$DEM", '-PexternalDEMNoDataValue=0.0',
        '-PincludeCoherence=true')
    if ($arm.Ramp) {
        # GSLC-only remedy for the residual ramp left by cross-acquisition GSLC interferometry
        # (annotation-vs-data deramp mismatch, ~1 fringe per 80 px per the operator's own help).
        # Off by default because a rigid low-order fit also absorbs a genuine scene-wide linear
        # deformation gradient -- which for a co-seismic pair is a real risk, hence a separate arm
        # rather than switching it on everywhere.
        $ifArgs += '-PsubtractResidualRamp=true'
    }
    $ifArgs += @('-t', $if, '-f', 'BEAM-DIMAP', '-q', '8', $st)
    Step "$($arm.Id)-ifg" $if $ifArgs | Out-Null
}

Log "================ arms complete ================"
Get-ChildItem $OUT -Filter '*_ifg.dim' | ForEach-Object { Log "produced: $($_.Name)" }
