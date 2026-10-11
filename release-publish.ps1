#requires -Version 7.0
# Publish is a separate, explicit step. Running this file does nothing remote
# unless -Publish is passed, and every check below has to pass first.
param([switch]$Publish)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
Set-Location $PSScriptRoot
if (-not $Publish) {
    Write-Host "Refusing to publish. Run .\release-verify.ps1 first. Publishing requires -Publish and is never implied by a successful check."
    exit 1
}
Import-Module (Join-Path $PSScriptRoot "scripts\ReleaseGate.psm1") -Force

function Fail([string]$Message) {
    Write-Host "RELEASE PUBLISH BLOCKED: $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Native([scriptblock]$Command) {
    return Invoke-NativeCommand $Command
}

$porcelain = @(& git status --porcelain)
if (-not (Test-PorcelainClean ($porcelain -join "`n"))) { Fail "Working tree is dirty. Nothing will be published." }
$gitDir = (& git rev-parse --git-dir).Trim()
$markers = @()
foreach ($name in @("MERGE_HEAD", "rebase-merge", "rebase-apply", "CHERRY_PICK_HEAD", "REVERT_HEAD")) {
    if (Test-Path (Join-Path $gitDir $name)) { $markers += $name }
}
if ((Get-GitInProgressMarkers $markers).Count) { Fail "A merge, rebase, cherry-pick, or revert is in progress." }

$commit = (& git rev-parse HEAD).Trim()
$summaryPath = Join-Path $PSScriptRoot "app\build\outputs\apk\release\release-summary.txt"
if (-not (Test-Path -LiteralPath $summaryPath)) { Fail "No release-summary.txt. Run .\release-verify.ps1 on this commit first." }
$summary = @{}
Get-Content -LiteralPath $summaryPath | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $summary[$Matches[1]] = $Matches[2] } }
if ((Test-ReleaseCommitBinding -SummaryCommit ([string]$summary["commit"]) -Head $commit) -ne "ok") {
    Fail "HEAD is $commit but the APK was built from $($summary['commit'])."
}
if ($summary["cleanTree"] -ne "true") { Fail "The APK was not built from a clean tree. Rebuild with .\release-verify.ps1 before publishing." }
if ($summary["buildType"] -ne "release") { Fail "The recorded build is not a release build." }

$remoteMain = Invoke-Native { git ls-remote origin refs/heads/main }
if ($remoteMain.Code -ne 0) { Fail "Could not read origin/main. Refusing to publish." }
$localOrigin = (& git rev-parse origin/main).Trim()
if ((Test-RemoteMainFresh -LocalOrigin $localOrigin -RemoteText $remoteMain.Text) -ne "fresh") {
    Fail "Local origin/main does not match the remote. Fetch and review it before publishing. Nothing was pushed."
}
$counts = (& git rev-list --left-right --count "origin/main...HEAD")
if ($LASTEXITCODE -ne 0) { Fail "Could not compare HEAD with origin/main." }
$behind, $ahead = $counts.Trim() -split "\s+"
$remoteBranches = @(git branch -r --contains $commit | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch ' -> ' })
$reach = Get-PublishReachabilityError -Behind ([int]$behind) -Ahead ([int]$ahead) -OnRemoteBranch ($remoteBranches.Count -gt 0)
if ($reach -eq "behind") { Fail "HEAD is behind origin/main. Refusing to tag a different commit than the remote." }
if ($reach -eq "not-on-remote") {
    Fail "This commit is not on any remote branch. Push it yourself, then retry. This script will not push, and GitHub cannot tag a commit it does not have."
}

