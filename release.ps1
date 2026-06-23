# Read the real versionCode/Name from build.gradle (source of truth)
$gradle = Get-Content "app/build.gradle" -Raw
$code = [regex]::Match($gradle, 'versionCode (\d+)').Groups[1].Value
$name = [regex]::Match($gradle, 'versionName "([^"]+)"').Groups[1].Value

$apkName = "MKTV.apk"
$tag = "v$name"
$apkUrl = "https://github.com/Oliver29Klozoff/IPTV-Mj/releases/download/$tag/$apkName"

Write-Host "Building release APK for v$name (build $code)..." -ForegroundColor Cyan
& .\gradlew assembleRelease 2>&1 | Select-Object -Last 2

Copy-Item "app\build\outputs\apk\release\app-release.apk" "$PWD\$apkName" -Force

Write-Host ""
Write-Host "=== DONE. Now do these 2 steps on GitHub: ===" -ForegroundColor Green
Write-Host ""
Write-Host "1. Create a new release with tag EXACTLY: $tag" -ForegroundColor Yellow
Write-Host "   Upload this file: $PWD\$apkName" -ForegroundColor Yellow
Write-Host ""
Write-Host "2. Paste this EXACT content into version.json:" -ForegroundColor Yellow
Write-Host ""
Write-Host "{"
Write-Host "  `"versionCode`": $code,"
Write-Host "  `"versionName`": `"$name`","
Write-Host "  `"apkUrl`": `"$apkUrl`","
Write-Host "  `"notes`": `"Update to v$name`""
Write-Host "}"
Write-Host ""
Write-Host "After both steps, verify with:" -ForegroundColor Cyan
Write-Host "  Invoke-WebRequest -Uri `"$apkUrl`" -Method Head -UseBasicParsing | Select StatusCode"