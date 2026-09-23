<#
    GSLC chain for one PAIR on the Venezuela 3-burst fixtures, through the CreateStack AUTO path
    (GSLC reference + raw secondary SLC), so the secondary is grid-locked, burst-ID matched and
    bias-corrected. The both-GSLC path has no lock and no bias estimate.

      -Ref  <dim>   reference SLC (already split + orbit [+ ETAD])   -> GSLC-Terrain-Correction
      -Sec  <dim>   secondary SLC, RAW (the stack builds its GSLC itself)
      -Tag  <name>  output prefix in E:\Output\parity\ven
      -Diag         run with -Dgslc.diagGeometry=true (adds diag_rangeIndex etc. for the synthetic rungs)

    Products:  <Tag>_gslc.dim   <Tag>_stack.dim   <Tag>_ifg.dim
#>
param(
    [Parameter(Mandatory = $true)][string]$Ref,
    [Parameter(Mandatory = $true)][string]$Sec,
    [Parameter(Mandatory = $true)][string]$Tag,
    [switch]$Diag
)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\$Tag.log"
$extra = if ($Diag) { '-Dgslc.diagGeometry=true' } else { '' }

$gslc  = "$D\$Tag`_gslc.dim"
$stack = "$D\$Tag`_stack.dim"
$ifg   = "$D\$Tag`_ifg.dim"

$pg = New-ParamFile "$D\$Tag`_gslc_params.xml" ([ordered]@{
    externalDEMFile = $script:DEM; externalDEMNoDataValue = '0.0';
    imgResamplingMethod = 'BISINC_5_POINT_INTERPOLATION'; gridSpacing = 'NATIVE_ANISOTROPIC';
    mapProjection = 'WGS84(DD)'; outputFlattened = 'false'; outputAzimuthCarrier = 'false';
    outputPhaseTerms = 'true'; nodataValueAtSea = 'false' })

$pi = New-ParamFile "$D\$Tag`_ifg_params.xml" ([ordered]@{
    subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = 'External DEM';
    externalDEMFile = $script:DEM; externalDEMNoDataValue = '0.0'; includeCoherence = 'true';
    cohWinSizeMeters = '100' })

$ok = Step "$Tag-gslc" $gslc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('GSLC-Terrain-Correction', "-Ssource=$Ref", '-p', $pg,
        '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8') "$D\$Tag`_gslc.log" '24g' $extra }
if (-not $ok) { Log "ABORT $Tag-gslc"; exit 1 }

# Auto path: reference GSLC first, RAW secondary second. The lock line proves the burst-selection
# lock engaged; without it a mixed-burst strip is incoherent by construction.
$ok = Step "$Tag-stack" $stack {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('CreateStack', '-Pextent=Master', '-t', $stack,
        '-f', 'BEAM-DIMAP', '-q', '8', $gslc, $Sec) "$D\$Tag`_stack.log" '24g' $extra }
if (-not $ok) { Log "ABORT $Tag-stack"; exit 1 }
if (-not (Assert-Log "$D\$Tag`_stack.log" @('locked to the reference \(\d+ of \d+ seam') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $Tag`: burst-overlap lock did not engage"; exit 1
}

$ok = Step "$Tag-ifg" $ifg {
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi, '-t', $ifg,
        '-f', 'BEAM-DIMAP', '-q', '8', $stack) "$D\$Tag`_ifg.log" }
if (-not $ok) { Log "ABORT $Tag-ifg"; exit 1 }
if (-not (Assert-Log "$D\$Tag`_ifg.log" @('cohWinSizeMeters=100\.0 m -> cohWinAz=\d+, cohWinRg=\d+') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $Tag`: ifg did not report the metre-based coherence window"; exit 1
}
Log "GSLC pair $Tag complete: $ifg"
