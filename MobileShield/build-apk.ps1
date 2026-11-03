# Builds MobileShield and copies the APK next to this script.
# Usage:  powershell -ExecutionPolicy Bypass -File build-apk.ps1 [-Install]
# Tools (JDK 17, Android SDK + NDK 26.3 + CMake 3.22, Gradle 8.7) are expected in $Tools.
param(
    [string]$Tools = "$env:LOCALAPPDATA\MSTools",
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot

$env:JAVA_HOME = (Get-ChildItem "$Tools\jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$Tools\sdk"
$env:GRADLE_USER_HOME = "$Tools\gh"
Set-Content -Path "$project\local.properties" -Value ("sdk.dir=" + ("$Tools\sdk" -replace '\\', '/')) -Encoding ascii
$gradle = (Get-ChildItem "$Tools\gradle" -Directory | Select-Object -First 1).FullName + '\bin\gradle.bat'

Push-Location $project
try {
    & $gradle assembleRelease --no-daemon --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
} finally {
    Pop-Location
}
$apk = "$project\MobileShield.apk"
Copy-Item "$project\app\build\outputs\apk\release\app-release.apk" $apk -Force
Write-Output "APK: $apk"

if ($Install) {
    # one-time grants that the app cannot ask for itself
    $adb = "$Tools\sdk\platform-tools\adb.exe"
    & $adb install -r -g --user 0 $apk
    & $adb shell pm grant com.ahmed.shield android.permission.WRITE_SECURE_SETTINGS
    & $adb shell pm grant com.ahmed.shield android.permission.GET_APP_OPS_STATS
    & $adb shell appops set com.ahmed.shield GET_USAGE_STATS allow
    & $adb shell appops set --uid com.ahmed.shield MANAGE_EXTERNAL_STORAGE allow
    & $adb shell cmd notification allow_listener com.ahmed.shield/com.ahmed.shield.ShieldNotificationListener
}
