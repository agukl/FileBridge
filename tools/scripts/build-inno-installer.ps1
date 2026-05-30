param(
  [switch]$SkipTests,
  [switch]$SkipTauriBuild,
  [switch]$NoCompile,
  [switch]$BundleLicense,
  [string]$InnoCompilerPath = "",
  [switch]$Sign,
  [string]$SignToolPath = "",
  [string]$SignPfxPath = "",
  [string]$SignPfxPassword = "",
  [string]$SignCertSubject = "",
  [string]$SignCertSha1 = "",
  [string]$SignStoreLocation = "CurrentUser",
  [string]$TimestampUrl = "",
  [switch]$SkipSignVerify
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Desktop = Join-Path $Root "dev\desktop"
$TauriDir = Join-Path $Desktop "src-tauri"
$ReleaseDir = Join-Path $TauriDir "target\release"
$InstallerDir = Join-Path $Root "package\installer\inno"
$StageDir = Join-Path $Root "package\stage"
$OutputDir = Join-Path $Root "package\output"

if ([string]::IsNullOrWhiteSpace($SignToolPath) -and $env:FILEBRIDGE_SIGNTOOL_PATH) {
  $SignToolPath = $env:FILEBRIDGE_SIGNTOOL_PATH
}
if ([string]::IsNullOrWhiteSpace($SignPfxPath) -and $env:FILEBRIDGE_SIGN_PFX) {
  $SignPfxPath = $env:FILEBRIDGE_SIGN_PFX
}
if ([string]::IsNullOrWhiteSpace($SignPfxPassword) -and $env:FILEBRIDGE_SIGN_PFX_PASSWORD) {
  $SignPfxPassword = $env:FILEBRIDGE_SIGN_PFX_PASSWORD
}
if ([string]::IsNullOrWhiteSpace($SignCertSubject) -and $env:FILEBRIDGE_SIGN_CERT_SUBJECT) {
  $SignCertSubject = $env:FILEBRIDGE_SIGN_CERT_SUBJECT
}
if ([string]::IsNullOrWhiteSpace($SignCertSha1) -and $env:FILEBRIDGE_SIGN_CERT_SHA1) {
  $SignCertSha1 = $env:FILEBRIDGE_SIGN_CERT_SHA1
}
if ([string]::IsNullOrWhiteSpace($SignStoreLocation) -and $env:FILEBRIDGE_SIGN_STORE_LOCATION) {
  $SignStoreLocation = $env:FILEBRIDGE_SIGN_STORE_LOCATION
}
if ([string]::IsNullOrWhiteSpace($TimestampUrl)) {
  $TimestampUrl = if ($env:FILEBRIDGE_TIMESTAMP_URL) { $env:FILEBRIDGE_TIMESTAMP_URL } else { "http://timestamp.digicert.com" }
}
if ($env:FILEBRIDGE_SIGN_SKIP_VERIFY -eq "1") {
  $SkipSignVerify = $true
}

function Run-Step($Name, [scriptblock]$Action) {
  Write-Host ""
  Write-Host "== $Name ==" -ForegroundColor Cyan
  & $Action
}

function Resolve-InnoCompiler {
  if ($InnoCompilerPath) {
    return (Resolve-Path $InnoCompilerPath -ErrorAction Stop).Path
  }

  $candidates = @()
  $machineIscc = [Environment]::GetEnvironmentVariable("ISCC_EXE", "Machine")
  $userIscc = [Environment]::GetEnvironmentVariable("ISCC_EXE", "User")
  $machineHome = [Environment]::GetEnvironmentVariable("INNO_SETUP_HOME", "Machine")
  $userHome = [Environment]::GetEnvironmentVariable("INNO_SETUP_HOME", "User")

  if ($machineIscc) {
    $candidates += $machineIscc
  }
  if ($machineHome) {
    $candidates += (Join-Path $machineHome "ISCC.exe")
  }
  if ($env:ProgramFiles) {
    $candidates += (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
  }
  if (${env:ProgramFiles(x86)}) {
    $candidates += (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe")
  }
  if ($env:ISCC_EXE) {
    $candidates += $env:ISCC_EXE
  }
  if ($env:INNO_SETUP_HOME) {
    $candidates += (Join-Path $env:INNO_SETUP_HOME "ISCC.exe")
  }
  if ($userIscc) {
    $candidates += $userIscc
  }
  if ($userHome) {
    $candidates += (Join-Path $userHome "ISCC.exe")
  }
  if ($env:LOCALAPPDATA) {
    $candidates += (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe")
  }

  $pathCommand = Get-Command "ISCC.exe" -ErrorAction SilentlyContinue
  if ($pathCommand) {
    $candidates += $pathCommand.Source
  }

  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path $candidate)) {
      return (Resolve-Path $candidate).Path
    }
  }

  return ""
}

function Resolve-SignTool {
  if ($SignToolPath) {
    return (Resolve-Path $SignToolPath -ErrorAction Stop).Path
  }

  $candidates = @()
  if ($env:SIGNTOOL_EXE) {
    $candidates += $env:SIGNTOOL_EXE
  }
  $pathCommand = Get-Command "signtool.exe" -ErrorAction SilentlyContinue
  if ($pathCommand) {
    $candidates += $pathCommand.Source
  }
  if (${env:ProgramFiles(x86)}) {
    $kitBin = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
    if (Test-Path $kitBin) {
      $candidates += Get-ChildItem -LiteralPath $kitBin -Recurse -Filter "signtool.exe" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\x64\\signtool\.exe$" } |
        Sort-Object FullName -Descending |
        ForEach-Object { $_.FullName }
    }
  }

  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path $candidate)) {
      return (Resolve-Path $candidate).Path
    }
  }

  return ""
}

