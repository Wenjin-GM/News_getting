$candidateRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (Test-Path -LiteralPath (Join-Path $candidateRoot 'desktop\main.go')) {
  $projectRoot = $candidateRoot
  $exePath = Join-Path $projectRoot 'dist\windows\NewsDesk.exe'
  $dataPath = Join-Path $projectRoot '.local\runtime\desktop\data'
} else {
  # Portable Windows release: start/stop/paths.ps1 live in the release's scripts/.
  $projectRoot = Split-Path -Parent $PSScriptRoot
  $exePath = Join-Path $projectRoot 'NewsDesk.exe'
  $dataPath = Join-Path $projectRoot 'data'
}
