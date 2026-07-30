<#
    SNAP engine adapter -- runs NATIVELY, not in a container.

    The shipped Windows build is the artifact under test, so containerising it would validate
    something we do not distribute. The harness therefore treats SNAP as a local CLI: consume a case
    YAML, write georeferenced output under $WORK_DIR\snap\<case>\, and report what was produced.

    THIS FILE IS DELIBERATELY PURE ASCII. It is invoked with `powershell` (5.1), which reads a
    BOM-less file as ANSI rather than UTF-8; any non-ASCII character (an em-dash, for instance)
    mojibakes and can break parsing. Use "--" instead of an em-dash here.

    Python lives in snap_helpers.py, NOT in here-strings. Python regex metacharacters inside a .ps1
    are read by the PowerShell parser and fail at parse time, before the script ever runs.

    Usage: run.ps1 <path-to-case.yml>
#>
param(
    [Parameter(Mandatory = $true)][string]$CasePath,
    # Which pipeline to build. 'gslc' = geocode-first (GSLC-Terrain-Correction).
    # 'classic' = the classical radar-coordinate chain: Apply-Orbit-File, Back-Geocoding, ESD,
    # Interferogram, TOPSAR-Deburst, Terrain-Correction. Both must land on the case's grid so the
    # two can be diffed; see the note on square cells in the classic branch.
    [ValidateSet('gslc', 'classic')][string]$Variant = 'gslc'
)

$ErrorActionPreference = 'Stop'

function Fail($msg) { Write-Error "SNAP ADAPTER: $msg"; exit 3 }

