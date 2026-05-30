param(
  [string]$InstallDir = (Join-Path $(if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }) "FileBridge"),
  [string]$ServiceName = "FileBridgeAgent",
  [switch]$RemoveFiles,
  [switch]$PreserveConfig
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Assert-Administrator {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = [Security.Principal.WindowsPrincipal]::new($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Please run tools\scripts\uninstall-service.cmd from an elevated cmd session."
  }
}

function Resolve-Nssm($InstallRoot) {
  $installed = Join-Path $InstallRoot "nssm.exe"
  if (Test-Path $installed) {
    return (Resolve-Path $installed).Path
  }
  $tool = Join-Path $Root "tools\bin\nssm.exe"
  if (Test-Path $tool) {
    return (Resolve-Path $tool).Path
  }
  return ""
}

Assert-Administrator

$installRoot = [IO.Path]::GetFullPath($InstallDir)
$nssmExe = Resolve-Nssm $installRoot
$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue

if ($service) {
  if ($service.Status -ne "Stopped") {
    Stop-Service -Name $ServiceName -ErrorAction Stop
    $service.WaitForStatus("Stopped", "00:00:20")
  }
  if ($nssmExe) {
    & $nssmExe remove $ServiceName confirm
    if ($LASTEXITCODE -ne 0) {
      throw "NSSM remove failed: $ServiceName"
    }
  } else {
    sc.exe delete $ServiceName
  }
  Write-Host "Service removed: $ServiceName" -ForegroundColor Green
} else {
  Write-Host "Service is not installed: $ServiceName" -ForegroundColor Yellow
}

if ($RemoveFiles -and (Test-Path $installRoot)) {
  if ($PreserveConfig) {
    foreach ($relative in @("agent", "runtime", "sql")) {
      $path = Join-Path $installRoot $relative
      if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
      }
    }
    foreach ($relative in @("nssm.exe", "$ServiceName.exe", "$ServiceName.xml")) {
      $path = Join-Path $installRoot $relative
      if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Force
      }
    }
    Write-Host "Removed service binaries and preserved config: $installRoot" -ForegroundColor Green
  } else {
    Remove-Item -LiteralPath $installRoot -Recurse -Force
    Write-Host "Removed files: $installRoot" -ForegroundColor Green
  }
}
