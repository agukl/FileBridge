param(
  [switch]$SkipTests,
  [switch]$SkipDesktop,
  [switch]$CheckTauri
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Desktop = Join-Path $Root "dev\desktop"

function Run-Step($Name, [scriptblock]$Action) {
  Write-Host ""
  Write-Host "== $Name ==" -ForegroundColor Cyan
  & $Action
}

Run-Step "Build Java Agent" {
  Push-Location $Root
  try {
    if ($SkipTests) {
      mvn.cmd -q -DskipTests package
    } else {
      mvn.cmd -q test
      mvn.cmd -q -DskipTests package
    }
  } finally {
    Pop-Location
  }
}

if (-not $SkipDesktop) {
  Run-Step "Install desktop dependencies" {
    Push-Location $Desktop
    try {
      if (Test-Path "node_modules") {
        Write-Host "node_modules already exists; skipping npm install."
      } else {
        npm.cmd install
      }
    } finally {
      Pop-Location
    }
  }

  Run-Step "Build desktop frontend" {
    Push-Location $Desktop
    try {
      npm.cmd run build
    } finally {
      Pop-Location
    }
  }
}

if ($CheckTauri) {
  Run-Step "Check Tauri shell" {
    Push-Location (Join-Path $Desktop "src-tauri")
    try {
      cargo check
    } finally {
      Pop-Location
    }
  }
}

Write-Host ""
Write-Host "Build completed." -ForegroundColor Green
