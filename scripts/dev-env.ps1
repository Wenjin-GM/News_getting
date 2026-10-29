# Process-local build caches. This script does not edit user/system settings.
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$env:GOCACHE = Join-Path $workspaceRoot '.cache\go-build'
$env:GOMODCACHE = Join-Path $workspaceRoot '.cache\go-mod'
$env:GOPATH = Join-Path $workspaceRoot '.cache\go-path'
$env:GOTMPDIR = Join-Path $workspaceRoot '.cache\tmp'
$env:TEMP = $env:GOTMPDIR
$env:TMP = $env:GOTMPDIR
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:GOTOOLCHAIN = 'local'
New-Item -ItemType Directory -Force -Path $env:GOCACHE,$env:GOMODCACHE,$env:GOPATH,$env:GOTMPDIR | Out-Null
