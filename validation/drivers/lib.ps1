# Shared helpers for the Venezuela parity drivers. Dot-source it:  . "$PSScriptRoot\lib.ps1"
# ASCII ONLY. Every -P argument is quoted. No Python here-strings (they break in PowerShell).
#
# Two ways to run an operator, and WHICH ONE MATTERS:
#   Invoke-Gpt      installed SNAP (C:\Program Files\esa-snap). Use ONLY for operators this work
#                   never changed: TOPSAR-Split, Apply-Orbit-File, S1-ETAD-Correction.
#   Invoke-MvnGpt   maven-exec GPT from the repo. Runs CURRENT source without deploying, so it is
#                   the only way to exercise the pre-flight fixes (cohWinSizeMeters, GSLC lock).
#                   NOTE: a sar-op-insar change is invisible to a run launched from another module
#                   until `mvn -o -pl sar-op-insar install -DskipTests` has put it in ~/.m2.
#
# Exit code is the verdict, never the log text. An exit-0 run that produced no product is a FALSE
# success (it has misled this work before), so Step re-checks that the target exists.

$script:GPT  = 'C:\Program Files\esa-snap\bin\gpt.exe'
$script:REPO = 'E:\ESA\microwave-toolbox'
$script:LOG  = $null

function Set-ParityLog([string]$Path) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    $script:LOG = $Path
}

function Log([string]$m) {
    $l = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $l
    if ($script:LOG) { $l | Add-Content $script:LOG }
}

function Invoke-Gpt([string[]]$GptArgs, [string]$StepLog = $null) {
    if (-not $StepLog) { $StepLog = $script:LOG }
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        if ($StepLog -eq $script:LOG) {
            # one file, opened once: teeing the same path twice fails on the file lock
            & $script:GPT @GptArgs 2>&1 | Tee-Object -FilePath $script:LOG -Append | Out-Host
        } else {
            & $script:GPT @GptArgs 2>&1 | Tee-Object -FilePath $StepLog | Tee-Object -FilePath $script:LOG -Append | Out-Host
        }
        return [int]$LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
}

