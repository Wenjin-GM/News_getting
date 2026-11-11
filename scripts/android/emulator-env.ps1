# Process-local emulator state. Does not enable Windows features or install drivers.
. (Join-Path $PSScriptRoot '..\dev-env.ps1')
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.local\android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.cache\android\avd'
$env:ANDROID_ADB_SERVER_PORT = '5039'
$env:ADB_SERVER_SOCKET = 'tcp:127.0.0.1:5039'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME | Out-Null
