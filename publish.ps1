# Uploads this project to GitHub as a public GPLv3 repository.
# Usage (from this folder):  powershell -ExecutionPolicy Bypass -File publish.ps1
# Private folders data/ and mvt/ are excluded by .gitignore and were removed from the history.
param([string]$Name = 'MobileShield')
# native tools write progress to stderr; failures are caught through $LASTEXITCODE below
$ErrorActionPreference = 'Continue'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
Set-Location $PSScriptRoot

Write-Host '== 1/4 GitHub sign-in' -ForegroundColor Cyan
& $gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    & $gh auth login --hostname github.com --git-protocol https --web --scopes repo
    if ($LASTEXITCODE -ne 0) { throw 'GitHub sign-in did not complete' }
}
& $gh auth setup-git
$login = (& $gh api user --jq .login).Trim()
$id = (& $gh api user --jq .id).Trim()
$email = "$id+$login@users.noreply.github.com"
Write-Host "   account: $login"

Write-Host '== 2/4 Hiding the work email in saved versions' -ForegroundColor Cyan
git config user.name $login
git config user.email $email
$env:NEW_EMAIL = $email
$env:NEW_NAME = $login
$env:FILTER_BRANCH_SQUELCH_WARNING = '1'
$fix = (Join-Path $PSScriptRoot '.git\fix-email.sh') -replace '\\', '/'
git filter-branch -f --env-filter ". '$fix'" --tag-name-filter cat -- --all
if ($LASTEXITCODE -ne 0) { throw 'rewriting the history failed' }
Remove-Item .git\refs\original -Recurse -Force -ErrorAction SilentlyContinue
$left = git log --all --format='%ae %ce' | Select-String -NotMatch 'users\.noreply\.github\.com'
if ($left) { throw 'a personal email is still present, upload stopped' }
git branch -M main

Write-Host '== 3/4 Creating the public repository' -ForegroundColor Cyan
& $gh repo view "$login/$Name" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    & $gh repo create $Name --public --description 'MobileShield: on-device Android firewall, privacy monitor and data shredder, no root (Arabic/English)'
    if ($LASTEXITCODE -ne 0) { throw 'could not create the repository' }
}
git remote remove origin 2>$null
git remote add origin "https://github.com/$login/$Name.git"

Write-Host '== 4/4 Uploading code and versions' -ForegroundColor Cyan
git push -u origin main --force
git push origin --tags --force
if ($LASTEXITCODE -ne 0) { throw 'upload failed' }
Write-Host ''
Write-Host "Done: https://github.com/$login/$Name" -ForegroundColor Green
