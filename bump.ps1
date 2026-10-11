# Retired. This script used to bump the version, build, commit, tag, push main,
# and upload an APK in one run. A release build could also be signed with the
# debug keystore. That path is closed.
Write-Host "bump.ps1 does not build or publish." -ForegroundColor Yellow
Write-Host "Check a candidate with .\release-verify.ps1" -ForegroundColor Yellow
Write-Host "Publish only as a separate step: .\release-publish.ps1 -Publish" -ForegroundColor Yellow
exit 1
