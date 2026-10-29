$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'paths.ps1')
$runtimePath = Join-Path $dataPath 'runtime.json'
if (-not (Test-Path -LiteralPath $runtimePath)) { Write-Host 'NewsDesk is not running.'; exit 0 }
$runtimeInfo = Get-Content -LiteralPath $runtimePath -Raw | ConvertFrom-Json
$appProcess = Get-Process -Id $runtimeInfo.pid -ErrorAction SilentlyContinue
if (-not $appProcess) { Write-Host 'NewsDesk is already stopped.'; exit 0 }
$expectedPaths = @($exePath)
if ($appProcess.Path -notin $expectedPaths) { throw 'Process path does not match this NewsDesk folder. No process was stopped.' }
$secretValue = ([uri]$runtimeInfo.localUrl).Fragment -replace '^#token=',''
try { Invoke-RestMethod -Uri ('http://127.0.0.1:' + $runtimeInfo.port + '/api/shutdown') -Method Post -Headers @{ Authorization = 'Bearer ' + $secretValue } -TimeoutSec 3 | Out-Null } catch {}
try { $appProcess.WaitForExit(6000) | Out-Null } catch {}
if (-not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id }
Write-Host 'NewsDesk stopped. Local data is preserved.'
