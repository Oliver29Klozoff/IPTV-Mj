# Pure checks for the MKTV release gate. No network, no Gradle, no secrets.

function ConvertTo-NormalizedSha256 {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    return ($Value -replace "[:\s]", "").Trim().ToLowerInvariant()
}

function Read-LocalPropertyMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_
        if ($line -match '^\s*#' -or $line -notmatch '=') { return }
        $name, $raw = $line.Split('=', 2)
        $value = $raw.Trim() -replace '\\:', ':' -replace '\\\\', '\'
        $map[$name.Trim()] = $value
    }
    return $map
}

function Get-SigningPropertyError {
    param(
        [hashtable]$Props,
        [string]$BaseDirectory
    )
    foreach ($name in @("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")) {
        if (-not $Props.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string]$Props[$name])) {
            return "missing"
        }
    }
    $store = [string]$Props["RELEASE_STORE_FILE"]
    # Gradle resolves a relative storeFile from the app module, not the repo root.
    if ($BaseDirectory -and -not [System.IO.Path]::IsPathRooted($store)) {
        $store = [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $store))
    }
    if (-not (Test-Path -LiteralPath $store -PathType Leaf)) {
        return "keystore-missing"
    }
    return "present"
}

function Invoke-NativeCommand {
    param([scriptblock]$Command)
    $previous = $ErrorActionPreference
    try {
        # The release scripts require PowerShell 7. Windows PowerShell 5 treats
        # native stderr merged with 2>&1 as a terminating error even inside this helper.
        $ErrorActionPreference = "Continue"
        $output = & $Command 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $text = @($output | ForEach-Object { "$_" }) -join "`n"
    return [pscustomobject]@{ Code = $code; Text = $text }
}

function Get-SkipBuildProvenanceError {
    param(
        [string]$Head,
        [string]$SummaryCommit,
        [string]$SummaryClean,
        [string]$SummaryHash,
        [string]$ApkHash
    )
    $errors = @()
    if ([string]::IsNullOrWhiteSpace($SummaryCommit)) { $errors += "no recorded build commit" }
    elseif ($SummaryCommit -ne $Head) { $errors += "recorded commit does not match HEAD" }
    if ($SummaryClean -ne "true") { $errors += "recorded build was not from a clean tree" }
    if (-not (Test-HashMatch $SummaryHash $ApkHash)) { $errors += "APK does not match the recorded build" }
    return @($errors)
}

function Test-RemoteMainFresh {
    param([string]$LocalOrigin, [string]$RemoteText)
    $remote = ""
    if ($RemoteText -match '(?m)^([0-9a-fA-F]{40})\b') { $remote = $Matches[1] }
    if (-not $remote) { return "unavailable" }
    if ($LocalOrigin.Trim() -ne $remote) { return "stale" }
    return "fresh"
}

function Get-GradleVersion {
    param([string]$GradleText)
    $code = [regex]::Match($GradleText, 'versionCode\s+(\d+)').Groups[1].Value
    $name = [regex]::Match($GradleText, 'versionName\s+"([^"]+)"').Groups[1].Value
    return [pscustomobject]@{ VersionCode = $code; VersionName = $name }
}

function Test-ReleaseVersionAgreement {
    param(
        [string]$GradleCode,
        [string]$GradleName,
        [string]$JsonCode,
        [string]$JsonName,
        [string]$JsonUrl,
        [string]$JsonSha256,
        [string]$ChangelogText,
        [string]$Tag
    )
    $errors = @()
    if ($GradleCode -ne $JsonCode) { $errors += "versionCode mismatch: gradle=$GradleCode version.json=$JsonCode" }
    if ($GradleName -ne $JsonName) { $errors += "versionName mismatch: gradle=$GradleName version.json=$JsonName" }
    $expectedUrl = "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/v$GradleName/MKTV.apk"
    if ($JsonUrl -ne $expectedUrl) { $errors += "apkUrl must be $expectedUrl" }
    if ($JsonSha256 -notmatch '^[0-9a-fA-F]{64}$') { $errors += "apkSha256 must be a full 64-character hex digest" }
    if ($ChangelogText -notmatch "(?m)^## v$([regex]::Escape($GradleName))\b") {
        $errors += "CHANGELOG.md has no heading for v$GradleName"
    }
    if ($Tag -and $Tag -ne "v$GradleName") { $errors += "tag $Tag does not match versionName $GradleName" }
    return @($errors)
}

function Test-HashMatch {
    param([string]$Expected, [string]$Actual)
    $left = ConvertTo-NormalizedSha256 $Expected
    $right = ConvertTo-NormalizedSha256 $Actual
    return ($left.Length -eq 64 -and $left -eq $right)
}

function Get-FileSha256Hex {
    param([string]$Path)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream)) -replace "-", "").ToLowerInvariant() }
        finally { $stream.Dispose() }
    } finally { $sha.Dispose() }
}

function Test-PorcelainClean {
    param([string]$Porcelain)
    return [string]::IsNullOrWhiteSpace($Porcelain)
}

function Get-GitInProgressMarkers {
    param([string[]]$Present)
    $blocked = @("MERGE_HEAD", "rebase-merge", "rebase-apply", "CHERRY_PICK_HEAD", "REVERT_HEAD")
    return @($Present | Where-Object { $blocked -contains $_ })
}

