<#
    Classical S1 TOPS control on the Venezuela 3-burst ETAD fixtures (S1A x S1C), plus the P8
    reproducibility twin (identical except the Back-Geocoding kernel).

      ven_trad_stack.dim       coreg (Back-Geocoding + ESD)              BISINC_5_POINT
      ven_trad_ifg_deb.dim     ifg (flat+topo removed, cohWin 100 m) + deburst  <- the R5 control
      ven_trad_stack_deb.dim   the coreg stack debursted, so both LEGS sit on the radar grid (R4)
      ven_trad_alt_stack.dim / ven_trad_alt_ifg_deb.dim                  BICUBIC  <- the P8 twin

    Runs through maven-exec GPT (sar-op-sentinel1 classpath) so the cohWinSizeMeters fix is live;
    that needs sar-op-insar installed into ~/.m2 first (done once in Task 1).
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\classical.log"

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
if (-not $A -or -not $C) { Log 'ABORT: ETAD fixtures missing - run ven_fixture.ps1'; exit 1 }

$G_COREG = 'validation/graphs/trad_s1_coreg_ven.xml'
$G_IFG   = 'validation/graphs/trad_s1_ifgdeb_ven.xml'

foreach ($v in @(@{ Tag = 'ven_trad';     Res = 'BISINC_5_POINT_INTERPOLATION' },
                 @{ Tag = 'ven_trad_alt'; Res = 'BICUBIC_INTERPOLATION' })) {
    $stack = "$D\$($v.Tag)_stack.dim"
    $ifg   = "$D\$($v.Tag)_ifg_deb.dim"
    $l1 = "$D\$($v.Tag)_coreg.log"
    $l2 = "$D\$($v.Tag)_ifg.log"

    $ok = Step "$($v.Tag)-coreg" $stack {
        Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @($G_COREG, "-Pinput1=$A", "-Pinput2=$C",
            "-Pdem=$script:DEM", "-Presampling=$($v.Res)", "-Poutput=$stack") $l1 }
    if (-not $ok) { Log "ABORT $($v.Tag)-coreg"; exit 1 }

    $ok = Step "$($v.Tag)-ifg" $ifg {
        Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @($G_IFG, "-Pinput1=$stack",
            "-Pdem=$script:DEM", "-Poutput=$ifg") $l2 }
    if (-not $ok) { Log "ABORT $($v.Tag)-ifg"; exit 1 }

    # Proof the fixed code ran: the window is now derived on the ground. At IW3's ~43.9 deg
    # incidence a 100 m request gives 30 range pixels (measured 2026-09-20); the old slant-spacing
    # formula gave 43. The pattern accepts 26-34 and so rejects 43.
    if (-not (Assert-Log $l2 @('cohWinSizeMeters=100\.0 m -> cohWinAz=7, cohWinRg=(2[6-9]|3[0-4])') @('OutOfMemoryError|NullPointerException'))) {
        Log "FAIL $($v.Tag): the ground-corrected coherence window did not engage"; exit 1
    }
}
# Legs on the debursted radar grid for R4 (TOPSAR-Deburst is unchanged by this work: installed gpt).
$sd = "$D\ven_trad_stack_deb.dim"
$ok = Step 'ven_trad-stack-deburst' $sd { Invoke-Gpt @('TOPSAR-Deburst', "-Ssource=$D\ven_trad_stack.dim",
        '-t', $sd, '-f', 'BEAM-DIMAP', '-q', '8') }
if (-not $ok) { Log 'ABORT stack deburst'; exit 1 }
Log 'classical control + P8 twin complete'
