$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'dev-env.ps1')
Set-Location -LiteralPath $workspaceRoot
go test -count=1 -v ./...
if ($LASTEXITCODE -ne 0) { throw 'Go tests failed' }
node --check desktop\web\app.js
if ($LASTEXITCODE -ne 0) { throw 'JavaScript syntax check failed' }
node --check desktop\web\ai.js
if ($LASTEXITCODE -ne 0) { throw 'AI JavaScript syntax check failed' }