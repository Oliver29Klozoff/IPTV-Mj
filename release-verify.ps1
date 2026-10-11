#requires -Version 7.0
# Verify a release candidate. This script never pushes, tags, or publishes.
# Without -Candidate it also refuses a candidate whose version.json or MKTV.apk
# does not match the APK that was just verified. It never rewrites those files.
param(
    [switch]$AllowDirty,
    [switch]$SkipBuild,
    [switch]$Candidate
)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
Set-Location $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot "scripts\ReleaseGate.psm1") -Force

function Fail([string]$Message) {
    Write-Host "RELEASE CHECK FAILED: $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Native([scriptblock]$Command) {
    return Invoke-NativeCommand $Command
}

$gitDir = (& git rev-parse --git-dir).Trim()
$markers = @()
foreach ($name in @("MERGE_HEAD", "rebase-merge", "rebase-apply", "CHERRY_PICK_HEAD", "REVERT_HEAD")) {
    if (Test-Path (Join-Path $gitDir $name)) { $markers += $name }
}
$busy = @(Get-GitInProgressMarkers $markers)
if ($busy.Count) { Fail "Git is in the middle of: $($busy -join ', '). Refusing to package a release." }

$branch = (& git branch --show-current).Trim()
if (-not $branch) { $branch = "(detached HEAD)" }
$commit = (& git rev-parse HEAD).Trim()
Write-Host "Branch: $branch"
Write-Host "Commit: $commit"
$counts = (& git rev-list --left-right --count "origin/main...HEAD" 2>$null)
if ($LASTEXITCODE -eq 0 -and $counts) {
    $behind, $ahead = $counts.Trim() -split "\s+"
    Write-Host "Compared with origin/main: behind $behind, ahead $ahead"
    $divergence = Get-OriginDivergenceError -Behind ([int]$behind) -Ahead ([int]$ahead) -AllowUnpushed $true
    if ($divergence -eq "behind") {
        Write-Host "HEAD is behind origin/main. A local candidate can be built, but release-publish.ps1 will refuse it." -ForegroundColor Yellow
    }
}

$porcelain = @(& git status --porcelain)
if (-not (Test-PorcelainClean ($porcelain -join "`n"))) {
    Write-Host "Working tree is not clean:" -ForegroundColor Yellow
    $porcelain | ForEach-Object { Write-Host "  $_" }
    if (-not $AllowDirty) { Fail "A release package requires a clean working tree. Nothing was stashed or committed." }
    Write-Host "Continuing because -AllowDirty was set. This is not a publishable tree." -ForegroundColor Yellow
}

$gradle = Get-GradleVersion (Get-Content "app\build.gradle" -Raw)
$versionJson = Get-Content "version.json" -Raw | ConvertFrom-Json
$changelog = if (Test-Path "CHANGELOG.md") { Get-Content "CHANGELOG.md" -Raw } else { "" }
$problems = @(Test-ReleaseVersionAgreement -GradleCode $gradle.VersionCode -GradleName $gradle.VersionName `
    -JsonCode ([string]$versionJson.versionCode) -JsonName ([string]$versionJson.versionName) `
    -JsonUrl ([string]$versionJson.apkUrl) -JsonSha256 ([string]$versionJson.apkSha256) `
    -ChangelogText $changelog -Tag "")
if ($problems.Count) { Fail ($problems -join "`n") }
$section = Get-ChangelogSection -ChangelogText $changelog -VersionName $gradle.VersionName
if (-not $section) { Fail "CHANGELOG.md has no section for v$($gradle.VersionName)." }
if ([string]::IsNullOrWhiteSpace([string]$versionJson.changelog)) { Fail "version.json changelog is empty." }

$expectedSigner = ConvertTo-NormalizedSha256 (Get-Content "release\expected-signer.sha256" -Raw)
if ($expectedSigner.Length -ne 64) { Fail "release/expected-signer.sha256 is not a SHA-256 fingerprint." }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr")) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
}
if (-not $SkipBuild) {
    $signing = Get-SigningPropertyError (Read-LocalPropertyMap (Join-Path $PSScriptRoot "local.properties")) -BaseDirectory (Join-Path $PSScriptRoot "app")
    if ($signing -eq "missing") { Fail "Release signing configuration is missing. Refusing to build a production APK." }
    if ($signing -eq "keystore-missing") { Fail "Release keystore was not found. Refusing to build a production APK." }
    if (Test-Path -LiteralPath $apk) { Remove-Item -LiteralPath $apk -Force }
    $buildStarted = Get-Date
    if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath "D:\Gradle")) { $env:GRADLE_USER_HOME = "D:\Gradle" }
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "The release build failed. No APK was staged." }
    if (-not (Test-Path -LiteralPath $apk)) { Fail "The release build reported success but did not write app-release.apk." }
    if ((Get-Item -LiteralPath $apk).LastWriteTime -lt $buildStarted.AddSeconds(-5)) {
        Fail "The release APK is older than this build. Refusing to hash a stale file."
    }
}
if (-not (Test-Path -LiteralPath $apk)) { Fail "The release APK is not at app\build\outputs\apk\release\app-release.apk." }

