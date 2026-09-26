<#
    Campi Flegrei fixtures (ESA request 2026-09-25): S1A descending track 22, IW1 VV, the bursts that
    cover the caldera (ASF burst IDs 022_045938_IW1 / 022_045939_IW1).

    Bursts are selected with a WKT AOI, not burst indices: burst numbering inside a frame depends on
    where each datatake's slice starts, the AOI selects the same ground on every date. The stack's
    burst-ID lock then pairs them.

    No ETAD on any leg (neither chain gets it), so the chains stay like for like. External truth is
    the CNR-IREA P-SBAS series on the same track (E:\TestData\campi_flegrei\reference), which also
    carries no atmospheric correction ("Applied_corrections: No_Corrections").

    Outputs in E:\Output\parity\cf:  <stem>_cf.dim (split)  <stem>_cf_orb.dim (precise orbit)
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$OUT = 'E:\Output\parity\cf'
New-Item -ItemType Directory -Force $OUT | Out-Null
Set-ParityLog "$OUT\fixture.log"

$SLC = 'E:\TestData\campi_flegrei\slc'
$STEMS = @(
    'S1A_IW_SLC__1SDV_20221013T051224_20221013T051252_045419_056E67_FA13',
    'S1A_IW_SLC__1SDV_20230809T051226_20230809T051254_049794_05FCF7_76CC',
    'S1A_IW_SLC__1SDV_20231020T051229_20231020T051257_050844_0620D9_2480')
# caldera + ~5 km margin; no whitespace allowed in gpt args, so the WKT goes through a param file
$aoi = 'POLYGON((14.00 40.76,14.26 40.76,14.26 40.90,14.00 40.90,14.00 40.76))'
$BURSTS = @('45938', '45939')   # ASF burst IDs 022_045938_IW1 / 022_045939_IW1

foreach ($stem in $STEMS) {
    $src = Join-Path $SLC "$stem.zip"
    if (-not (Test-Path $src)) { Log "ABORT: $src missing"; exit 1 }
    $t = Join-Path $OUT "$stem`_cf.dim"
    $o = Join-Path $OUT "$stem`_cf_orb.dim"
    $ps = New-ParamFile (Join-Path $OUT "$stem`_split_params.xml") ([ordered]@{
        subswath = 'IW1'; selectedPolarisations = 'VV'; wktAoi = $aoi })

    $ok = Step "split-$stem" $t { Invoke-Gpt @('TOPSAR-Split', "-Ssource=$src", '-p', $ps,
            '-t', $t, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT split-$stem"; exit 1 }

    $ok = Step "orbit-$stem" $o { Invoke-Gpt @('Apply-Orbit-File', "-Ssource=$t",
            '-PorbitType=Sentinel Precise (Auto Download)', '-PcontinueOnFail=false',
            '-t', $o, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT orbit-$stem"; exit 1 }
    # precise orbit, not a silent restituted/predicted fallback - anchored on the orbit-file attribute
    if (-not (Select-String -Path $o -Pattern 'name="orbit_state_vector_file"[^<]*POEORB' -Quiet)) {
        Log "FAIL orbit-$stem`: orbit_state_vector_file is not a POEORB"; exit 1
    }
    # the AOI must have selected exactly the caldera bursts on every date (a shifted AOI or datatake
    # would otherwise silently change the ground covered)
    $ids = @(Select-String -Path $o -Pattern 'name="burstId"[^>]*>(\d+)<' -AllMatches |
             ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if (($ids -join ',') -ne ($BURSTS -join ',')) {
        Log "FAIL orbit-$stem`: burst IDs [$($ids -join ',')] != expected [$($BURSTS -join ',')]"; exit 1
    }
}
Log 'cf fixture complete'