$gradle = Get-GradleVersion (Get-Content "app\build.gradle" -Raw)
$versionJson = Get-Content "version.json" -Raw | ConvertFrom-Json
$changelog = Get-Content "CHANGELOG.md" -Raw
$tag = "v$($gradle.VersionName)"
$problems = @(Test-ReleaseVersionAgreement -GradleCode $gradle.VersionCode -GradleName $gradle.VersionName `
    -JsonCode ([string]$versionJson.versionCode) -JsonName ([string]$versionJson.versionName) `
    -JsonUrl ([string]$versionJson.apkUrl) -JsonSha256 ([string]$versionJson.apkSha256) `
    -ChangelogText $changelog -Tag $tag)
if ($problems.Count) { Fail ($problems -join "`n") }
$section = Get-ChangelogSection -ChangelogText $changelog -VersionName $gradle.VersionName
if (-not $section) { Fail "CHANGELOG.md has no section for $tag." }
if ($summary["versionCode"] -ne $gradle.VersionCode -or $summary["versionName"] -ne $gradle.VersionName) {
    Fail "The APK summary version does not match Gradle."
}

$apk = Join-Path $PSScriptRoot "MKTV.apk"
$stagedExists = Test-Path -LiteralPath $apk
$apkHash = if ($stagedExists) { Get-FileSha256Hex $apk } else { "" }
$built = [string]$summary["apkPath"]
if (-not (Test-Path -LiteralPath $built)) { Fail "The built APK recorded in the summary is gone." }
$builtHash = Get-FileSha256Hex $built
$binding = @(Get-ArtifactBindingErrors -BuiltHash $builtHash -JsonHash ([string]$versionJson.apkSha256) `
    -StagedExists $stagedExists -StagedHash $apkHash)
if ($binding.Count) { Fail ($binding -join "`n") }
if (-not (Test-HashMatch $apkHash ([string]$summary["apkSha256"]))) { Fail "MKTV.apk is not the APK that release-verify.ps1 checked." }
if (-not (Test-HashMatch $builtHash $apkHash)) { Fail "MKTV.apk does not match the built release APK." }

$expected = ConvertTo-NormalizedSha256 (Get-Content "release\expected-signer.sha256" -Raw)
if (-not (Test-HashMatch $expected ([string]$summary["signerSha256"]))) { Fail "The summary signer is not the expected production certificate." }
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr")) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
}
$props = Read-LocalPropertyMap (Join-Path $PSScriptRoot "local.properties")
$tools = Get-ChildItem (Join-Path ([string]$props["sdk.dir"]) "build-tools") -Directory | Where-Object { $_.Name -notmatch "rc" } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $tools) { Fail "No stable Android build-tools directory was found." }
$apksigner = Join-Path $tools.FullName "apksigner.bat"
$certs = Invoke-Native { & $apksigner verify --print-certs $apk }
if ((Test-ApkSigner -ApksignerText $certs.Text -ExpectedSha256 $expected -ExitCode $certs.Code) -ne "ok") {
    Fail "MKTV.apk is not signed by the production certificate."
}
$schemes = Invoke-Native { & $apksigner verify -v --min-sdk-version 25 $apk }
$missingSchemes = @(Test-SignatureSchemes $schemes.Text)
if ($schemes.Code -ne 0 -or $missingSchemes.Count) { Fail "MKTV.apk signature schemes did not verify." }
$aapt = Join-Path $tools.FullName "aapt.exe"
$badging = Invoke-Native { & $aapt dump badging $apk }
$identity = @(Get-ApkIdentityErrors -Badging $badging.Text -ExpectedPackage "com.iptvapp" `
    -ExpectedCode $gradle.VersionCode -ExpectedName $gradle.VersionName)
if ($badging.Code -ne 0 -or $identity.Count) { Fail "MKTV.apk identity does not match this version." }

$localTags = @(git tag --list $tag | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$remote = Invoke-Native { git ls-remote --tags origin "refs/tags/$tag" }
if ($remote.Code -ne 0) { Fail "Could not check whether $tag already exists on origin." }
$remoteTags = @($remote.Text -split "`r?`n" | Where-Object { $_.Trim() })
if (-not (Test-TagAvailable $localTags $tag) -or $remoteTags.Count -gt 0) {
    Fail "Tag $tag already exists. Refusing to replace it."
}
$existing = Invoke-Native { gh release view $tag --repo Oliver29Klozoff/IPTV-Mj }
if ($existing.Code -eq 0) { Fail "GitHub release $tag already exists. Refusing to replace it." }

$notes = Join-Path $env:TEMP "mktv-$tag-notes.md"
Set-Content -LiteralPath $notes -Value $section -Encoding utf8
Write-Host "Publishing $tag from $commit"
& gh release create $tag $apk --repo Oliver29Klozoff/IPTV-Mj --title "MKTV $tag" --notes-file $notes --target $commit
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed. The existing release was not replaced." }
Write-Host "Published $tag"