function Test-SigningRequested {
  if ($Sign) {
    return $true
  }
  if ($env:FILEBRIDGE_SIGN -eq "1") {
    return $true
  }
  return -not (
    [string]::IsNullOrWhiteSpace($SignPfxPath) -and
    [string]::IsNullOrWhiteSpace($SignCertSubject) -and
    [string]::IsNullOrWhiteSpace($SignCertSha1)
  )
}

function Invoke-CodeSign($Path) {
  if (!(Test-Path $Path)) {
    throw "File to sign not found: $Path"
  }

  $signtool = Resolve-SignTool
  if (-not $signtool) {
    throw "signtool.exe not found. Install Windows SDK, add signtool.exe to PATH, set SIGNTOOL_EXE, or pass -SignToolPath."
  }

  $arguments = @("sign", "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256")
  if (-not [string]::IsNullOrWhiteSpace($SignPfxPath)) {
    $resolvedPfx = (Resolve-Path $SignPfxPath -ErrorAction Stop).Path
    $arguments += @("/f", $resolvedPfx)
    if (-not [string]::IsNullOrWhiteSpace($SignPfxPassword)) {
      $arguments += @("/p", $SignPfxPassword)
    }
  } elseif (-not [string]::IsNullOrWhiteSpace($SignCertSha1)) {
    $arguments += @("/sha1", $SignCertSha1)
    if ($SignStoreLocation -ieq "LocalMachine") {
      $arguments += "/sm"
    }
  } elseif (-not [string]::IsNullOrWhiteSpace($SignCertSubject)) {
    $arguments += @("/n", $SignCertSubject)
    if ($SignStoreLocation -ieq "LocalMachine") {
      $arguments += "/sm"
    }
  } else {
    throw "Signing was requested but no certificate was configured. Set -SignPfxPath, -SignCertSubject, or -SignCertSha1."
  }

  $arguments += $Path
  Write-Host "Signing: $Path"
  & $signtool @arguments
  if ($LASTEXITCODE -ne 0) {
    throw "signtool sign failed with exit code $LASTEXITCODE for $Path"
  }

  if ($SkipSignVerify) {
    Write-Host "Skipping signature verification for development signing: $Path" -ForegroundColor Yellow
  } else {
    & $signtool verify /pa /v $Path
    if ($LASTEXITCODE -ne 0) {
      throw "signtool verify failed with exit code $LASTEXITCODE for $Path"
    }
  }
}

