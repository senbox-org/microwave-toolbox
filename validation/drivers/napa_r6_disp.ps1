<#
    Napa R6 stage 2: unwrapped phase -> line-of-sight displacement, both chains, on a common map
    grid. Run after napa_r6.ps1.

    The classical chain is in radar geometry, so it is terrain-corrected after PhaseToDisplacement
    (the displacement raster is a smooth scalar field - resampling it is fine, unlike a wrapped
    phase raster).
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\napa'
Set-ParityLog "$D\r6_disp.log"

foreach ($t in @('gslc', 'trad')) {
    $wrapped = Join-Path $D "napa_r6_${t}_ml_flt.dim"
    $unwDir = (Get-ChildItem (Join-Path $D "napa_r6_${t}_snaphu") -Recurse -Filter 'UnwPhase*.snaphu.hdr' |
               Select-Object -First 1)
    if (-not $unwDir) { Log "ABORT ${t}: no unwrapped header"; exit 1 }
    $unw = $unwDir.FullName
    $imported = Join-Path $D "napa_r6_${t}_unw.dim"
    $disp = Join-Path $D "napa_r6_${t}_disp.dim"
    Log "${t} unwrapped: $unw"

    # INSTALLED gpt, not maven-exec. No single maven module carries SnaphuImport (jlinda-nest),
    # PhaseToDisplacement (sar-op-insar) AND the ENVI reader the .snaphu.hdr needs; the installed
    # SNAP has every module on one classpath. lib.ps1's rule allows this precisely here: none of
    # these three operators was changed by this work, so the Jul-29 build is the same code.
    $ok = Step "$t-snaphu-import" $imported {
        Invoke-Gpt @('validation/graphs/snaphu_import_disp_tc.xml',
            "-Pwrapped=$wrapped", "-Punwrapped=$unw", "-Poutput=$imported") `
            (Join-Path $D "napa_r6_${t}_import.log") }
    if (-not $ok) { Log "ABORT $t-snaphu-import"; exit 1 }

    $ok = Step "$t-displacement" $disp {
        Invoke-Gpt @('PhaseToDisplacement', "-Ssource=$imported",
            '-t', $disp, '-f', 'BEAM-DIMAP', '-q', '8') `
            (Join-Path $D "napa_r6_${t}_disp.log") }
    if (-not $ok) { Log "ABORT $t-displacement"; exit 1 }
}

# the classical chain is in radar geometry: terrain-correct its displacement onto a map grid
$tcIn = Join-Path $D 'napa_r6_trad_disp.dim'
$tcOut = Join-Path $D 'napa_r6_trad_disp_TC.dim'
$pt = New-ParamFile (Join-Path $D 'napa_r6_tc_params.xml') ([ordered]@{
    demName = 'Copernicus 30m Global DEM'; imgResamplingMethod = 'BILINEAR_INTERPOLATION';
    mapProjection = 'WGS84(DD)'; nodataValueAtSea = 'false'; saveDEM = 'false' })
$ok = Step 'trad-disp-tc' $tcOut {
    Invoke-Gpt @('Terrain-Correction', "-Ssource=$tcIn",
        '-p', $pt, '-t', $tcOut, '-f', 'BEAM-DIMAP', '-q', '8') `
        (Join-Path $D 'napa_r6_trad_disp_tc.log') }
if (-not $ok) { Log 'ABORT trad-disp-tc'; exit 1 }
Log "R6 displacement products ready: napa_r6_gslc_disp.dim and napa_r6_trad_disp_TC.dim"
