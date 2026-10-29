param([string]$Version = '1.1.0')
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'dev-env.ps1')
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected a numeric release version.' }
$releaseName = 'NewsDesk-' + $Version + '-windows-x64'
$cacheRoot = Join-Path $workspaceRoot '.cache\windows-package'
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $cacheRoot $releaseName))
if (-not $releaseRoot.StartsWith($cacheRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe package directory' }
if (Test-Path -LiteralPath $releaseRoot) { Remove-Item -LiteralPath $releaseRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $releaseRoot,(Join-Path $releaseRoot 'scripts'),(Join-Path $releaseRoot 'licenses'),(Join-Path $workspaceRoot 'dist\windows') | Out-Null
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'dist\windows\NewsDesk.exe') -Destination (Join-Path $releaseRoot 'NewsDesk.exe')
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'desktop\README.md') -Destination (Join-Path $releaseRoot 'README.md')
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $releaseRoot 'THIRD_PARTY_NOTICES.md')
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'docs\desktop\AI_GUIDE.md') -Destination (Join-Path $releaseRoot 'AI_GUIDE.md')
foreach ($name in @('启动一览.cmd','启动手机访问.cmd','停止一览.cmd')) {
  $content = [IO.File]::ReadAllText((Join-Path $workspaceRoot ('desktop\'+$name))).Replace('..\scripts\desktop\','scripts\')
  [IO.File]::WriteAllText((Join-Path $releaseRoot $name),$content)
}
foreach ($name in @('start.ps1','stop.ps1','paths.ps1')) {
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $releaseRoot ('scripts\'+$name))
}
Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'docs\desktop\licenses') -File | Copy-Item -Destination (Join-Path $releaseRoot 'licenses') -Force
$archive = Join-Path $workspaceRoot ('dist\windows\'+$releaseName+'.zip')
Compress-Archive -LiteralPath $releaseRoot -DestinationPath $archive -Force
Write-Host ('Packaged '+$archive+' (no personal data, access token or cache included)')