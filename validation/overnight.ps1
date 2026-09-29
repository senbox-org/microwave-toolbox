<#
    Overnight validation campaign: Venezuela IW3 VV S1A/S1C, plus the ETAD ablation.

    DESIGN RULES, learned the hard way earlier in this work:
      * SERIAL. Docker's VM has 15 GB and SNAP reserves a large heap; running these concurrently
        OOM-killed COMPASS once already (SIGKILL/137 with no attribution).
      * Every stage's exit code is read from the PROCESS, never through a pipe. A pipeline's last
        stage reporting success has masked three real failures in this work.
      * A failing stage does NOT abort the campaign -- it is recorded and the next stage runs, so a
        night is never lost to one bad step.
      * Each stage is SKIPPED if its final output already exists, making the script resumable. Some
        stages take hours; re-running them from scratch after an interruption wastes the night.
      * Nothing is deleted. Disk is checked before each stage and the campaign stops cleanly if it
        would run out, rather than dying mid-write and corrupting a product.

    ASCII only, and no Python here-strings: both have broken this file before (PowerShell 5.1 reads
    a BOM-less file as ANSI; PS parses Python regex metacharacters as PowerShell).
#>
param(
    [string]$Root = 'E:\ESA\microwave-toolbox\validation',
    [int]$MinFreeGB = 25
)

$ErrorActionPreference = 'Continue'   # a stage failure must not kill the campaign
$campaignLog = 'E:\Output\harness\overnight.log'
$summary = @()

function Log($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    $line | Add-Content -Path $campaignLog
    Write-Host $line
}

function Get-FreeGB {
    (Get-PSDrive E).Free / 1GB
}

# Runs one stage. Returns $true on success. Never throws: the campaign must survive any stage.
function Invoke-Stage {
    param(
        [string]$Name,
        [string]$ExpectPath,      # if this exists, the stage is already done
        [scriptblock]$Body
    )
    if ($ExpectPath -and (Test-Path $ExpectPath)) {
        Log "SKIP  $Name (output exists: $ExpectPath)"
        $script:summary += [pscustomobject]@{ Stage = $Name; Result = 'SKIP'; Detail = 'already present' }
        return $true
    }
    $free = Get-FreeGB
    if ($free -lt $MinFreeGB) {
        Log "STOP  $Name -- only $([math]::Round($free,1)) GB free, need $MinFreeGB. Halting cleanly."
        $script:summary += [pscustomobject]@{ Stage = $Name; Result = 'STOPPED'; Detail = "low disk ($([math]::Round($free,1)) GB)" }
        return $false
    }
    Log "START $Name  (free $([math]::Round($free,1)) GB)"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $rc = 1
    try { $rc = & $Body } catch { Log "ERROR $Name threw: $($_.Exception.Message)"; $rc = 99 }
    $sw.Stop()
    $mins = [math]::Round($sw.Elapsed.TotalMinutes, 1)
    if ($rc -eq 0 -and (-not $ExpectPath -or (Test-Path $ExpectPath))) {
        Log "OK    $Name in $mins min"
        $script:summary += [pscustomobject]@{ Stage = $Name; Result = 'OK'; Detail = "$mins min" }
        return $true
    }
    # Exit 0 with no output is a FALSE SUCCESS -- distinguish it, because it has fooled this work before.
    if ($rc -eq 0) {
        Log "FAIL  $Name exited 0 but '$ExpectPath' is absent (false success)"
        $script:summary += [pscustomobject]@{ Stage = $Name; Result = 'FAIL'; Detail = 'exit 0, no output' }
    } else {
        Log "FAIL  $Name exit $rc after $mins min"
        $script:summary += [pscustomobject]@{ Stage = $Name; Result = 'FAIL'; Detail = "exit $rc" }
    }
    return $false
}

function Snap-Case {
    param([string]$Case, [string]$Variant)
    $args = @('-NoProfile', '-File', (Join-Path $Root 'engines\snap\run.ps1'), (Join-Path $Root "cases\$Case.yml"))
    if ($Variant) { $args += @('-Variant', $Variant) }
    $p = Start-Process powershell -ArgumentList $args -Wait -PassThru -NoNewWindow
    return $p.ExitCode
}

