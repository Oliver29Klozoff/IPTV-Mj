param(
    [Parameter(Mandatory=$true)][string]$Tag
)

# Read the real versionCode/Name from build.gradle (source of truth)
$gradle = Get-Content "app/build.gradle" -Raw
$code = [regex]::Match($gradle, 'versionCode (\d+)').Groups[1].Value
$name = [regex]::Match($gradle, 'versionName "([^"]+)"').Groups[1].Value

# Consistent APK name, always the same
$apkName = "MKTV.apk"
$apkUrl = "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/$Tag/$apkName"

# Build the release APK
Write-Host "Building release APK for build $code (v$name)..." -ForegroundColor Cyan
& .\gradlew assembleRelease 2>&1 | Select-Object -Last 2

# Copy + rename to the consistent name
Copy-Item "app\build\outputs\apk\release\app-release.apk" "$PWD\$apkName" -Force

Write-Host ""
Write-Host "=== DONE. Now do these 2 steps on GitHub: ===" -ForegroundColor Green
Write-Host ""
Write-Host "1. Create a new release with tag EXACTLY: $Tag" -ForegroundColor Yellow
Write-Host "   Upload this file: $PWD\$apkName" -ForegroundColor Yellow
Write-Host "   (asset MUST stay named $apkName)" -ForegroundColor Yellow
Write-Host ""
Write-Host "2. Paste this EXACT content into version.json:" -ForegroundColor Yellow
Write-Host ""
Write-Host "{"
Write-Host "  `"versionCode`": $code,"
Write-Host "  `"versionName`": `"$name`","
Write-Host "  `"apkUrl`": `"$apkUrl`","
Write-Host "  `"notes`": `"Update to build $code`""
Write-Host "}"
Write-Host ""
Write-Host "After both steps, verify with:" -ForegroundColor Cyan
Write-Host "  Invoke-WebRequest -Uri `"$apkUrl`" -Method Head -UseBasicParsing | Select StatusCode"
