<#
    R3 phase closure on Venezuela S1A / S1C / S1D, ETAD-OFF on all three legs (no S1D ETAD product
    exists anywhere, so this is a deliberately DIFFERENT configuration from the pairwise rungs and
    every output says so).

    Three independent pair runs, one heavy job at a time. Each is its own GSLC -> auto-stack -> ifg,
    so each pair has its own bias estimate, burst lock and coreg - which is the whole point: pairwise
    products formed from the SAME three SLCs by plain complex arithmetic would close identically to
    zero and test nothing.

        AC = A*conj(C)  (ref A, sec C)      CD = C*conj(D)  (ref C, sec D)      AD = A*conj(D)  (ref A, sec D)
        closure = AC * CD * conj(AD)
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\closure.log"
function Pick([string]$tag) { (Get-ChildItem $D -Filter "S1$tag`_IW_SLC*_b4-6_orb.dim" | Select-Object -First 1).FullName }
$A = Pick 'A'; $C = Pick 'C'; $Dd = Pick 'D'
if (-not $A -or -not $C -or -not $Dd) { Log 'ABORT: ETAD-off fixtures missing - run ven_fixture.ps1'; exit 1 }

foreach ($p in @(@{ T = 'clos_AC'; R = $A; S = $C }, @{ T = 'clos_CD'; R = $C; S = $Dd }, @{ T = 'clos_AD'; R = $A; S = $Dd })) {
    & "$PSScriptRoot\ven_gslc.ps1" -Ref $p.R -Sec $p.S -Tag $p.T
    if ($LASTEXITCODE -ne 0) { Log "ABORT pair $($p.T)"; exit 1 }
}
& python "$script:PY\closure.py" run "$D\clos_AC_ifg.dim" "$D\clos_CD_ifg.dim" "$D\clos_AD_ifg.dim"
exit $LASTEXITCODE
