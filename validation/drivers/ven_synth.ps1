<#
    Synthetic pairs for R1 / R2-lite. SLC_B = SLC_A (S1A bursts 4-6, orbit-corrected, ETAD-off) with
    every date shifted +12 days and the samples multiplied by exp(-j*phi(range column)):
      syn0 : phi = 0     -> the generator floor (a zero-perturbation pair must give zero phase)
      syn1 : phi = lobe  -> R1 (GSLC, ramp off) and R2-lite (retention: GSLC ramp on/off vs classical)

    Because only the DATE moves, the geometry of B is bit-identical to A's and the lobe is the only
    difference between the legs. Requires the go/no-go spike in Task 8 to have passed.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\synth.log"
$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb.dim' | Select-Object -First 1).FullName
if (-not $A) { Log 'ABORT: ETAD-off S1A fixture missing'; exit 1 }

$B0 = "$D\SYN_B0_b4-6_orb.dim"
$B1 = "$D\SYN_B1_b4-6_orb.dim"
foreach ($b in @(@{ P = $B0; K = 'zero' }, @{ P = $B1; K = 'lobe' })) {
    $ok = Step "make-$($b.K)" $b.P { & python "$script:PY\synth.py" make $A $b.P 12 $b.K | Out-Host; $LASTEXITCODE }
    if (-not $ok) { Log "ABORT make-$($b.K)"; exit 1 }
}

# GSLC pairs. -Diag adds diag_rangeIndex to the master GSLC so the lobe is evaluated exactly.
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B0 -Tag syn0 -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn0'; exit 1 }
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B1 -Tag syn1 -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn1'; exit 1 }
# Same GSLC + stack are reused (Step skips them); only the ifg is rebuilt with the ramp option ON.
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B1 -Tag syn1 -Diag -Ramp
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn1 ramp'; exit 1 }

# Classical on the same synthetic pair (BISINC control settings, ETAD-off inputs).
$stack = "$D\syn1_trad_stack.dim"; $ifg = "$D\syn1_trad_ifg_deb.dim"
$ok = Step 'syn1-trad-coreg' $stack { Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_coreg_ven.xml',
        "-Pinput1=$A", "-Pinput2=$B1", "-Pdem=$script:DEM", '-Presampling=BISINC_5_POINT_INTERPOLATION',
        "-Poutput=$stack") "$D\syn1_trad_coreg.log" }
if (-not $ok) { Log 'ABORT syn1 classical coreg'; exit 1 }
$ok = Step 'syn1-trad-ifg' $ifg { Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifgdeb_ven.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifg") "$D\syn1_trad_ifg.log" }
if (-not $ok) { Log 'ABORT syn1 classical ifg'; exit 1 }
Log 'synthetic products complete'