function Isce3-Case {
    param([string]$Case)
    # MSYS_NO_PATHCONV stops Git-Bash-style path mangling of /engines and /cases.
    $env:MSYS_NO_PATHCONV = '1'
    $p = Start-Process docker -ArgumentList @('compose', 'run', '--rm', 'isce3',
        "/engines/isce3/run.sh", "/cases/$Case.yml") -Wait -PassThru -NoNewWindow -WorkingDirectory $Root
    return $p.ExitCode
}

Log "================ overnight campaign start ================"
Log "free disk: $([math]::Round((Get-FreeGB),1)) GB   min required per stage: $MinFreeGB GB"

$O = 'E:\Output\harness'

# ---------------------------------------------------------------------------------------------
# 1. PRIMARY: Venezuela IW3 VV, GSLC InSAR on the NATIVE_ANISOTROPIC grid (SNAP).
#    Ordered first because it is the explicit request and everything else is secondary to it.
# ---------------------------------------------------------------------------------------------
$ok = Invoke-Stage 'snap-gslc-iw3-native' "$O\snap\gslc_ven_iw3_native\gslc_ven_iw3_native_ifg.dim" {
    Snap-Case 'gslc_ven_iw3_native' ''
}

# ---------------------------------------------------------------------------------------------
# 2. Classic vs GSLC, IW3 VV, on a SQUARE grid (Terrain-Correction cannot do anisotropic).
#    Two variants of the same case, so the pair is directly comparable.
# ---------------------------------------------------------------------------------------------
Invoke-Stage 'snap-gslc-iw3-square' "$O\snap\insar_ven_iw3_square\insar_ven_iw3_square_ifg.dim" {
    Snap-Case 'insar_ven_iw3_square' 'gslc'
} | Out-Null

Invoke-Stage 'snap-classic-iw3-square' "$O\snap\insar_ven_iw3_square\classic\insar_ven_iw3_square_ifg.dim" {
    Snap-Case 'insar_ven_iw3_square' 'classic'
} | Out-Null

# ---------------------------------------------------------------------------------------------
# 3. COMPASS on the same IW3 pair. Runs AFTER SNAP so the driver can match COMPASS's explicit
#    x_posting/y_posting to whatever grid NATIVE_ANISOTROPIC actually produced -- COMPASS has no
#    equivalent setting, so reading SNAP's transform is the only way onto one lattice.
# ---------------------------------------------------------------------------------------------
$snapDim = "$O\snap\gslc_ven_iw3_native\gslc_ven_iw3_native_ifg.dim"
if (Test-Path $snapDim) {
    $m = Select-String -Path $snapDim -Pattern 'IMAGE_TO_MODEL_TRANSFORM>([^<]+)' | Select-Object -First 1
    if ($m) {
        $v = ($m.Matches[0].Groups[1].Value -split '[,\s]+') | Where-Object { $_ } | ForEach-Object { [double]$_ }
        $dx = $v[0]; $dy = [math]::Abs($v[3])
        Log "SNAP derived grid: dx=$dx dy=$dy m -- passing to COMPASS as x_posting/y_posting"
        $caseFile = Join-Path $Root 'cases\gslc_ven_iw3_native.yml'
        $txt = Get-Content $caseFile -Raw
        if ($txt -notmatch 'x_posting') {
            $txt = $txt -replace '(?m)^(    analytic_carrier_removal: true)',
                "`$1`n    x_posting: $dx`n    y_posting: $dy"
            Set-Content -Path $caseFile -Value $txt -Encoding ascii
            Log "case updated with x_posting=$dx y_posting=$dy"
        }
    }
} else {
    Log "WARN  SNAP ifg absent; COMPASS will use the case's own posting and may land on a different lattice"
}

Invoke-Stage 'compass-iw3' "$O\isce3\gslc_ven_iw3_native\ifg.tif" {
    Isce3-Case 'gslc_ven_iw3_native'
} | Out-Null

