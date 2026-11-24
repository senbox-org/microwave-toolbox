<#
.SYNOPSIS
    Create a git worktree of microwave-toolbox, build it, and print the SNAP Desktop run-config parameters for it.

.DESCRIPTION
    The worktree is created next to the repository (default ..\mtbx-wt\<Name>), not inside it, so the
    E:\ESA IntelliJ project does not index it. snap-engine and snap-desktop are shared: the worktree
    resolves them from the 15.0.0-SNAPSHOT jars in the local Maven repository.

    The build runs 'mvn package', never 'install'. Installing from a worktree would overwrite the shared
    eu/esa/microwavetbx SNAPSHOT jars that the main checkout, snap-gpt-tests and snap-reader-tests use.
    Pass -OwnRepo to give the worktree its own local Maven repository if it must install.

.PARAMETER Name
    Worktree folder name and branch name.

.PARAMETER Base
    Commit or branch to start a new branch from. Ignored if the branch already exists. Default: master.

.PARAMETER Root
    Folder that holds the worktrees. Default: <repo>\..\mtbx-wt.

.PARAMETER SkipBuild
    Create the worktree only.

.PARAMETER Online
    Let Maven download missing artifacts (the default build is offline, -o).

.PARAMETER OwnRepo
    Build with a separate local Maven repository (E:\cache\.m2-<Name>). The first build downloads everything.

.EXAMPLE
    .\tools\new-worktree.ps1 nisar-gcov
.EXAMPLE
    .\tools\new-worktree.ps1 my-fix -Base validation -SkipBuild
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^[A-Za-z0-9._/-]+$')]
    [string]$Name,
    [string]$Base = 'master',
    [string]$Root,
    [switch]$SkipBuild,
    [switch]$Online,
    [switch]$OwnRepo
)

$ErrorActionPreference = 'Stop'

$repo = (git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw "Not inside a git repository: $PSScriptRoot" }
$repo = [IO.Path]::GetFullPath($repo)
$snapRoot = Split-Path $repo -Parent

if (-not $Root) { $Root = Join-Path $snapRoot 'mtbx-wt' }
$Root = [IO.Path]::GetFullPath($Root)
$folder = $Name -replace '/', '-'
$wt = Join-Path $Root $folder

if (Test-Path $wt) { throw "Worktree folder already exists: $wt" }
New-Item -ItemType Directory -Force $Root | Out-Null

# Create the worktree: reuse the branch if it exists, otherwise branch from $Base.
git -C $repo show-ref --verify --quiet "refs/heads/$Name"
if ($LASTEXITCODE -eq 0) {
    Write-Host "Using existing branch '$Name'"
    git -C $repo worktree add $wt $Name
} else {
    Write-Host "Creating branch '$Name' from '$Base'"
    git -C $repo worktree add -b $Name $wt $Base
}
if ($LASTEXITCODE -ne 0) { throw 'git worktree add failed' }

$mvnArgs = @('package', '-DskipTests')
if (-not $Online) { $mvnArgs = @('-o') + $mvnArgs }
if ($OwnRepo) {
    $ownRepoPath = "E:\cache\.m2-$folder"
    $mvnArgs += "-Dmaven.repo.local=$ownRepoPath"
}

if (-not $SkipBuild) {
    Write-Host "Building: mvn $($mvnArgs -join ' ')  (in $wt)"
    Push-Location $wt
    try {
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            $hint = if ($Online) { '' } else { ' If artifacts are missing offline, rerun the build with: mvn package -DskipTests' }
            throw "Maven build failed.$hint"
        }
    } finally {
        Pop-Location
    }
}

# Run-config parameters for SNAP Desktop (copy of 'ESA SNAP Microwave' pointed at the worktree).
$clusters = Join-Path $wt 'microwavetbx-kit\target\netbeans_clusters'
$programParams = "--clusters `"$clusters\microwavetbx`";`"$clusters\rstb`" " +
        "--patches `"$snapRoot\snap-engine\`$\target\classes`";`"$wt\`$\target\classes`";`"$wt\rstb\`$\target\classes`""
$workDir = Join-Path $snapRoot 'snap-desktop\snap-application\target\snap'

$notes = @"
Worktree : $wt
Branch   : $Name

IntelliJ : open '$wt' as its own project (do not add it to the E:\ESA project).

SNAP Desktop run config: copy 'ESA SNAP Microwave', rename it 'SNAP Microwave [$Name]', and set
  Program arguments:
    $programParams
  Working directory:
    $workDir

Build (never 'install' unless -OwnRepo was used):
  cd $wt
  mvn -o package -DskipTests          # full build, refreshes the clusters
  mvn -o test -pl sar-io -am          # one module and its dependencies
$(if ($OwnRepo) { "  add -Dmaven.repo.local=$ownRepoPath to every mvn command`n" })
Remove when done:
  git -C $repo worktree remove $wt
"@

$notesFile = Join-Path $Root "$folder.runconfig.txt"
Set-Content -Path $notesFile -Value $notes -Encoding utf8
Write-Host ''
Write-Host $notes
Write-Host "(saved to $notesFile)"
