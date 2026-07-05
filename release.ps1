# release.ps1 — Create v2.83 GitHub release and upload existing MKTV.apk
# Reads GH_TOKEN=<plain> or GH_TOKEN_B64=<base64> from local.properties

Set-Location $PSScriptRoot

$tag     = "v2.83"
$apkDest = "MKTV.apk"
$note    = "mini player larger on tablet/landscape; genre filter chips in Live tab; best fit/zoom/stretch resize modes with toast; restore picker opens at storage root; M3U channels now visible on home screen; mini player stays on full-screen channel when returning; token no longer compiled into APK"
$repo    = "Oliver29Klozoff/IPTV-Mj"

if (-not (Test-Path $apkDest)) {
    Write-Host "ERROR: $apkDest not found" -ForegroundColor Red
    exit 1
}

$ghToken = ""
if (Test-Path "local.properties") {
    $lp = Get-Content "local.properties" -Raw
    $m = [regex]::Match($lp, 'GH_TOKEN=([^\r\n]+)')
    if ($m.Success) {
        $ghToken = $m.Groups[1].Value.Trim()
    } else {
        $m2 = [regex]::Match($lp, 'GH_TOKEN_B64=([^\r\n]+)')
        if ($m2.Success) {
            try { $ghToken = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($m2.Groups[1].Value.Trim())) } catch {}
        }
    }
}

if (-not $ghToken) {
    Write-Host ""
    Write-Host "  No GH_TOKEN in local.properties." -ForegroundColor Red
    Write-Host "  Add:  GH_TOKEN=<your_token>  and re-run." -ForegroundColor Yellow
    exit 1
}

$headers = @{ Authorization = "token $ghToken"; Accept = "application/vnd.github.v3+json" }

Write-Host ""
Write-Host "  Creating GitHub release $tag ..." -ForegroundColor Cyan

# Check if release already exists for this tag
$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$tag" -Headers $headers -ErrorAction Stop
} catch {}

if ($existing) {
    Write-Host "  Release $tag already exists (id=$($existing.id)) — re-uploading APK..." -ForegroundColor Yellow
    # Delete existing APK asset if present so we can re-upload
    foreach ($asset in $existing.assets) {
        if ($asset.name -eq $apkDest) {
            Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($asset.id)" -Method Delete -Headers $headers | Out-Null
        }
    }
    $uploadUrl = "https://uploads.github.com/repos/$repo/releases/$($existing.id)/assets?name=$apkDest"
} else {
    $body = @{ tag_name = $tag; name = "MKTV $tag"; body = $note; draft = $false; prerelease = $false } | ConvertTo-Json
    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Method Post -Headers $headers -Body $body -ContentType "application/json" -ErrorAction Stop
        $uploadUrl = $release.upload_url -replace '\{.*\}', "?name=$apkDest"
        Write-Host "  Release created (id=$($release.id))" -ForegroundColor Green
    } catch {
        Write-Host "  Failed to create release: $_" -ForegroundColor Red
        Write-Host "  Token may be revoked — generate a new PAT at github.com/settings/tokens" -ForegroundColor Yellow
        Write-Host "  Scope needed: Contents (write)" -ForegroundColor Yellow
        Write-Host "  Then add to local.properties:  GH_TOKEN=<new_token>" -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "  Uploading $apkDest ..." -ForegroundColor Cyan
try {
    $apkBytes = [System.IO.File]::ReadAllBytes("$PSScriptRoot\$apkDest")
    Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $headers -Body $apkBytes -ContentType "application/vnd.android.package-archive" -ErrorAction Stop | Out-Null
    Write-Host ""
    Write-Host "  Done! OTA update is live." -ForegroundColor Green
    Write-Host "  https://github.com/$repo/releases/download/$tag/$apkDest" -ForegroundColor Cyan
    Write-Host ""
} catch {
    Write-Host "  APK upload failed: $_" -ForegroundColor Red
    exit 1
}
