# bump.ps1 — full release pipeline
# Usage: .\bump.ps1 "What changed in this version"
# Requires: GH_TOKEN=<token> in local.properties

param(
    [Parameter(Mandatory=$false)]
    [string]$Note = "",
    [Parameter(Mandatory=$false)]
    [string]$Version = ""
)

Set-Location $PSScriptRoot

$utf8NoBom = New-Object System.Text.UTF8Encoding $false

# ─── 1. Read current version ────────────────────────────────────────────────
$gradle = Get-Content "app/build.gradle" -Raw
$code   = [int][regex]::Match($gradle, 'versionCode (\d+)').Groups[1].Value
$name   = [regex]::Match($gradle, 'versionName "([^"]+)"').Groups[1].Value

# Auto-increment patch (2.82 -> 2.83), or use explicit -Version override
if ($Version) {
    $newName = $Version
} else {
    $parts     = $name -split '\.'
    $parts[-1] = [string]([int]$parts[-1] + 1)
    $newName   = $parts -join '.'
}
$newCode   = $code + 1
$tag       = "v$newName"
$apkSrc    = "app/build/outputs/apk/release/app-release.apk"
$apkDest   = "MKTV.apk"
$apkUrl    = "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/$tag/$apkDest"
$note      = if ($Note) { $Note } else { "Update to v$newName" }

Write-Host ""
Write-Host "  Bumping  $name -> $newName  (build $code -> $newCode)" -ForegroundColor Cyan
Write-Host "  Note:    $note" -ForegroundColor Cyan
Write-Host ""

# ─── 2. Update build.gradle ─────────────────────────────────────────────────
$gradle = $gradle -replace "versionCode $code", "versionCode $newCode"
$gradle = $gradle -replace "versionName `"$name`"", "versionName `"$newName`""
[System.IO.File]::WriteAllText("$PSScriptRoot/app/build.gradle", $gradle, $utf8NoBom)
Write-Host "  [1/7] build.gradle updated" -ForegroundColor Green

# ─── 3. Build APK ───────────────────────────────────────────────────────────
Write-Host "  [2/7] Building release APK..." -ForegroundColor Yellow
$buildOut = & .\gradlew assembleRelease 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  BUILD FAILED" -ForegroundColor Red
    $buildOut | Select-Object -Last 30 | ForEach-Object { Write-Host "    $_" }
    exit 1
}
Copy-Item $apkSrc $apkDest -Force
Write-Host "  [2/7] APK built -> $apkDest" -ForegroundColor Green

# ─── 4. Update version.json ─────────────────────────────────────────────────
# Check if CHANGELOG already has a pre-written entry for this version
$clHeader   = "# IPTV App - Changelog`n`n"
$clRaw      = if (Test-Path "CHANGELOG.md") { Get-Content "CHANGELOG.md" -Raw } else { "" }
$clBody     = $clRaw -replace [regex]::Escape($clHeader), ""
$entryRx    = "## v$([regex]::Escape($newName))[^\r\n]*\r?\n((?:- [^\r\n]+\r?\n?)+)"
$entryMatch = [regex]::Match($clBody, $entryRx)
$notes      = if ($entryMatch.Success) {
    $entryMatch.Groups[1].Value.Trim() -split "\r?\n" |
    Where-Object { $_ -match "^- " } |
    ForEach-Object { ($_ -replace "^- ", "").Trim() }
} else { @($note) }

$vj = [ordered]@{
    versionCode = $newCode
    versionName = $newName
    apkUrl      = $apkUrl
    changelog   = $notes
}
[System.IO.File]::WriteAllText("$PSScriptRoot/version.json", ($vj | ConvertTo-Json -Depth 3), $utf8NoBom)
Write-Host "  [3/7] version.json updated" -ForegroundColor Green

