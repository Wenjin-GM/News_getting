param([string]$Output = 'dist\windows\NewsDesk.exe')
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'dev-env.ps1')
Set-Location -LiteralPath $workspaceRoot
$outputPath = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $Output))
if (-not $outputPath.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Build output must stay inside this workspace.' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
go build -trimpath -ldflags '-s -w' -o $outputPath ./desktop
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Write-Host ('Built ' + $outputPath)