function Copy-Directory($Source, $Destination) {
  if (!(Test-Path $Source)) {
    throw "Required directory not found: $Source"
  }
  if (Test-Path $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
  }
  New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Normalize-CmdLineEndings($Path) {
  if (!(Test-Path $Path)) {
    return
  }
  $content = [IO.File]::ReadAllText($Path)
  $content = $content -replace "`r`n|`r|`n", "`r`n"
  [IO.File]::WriteAllText($Path, $content, [Text.UTF8Encoding]::new($false))
}

function Find-WebView2OfflineInstaller {
  $candidates = @()
  if ($env:LOCALAPPDATA) {
    $tauriCache = Join-Path $env:LOCALAPPDATA "tauri\x64"
    if (Test-Path $tauriCache) {
      $candidates += Get-ChildItem -LiteralPath $tauriCache -Recurse -Filter "MicrosoftEdgeWebView2RuntimeInstallerX64.exe" -File -ErrorAction SilentlyContinue
    }
  }

  $repoCandidate = Join-Path $Root "tools\bin\MicrosoftEdgeWebView2RuntimeInstallerX64.exe"
  if (Test-Path $repoCandidate) {
    $candidates += Get-Item -LiteralPath $repoCandidate
  }

  $candidates |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}

Run-Step "Check prerequisites" {
  $nssm = Join-Path $Root "tools\bin\nssm.exe"
  if (!(Test-Path $nssm)) {
    throw "NSSM is missing: $nssm"
  }
}

if (-not $SkipTests) {
  Run-Step "Run Java tests" {
    Push-Location $Root
    try {
      mvn.cmd -q test
    } finally {
      Pop-Location
    }
  }
}

if (-not $SkipTauriBuild) {
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

  Run-Step "Build Tauri release app without bundling" {
    Push-Location $Desktop
    $previousBundleLicense = $env:FILEBRIDGE_BUNDLE_LICENSE
    try {
      if ($BundleLicense) {
        $env:FILEBRIDGE_BUNDLE_LICENSE = "1"
      } else {
        Remove-Item Env:\FILEBRIDGE_BUNDLE_LICENSE -ErrorAction SilentlyContinue
      }
      npm.cmd run tauri:build -- --no-bundle
    } finally {
      if ($null -eq $previousBundleLicense) {
        Remove-Item Env:\FILEBRIDGE_BUNDLE_LICENSE -ErrorAction SilentlyContinue
      } else {
        $env:FILEBRIDGE_BUNDLE_LICENSE = $previousBundleLicense
      }
      Pop-Location
    }
  }
}

Run-Step "Stage Inno installer files" {
  $appExe = Join-Path $ReleaseDir "filebridge.exe"
  $embeddedRuntime = Join-Path $ReleaseDir "embedded-runtime"
  if (!(Test-Path $appExe)) {
    throw "Tauri release executable not found: $appExe"
  }
  if (!(Test-Path $embeddedRuntime)) {
    throw "Embedded runtime not found: $embeddedRuntime"
  }

  if (Test-Path $StageDir) {
    Remove-Item -LiteralPath $StageDir -Recurse -Force
  }
  New-Item -ItemType Directory -Path (Join-Path $StageDir "app"), (Join-Path $StageDir "resources"), (Join-Path $StageDir "webview2") -Force | Out-Null
  Copy-Item -LiteralPath $appExe -Destination (Join-Path $StageDir "app\filebridge.exe") -Force
  Copy-Directory $embeddedRuntime (Join-Path $StageDir "resources\embedded-runtime")
  $currentEmbeddedRuntime = Join-Path $TauriDir "resources\embedded-runtime"
  $currentAgentDir = Join-Path $currentEmbeddedRuntime "agent"
  $currentJreDir = Join-Path $currentEmbeddedRuntime "jre"
  if (Test-Path $currentAgentDir) {
    Copy-Directory $currentAgentDir (Join-Path $StageDir "resources\embedded-runtime\agent")
  }
  if (Test-Path $currentJreDir) {
    Copy-Directory $currentJreDir (Join-Path $StageDir "resources\embedded-runtime\jre")
  }
  $stageServiceDir = Join-Path $StageDir "resources\embedded-runtime\service"
  if (Test-Path $stageServiceDir) {
    Remove-Item -LiteralPath $stageServiceDir -Recurse -Force
  }
  New-Item -ItemType Directory -Path $stageServiceDir -Force | Out-Null
  foreach ($serviceScript in @(
    "install-service.cmd",
    "uninstall-service.cmd",
    "start-service.cmd",
    "stop-service.cmd",
    "service-status.cmd"
  )) {
    $sourceScript = Join-Path $Root "tools\scripts\$serviceScript"
    if (Test-Path $sourceScript) {
      $targetScript = Join-Path $stageServiceDir $serviceScript
      Copy-Item -LiteralPath $sourceScript -Destination $targetScript -Force
      Normalize-CmdLineEndings $targetScript
    }
  }
  Copy-Item -LiteralPath (Join-Path $Root "tools\bin\nssm.exe") -Destination (Join-Path $stageServiceDir "nssm.exe") -Force

  $webView2Installer = Find-WebView2OfflineInstaller
  if ($webView2Installer) {
    Copy-Item -LiteralPath $webView2Installer.FullName -Destination (Join-Path $StageDir "webview2\MicrosoftEdgeWebView2RuntimeInstallerX64.exe") -Force
    Write-Host "WebView2 offline installer: $($webView2Installer.FullName)"
  } else {
    Write-Host "WebView2 offline installer was not found. The Inno script will compile, but setup will require WebView2 to already be installed." -ForegroundColor Yellow
  }
}

if (Test-SigningRequested) {
  Run-Step "Sign staged desktop executable" {
    Invoke-CodeSign (Join-Path $StageDir "app\filebridge.exe")
  }
} else {
  Write-Host ""
  Write-Host "Code signing is not configured; staged executable and installer will be unsigned." -ForegroundColor Yellow
}

if ($NoCompile) {
  Write-Host ""
  Write-Host "Inno stage is ready: $StageDir" -ForegroundColor Green
  return
}

Run-Step "Compile Inno installer" {
  $iscc = Resolve-InnoCompiler
  if (-not $iscc) {
    throw "Inno Setup compiler not found. Install Inno Setup 6, add ISCC.exe to PATH, set INNO_SETUP_HOME, or pass -InnoCompilerPath."
  }
  Write-Host "Inno compiler: $iscc"
  New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
  Get-ChildItem -LiteralPath $OutputDir -Filter "FileBridge Setup *.exe" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force
  Get-ChildItem -LiteralPath $OutputDir -Filter "FTP Remote File Manager Setup *.exe" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force
  Push-Location $InstallerDir
  try {
    & $iscc "FileBridge.iss"
    if ($LASTEXITCODE -ne 0) {
      throw "Inno Setup compiler failed with exit code $LASTEXITCODE."
    }
  } finally {
    Pop-Location
  }
}

if (Test-SigningRequested) {
  Run-Step "Sign installer" {
    $installer = Get-ChildItem -LiteralPath $OutputDir -Filter "FileBridge Setup *.exe" -File |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
    if (-not $installer) {
      throw "Compiled installer not found under: $OutputDir"
    }
    Invoke-CodeSign $installer.FullName
  }
}

Write-Host ""
Write-Host "Inno installer output:" -ForegroundColor Green
Get-ChildItem -LiteralPath $OutputDir -Filter "*.exe" -File |
  Sort-Object LastWriteTime -Descending |
  Select-Object FullName, Length, LastWriteTime |
  Format-List
