param(
    [Parameter(Mandatory=$true)][string]$Note
)
$gradlePath = "app/build.gradle"
$changelogPath = "CHANGELOG.md"

$gradle = Get-Content $gradlePath -Raw

# Bump versionCode
$currentCode = [int]([regex]::Match($gradle, 'versionCode (\d+)').Groups[1].Value)
$newCode = $currentCode + 1
$gradle = $gradle -replace "versionCode $currentCode", "versionCode $newCode"

# Auto-increment minor version (e.g. 2.0 -> 2.1)
$currentName = [regex]::Match($gradle, 'versionName "([^"]+)"').Groups[1].Value
$parts = $currentName.Split('.')
$major = $parts[0]
$minor = [int]$parts[1] + 1
$newName = "$major.$minor"
$gradle = $gradle -replace "versionName `"$currentName`"", "versionName `"$newName`""

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText("$PWD\$gradlePath", $gradle, $utf8NoBom)

# Prepend to changelog
$date = Get-Date -Format "yyyy-MM-dd HH:mm"
$existing = Get-Content $changelogPath -Raw
$header = "# IPTV App - Changelog`r`n`r`n"
$body = $existing -replace [regex]::Escape($header), ""
$newEntry = "## v$newName - $date`r`n- $Note`r`n`r`n"
[System.IO.File]::WriteAllText("$PWD\$changelogPath", $header + $newEntry + $body)

$assetsDir = "app/src/main/assets"
if (-not (Test-Path $assetsDir)) { New-Item -ItemType Directory -Path $assetsDir | Out-Null }
Copy-Item $changelogPath "$assetsDir/CHANGELOG.md" -Force

Write-Host "Bumped to v$newName (versionCode $newCode)"
Write-Host "Added changelog entry: $Note"