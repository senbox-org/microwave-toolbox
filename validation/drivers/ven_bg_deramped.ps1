<#
    DIAGNOSTIC: classical Back-Geocoding with reramp disabled and the model phase output (see the graph).
    -> E:\Output\parity\ven\ven_bgd_stack.dim
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\bg_deramped.log"
$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$out = "$D\ven_bgd_stack.dim"
$ok = Step 'ven_bgd' $out {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_bg_deramped.xml',
        "-Pinput1=$A", "-Pinput2=$C", "-Pdem=$script:DEM", "-Poutput=$out") "$D\ven_bgd.log" }
if (-not $ok) { Log 'ABORT ven_bgd'; exit 1 }
Log 'deramped classical legs complete'
