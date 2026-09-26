<#
    Fetch the S1 ETAD products for the Campi Flegrei 72 d pair (2023-08-09, 2023-10-20) into SNAP's ETAD
    cache (%USERPROFILE%\.snap\var\cache\etad, as <name>.SAFE.zip), through SNAP's own ETAD auto-search +
    download (Copernicus Data Space credentials from SNAP's store). validation/gslc_parity/cf_etad_check.py
    then reads the netCDF straight out of those zips.

    How: S1-ETAD-Correction is run once per date in its lightest mode (no image resampling) on the split
    fixture, only to trigger the download. On the maven-exec classpath (sar-op-sentinel1) there is no ETAD
    READER, so the operator downloads the product and then fails (NPE in validateETADProduct: the product
    reads as null). That failure is EXPECTED here: success is judged by the matching zip being in the
    cache afterwards, never by the exit code, and the throwaway output is deleted.

    The search is a temporal OVERLAP query (DataSpaces.constructQuery), so a 2-burst split finds its slice.
    No ETAD exists for 2022-10-13 (CDSE catalogue, 2026-09-26), so the 1 yr pair cannot be checked.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\cf'
$CACHE = Join-Path $env:USERPROFILE '.snap\var\cache\etad'
Set-ParityLog "$D\etad_fetch.log"

$failed = 0
foreach ($date in @('20230809', '20231020')) {
    $pattern = "S1A_IW_ETA__AXDV_$date`T*.SAFE.zip"
    $have = @(Get-ChildItem $CACHE -Filter $pattern -ErrorAction SilentlyContinue)
    if ($have) { Log "ETAD $date already cached: $($have[0].Name)"; continue }

    $src = @(Get-ChildItem $D -Filter "S1A_IW_SLC__1SDV_$date*_cf_orb.dim")
    if ($src.Count -ne 1) { Log "ABORT: expected exactly one fixture for $date, found $($src.Count)"; exit 1 }
    $out = "$D\etad_probe_$date.dim"
    $lg = "$D\etad_probe_$date.log"
    Log "RUN  etad-fetch-$date (a read failure after the download is expected on this classpath)"
    $null = Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('S1-ETAD-Correction', "-Ssource=$($src[0].FullName)",
        '-PresamplingImage=false', '-PoutputPhaseCorrections=false',
        '-t', $out, '-f', 'BEAM-DIMAP', '-q', '4') $lg '8g'
    Remove-Partial $out

    $have = @(Get-ChildItem $CACHE -Filter $pattern -ErrorAction SilentlyContinue)
    if ($have) {
        $sel = Select-String $lg -Pattern "ETAD search: .*selected '([^']+)'" | Select-Object -Last 1
        Log "OK   etad-fetch-$date`: $($have[0].Name) ($([int]($have[0].Length / 1MB)) MB)$(if ($sel) { ' - search selected ' + $sel.Matches[0].Groups[1].Value })"
    } else {
        Log "FAIL etad-fetch-$date`: no $pattern in $CACHE after the run (see $lg - credentials? catalogue?)"
        $failed++
    }
}
if ($failed) { Log "etad fetch: $failed date(s) missing"; exit 1 }
Log 'etad fetch complete'
