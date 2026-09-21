<#
  Runs OpenAI Codex CLI as a reviewer over changes Claude Code just implemented.
  Usage (from repo root or anywhere — script cd's to repo root itself):
    .\.vscode\scripts\codex-review.ps1                  # review the last commit (HEAD)
    .\.vscode\scripts\codex-review.ps1 -Uncommitted      # review staged/unstaged/untracked changes
    .\.vscode\scripts\codex-review.ps1 -Base main         # review current branch against main
    .\.vscode\scripts\codex-review.ps1 -Commit <sha>      # review a specific commit
#>
param(
  [string]$Base,
  [string]$Commit,
  [switch]$Uncommitted,
  [string]$OutFile = "$PSScriptRoot\codex-review-latest.md"
)

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0) {
  Write-Error "Could not resolve a git repository from $PSScriptRoot`: $repoRoot"
  exit 1
}
$repoRoot = $repoRoot.Trim()
Set-Location $repoRoot

$reviewArgs = @()
if ($Uncommitted) {
  $reviewArgs += "--uncommitted"
} elseif ($Base) {
  $reviewArgs += "--base"; $reviewArgs += $Base
} elseif ($Commit) {
  $reviewArgs += "--commit"; $reviewArgs += $Commit
} else {
  $reviewArgs += "--commit"; $reviewArgs += "HEAD"
}

if (Test-Path $OutFile) { Remove-Item $OutFile -Force }

Write-Host "Running codex review with: $reviewArgs" -ForegroundColor Cyan
& codex exec review @reviewArgs -o $OutFile
$codexExitCode = $LASTEXITCODE

if ($codexExitCode -ne 0) {
  Write-Error "codex exec review exited with code $codexExitCode — not displaying output (may be missing or stale)."
  exit $codexExitCode
}

if (Test-Path $OutFile) {
  Write-Host "`n----- Codex review -----`n" -ForegroundColor Yellow
  Get-Content $OutFile -Raw | Write-Host
  Write-Host "`n(full text saved to $OutFile)" -ForegroundColor DarkGray
} else {
  Write-Error "Codex exited successfully but produced no output file — check the log above."
  exit 1
}