# ─── 5. Update CHANGELOG.md ─────────────────────────────────────────────────
$date = Get-Date -Format "yyyy-MM-dd"
if ($entryMatch.Success) {
    # Pre-written entry exists — rewrite with header only (no duplicate)
    [System.IO.File]::WriteAllText("$PSScriptRoot/CHANGELOG.md", $clHeader + $clBody, $utf8NoBom)
} else {
    $entry = "## v$newName - $date`n- $note`n`n"
    [System.IO.File]::WriteAllText("$PSScriptRoot/CHANGELOG.md", $clHeader + $entry + $clBody, $utf8NoBom)
}

# Keep assets copy in sync
$assetsDir = "app/src/main/assets"
if (-not (Test-Path $assetsDir)) { New-Item -ItemType Directory -Path $assetsDir | Out-Null }
Copy-Item "CHANGELOG.md" "$assetsDir/CHANGELOG.md" -Force
Write-Host "  [4/7] CHANGELOG.md updated" -ForegroundColor Green

# ─── 6. Commit ──────────────────────────────────────────────────────────────
git add app/build.gradle version.json CHANGELOG.md app/src/main/assets/CHANGELOG.md
git commit -m "v${newName}: $($notes[0])"
Write-Host "  [5/7] Committed" -ForegroundColor Green

# ─── 7. Tag + push ──────────────────────────────────────────────────────────
git tag $tag
git push origin main $tag
Write-Host "  [6/7] Pushed + tagged $tag" -ForegroundColor Green

# ─── 8. GitHub release + APK upload ─────────────────────────────────────────
Write-Host "  [7/7] Creating GitHub release $tag..." -ForegroundColor Yellow

# Read token from local.properties (GH_TOKEN=xxx or GH_TOKEN_B64=base64)
$ghToken = ""
if (Test-Path "local.properties") {
    $localProps = Get-Content "local.properties" -Raw
    $tokenMatch = [regex]::Match($localProps, 'GH_TOKEN=([^\r\n]+)')
    if ($tokenMatch.Success) {
        $ghToken = $tokenMatch.Groups[1].Value.Trim()
    } else {
        $b64Match = [regex]::Match($localProps, 'GH_TOKEN_B64=([^\r\n]+)')
        if ($b64Match.Success) {
            $ghToken = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($b64Match.Groups[1].Value.Trim()))
        }
    }
}

if (-not $ghToken) {
    Write-Host "  No GH_TOKEN in local.properties — skipping GitHub release." -ForegroundColor Yellow
    Write-Host "  Add GH_TOKEN=<your_token> to local.properties and re-run, or create release manually." -ForegroundColor Yellow
} else {
    $repo = "Oliver29Klozoff/IPTV-Mj"
    $headers = @{ Authorization = "token $ghToken"; Accept = "application/vnd.github.v3+json" }

    # Create the release
    $releaseBodyText = ($notes | ForEach-Object { "- $_" }) -join "`n"
    $releaseBody = @{ tag_name = $tag; name = "MKTV v$newName"; body = $releaseBodyText; draft = $false; prerelease = $false } | ConvertTo-Json
    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Method Post -Headers $headers -Body $releaseBody -ContentType "application/json"
        $uploadUrl = $release.upload_url -replace '\{.*\}', "?name=$apkDest"

        # Upload APK
        $apkBytes = [System.IO.File]::ReadAllBytes("$PSScriptRoot/$apkDest")
        Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $headers -Body $apkBytes -ContentType "application/vnd.android.package-archive" | Out-Null

        Write-Host ""
        Write-Host "  Done!  MKTV v$newName is live." -ForegroundColor Green
        Write-Host "  OTA:   $apkUrl" -ForegroundColor Cyan
    } catch {
        Write-Host "  GitHub release failed: $_" -ForegroundColor Red
        Write-Host "  APK is at $apkDest — create release manually at github.com/$repo/releases/new" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "  Done!  MKTV v$newName is live." -ForegroundColor Green
Write-Host "  OTA:   $apkUrl" -ForegroundColor Cyan
Write-Host ""