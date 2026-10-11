# Focused checks for the release gate. No production keystore or passwords.
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "ReleaseGate.psm1") -Force
$failed = 0
function Check($Name, $Condition) {
    if ($Condition) { Write-Host "  ok   $Name" }
    else { Write-Host "  FAIL $Name"; $script:failed++ }
}

$props = @{
    RELEASE_STORE_FILE = $PSCommandPath
    RELEASE_STORE_PASSWORD = "x"
    RELEASE_KEY_ALIAS = "mktv"
    RELEASE_KEY_PASSWORD = "y"
}
Check "complete signing properties are present" ((Get-SigningPropertyError $props) -eq "present")
$missing = @{ RELEASE_STORE_FILE = "C:\nope.keystore"; RELEASE_KEY_ALIAS = "mktv" }
Check "missing password fails closed" ((Get-SigningPropertyError $missing) -eq "missing")
$blank = @{
    RELEASE_STORE_FILE = "  "
    RELEASE_STORE_PASSWORD = "x"
    RELEASE_KEY_ALIAS = "mktv"
    RELEASE_KEY_PASSWORD = "y"
}
Check "blank keystore path fails closed" ((Get-SigningPropertyError $blank) -eq "missing")
$gone = @{
    RELEASE_STORE_FILE = "D:\does-not-exist\mktv-missing.keystore"
    RELEASE_STORE_PASSWORD = "x"
    RELEASE_KEY_ALIAS = "mktv"
    RELEASE_KEY_PASSWORD = "y"
}
Check "missing keystore file fails closed" ((Get-SigningPropertyError $gone) -eq "keystore-missing")
$dirKey = @{
    RELEASE_STORE_FILE = $PSScriptRoot
    RELEASE_STORE_PASSWORD = "x"
    RELEASE_KEY_ALIAS = "mktv"
    RELEASE_KEY_PASSWORD = "y"
}
Check "a directory is not a keystore" ((Get-SigningPropertyError $dirKey) -eq "keystore-missing")

$gradle = Get-GradleVersion 'versionCode 562`nversionName "7.24"'
Check "gradle version is parsed" ($gradle.VersionCode -eq "562" -and $gradle.VersionName -eq "7.24")
$agree = Test-ReleaseVersionAgreement -GradleCode "562" -GradleName "7.24" -JsonCode "562" -JsonName "7.24" `
    -JsonUrl "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/v7.24/MKTV.apk" `
    -JsonSha256 ("ab" * 32) -ChangelogText "## v7.24 - 2026-10-01`n- note`n" -Tag "v7.24"
Check "matching versions pass" ($agree.Count -eq 0)
$bad = Test-ReleaseVersionAgreement -GradleCode "563" -GradleName "7.25" -JsonCode "562" -JsonName "7.24" `
    -JsonUrl "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/v7.24/MKTV.apk" `
    -JsonSha256 "abc" -ChangelogText "## v7.24`n" -Tag "v7.24"
Check "version, url, hash, changelog, and tag mismatches are reported" ($bad.Count -ge 5)

Check "full hashes match ignoring case and colons" (Test-HashMatch ("AB:" * 32).TrimEnd(':') (("ab" * 32)))
Check "a short hash is rejected" (-not (Test-HashMatch ("ab" * 16) ("ab" * 32)))

Check "clean status passes" (Test-PorcelainClean "")
Check "a dirty tree fails" (-not (Test-PorcelainClean " M app/build.gradle"))
Check "a merge in progress fails" ((Get-GitInProgressMarkers @("MERGE_HEAD")).Count -eq 1)
Check "a rebase in progress fails" ((Get-GitInProgressMarkers @("rebase-merge")).Count -eq 1)
Check "an idle git dir passes" ((Get-GitInProgressMarkers @()).Count -eq 0)
Check "an existing tag collides" (-not (Test-TagAvailable @("v7.24") "v7.24"))
Check "a new tag is free" (Test-TagAvailable @("v7.24") "v7.25")

