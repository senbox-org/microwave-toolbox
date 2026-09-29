<#
    Venezuela IW3 VV bursts 4-6 fixtures for S1A (23 Jun), S1C (24 Jun), S1D (30 Jun 2026).

    TOPSAR-Split ONLY (spec Rule 1): never SubsetOp, never a range crop. Bursts 4-6 carry the
    same burstIds (225587/8/9) in all three products. S1C has 23767 samples per line against
    S1A/S1D's 23665 - "full range width" is not the same number on every leg.

    Outputs in E:\Output\parity\ven:
      <stem>_b4-6_orb.dim         precise orbit, NO ETAD   (A, C, D: the R3 closure legs and the synthetic base)
      <stem>_b4-6_orb_etad.dim    precise orbit + ETAD option 1   (A and C only: no S1D ETAD exists)

    The ETAD source product NAME must keep the original S1 stem or ETADUtils.getProductIndex
    cannot parse the timestamps from it, and ETAD auto-search cannot work on a burst subset
    (a ~9 s window against a ~30 s frame matches nothing) - so -PetadFile is always explicit.
    ETAD must be option 1 (resamplingImage=true + outputPhaseCorrections=true): option 2 is a
    guaranteed silent no-op for the GSLC chain.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$OUT = 'E:\Output\parity\ven'
Set-ParityLog "$OUT\fixture.log"
$FB = 4; $LB = 6

$SRC = @(
    @{ N = 'A'; F = 'E:\Data\Venezuela\S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE.zip';
       ETAD = 'C:\Users\luis_\.snap\var\cache\etad\S1A_IW_ETA__AXDV_20260623T225050_20260623T225120_065103_0834C8_D7B7.SAFE.zip' },
    @{ N = 'C'; F = 'E:\Data\Venezuela\S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE.zip';
       ETAD = 'C:\Users\luis_\.snap\var\cache\etad\S1C_IW_ETA__AXDV_20260624T224958_20260624T225025_008254_010515_8B0D.SAFE.zip' },
    @{ N = 'D'; F = 'E:\Data\Venezuela\S1D_IW_SLC__1SDV_20260630T225009_20260630T225040_003472_006226_2543.SAFE.zip';
       ETAD = $null }
)
foreach ($s in $SRC) {
    if (-not (Test-Path $s.F)) { Log "ABORT: source $($s.F) not found"; exit 1 }
    $stem = [IO.Path]::GetFileNameWithoutExtension($s.F) -replace '\.SAFE$', ''
    $t = Join-Path $OUT "$stem`_b$FB-$LB.dim"
    $o = Join-Path $OUT "$stem`_b$FB-$LB`_orb.dim"
    $e = Join-Path $OUT "$stem`_b$FB-$LB`_orb_etad.dim"

    $ok = Step "split-$($s.N)" $t { Invoke-Gpt @('TOPSAR-Split', "-Ssource=$($s.F)", '-Psubswath=IW3',
            '-PselectedPolarisations=VV', "-PfirstBurstIndex=$FB", "-PlastBurstIndex=$LB",
            '-t', $t, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT split-$($s.N)"; exit 1 }

    $ok = Step "orbit-$($s.N)" $o { Invoke-Gpt @('Apply-Orbit-File', "-Ssource=$t",
            '-PorbitType=Sentinel Precise (Auto Download)', '-PcontinueOnFail=false',
            '-t', $o, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT orbit-$($s.N)"; exit 1 }

    if ($s.ETAD) {
        if (-not (Test-Path $s.ETAD)) { Log "ABORT: ETAD product missing at $($s.ETAD)"; exit 1 }
        $ok = Step "etad-$($s.N)" $e { Invoke-Gpt @('S1-ETAD-Correction', "-Ssource=$o", "-PetadFile=$($s.ETAD)",
                '-PresamplingImage=true', '-PoutputPhaseCorrections=true',
                '-PsumOfRangeCorrections=true', '-PtroposphericCorrectionRg=true',
                '-PionosphericCorrectionRg=true', '-PgeodeticCorrectionRg=true',
                '-PoutputETADPhaseBand=true',
                '-t', $e, '-f', 'BEAM-DIMAP', '-q', '8') }
        if (-not $ok) { Log "ABORT etad-$($s.N)"; exit 1 }
    }
}

& python "$script:PY\ven_checks.py" fixture $OUT
if ($LASTEXITCODE -ne 0) { Log 'FIXTURE CHECK FAILED'; exit 1 }
Log 'fixture complete'
