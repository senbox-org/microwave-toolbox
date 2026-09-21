<#
    R5b: GSLC vs classical in the radar domain through the terrain-aware bin link (see run_r5b.py).

      1. the R5 control interferogram in BURST geometry (no deburst)   -> ven_trad_ifg_burst.dim
      2. the ETAD-on S1A x S1C GSLC pair built WITH the diagnostic bands (-Diag) -> ven_etadD_*.dim
      3. run_r5b.py r5b <ven_etadD_ifg> <ven_etadD_gslc> <ven_trad_ifg_burst> <P8 floor>

    -WaitForClosure blocks until the R3 closure run has finished (or aborted): only one heavy job may
    run at a time (memory and disk).
#>
param([switch]$WaitForClosure, [double]$FloorConc = 0.999945149102415)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\r5b.log"

if ($WaitForClosure) {
    Log 'waiting for the R3 closure run to finish'
    while ($true) {
        $t = if (Test-Path "$D\closure.stdout") { Get-Content "$D\closure.stdout" -Raw } else { '' }
        if ($t -match 'GATE closure|ABORT') { break }
        Start-Sleep -Seconds 60
    }
    Log 'closure run finished; continuing'
}

$stack = "$D\ven_trad_stack.dim"
$ifgB  = "$D\ven_trad_ifg_burst.dim"
if (-not (Test-Path $stack)) { Log "ABORT: $stack missing - run ven_classical.ps1"; exit 1 }
$ok = Step 'ven_trad-ifg-burst' $ifgB {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifg_ven_burst.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifgB") "$D\ven_trad_ifg_burst.log" }
if (-not $ok) { Log 'ABORT classical burst ifg'; exit 1 }

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $C -Tag ven_etadD -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT GSLC pair with diagnostic bands'; exit 1 }

& python "$script:PY\run_r5b.py" r5b "$D\ven_etadD_ifg.dim" "$D\ven_etadD_gslc.dim" $ifgB $FloorConc
Log "run_r5b exit $LASTEXITCODE"
exit $LASTEXITCODE
