<#
    NISAR R7 - external reference (Tier L, the only rung in the matrix that compares against a
    third party rather than against our own classical chain).

    Produces OUR GSLC from the NISAR RSLC on JPL's own grid so the two can be compared without
    resampling either one. The grid was read from the JPL L2 GSLC itself, not from the spec:

        science/LSAR/GSLC/grids/frequencyA/projection = 32611  (WGS 84 / UTM zone 11N)
        xCoordinates  9090 columns  365485.00 .. 456375.00  step +10.0 m
        yCoordinates 24879 rows    3913612.50 .. 3789222.50 step  -5.0 m
        HH           (24879, 9090) complex64

    RSLC #2 (20081127) is the leg whose start time matches the L2 GSLC exactly - that is the
    pairing that makes the comparison meaningful.

    CAVEATS, recorded before any number is produced:
      * These are SIMULATED sample products (productVersion 0.1.0, absoluteOrbitNumber 0,
        orbitType Custom, UAVSAR-derived over Los Angeles). "Third-party reference" is true;
        "real mission data" is not.
      * JPL's GSLC metadata has demFiles = '' (EMPTY) and demInterpolation = 'biquintic', so the
        reference DEM is UNKNOWN. We use Copernicus 30 m. Any DEM difference moves the sampling
        position and therefore shows up as phase - an exact phase match is not achievable and a
        residual smooth term is expected. Say so in the result rather than tuning to match.
      * Polarisation is HH only despite DHDH in the filename.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\nisar'
Set-ParityLog "$D\nisar_r7.log"

$SRC = 'E:\TestData\s1tbx\SAR\NISAR'
$RSLC2 = "$SRC\NISAR_L1_PR_RSLC_001_005_A_219_2005_DHDH_A_20081127T060959_20081127T061015_P01101_F_N_J_001.h5"
if (-not (Test-Path $RSLC2)) { Log "ABORT: RSLC not found at $RSLC2"; exit 1 }

$gslc = Join-Path $D 'nisar_gslc_utm11n.dim'
$logG = Join-Path $D 'nisar_gslc.log'

# JPL's grid, exactly: UTM 11N, 10 m east x 5 m north. GSLCGeocodingOp takes the east/X spacing
# as pixelSpacingInMeter and the north/Y spacing as pixelSpacingInMeterY (rectangular cells).
$pg = New-ParamFile (Join-Path $D 'nisar_gslc_params.xml') ([ordered]@{
    demName = 'Copernicus 30m Global DEM';
    imgResamplingMethod = 'BISINC_5_POINT_INTERPOLATION';
    mapProjection = 'EPSG:32611';
    pixelSpacingInMeter = '10.0'; pixelSpacingInMeterY = '5.0';
    outputFlattened = 'false'; outputAzimuthCarrier = 'false'; outputPhaseTerms = 'true';
    nodataValueAtSea = 'false' })

$ok = Step 'nisar-gslc' $gslc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('GSLC-Terrain-Correction', "-Ssource=$RSLC2",
        '-p', $pg, '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8') $logG '20g' }
if (-not $ok) { Log 'ABORT nisar-gslc'; exit 1 }

# Stripmap: the data-driven Doppler-centroid table must have engaged, exactly as for Napa.
if (-not (Assert-Log $logG @('residual Doppler centroid built from \d+ coefficient') `
        @('OutOfMemoryError|NullPointerException'))) {
    Log 'FAIL nisar-gslc: no stripmap Doppler-centroid line'; exit 1
}
Log "NISAR GSLC on JPL's grid complete: $gslc"
Log 'next: python validation/gslc_parity/nisar_r7.py to score it against the L2 GSLC'
