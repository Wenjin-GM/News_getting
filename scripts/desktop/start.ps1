param([switch]$LAN,[switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'paths.ps1')
if (-not (Test-Path -LiteralPath $exePath)) { throw 'NewsDesk.exe not found. Run scripts\desktop\build.ps1 first.' }
New-Item -ItemType Directory -Force -Path $dataPath | Out-Null
$runtimePath = Join-Path $dataPath 'runtime.json'
$runtimeInfo = $null
if (Test-Path -LiteralPath $runtimePath) {
  try {
    $existingInfo = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json
    $existingProcess = Get-Process -Id $existingInfo.pid -ErrorAction Stop
    if ($existingProcess.Path -eq $exePath) {
      $runtimeInfo = $existingInfo
      if ($LAN -and -not $runtimeInfo.lanEnabled) {
        Write-Host 'Restarting NewsDesk with LAN access...'
        & (Join-Path $PSScriptRoot 'stop.ps1')
        $runtimeInfo = $null
      }
    }
  } catch { $runtimeInfo = $null }
}
if (-not $runtimeInfo) {
  $bindAddress = '127.0.0.1'
  if ($LAN) { $bindAddress = '0.0.0.0' }
  $argumentLine = '-data "' + $dataPath + '" -bind ' + $bindAddress + ' -port 8765'
  $appProcess = Start-Process -FilePath $exePath -ArgumentList $argumentLine -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $dataPath 'launcher.log') -RedirectStandardError (Join-Path $dataPath 'launcher-error.log') -PassThru
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 150
    if ($appProcess.HasExited) { throw ('NewsDesk failed to start. Check ' + (Join-Path $dataPath 'launcher-error.log')) }
    if (Test-Path -LiteralPath $runtimePath) {
      try { $candidateInfo = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json; if ($candidateInfo.pid -eq $appProcess.Id) { $runtimeInfo = $candidateInfo; break } } catch {}
    }
  }
  if (-not $runtimeInfo) { throw 'Start timed out. Check data\launcher-error.log.' }
}
Write-Host ''
Write-Host 'NewsDesk is running. Keep the computer awake to refresh news.'
Write-Host ('Local: ' + $runtimeInfo.localUrl)
if ($runtimeInfo.lanEnabled) { foreach ($lanUrl in $runtimeInfo.lanUrls) { Write-Host ('Phone: ' + $lanUrl) } }
Write-Host ('Data:  ' + $dataPath)
Write-Host 'No firewall, startup, registry or system proxy settings were changed.'
if (-not $NoBrowser) { Start-Process $runtimeInfo.localUrl }