# All gpt invocations go through here.
#
# gpt writes routine INFO/WARNING lines to stderr. Under $ErrorActionPreference='Stop', piping a
# NATIVE command's stderr with 2>&1 makes PowerShell raise each stderr line as a TERMINATING
# NativeCommandError -- so the adapter dies on an ordinary log line ("Initializing external tool
# adapters") while gpt itself is perfectly healthy. A native tool must be judged by its EXIT CODE.
# Preference is restored in `finally` so a genuine PowerShell error later still stops the script.
# Returns the gpt EXIT CODE and nothing else.
#
# The `| Out-Host` is load-bearing. A PowerShell function returns EVERYTHING written to the output
# stream, and Tee-Object PASSES ITS INPUT THROUGH -- so without Out-Host the caller receives an array
# of every gpt log line with the exit code appended. `$rc -ne 0` on an array then FILTERS rather than
# compares and is truthy whenever any log line exists, i.e. a successful run is reported as a
# failure. Out-Host displays the lines and emits nothing to the pipeline.
function Invoke-Gpt {
    param([string[]]$GptArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $gpt @GptArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
        return [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

# Captures gpt's combined output as a STRING (for capability probing) rather than running a job.
# Shares Invoke-Gpt's stderr guard: EVERY `& $gpt ... 2>&1` in this file must be wrapped, or the
# routine INFO lines gpt writes to stderr terminate the script under $ErrorActionPreference='Stop'.
function Get-GptOutput {
    param([string[]]$GptArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $gpt @GptArgs 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $prev
    }
}

# Writes one line to the log AND the console, emitting NOTHING to the pipeline.
#
# This exists because `"text" | Tee-Object -FilePath $log -Append` PASSES ITS INPUT THROUGH. Inside a
# function that returns a value, every such line joins the return value, so a caller doing
# `$p = Split-Only ...` receives an array of log lines with the path at the end -- and
# "-Ssource=$p" then expands to the entire log. Add-Content + Write-Host avoids the whole class.
function Log($msg) {
    $msg | Add-Content -Path $log
    Write-Host $msg
}

# --- environment -----------------------------------------------------------------------------
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # validation/
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { Fail "$envFile not found. Copy .env.example and set the host paths." }
$cfg = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $cfg[$k.Trim()] = $v.Trim()
}
$gpt     = $cfg['GPT'];      if (-not $gpt)     { Fail 'GPT not set in .env' }
$dataDir = $cfg['DATA_DIR']; if (-not $dataDir) { Fail 'DATA_DIR not set in .env' }
$workDir = $cfg['WORK_DIR']; if (-not $workDir) { Fail 'WORK_DIR not set in .env' }
$scenesDir = $cfg['SCENES_DIR']
if (-not (Test-Path $gpt)) { Fail "gpt not found at '$gpt'" }

$helper = Join-Path $PSScriptRoot 'snap_helpers.py'
if (-not (Test-Path $helper)) { Fail "snap_helpers.py not found next to run.ps1" }

# --- case ------------------------------------------------------------------------------------
# The case is read by the same Python that runs the comparator, so the harness has exactly ONE YAML
# parser and no dialect drift between engines.
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { Fail 'python not on PATH' }
$json = & python $helper 'read-case' $CasePath
if ($LASTEXITCODE -ne 0) { Fail "could not parse case '$CasePath'" }
$case = $json | ConvertFrom-Json

$name = if ($case.case) { $case.case } else { [IO.Path]::GetFileNameWithoutExtension($CasePath) }
$out  = Join-Path (Join-Path $workDir 'snap') $name
if ($Variant -ne 'gslc') { $out = Join-Path $out $Variant }   # keep variants side by side, not overwriting
New-Item -ItemType Directory -Force -Path $out | Out-Null
$log = Join-Path $out 'engine.log'
# Truncate ONCE, here, before anything appends. Every later write uses -Append.
"=== snap engine adapter ===" | Set-Content -Path $log

# Container paths in the case are canonical; map them onto the host for the native engine.
# Two roots exist: /data (shared test tree: DEM + orbits) and /scenes (acquisition data). Keeping
# them distinct means a case never has to know which engine reads it, or from where.
function Resolve-DataPath($p) {
    if (-not $p) { return $p }
    if ($p.StartsWith('/data/'))   { return (Join-Path $dataDir   ($p.Substring(6) -replace '/', '\')) }
    if ($p.StartsWith('/scenes/')) {
        if (-not $scenesDir) { Fail 'case references /scenes/ but SCENES_DIR is not set in .env' }
        return (Join-Path $scenesDir ($p.Substring(8) -replace '/', '\'))
    }
    return $p
}

$slc = Resolve-DataPath $case.scene.slc
$dem = Resolve-DataPath $case.dem
$secSlc = $null
if ($case.secondary -and $case.secondary.slc) {
    $secSlc = Resolve-DataPath $case.secondary.slc
    if (-not (Test-Path $secSlc)) { Fail "secondary scene not found: $secSlc" }
}
if (-not (Test-Path $slc)) { Fail "scene not found: $slc" }
if (-not (Test-Path $dem)) {
    Fail "staged DEM not found: $dem. The harness requires ONE explicit DEM file shared by every engine."
}

# --- conventions -----------------------------------------------------------------------------
# The case is authoritative. Engine defaults disagree on BOTH axes (SNAP defaults carrier-free and
# unflattened; ISCE3/COMPASS defaults the opposite), so both are set explicitly from the case.
$conv = $case.conventions
$flattened = [bool]$conv.flattened
$carrier   = ("$($conv.azimuth_carrier)".ToLower() -in @('restored', 'true', 'on'))

# engine_params lookup is VARIANT-AWARE: a case that compares pipelines within SNAP keys its
# parameters as `snap-classic` / `snap-gslc`, not `snap`. Reading only `snap` would silently ignore
# them and fall back to defaults -- so `esd: true` would be dropped and the classic side quietly
# degraded, making a GSLC defect look like an improvement. Most specific key wins; `snap` is fallback.
$ep = $null
if ($case.engine_params) {
    foreach ($key in @("snap-$Variant", 'snap')) {
        $prop = $case.engine_params.PSObject.Properties[$key]
        if ($prop -and $prop.Value) { $ep = $prop.Value; break }
    }
}
$epKey = if ($ep) { 'found' } else { 'NONE (all defaults)' }
$resampling = if ($ep -and $ep.imgResamplingMethod) { $ep.imgResamplingMethod } else { 'BISINC_5_POINT_INTERPOLATION' }
$phaseTerms = if ($ep -and ($null -ne $ep.outputPhaseTerms)) { [bool]$ep.outputPhaseTerms } else { $false }
$applyEtad = if ($ep -and ($null -ne $ep.applyETAD)) { [bool]$ep.applyETAD } else { $false }

# grid.spacing may be the literal string 'native', meaning: let gridSpacing derive the step.
# In that mode spacingX/Y are unknown until gpt has run, so anything needing them (the classic
# path's square-cell check) must handle $null rather than assume a number.
$gridSpacing = $null
$spacingX = $null; $spacingY = $null
if ("$($case.grid.spacing)" -eq 'native' -or $case.grid.gridSpacing) {
    $gridSpacing = if ($case.grid.gridSpacing) { $case.grid.gridSpacing } else { 'NATIVE_ANISOTROPIC' }
} else {
    $spacingX = [double]$case.grid.spacing[0]
    $spacingY = [double]$case.grid.spacing[1]
}

$target = Join-Path $out "$name`_GSLC.dim"

Write-Host "SNAP ADAPTER: case        $name"
Write-Host "SNAP ADAPTER: variant     $Variant  (engine_params: $epKey)"
Write-Host "SNAP ADAPTER: scene       $slc"
Write-Host "SNAP ADAPTER: dem         $dem"
Write-Host "SNAP ADAPTER: posting     $(if ($gridSpacing) { "derived by gridSpacing=$gridSpacing" } else { "$spacingX x $spacingY m" })"
Write-Host "SNAP ADAPTER: flattened   $flattened  (outputFlattened)"
Write-Host "SNAP ADAPTER: carrier     $carrier  (outputAzimuthCarrier)"
Write-Host "SNAP ADAPTER: ETAD        $applyEtad (auto-download, phase-correction mode)"
Write-Host "SNAP ADAPTER: phaseTerms  $phaseTerms (azimuthCarrierPhase + flatteningPhase bands)"

Log "gpt --diag"
[void](Invoke-Gpt @('--diag'))

# --- honour scene.subswath / scene.polarisation ----------------------------------------------
# COMPASS is inherently per-burst/per-subswath, so a case naming a subswath MUST be split on the
# SNAP side too. Skipping this would geocode all three subswaths and both polarisations and then
# compare that against a single-subswath CSLC, a mismatch that looks like an algorithmic difference.
$subswath = $case.scene.subswath
$polar    = $case.scene.polarisation

# Applies S1-ETAD-Correction to a split product, returning the corrected product path.
#
# etadFile is deliberately NOT passed: the operator AUTO-DOWNLOADS the matching ETAD product for the
# scene, which is why no ETAD auxiliary data has to be staged.
#
# Mode matters. resamplingImage=$false + outputPhaseCorrections=$true is the mode the GSLC path can
# consume: ETAD contributes a PHASE grid rather than geometrically resampling the image. The default
# (resamplingImage=true) resamples instead, and that correction never reaches the GSLC phase.
#
# The individual range terms all default to FALSE, so enabling none of them yields a correction of
# ZERO -- an ablation would then show no difference and look like an honest null. Tropospheric,
# ionospheric and geodetic range terms are therefore switched on explicitly.
function Invoke-Etad {
    param([string]$Src, [string]$Tag)
    $out2 = Join-Path $out "$name`_$Tag`_etad.dim"
    $a = @('S1-ETAD-Correction', "-Ssource=$Src",
           '-PresamplingImage=false',
           '-PoutputPhaseCorrections=true',
           '-PsumOfRangeCorrections=true',
           '-PtroposphericCorrectionRg=true',
           '-PionosphericCorrectionRg=true',
           '-PgeodeticCorrectionRg=true',
           '-PoutputETADPhaseBand=true',
           '-t', $out2, '-f', 'BEAM-DIMAP', '-q', '8')
    Log "gpt $($a -join ' ')"
    Write-Host "SNAP ADAPTER: [$Tag] ETAD correction (auto-download; phase-correction mode)"
    $rc = Invoke-Gpt $a
    if ($rc -ne 0) { Fail "[$Tag] S1-ETAD-Correction exited $rc (see $log)" }
    if (-not (Test-Path $out2)) { Fail "[$Tag] S1-ETAD-Correction reported success but '$out2' is absent" }
    return $out2
}

# Split then geocode ONE acquisition. Both dates must go through an identical parameter set --
# any asymmetry between reference and secondary would appear as interferometric phase and be
# indistinguishable from ground motion.
function Split-And-Geocode {
    param([string]$SrcSlc, [string]$Tag)

    $src = $SrcSlc
    if ($subswath) {
        $split = Join-Path $out "$name`_$Tag`_$subswath.dim"
        $splitArgs = @('TOPSAR-Split', "-Ssource=$src", "-Psubswath=$subswath")
        if ($polar) { $splitArgs += "-PselectedPolarisations=$polar" }
        $splitArgs += @('-t', $split, '-f', 'BEAM-DIMAP', '-q', '8')
        Write-Host "SNAP ADAPTER: [$Tag] split $subswath $polar"
        Log "gpt $($splitArgs -join ' ')"
        $rcS = Invoke-Gpt $splitArgs
        if ($rcS -ne 0) { Fail "[$Tag] TOPSAR-Split exited $rcS (see $log)" }
        if (-not (Test-Path $split)) { Fail "[$Tag] TOPSAR-Split reported success but '$split' is absent" }
        $src = $split
    }
    if ($applyEtad) { $src = Invoke-Etad -Src $src -Tag $Tag }

    $gridArgs = if ($gridSpacing) { @("-PgridSpacing=$gridSpacing") }
                else { @("-PpixelSpacingInMeter=$spacingX", "-PpixelSpacingInMeterY=$spacingY") }

    $gslc = Join-Path $out "$name`_$Tag`_GSLC.dim"
    $gArgs = @(
        'GSLC-Terrain-Correction',
        "-Ssource=$src",
        "-PexternalDEMFile=$dem",
        "-PimgResamplingMethod=$resampling",
        # gridSpacing (NATIVE_ANISOTROPIC etc.) only takes effect when NO explicit pixel spacing is
        # given -- gpt's own help says "How the output grid step is derived when no explicit pixel
        # spacing is given". Passing pixelSpacingInMeter unconditionally would silently OVERRIDE the
        # case's gridSpacing choice and put the product on a different lattice than asked for.
        # Built as an array and splatted below. An inline $(...) returning two strings is joined
        # into ONE argument by PowerShell, which gpt then reads as the literal
        # "14 -PpixelSpacingInMeterY=14" and rejects with a ConversionException.
        $gridArgs,
        "-PmapProjection=$($case.grid.crs)",
        "-PoutputFlattened=$($flattened.ToString().ToLower())",
        "-PoutputAzimuthCarrier=$($carrier.ToString().ToLower())",
        "-PoutputPhaseTerms=$($phaseTerms.ToString().ToLower())",
        '-PnodataValueAtSea=false',
        '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8'
    )
    Write-Host "SNAP ADAPTER: [$Tag] geocode -> $gslc"
    Log "gpt $($gArgs -join ' ')"
    $rcG = Invoke-Gpt $gArgs
    if ($rcG -ne 0) { Fail "[$Tag] GSLC-Terrain-Correction exited $rcG (see $log)" }
    if (-not (Test-Path $gslc)) { Fail "[$Tag] GSLC reported success but '$gslc' is absent" }
    return $gslc
}

# --- refuse engine_params this adapter does not implement ---------------------------------------
# A case may ask for something the adapter silently ignores, and the run then LOOKS successful while
# producing the baseline product. For an ablation that is the worst possible outcome: the "with" and
# "without" arms come out identical and the honest conclusion ("no effect") is indistinguishable from
# the bug. So unimplemented keys are a hard failure, not a warning.
if ($ep) {
    $implemented = @('imgResamplingMethod', 'outputPhaseTerms', 'gridSpacing', 'esd',
                     'demResamplingMethod', 'flattened', 'azimuth_carrier', 'applyETAD')
    $unknown = @($ep.PSObject.Properties.Name | Where-Object { $implemented -notcontains $_ })
    if ($unknown.Count -gt 0) {
        Fail ("engine_params request features this adapter does not implement: $($unknown -join ', ').`n" +
              "  Running anyway would silently produce the BASELINE product and, in an ablation, make`n" +
              "  the two arms identical -- a false null result. Implement them in run.ps1 or remove`n" +
              "  them from the case. Implemented keys: $($implemented -join ', ').")
    }
}

# --- preflight: does the INSTALLED build support what the case asks for? ---------------------
# The harness runs against whatever gpt is installed, which may lag the source tree. Without this
# probe a missing parameter surfaces as a 40-line Java stack trace ("unknown parameter 'x'") that
# reads like an operator defect rather than a stale install. Ask gpt what it actually supports.
$opHelp = Get-GptOutput @('-h', 'GSLC-Terrain-Correction')
$required = @('outputFlattened', 'outputAzimuthCarrier')
if ($phaseTerms) { $required += 'outputPhaseTerms' }
$absent = $required | Where-Object { $opHelp -notmatch [regex]::Escape("-P$_=") }
if ($absent) {
    Fail ("the installed GSLC-Terrain-Correction does not support: $($absent -join ', ').`n" +
          "  The case requires it, so this run would silently measure a different product.`n" +
          "  Rebuild and reinstall the sar-op-sar-processing module, or set the corresponding`n" +
          "  engine_params.snap flag to false in the case (losing term-level comparison).")
}

# --- classic pipeline: Apply-Orbit-File, Back-Geocoding, ESD, Interferogram, Deburst, TC --------
# This is the reference implementation the geocode-first path is checked AGAINST, so it uses standard
# practice throughout rather than settings chosen to flatter the comparison. In particular ESD is
# applied for TOPS pairs unless the case disables it: omitting it would compare GSLC against a
# knowingly degraded classical result and could make a GSLC defect look like an improvement.
function Split-Only {
    param([string]$SrcSlc, [string]$Tag)
    $split = Join-Path $out "$name`_$Tag`_$subswath.dim"
    $splitArgs = @('TOPSAR-Split', "-Ssource=$SrcSlc", "-Psubswath=$subswath")
    if ($polar) { $splitArgs += "-PselectedPolarisations=$polar" }
    $splitArgs += @('-t', $split, '-f', 'BEAM-DIMAP', '-q', '8')
    Log "gpt $($splitArgs -join ' ')"
    $rc = Invoke-Gpt $splitArgs
    if ($rc -ne 0) { Fail "[$Tag] TOPSAR-Split exited $rc (see $log)" }

    # Orbits must be applied BEFORE Back-Geocoding: it coregisters using orbit state vectors, so with
    # only the predicted orbits in the SAFE the coregistration is degraded and every downstream number
    # would be pessimistic for no algorithmic reason.
    $orb = Join-Path $out "$name`_$Tag`_orb.dim"
    $orbArgs = @('Apply-Orbit-File', "-Ssource=$split",
                 '-PorbitType=Sentinel Precise (Auto Download)',
                 '-PcontinueOnFail=false',
                 '-t', $orb, '-f', 'BEAM-DIMAP', '-q', '8')
    Log "gpt $($orbArgs -join ' ')"
    $rc = Invoke-Gpt $orbArgs
    if ($rc -ne 0) { Fail "[$Tag] Apply-Orbit-File exited $rc. continueOnFail=false is deliberate: a silently un-orbited product degrades coregistration invisibly. See $log" }
    return $orb
}

function Invoke-Classic {
    if (-not $secSlc) { Fail "the classic pipeline needs case.secondary -- an interferogram requires two dates" }

    # PREFLIGHT, before any expensive work. Terrain-Correction (the last step) exposes only
    # pixelSpacingInMeter -- no Y counterpart -- so it can produce SQUARE cells ONLY. Checking this
    # here rather than at the point of use is deliberate: discovering it after Back-Geocoding and ESD
    # have run wastes hours, which is precisely the late-failure pattern that made the COMPASS
    # NumPy/GDAL defects so expensive to diagnose.
    if ($gridSpacing) {
        Fail ("the classic path cannot honour grid.gridSpacing=${gridSpacing}: Terrain-Correction takes " +
              "only pixelSpacingInMeter (square cells) and cannot reproduce a derived anisotropic " +
              "grid. Give this case an explicit square grid.spacing, or compare the classic path " +
              "against a separate square-grid GSLC case.")
    }
    if ([math]::Abs($spacingX - $spacingY) -gt 1e-9) {
        Fail ("case grid.spacing is $spacingX x $spacingY, but Terrain-Correction supports square " +
              "cells only (pixelSpacingInMeter, no Y counterpart). Declare a square spacing for any " +
              "case that compares the classic path, or the two pipelines land on different lattices " +
              "and the diff measures resampling instead of the algorithms.")
    }

    $refP = Split-Only -SrcSlc $slc    -Tag 'ref'
    $secP = Split-Only -SrcSlc $secSlc -Tag 'sec'

    $esd = $true
    if ($ep -and ($null -ne $ep.esd)) { $esd = [bool]$ep.esd }
    $demResampling = if ($ep -and $ep.demResamplingMethod) { $ep.demResamplingMethod } else { 'BILINEAR_INTERPOLATION' }

    # Back-Geocoding resamples the secondary into the reference radar geometry using the DEM. The SAME
    # staged DEM file as the GSLC path is passed explicitly, so the comparison cannot be contaminated
    # by the two paths resolving different elevation data.
    $coreg = Join-Path $out "$name`_coreg.dim"
    $bgArgs = @('Back-Geocoding',
        # Sources are TRAILING POSITIONAL args, not the -S array form: these operators declare a
        # @SourceProducts ARRAY which gpt fills from positional file arguments. The -S form gives
        # "java.io.IOException: The filename, directory name, or volume label syntax is incorrect".
        '-PdemName=External DEM',
        "-PexternalDEMFile=$dem",
        '-PexternalDEMNoDataValue=0.0',
        "-PdemResamplingMethod=$demResampling",
        "-PresamplingType=$resampling",
        '-PmaskOutAreaWithoutElevation=false',
        '-t', $coreg, '-f', 'BEAM-DIMAP', '-q', '8',
        $refP, $secP)
    Log "gpt $($bgArgs -join ' ')"
    Write-Host "SNAP ADAPTER: back-geocode  -> $coreg"
    $rc = Invoke-Gpt $bgArgs
    if ($rc -ne 0) { Fail "Back-Geocoding exited $rc (see $log)" }

    $forIfg = $coreg
    if ($esd) {
        $esdOut = Join-Path $out "$name`_esd.dim"
        $esdArgs = @('Enhanced-Spectral-Diversity', "-Ssource=$coreg",
                     '-t', $esdOut, '-f', 'BEAM-DIMAP', '-q', '8')
        Log "gpt $($esdArgs -join ' ')"
        Write-Host "SNAP ADAPTER: esd           -> $esdOut"
        $rc = Invoke-Gpt $esdArgs
        if ($rc -ne 0) { Fail "Enhanced-Spectral-Diversity exited $rc (see $log)" }
        $forIfg = $esdOut
    } else {
        Write-Host "SNAP ADAPTER: esd           SKIPPED (engine_params.snap-classic.esd=false)"
    }

    # subtractFlatEarthPhase comes from the CASE, exactly as in the GSLC branch, so both paths are
    # asked for the same phase content. With flattened=false the flat-earth term stays in and the
    # fringes are present in both.
    $ifgR = Join-Path $out "$name`_ifg_radar.dim"
    # Positional source: InterferogramOp's field is `sourceProduct`, so -Ssource= does not bind.
    $ifgArgs = @('Interferogram',
        "-PsubtractFlatEarthPhase=$($flattened.ToString().ToLower())",
        '-PincludeCoherence=true',
        '-t', $ifgR, '-f', 'BEAM-DIMAP', '-q', '8', $forIfg)
    Log "gpt $($ifgArgs -join ' ')"
    Write-Host "SNAP ADAPTER: interferogram -> $ifgR"
    $rc = Invoke-Gpt $ifgArgs
    if ($rc -ne 0) { Fail "Interferogram exited $rc (see $log)" }

    $deb = Join-Path $out "$name`_deburst.dim"
    $debArgs = @('TOPSAR-Deburst', "-Ssource=$ifgR", '-t', $deb, '-f', 'BEAM-DIMAP', '-q', '8')
    Log "gpt $($debArgs -join ' ')"
    Write-Host "SNAP ADAPTER: deburst       -> $deb"
    $rc = Invoke-Gpt $debArgs
    if ($rc -ne 0) { Fail "TOPSAR-Deburst exited $rc (see $log)" }

    # Terrain-Correction onto the case grid. The square-cell precondition was already
    # enforced at the top of this function, before any expensive work ran.
    $tc = Join-Path $out "$name`_ifg.dim"
    $tcArgs = @('Terrain-Correction', "-Ssource=$deb",
        '-PdemName=External DEM',
        "-PexternalDEMFile=$dem",
        '-PexternalDEMNoDataValue=0.0',
        "-PdemResamplingMethod=$demResampling",
        "-PimgResamplingMethod=$resampling",
        "-PpixelSpacingInMeter=$spacingX",
        "-PmapProjection=$($case.grid.crs)",
        '-PnodataValueAtSea=false',
        '-t', $tc, '-f', 'BEAM-DIMAP', '-q', '8')
    Log "gpt $($tcArgs -join ' ')"
    Write-Host "SNAP ADAPTER: terrain-corr  -> $tc"
    $rc = Invoke-Gpt $tcArgs
    if ($rc -ne 0) { Fail "Terrain-Correction exited $rc (see $log)" }
    Log ((& python $helper 'describe' $tc) | Out-String).TrimEnd()
    Write-Host "SNAP ADAPTER: classic interferogram at $tc"
}

if ($Variant -eq 'classic') {
    Invoke-Classic
    Write-Host "snap adapter: outputs and log under $out"
    exit 0
}

# --- run: reference, then secondary, then the interferogram ------------------------------------
$refGslc = Split-And-Geocode -SrcSlc $slc -Tag 'ref'
Log ((& python $helper 'describe' $refGslc) | Out-String).TrimEnd()
if (-not $secSlc) {
    Write-Host "SNAP ADAPTER: no case.secondary -- single-GSLC case, no interferogram formed"
} else {
    $secGslc = Split-And-Geocode -SrcSlc $secSlc -Tag 'sec'
    Log ((& python $helper 'describe' $secGslc) | Out-String).TrimEnd()
    # CreateStack with resamplingType=NONE. The two GSLCs are already on ONE map lattice (identical
    # CRS, posting and snapped origins), so no resampling is needed or wanted -- resampling here
    # would blur the very phase being compared. CreateStack still harmonises the phase conventions
    # and applies the standard-grid snapping, which is why it is not skipped entirely.
    $stack = Join-Path $out "$name`_stack.dim"
    $stackArgs = @('CreateStack',
        # Sources are TRAILING POSITIONAL args, not the -S array form: these operators declare a
        # @SourceProducts ARRAY which gpt fills from positional file arguments. The -S form gives
        # "java.io.IOException: The filename, directory name, or volume label syntax is incorrect".
        '-PresamplingType=NONE',
        '-Pextent=Master',
        '-t', $stack, '-f', 'BEAM-DIMAP', '-q', '8',
        $refGslc, $secGslc)
    Log "gpt $($stackArgs -join ' ')"
    Write-Host "SNAP ADAPTER: stack       $stack"
    $rcSt = Invoke-Gpt $stackArgs
    if ($rcSt -ne 0) { Fail "CreateStack exited $rcSt (see $log)" }
    Log ((& python $helper 'describe' $stack) | Out-String).TrimEnd()
    # Interferogram. subtractFlatEarthPhase is driven by the CASE, not left to the default: with
    # conventions.flattened=false the flat-earth term must remain in the product, because that
    # (R_ref - R_sec) difference IS the fringe signal being compared.
    $ifg = Join-Path $out "$name`_ifg.dim"
    # Positional source: InterferogramOp's field is `sourceProduct`, so -Ssource= does not bind.
    $ifgArgs = @('Interferogram',
        "-PsubtractFlatEarthPhase=$($flattened.ToString().ToLower())",
        '-PincludeCoherence=true',
        '-t', $ifg, '-f', 'BEAM-DIMAP', '-q', '8', $stack)
    Log "gpt $($ifgArgs -join ' ')"
    Write-Host "SNAP ADAPTER: ifg         $ifg"
    $rcIfg = Invoke-Gpt $ifgArgs
    if ($rcIfg -ne 0) { Fail "Interferogram exited $rcIfg (see $log)" }
    Log ((& python $helper 'describe' $ifg) | Out-String).TrimEnd()
}

Write-Host "snap adapter: outputs and log under $out"
