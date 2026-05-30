param(
  [string]$InstallDir = (Join-Path $(if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }) "FileBridge"),
  [string]$ServiceName = "FileBridgeAgent"
)

$ErrorActionPreference = "Stop"
$installRoot = [IO.Path]::GetFullPath($InstallDir)
$nssmExe = Join-Path $installRoot "nssm.exe"
$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue

if ($service) {
  $service | Format-List Name, DisplayName, Status, StartType
  if (Test-Path $nssmExe) {
    Write-Host "NSSM application:"
    & $nssmExe get $ServiceName Application
    Write-Host "NSSM parameters:"
    & $nssmExe get $ServiceName AppParameters
    Write-Host ""
  }
} else {
  Write-Host "Service is not installed: $ServiceName" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "InstallDir: $installRoot"
Write-Host "Config:     $(Join-Path $installRoot "config\agent-config.json")"
Write-Host "Jar:        $(Join-Path $installRoot "agent\filebridge-agent.jar")"
Write-Host "Logs:       $(Join-Path $installRoot "logs")"
