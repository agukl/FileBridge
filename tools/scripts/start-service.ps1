param(
  [string]$ServiceName = "FileBridgeAgent"
)

$ErrorActionPreference = "Stop"
$service = Get-Service -Name $ServiceName -ErrorAction Stop
if ($service.Status -ne "Running") {
  Start-Service -Name $ServiceName -ErrorAction Stop
  (Get-Service -Name $ServiceName).WaitForStatus("Running", "00:00:20")
}
Get-Service -Name $ServiceName
