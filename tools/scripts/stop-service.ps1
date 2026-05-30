param(
  [string]$ServiceName = "FileBridgeAgent"
)

$ErrorActionPreference = "Stop"
$service = Get-Service -Name $ServiceName -ErrorAction Stop
if ($service.Status -ne "Stopped") {
  Stop-Service -Name $ServiceName -ErrorAction Stop
  (Get-Service -Name $ServiceName).WaitForStatus("Stopped", "00:00:20")
}
Get-Service -Name $ServiceName