$expected = "0567b23734cde9bd40810a01e6394948ef4d949705d95acc3c5050c3012b55f0"
$goodCert = "Signer #1 certificate SHA-256 digest: 05:67:b2:37:34:cd:e9:bd:40:81:0a:01:e6:39:49:48:ef:4d:94:97:05:d9:5a:cc:3c:50:50:c3:01:2b:55:f0"
Check "the production signer is accepted" ((Test-ApkSigner $goodCert $expected 0) -eq "ok")
Check "a debug signer is rejected" ((Test-ApkSigner "Signer #1 certificate SHA-256 digest: aa:bb" $expected 0) -eq "wrong-signer")
Check "a second signer is rejected" ((Test-ApkSigner ($goodCert + "`nSigner #2 certificate SHA-256 digest: aa") $expected 0) -eq "unexpected-signers")
Check "an unsigned apk is rejected" ((Test-ApkSigner "DOES NOT VERIFY" $expected 1) -eq "unsigned-or-invalid")
$schemes = "Verified using v1 scheme (JAR signing): false`nVerified using v2 scheme (APK Signature Scheme v2): true`nVerified using v3 scheme (APK Signature Scheme v3): true"
Check "v2 and v3 pass when v1 is absent" (@(Test-SignatureSchemes $schemes).Count -eq 0)
Check "a missing v2 scheme fails" (@(Test-SignatureSchemes ($schemes -replace "v2 scheme.*true", "v2 scheme (APK Signature Scheme v2): false")).Count -eq 1)
Check "a missing v3 scheme fails" (@(Test-SignatureSchemes ($schemes -replace "v3 scheme.*true", "v3 scheme (APK Signature Scheme v3): false")).Count -eq 1)

$dir = Join-Path $env:TEMP "mktv-release-gate-tests"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$source = Join-Path $dir "fresh.apk"
$stale = Join-Path $dir "MKTV.apk"
[System.IO.File]::WriteAllBytes($source, [byte[]](1, 2, 3, 4))
[System.IO.File]::WriteAllBytes($stale, [byte[]](1, 2, 3, 4))
$sourceHash = Get-FileSha256Hex $source
Check "identical bytes are not stale" (Test-HashMatch $sourceHash (Get-FileSha256Hex $stale))
[System.IO.File]::WriteAllBytes($stale, [byte[]](9, 9, 9))
Check "a changed apk fails the stored hash" (-not (Test-HashMatch $sourceHash (Get-FileSha256Hex $stale)))
Remove-Item $dir -Recurse -Force

$notes = Get-ChangelogSection -ChangelogText "## v7.24 - day`n- current`n`n## v7.23`n- old`n" -VersionName "7.24"
Check "changelog notes keep only this version" ($notes -match "current" -and $notes -notmatch "v7.23" -and $notes -notmatch "old")
Check "a missing changelog section is empty" ((Get-ChangelogSection -ChangelogText "## v7.23`n" -VersionName "7.25") -eq "")

$full = "ab" * 32
$other = "cd" * 32
Check "bound artifacts pass" (@(Get-ArtifactBindingErrors -BuiltHash $full -JsonHash $full -StagedExists $true -StagedHash $full).Count -eq 0)
Check "a wrong version.json hash is reported" (@(Get-ArtifactBindingErrors -BuiltHash $full -JsonHash $other -StagedExists $true -StagedHash $full).Count -ge 1)
Check "a missing MKTV.apk is reported" (@(Get-ArtifactBindingErrors -BuiltHash $full -JsonHash $full -StagedExists $false -StagedHash "").Count -eq 1)
Check "a stale MKTV.apk is reported" (@(Get-ArtifactBindingErrors -BuiltHash $full -JsonHash $full -StagedExists $true -StagedHash $other).Count -eq 1)

$badging = "package: name='com.iptvapp' versionCode='562' versionName='7.24' platformBuildVersionName='15'"
Check "apk identity matches" (@(Get-ApkIdentityErrors -Badging $badging -ExpectedPackage "com.iptvapp" -ExpectedCode "562" -ExpectedName "7.24").Count -eq 0)
Check "a versionCode mismatch is reported" (@(Get-ApkIdentityErrors -Badging $badging -ExpectedPackage "com.iptvapp" -ExpectedCode "563" -ExpectedName "7.24").Count -eq 1)
Check "a debuggable release is rejected" (@(Get-ApkIdentityErrors -Badging ($badging + "`napplication-debuggable") -ExpectedPackage "com.iptvapp" -ExpectedCode "562" -ExpectedName "7.24").Count -eq 1)