$props = Read-LocalPropertyMap (Join-Path $PSScriptRoot "local.properties")
$sdk = [string]$props["sdk.dir"]
if (-not $sdk) { Fail "sdk.dir is missing from local.properties, so the APK signer cannot be checked." }
$tools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Where-Object { $_.Name -notmatch "rc" } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $tools) { Fail "No stable Android build-tools directory was found." }
$apksigner = Join-Path $tools.FullName "apksigner.bat"
$aapt = Join-Path $tools.FullName "aapt.exe"
if (-not (Test-Path -LiteralPath $apksigner)) { Fail "apksigner was not found." }
if (-not (Test-Path -LiteralPath $aapt)) { Fail "aapt was not found." }

$certs = Invoke-Native { & $apksigner verify --print-certs $apk }
$signerResult = Test-ApkSigner -ApksignerText $certs.Text -ExpectedSha256 $expectedSigner -ExitCode $certs.Code
if ($signerResult -ne "ok") { Fail "APK signer check returned $signerResult. The APK was not copied or hashed for release." }
$schemes = Invoke-Native { & $apksigner verify -v --min-sdk-version 25 $apk }
if ($schemes.Code -ne 0) { Fail "apksigner could not verify the APK signature schemes." }
$missingSchemes = @(Test-SignatureSchemes $schemes.Text)
if ($missingSchemes.Count) { Fail "APK is missing signature schemes: $($missingSchemes -join ', ')." }

$badging = Invoke-Native { & $aapt dump badging $apk }
if ($badging.Code -ne 0) { Fail "aapt could not read the APK identity." }
$identity = @(Get-ApkIdentityErrors -Badging $badging.Text -ExpectedPackage "com.iptvapp" `
    -ExpectedCode $gradle.VersionCode -ExpectedName $gradle.VersionName)
if ($identity.Count) { Fail ($identity -join "`n") }

$apkHash = Get-FileSha256Hex $apk
$again = Get-FileSha256Hex $apk
if (-not (Test-HashMatch $apkHash $again)) { Fail "The release APK changed while it was being hashed." }

$summaryPath = Join-Path (Split-Path -Parent $apk) "release-summary.txt"
$prior = @{}
if (Test-Path -LiteralPath $summaryPath) {
    Get-Content -LiteralPath $summaryPath | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $prior[$Matches[1]] = $Matches[2] } }
}
$cleanTree = -not $AllowDirty
$recordedCommit = $commit
if ($SkipBuild) {
    $provenance = @(Get-SkipBuildProvenanceError -Head $commit -SummaryCommit ([string]$prior["commit"]) `
        -SummaryClean ([string]$prior["cleanTree"]) -SummaryHash ([string]$prior["apkSha256"]) -ApkHash $apkHash)
    if ($provenance.Count) {
        Write-Host "This command did not build the APK, so it will not attest a commit:" -ForegroundColor Yellow
        $provenance | ForEach-Object { Write-Host "  $_" }
        if (-not $Candidate) { Fail "Rebuild without -SkipBuild from a clean tree before publishing." }
        $cleanTree = $false
        $recordedCommit = ""
    } else {
        $recordedCommit = [string]$prior["commit"]
        $cleanTree = $true
    }
}

$staged = Join-Path $PSScriptRoot "MKTV.apk"
$stagedExists = Test-Path -LiteralPath $staged
$stagedHash = if ($stagedExists) { Get-FileSha256Hex $staged } else { "" }
$binding = @(Get-ArtifactBindingErrors -BuiltHash $apkHash -JsonHash ([string]$versionJson.apkSha256) `
    -StagedExists $stagedExists -StagedHash $stagedHash)

$summaryCommit = if ($recordedCommit) { $recordedCommit } else { "(not attested)" }
$summary = Format-ReleaseSummary -VersionName $gradle.VersionName -VersionCode $gradle.VersionCode `
    -Commit $summaryCommit -ApkPath $apk -SignerSha256 $expectedSigner -ApkSha256 $apkHash
$publishable = ($cleanTree -and $recordedCommit -and -not $binding.Count)
@(
    "commit=$recordedCommit"
    "versionCode=$($gradle.VersionCode)"
    "versionName=$($gradle.VersionName)"
    "buildType=release"
    "signerSha256=$expectedSigner"
    "apkSha256=$apkHash"
    "apkPath=$apk"
    "cleanTree=$(if ($cleanTree) { 'true' } else { 'false' })"
    "publishable=$(if ($publishable) { 'true' } else { 'false' })"
) | Set-Content -LiteralPath $summaryPath -Encoding ascii
Write-Host ""
Write-Host $summary
Write-Host ""
$blocks = @($binding)
if (-not $cleanTree) { $blocks += "The working tree was dirty, so this APK is not attested to a commit." }
if (-not $recordedCommit) { $blocks += "No build commit was attested." }
if ($blocks.Count) {
    Write-Host "Publish blocked:" -ForegroundColor Yellow
    $blocks | ForEach-Object { Write-Host "  $_" }
    Write-Host "version.json was not modified. MKTV.apk was not replaced."
    if (-not $Candidate) { exit 1 }
    Write-Host "Continuing because -Candidate was set. This APK is signed, but it is not the published artifact."
}
Write-Host "Verification only. Nothing was pushed, tagged, or published."
exit 0
