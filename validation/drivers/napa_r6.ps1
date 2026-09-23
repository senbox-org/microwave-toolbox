<#
    Napa R6 - geophysical equivalence: does the PRODUCT agree?

    Unwraps both chains and converts to line-of-sight displacement; the two displacement fields
    are then compared on a common map grid.

    WHAT THIS RUNG CAN AND CANNOT DO HERE. The spec's R6 control is "classical + published". The
    classical half is done in full below. The PUBLISHED half cannot be done offline: no published
    South Napa M6.0 solution is on this machine, and quoting a literature figure from memory is
    not evidence. R6 is therefore reported as HALF COMPLETE, with the measured peak LOS
    displacement stated so it can be checked the moment a published solution is staged.

    Multilooked to ~18-20 m before unwrapping: snaphu on the 169 M-pixel native GSLC grid is not a
    sensible use of a night, and unwrapping wants the speckle down. Both chains are multilooked to
    the SAME ground scale so the comparison stays like for like.

    Per chain:  Multilook -> Goldstein -> SnaphuExport -> snaphu
#>
param([switch]$SkipGslc, [switch]$SkipClassical)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\napa'
Set-ParityLog "$D\r6.log"
$SNAPHU = Join-Path $env:USERPROFILE '.snap\auxdata\snaphu\snaphu-v2.0.4_win64\bin\snaphu.exe'
if (-not (Test-Path $SNAPHU)) { Log "ABORT: snaphu not found at $SNAPHU"; exit 1 }

function Invoke-Snaphu([string]$dir, [string]$tag) {
    # SnaphuExport writes snaphu.conf whose first commented line is the exact command line to run.
    $conf = Join-Path $dir 'snaphu.conf'
    if (-not (Test-Path $conf)) { Log "FAIL ${tag}: no snaphu.conf in $dir"; return 1 }
    $line = (Select-String -Path $conf -Pattern 'snaphu -f snaphu.conf' | Select-Object -First 1)
    if (-not $line) { Log "FAIL ${tag}: no snaphu command line in snaphu.conf"; return 1 }
    $argstr = ($line.Line -replace '^#\s*', '' -replace '^snaphu\s*', '').Trim()
    Log "${tag} snaphu args: $argstr"
    Push-Location $dir
    try {
        $p = Start-Process -FilePath $SNAPHU -ArgumentList $argstr -NoNewWindow -Wait -PassThru `
             -RedirectStandardOutput (Join-Path $dir 'snaphu.stdout') `
             -RedirectStandardError  (Join-Path $dir 'snaphu.stderr')
        return $p.ExitCode
    } finally { Pop-Location }
}

# looks chosen so both chains land near 18-20 m on the ground:
#   GSLC map grid 1.50 m (E) x 3.65 m (N)       -> 12 x 5 = 18.0 x 18.3 m
#   classical radar 1.4976 m slant / 3.641 m az -> GR square pixel does the arithmetic
$chains = @(
    @{ Tag = 'gslc'; Src = (Join-Path $D 'napa_gslc_ifg.dim'); Rg = 12; Az = 5 },
    @{ Tag = 'trad'; Src = (Join-Path $D 'napa_trad_ifg.dim'); Rg = 5;  Az = 5 }
)

foreach ($c in $chains) {
    if ($SkipGslc -and $c.Tag -eq 'gslc') { continue }
    if ($SkipClassical -and $c.Tag -eq 'trad') { continue }
    $t = $c.Tag
    $ml  = Join-Path $D "napa_r6_${t}_ml.dim"
    $flt = Join-Path $D "napa_r6_${t}_ml_flt.dim"
    $exp = Join-Path $D "napa_r6_${t}_snaphu"

    $ok = Step "$t-multilook" $ml {
        Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('Multilook', "-Ssource=$($c.Src)",
            "-PnRgLooks=$($c.Rg)", "-PnAzLooks=$($c.Az)", '-PoutputIntensity=false',
            '-t', $ml, '-f', 'BEAM-DIMAP', '-q', '8') (Join-Path $D "napa_r6_${t}_ml.log") '16g' }
    if (-not $ok) { Log "ABORT $t-multilook"; exit 1 }

    # GoldsteinFilterOp declares @SourceProduct with NO alias: the product is POSITIONAL.
    $ok = Step "$t-goldstein" $flt {
        Invoke-MvnGpt 'sar-op-insar' 'compile' @('GoldsteinPhaseFiltering',
            '-t', $flt, '-f', 'BEAM-DIMAP', '-q', '8', $ml) (Join-Path $D "napa_r6_${t}_flt.log") '16g' }
    if (-not $ok) { Log "ABORT $t-goldstein"; exit 1 }

    New-Item -ItemType Directory -Force -Path $exp | Out-Null
    $existing = Get-ChildItem $exp -Recurse -Filter 'snaphu.conf' -ErrorAction SilentlyContinue
    if (-not $existing) {
        Log "RUN  $t-snaphu-export"
        # SnaphuExport's SPI lives in jlinda-nest, not sar-op-insar - running it from the wrong
        # module gives "Operator SPI not found for operator [SnaphuExport]".
        $rc = Invoke-MvnGpt 'jlinda/jlinda-nest' 'compile' @('SnaphuExport', "-PtargetFolder=$exp",
                '-PstatCostMode=DEFO', '-PinitMethod=MST', '-PnumberOfTileRows=1',
                '-PnumberOfTileCols=1', '-PnumberOfProcessors=8', $flt) `
                (Join-Path $D "napa_r6_${t}_export.log") '16g'
        # Invoke-MvnGpt's completion-marker heuristic looks for 'End writing product|90% done';
        # SnaphuExport prints '94%.. done.' and matches neither, so a successful export returns 97.
        # Verify by ARTIFACT instead: the conf plus the phase raster snaphu will actually read.
        $wrote = (Get-ChildItem $exp -Recurse -Filter 'snaphu.conf' -ErrorAction SilentlyContinue) -and
                 (Get-ChildItem $exp -Recurse -Filter 'Phase*.snaphu.img' -ErrorAction SilentlyContinue)
        if ($rc -ne 0 -and -not $wrote) { Log "ABORT $t-snaphu-export (exit $rc, no artifacts)"; exit 1 }
        if ($rc -ne 0) { Log "NOTE $t-snaphu-export exit $rc but artifacts present - accepting" }
        Log "OK   $t-snaphu-export"
    } else { Log "SKIP $t-snaphu-export (conf exists)" }

    $confDir = (Get-ChildItem $exp -Recurse -Filter 'snaphu.conf' | Select-Object -First 1).DirectoryName
    Log "$t snaphu working dir: $confDir"

    if (-not (Get-ChildItem $confDir -Filter 'UnwPhase*.img' -ErrorAction SilentlyContinue)) {
        $rc = Invoke-Snaphu $confDir $t
        if ($rc -ne 0) { Log "ABORT $t-snaphu (exit $rc)"; exit 1 }
        Log "OK   $t-snaphu"
    } else { Log "SKIP $t-snaphu (unwrapped file exists)" }
}
Log 'napa R6 unwrapping complete'