Check "the same commit is accepted" ((Test-ReleaseCommitBinding "abc" "abc") -eq "ok")
Check "a different commit is rejected" ((Test-ReleaseCommitBinding "abc" "def") -eq "mismatch")
Check "a missing commit is rejected" ((Test-ReleaseCommitBinding "" "abc") -eq "missing")
Check "being behind origin is rejected" ((Get-PublishReachabilityError -Behind 2 -Ahead 0 -OnRemoteBranch $true) -eq "behind")
Check "an unpushed commit is rejected" ((Get-PublishReachabilityError -Behind 0 -Ahead 1 -OnRemoteBranch $false) -eq "not-on-remote")
Check "a pushed branch can be published" ((Get-PublishReachabilityError -Behind 0 -Ahead 1 -OnRemoteBranch $true) -eq "")
Check "an equal remote is accepted" ((Get-PublishReachabilityError -Behind 0 -Ahead 0 -OnRemoteBranch $false) -eq "")
Check "a fresh origin/main is accepted" ((Test-RemoteMainFresh -LocalOrigin ("a" * 40) -RemoteText (("a" * 40) + "`trefs/heads/main")) -eq "fresh")
Check "a stale origin/main is rejected" ((Test-RemoteMainFresh -LocalOrigin ("a" * 40) -RemoteText (("b" * 40) + "`trefs/heads/main")) -eq "stale")
Check "a missing remote main is rejected" ((Test-RemoteMainFresh -LocalOrigin ("a" * 40) -RemoteText "") -eq "unavailable")
$same = "ab" * 32
Check "skip-build accepts the recorded clean commit" (@(Get-SkipBuildProvenanceError -Head "abc" -SummaryCommit "abc" -SummaryClean "true" -SummaryHash $same -ApkHash $same).Count -eq 0)
Check "skip-build rejects a different commit" (@(Get-SkipBuildProvenanceError -Head "def" -SummaryCommit "abc" -SummaryClean "true" -SummaryHash $same -ApkHash $same).Count -ge 1)
Check "skip-build rejects a dirty build" (@(Get-SkipBuildProvenanceError -Head "abc" -SummaryCommit "abc" -SummaryClean "false" -SummaryHash $same -ApkHash $same).Count -ge 1)

$relRoot = Join-Path $env:TEMP "mktv-rel-keystore"
$relApp = Join-Path $relRoot "app"
New-Item -ItemType Directory -Force -Path $relApp | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $relRoot "release.keystore"), [byte[]](1, 2, 3))
$relProps = @{
    RELEASE_STORE_FILE = "..\release.keystore"
    RELEASE_STORE_PASSWORD = "x"
    RELEASE_KEY_ALIAS = "mktv"
    RELEASE_KEY_PASSWORD = "y"
}
Check "a relative keystore resolves from the app directory" ((Get-SigningPropertyError $relProps -BaseDirectory $relApp) -eq "present")
Remove-Item $relRoot -Recurse -Force
$native = Invoke-NativeCommand { cmd.exe /c "echo native-stderr 1>&2 & exit /b 3" }
Check "native stderr keeps the exit code" ($native.Code -eq 3 -and $native.Text -match "native-stderr")

$repoRoot = Split-Path $PSScriptRoot -Parent
$publishText = Get-Content (Join-Path $repoRoot "release-publish.ps1") -Raw
$verifyText = Get-Content (Join-Path $repoRoot "release-verify.ps1") -Raw
$bumpText = Get-Content (Join-Path $repoRoot "bump.ps1") -Raw
$oldReleaseText = Get-Content (Join-Path $repoRoot "release.ps1") -Raw
Check "publish script cannot clobber or push" ($publishText -notmatch '--clobber' -and $publishText -notmatch 'git push')
Check "release scripts require PowerShell 7" ($publishText -match '(?m)^#requires -Version 7\.0' -and $verifyText -match '(?m)^#requires -Version 7\.0')
Check "verify script cannot publish" ($verifyText -notmatch 'gh release' -and $verifyText -notmatch 'git push' -and $verifyText -notmatch 'git tag')
Check "retired bump script cannot build or publish" ($bumpText -notmatch 'assembleRelease|git push|gh release create')
Check "retired release script cannot upload" ($oldReleaseText -notmatch 'gh release|Invoke-WebRequest|curl')

$summary = Format-ReleaseSummary -VersionName "7.24" -VersionCode "562" -Commit "9da9d06" `
    -ApkPath "app\build\outputs\apk\release\app-release.apk" -SignerSha256 $expected -ApkSha256 ("ab" * 32)
Check "the summary names the commit and both hashes" ($summary -match "9da9d06" -and $summary -match $expected -and $summary -notmatch "password")

Write-Host ""
if ($failed) { Write-Host "$failed failed"; exit 1 }
Write-Host "release-gate tests passed"
exit 0
