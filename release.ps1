# Retired. This script hardcoded v2.83 and re-uploaded MKTV.apk onto an existing
# GitHub release without checking the signer, hash, commit, or working tree.
Write-Host "release.ps1 does not publish." -ForegroundColor Yellow
Write-Host "Check a candidate with .\release-verify.ps1" -ForegroundColor Yellow
Write-Host "Publish only as a separate step: .\release-publish.ps1 -Publish" -ForegroundColor Yellow
exit 1