# Module/Scope pairs that are known to work (see the project memory notes):
#   GSLC-Terrain-Correction, CreateStack  -> sar-op-sar-processing , test
#   Interferogram                         -> sar-op-insar          , compile
#   classical graphs (Back-Geocoding, ESD, Interferogram, Deburst) -> sar-op-sentinel1 , compile
function Invoke-MvnGpt([string]$Module, [string]$Scope, [string[]]$GptArgs, [string]$StepLog,
                       [string]$Xmx = '12g', [string]$ExtraOpts = '') {
    foreach ($a in $GptArgs) {
        if ($a -match '\s') { throw "argument contains whitespace (use a -p parameter file): '$a'" }
    }
    $argStr = ($GptArgs | ForEach-Object { $_.Replace('\', '/') }) -join ' '
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $script:REPO
    $prevOpts = $env:MAVEN_OPTS
    try {
        $env:MAVEN_OPTS = ("-Xmx$Xmx $ExtraOpts").Trim()
        $mvnArgs = @('-o', '-q', '-pl', $Module, 'exec:java', '-Dexec.mainClass=org.esa.snap.core.gpf.main.GPT',
                     "-Dexec.classpathScope=$Scope", "-Dexec.args=$argStr")
        if ($StepLog -eq $script:LOG) {
            & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $script:LOG -Append | Out-Host
        } else {
            & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $StepLog | Tee-Object -FilePath $script:LOG -Append | Out-Host
        }
        $rc = [int]$LASTEXITCODE
        # A killed or crashed maven-exec run can still return 0 and leave a partial product behind
        # (seen 2026-09-20: a run killed at 60% logged OK). A finished graph run prints "...90% done."
        # and a finished single-operator run logs "End writing product". (gpt.exe's short steps print
        # neither, so this check is NOT applied in Invoke-Gpt.) The FIRST version of this check looked
        # only for '90% done', which single-operator runs never print - it flagged a good GSLC product.
        if ($rc -eq 0 -and $StepLog -ne $script:LOG -and
            -not (Select-String -Path $StepLog -Pattern 'End writing product|90% done' -Quiet)) {
            Log "no completion marker in $StepLog - treating the run as incomplete"
            return 97
        }
        return $rc
    } finally { Pop-Location; $ErrorActionPreference = $prev; $env:MAVEN_OPTS = $prevOpts }
}

# Operator parameters go in a file (-p) so no argument ever needs a space or a quote.
function New-ParamFile([string]$Path, $Params) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<parameters>')
    foreach ($k in $Params.Keys) { [void]$sb.AppendLine("  <$k>$($Params[$k])</$k>") }
    [void]$sb.AppendLine('</parameters>')
    [IO.File]::WriteAllText($Path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    return $Path
}

function Remove-Partial([string]$Dim) {
    $d = [IO.Path]::ChangeExtension($Dim, '.data')
    if (Test-Path $Dim) { Remove-Item $Dim -Force }
    if (Test-Path $d)   { Remove-Item $d -Recurse -Force }
    if (Test-Path "$Dim.ok") { Remove-Item "$Dim.ok" -Force }
}

# $Run returns the process exit code. A step counts as DONE only when its completion sentinel
# "<Target>.ok" exists; the sentinel is written after a verified-successful run. A product WITHOUT a
# sentinel is a partial - most often from a run killed from outside (memory reaper, closed terminal),
# where no exit code ever reaches this script and the DIMAP writer has already written a well-formed
# .dim header - so it is deleted and the step re-run. (Before 2026-09-26 Step skipped on "the .dim
# exists", and killed runs were only recovered by deleting the partial by hand.)
function Test-StepDone([string]$Target) { return (Test-Path "$Target.ok") }

function Set-StepDone([string]$Target, [string]$Note = '') {
    [IO.File]::WriteAllText("$Target.ok", ("{0:o} {1}" -f (Get-Date), $Note))
}

function Step([string]$Name, [string]$Target, [scriptblock]$Run) {
    if (Test-StepDone $Target) { Log "SKIP $Name (done)"; return $true }
    if (Test-Path $Target) {
        Log "PARTIAL $Name`: '$Target' exists without a completion sentinel - deleting and re-running"
        Remove-Partial $Target
    }
    Log "RUN  $Name"
    $rc = [int](& $Run | Select-Object -Last 1)
    if ($rc -eq 97) {
        # heuristic verdict: set the product aside rather than destroy it, and never leave it where
        # a later run would treat it as finished (the .data directory goes with it)
        $d = [IO.Path]::ChangeExtension($Target, '.data')
        if (Test-Path $Target) { Move-Item $Target "$Target.incomplete" -Force }
        if (Test-Path $d) { Move-Item $d "$d.incomplete" -Force }
        Log "FAIL $Name incomplete (no completion marker); product renamed to $Target.incomplete"
        return $false
    }
    if ($rc -ne 0) { Log "FAIL $Name exit $rc"; Remove-Partial $Target; return $false }
    if (-not (Test-Path $Target)) { Log "FAIL $Name exit 0 but '$Target' absent"; return $false }
    Set-StepDone $Target $Name
    Log "OK   $Name"
    return $true
}

# Gate on a log line proving the code path ran (see validation/drivers/assert_log.py).
function Assert-Log([string]$LogFile, [string[]]$Require, [string[]]$Forbid = @()) {
    $a = @("$script:REPO\validation\drivers\assert_log.py", $LogFile)
    foreach ($r in $Require) { $a += @('--require', $r) }
    foreach ($f in $Forbid)  { $a += @('--forbid', $f) }
    & python @a | Out-Host
    return ($LASTEXITCODE -eq 0)
}

$script:DEM = 'E:/TestData/dem/copernicus30_venezuela_orbit106.tif'
$script:PY  = "$script:REPO\validation\gslc_parity"