# ---------------------------------------------------------------------------------------------
# 4. ETAD ablation -- with and without.
#    NOT on Venezuela: no ETAD product exists for 2026-06-23/24, and ETAD is scene-and-date
#    specific, so it cannot be substituted or synthesised. The S1B 2020-08-15 / 2020-09-08 pair has
#    matching AXDV ETAD products staged, so the ablation runs there and is reported as such.
# ---------------------------------------------------------------------------------------------
foreach ($v in @('noetad', 'etad')) {
    $cf = Join-Path $Root "cases\etad_s1b_iw_$v.yml"
    if (-not (Test-Path $cf)) { Log "SKIP  etad-$v (case file not written)"; continue }
    Invoke-Stage "snap-etad-$v" "$O\snap\etad_s1b_iw_$v\etad_s1b_iw_${v}_ifg.dim" {
        Snap-Case "etad_s1b_iw_$v" ''
    } | Out-Null
}

# ---------------------------------------------------------------------------------------------
# 5. Diffs + renders for whatever completed. Each is independent, so a missing input skips only
#    its own comparison.
# ---------------------------------------------------------------------------------------------
$pairs = @(
    @{ Name = 'diff-snap-vs-compass-iw3';
       Snap = "$O\snap\gslc_ven_iw3_native\gslc_ven_iw3_native_ifg.data";
       Other = "$O\isce3\gslc_ven_iw3_native\ifg.tif";
       Case = 'gslc_ven_iw3_native' },
    @{ Name = 'diff-gslc-vs-classic-iw3';
       Snap = "$O\snap\insar_ven_iw3_square\insar_ven_iw3_square_ifg.data";
       Other = "$O\snap\insar_ven_iw3_square\classic\insar_ven_iw3_square_ifg.data";
       Case = 'insar_ven_iw3_square' }
)
foreach ($p in $pairs) {
    if (-not (Test-Path $p.Snap))  { Log "SKIP  $($p.Name): missing $($p.Snap)";  continue }
    if (-not (Test-Path $p.Other)) { Log "SKIP  $($p.Name): missing $($p.Other)"; continue }
    Invoke-Stage $p.Name "" {
        $env:MSYS_NO_PATHCONV = '1'
        $sn = $p.Snap  -replace '^E:\\Output\\harness', '/work' -replace '\\', '/'
        $ot = $p.Other -replace '^E:\\Output\\harness', '/work' -replace '\\', '/'
        $rp = "/work/reports/$($p.Case)/$($p.Name).md"
        $proc = Start-Process docker -ArgumentList @('compose', 'run', '--rm', '--entrypoint', 'bash',
            'isce3', '-lc',
            "micromamba run -n base python /compare/diff_fringes.py --case /cases/$($p.Case).yml --snap $sn --isce3 $ot --report $rp") `
            -Wait -PassThru -NoNewWindow -WorkingDirectory $Root
        return $proc.ExitCode
    } | Out-Null
}

# Native-resolution crops for every interferogram produced. A whole-scene view aliases into noise at
# these fringe rates, so crops are what can actually be judged.
Get-ChildItem "$O\snap" -Recurse -Filter '*_ifg.data' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $tag = $_.Parent.Name + '_' + $_.Name.Replace('_ifg.data', '')
    Invoke-Stage "render-$tag" "" {
        $env:MSYS_NO_PATHCONV = '1'
        $src = $_.FullName -replace '^E:\\Output\\harness', '/work' -replace '\\', '/'
        $proc = Start-Process docker -ArgumentList @('compose', 'run', '--rm', '--entrypoint', 'bash',
            'isce3', '-lc',
            "micromamba run -n base python /compare/render_crop.py $src /work/reports/crops/$tag.png `"$tag`" 1200") `
            -Wait -PassThru -NoNewWindow -WorkingDirectory $Root
        return $proc.ExitCode
    } | Out-Null
}

Log "================ campaign summary ================"
$summary | ForEach-Object { Log ("{0,-32} {1,-8} {2}" -f $_.Stage, $_.Result, $_.Detail) }
Log ("free disk at end: {0:N1} GB" -f (Get-FreeGB))
$summary | Export-Csv -Path 'E:\Output\harness\overnight_summary.csv' -NoTypeInformation
Log "summary CSV: E:\Output\harness\overnight_summary.csv"
