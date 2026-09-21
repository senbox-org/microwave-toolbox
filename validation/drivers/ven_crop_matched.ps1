<#
    MATCHED-CONFIGURATION classical fixture for the crop-validity gate.

    The first crop-gate run compared the fixture chain (ground-window coherence, staged DEM, bicubic
    DEM resampling, ESD) against a full-scene control built differently (coherence window 2x10,
    auto DEM, BILINEAR resampling, NO ESD). That measured chain differences, not the crop. This
    driver rebuilds the 3-burst fixture with the control's own recorded parameters
    (validation/graphs/trad_s1_coreg_ctl.xml, trad_s1_ifgdeb_ctl.xml) so that the only differences
    left are the crop and ONE deliberate deviation: the interferogram reads the staged Copernicus 30 m
    GeoTIFF instead of the auto-downloaded DEM (same data; the auto reader is serialised by a
    synchronised getSample and does not finish in practice - see trad_s1_ifgdeb_ctl.xml).

      ven_ctl_stack.dim, ven_ctl_ifg_deb.dim   in E:\Output\parity\ven
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\crop_matched.log"

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
if (-not $A -or -not $C) { Log 'ABORT: ETAD fixtures missing - run ven_fixture.ps1'; exit 1 }

$stack = "$D\ven_ctl_stack.dim"
$ifg   = "$D\ven_ctl_ifg_deb.dim"
$ok = Step 'ven_ctl-coreg' $stack {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_coreg_ctl.xml',
        "-Pinput1=$A", "-Pinput2=$C", "-Poutput=$stack") "$D\ven_ctl_coreg.log" }
if (-not $ok) { Log 'ABORT ven_ctl-coreg'; exit 1 }
$ok = Step 'ven_ctl-ifg' $ifg {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifgdeb_ctl.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifg") "$D\ven_ctl_ifg.log" }
if (-not $ok) { Log 'ABORT ven_ctl-ifg'; exit 1 }
Log 'matched-configuration fixture complete'
