param(
  [Parameter(Mandatory = $true)]
  [string]$DeviceId,
  [string]$LicenseId = "",
  [string]$Customer = "Development Temporary",
  [string]$Edition = "temporary",
  [string]$ExpiresAt = "",
  [string]$Features = "FILE_SOURCE_MANAGE,REMOTE_FTP,FILE_COPY,DIRECTORY_CACHE",
  [int]$MaxFileSources = 100,
  [int]$MaxConcurrentOperations = 8,
  [int]$GraceDays = 30,
  [string]$PrivateKeyPath = "",
  [string]$OutPath = "",
  [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Find-AgentJar {
  $jar = Get-ChildItem -LiteralPath (Join-Path $Root "package\target") -Filter "*-jar-with-dependencies.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($jar) {
    return $jar.FullName
  }
  return ""
}

if ([string]::IsNullOrWhiteSpace($PrivateKeyPath)) {
  $PrivateKeyPath = Join-Path $Root "dev\agent\config\dev\license\dev-private-key.pkcs8.base64"
}
if ([string]::IsNullOrWhiteSpace($OutPath)) {
  $OutPath = Join-Path $Root "dev\agent\config\license\license.json"
}
$autoJarPath = [string]::IsNullOrWhiteSpace($JarPath)
if ($autoJarPath) {
  $JarPath = Find-AgentJar
}
if ([string]::IsNullOrWhiteSpace($ExpiresAt)) {
  $ExpiresAt = (Get-Date).ToUniversalTime().AddYears(1).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
}
if ([string]::IsNullOrWhiteSpace($LicenseId)) {
  $suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
  $LicenseId = "DEV-$suffix"
}

if ($autoJarPath -and [string]::IsNullOrWhiteSpace($JarPath)) {
  Push-Location $Root
  try {
    mvn.cmd -q -DskipTests package
  } finally {
    Pop-Location
  }
  $JarPath = Find-AgentJar
} elseif (!(Test-Path -LiteralPath $JarPath)) {
  Push-Location $Root
  try {
    mvn.cmd -q -DskipTests package
  } finally {
    Pop-Location
  }
}
if (!(Test-Path -LiteralPath $JarPath)) {
  throw "Agent jar not found: $JarPath"
}
if (!(Test-Path -LiteralPath $PrivateKeyPath)) {
  throw "Development private key not found: $PrivateKeyPath"
}

New-Item -ItemType Directory -Path (Split-Path $OutPath -Parent) -Force | Out-Null
$arguments = @(
  "-cp", $JarPath,
  "com.acme.ftpsync.license.LicenseAdminTool",
  "sign",
  "--private-key", $PrivateKeyPath,
  "--device-id", $DeviceId,
  "--license-id", $LicenseId,
  "--customer", $Customer,
  "--edition", $Edition,
  "--expires-at", $ExpiresAt,
  "--features", $Features,
  "--max-file-sources", [string]$MaxFileSources,
  "--max-concurrent-operations", [string]$MaxConcurrentOperations,
  "--grace-days", [string]$GraceDays,
  "--out", $OutPath
)

& java @arguments
if ($LASTEXITCODE -ne 0) {
  throw "LicenseAdminTool sign failed with exit code $LASTEXITCODE."
}

Write-Host "License written to: $OutPath"
Write-Host "Device ID: $DeviceId"
Write-Host "License ID: $LicenseId"
Write-Host "Expires At: $ExpiresAt"