function Test-TagAvailable {
    param([string[]]$ExistingTags, [string]$Tag)
    return -not ($ExistingTags -contains $Tag)
}

function Get-ApkSignerFingerprints {
    param([string]$ApksignerText)
    $found = [regex]::Matches($ApksignerText, 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
    return @($found | ForEach-Object { ConvertTo-NormalizedSha256 $_.Groups[1].Value })
}

function Test-ApkSigner {
    param([string]$ApksignerText, [string]$ExpectedSha256, [int]$ExitCode = 0)
    if ($ExitCode -ne 0) { return "unsigned-or-invalid" }
    $signers = @(Get-ApkSignerFingerprints $ApksignerText)
    if ($signers.Count -eq 0) { return "unsigned-or-invalid" }
    if ($signers.Count -ne 1) { return "unexpected-signers" }
    if (-not (Test-HashMatch $ExpectedSha256 $signers[0])) { return "wrong-signer" }
    return "ok"
}

function Test-SignatureSchemes {
    param([string]$VerboseText)
    # minSdk is 25, so JAR v1 signing is not required. v2 and v3 are.
    $missing = @()
    foreach ($scheme in @("v2", "v3")) {
        if ($VerboseText -notmatch "Verified using $scheme scheme.*:\s*true") { $missing += $scheme }
    }
    return @($missing)
}

function Get-ChangelogSection {
    param([string]$ChangelogText, [string]$VersionName)
    if ([string]::IsNullOrWhiteSpace($ChangelogText) -or [string]::IsNullOrWhiteSpace($VersionName)) { return "" }
    $lines = $ChangelogText -split "`r?`n"
    $start = -1
    $heading = "^## v$([regex]::Escape($VersionName))\b"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $heading) { $start = $i; break }
    }
    if ($start -lt 0) { return "" }
    $section = New-Object System.Collections.Generic.List[string]
    for ($i = $start; $i -lt $lines.Count; $i++) {
        if ($i -gt $start -and $lines[$i] -match '^## ') { break }
        $section.Add($lines[$i])
    }
    return ($section -join "`n").Trim()
}

function Get-ArtifactBindingErrors {
    param(
        [string]$BuiltHash,
        [string]$JsonHash,
        [bool]$StagedExists,
        [string]$StagedHash
    )
    $errors = @()
    if (-not (Test-HashMatch $BuiltHash $JsonHash)) {
        $errors += "version.json apkSha256 does not match the verified APK"
    }
    if (-not $StagedExists) {
        $errors += "MKTV.apk is missing"
    } elseif (-not (Test-HashMatch $BuiltHash $StagedHash)) {
        $errors += "MKTV.apk does not match the verified APK"
    }
    return @($errors)
}

function Get-ApkIdentityErrors {
    param(
        [string]$Badging,
        [string]$ExpectedPackage,
        [string]$ExpectedCode,
        [string]$ExpectedName
    )
    $errors = @()
    $match = [regex]::Match($Badging, "package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']+)'")
    if (-not $match.Success) {
        $errors += "APK package identity could not be read"
        return @($errors)
    }
    if ($match.Groups[1].Value -ne $ExpectedPackage) { $errors += "applicationId is $($match.Groups[1].Value), expected $ExpectedPackage" }
    if ($match.Groups[2].Value -ne $ExpectedCode) { $errors += "APK versionCode is $($match.Groups[2].Value), expected $ExpectedCode" }
    if ($match.Groups[3].Value -ne $ExpectedName) { $errors += "APK versionName is $($match.Groups[3].Value), expected $ExpectedName" }
    if ($Badging -match 'application-debuggable') { $errors += "release APK is debuggable" }
    return @($errors)
}

function Test-ReleaseCommitBinding {
    param([string]$SummaryCommit, [string]$Head)
    if ([string]::IsNullOrWhiteSpace($SummaryCommit) -or [string]::IsNullOrWhiteSpace($Head)) { return "missing" }
    if ($SummaryCommit -ne $Head) { return "mismatch" }
    return "ok"
}

function Get-OriginDivergenceError {
    param([int]$Behind, [int]$Ahead, [bool]$AllowUnpushed)
    if ($Behind -gt 0) { return "behind" }
    if ($Ahead -gt 0 -and -not $AllowUnpushed) { return "ahead-unpushed" }
    return ""
}

function Get-PublishReachabilityError {
    param([int]$Behind, [int]$Ahead, [bool]$OnRemoteBranch)
    if ($Behind -gt 0) { return "behind" }
    # Equal to the fetched origin/main means GitHub already has this commit.
    if ($Behind -eq 0 -and $Ahead -eq 0) { return "" }
    if (-not $OnRemoteBranch) { return "not-on-remote" }
    return ""
}

function Format-ReleaseSummary {
    param(
        [string]$VersionName,
        [string]$VersionCode,
        [string]$Commit,
        [string]$ApkPath,
        [string]$SignerSha256,
        [string]$ApkSha256
    )
    return @(
        "Release candidate:",
        "Version: $VersionName ($VersionCode)",
        "Commit: $Commit",
        "APK: $ApkPath",
        "Signer SHA-256: $(ConvertTo-NormalizedSha256 $SignerSha256)",
        "APK SHA-256: $(ConvertTo-NormalizedSha256 $ApkSha256)"
    ) -join "`n"
}

Export-ModuleMember -Function *
