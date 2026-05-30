param(
  [string]$KeyDir = "",
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

if ([string]::IsNullOrWhiteSpace($KeyDir)) {
  $KeyDir = Join-Path $Root "dev\agent\config\dev\license"
}
$autoJarPath = [string]::IsNullOrWhiteSpace($JarPath)
if ($autoJarPath) {
  $JarPath = Find-AgentJar
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

$rawJson = (& java -cp $JarPath com.acme.ftpsync.license.LicenseAdminTool generate-keypair | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
  throw "LicenseAdminTool generate-keypair failed with exit code $LASTEXITCODE."
}
$keypair = $rawJson | ConvertFrom-Json

New-Item -ItemType Directory -Path $KeyDir -Force | Out-Null
$keypair | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $KeyDir "dev-keypair.json") -Encoding UTF8
Set-Content -LiteralPath (Join-Path $KeyDir "dev-private-key.pkcs8.base64") -Value $keypair.privateKeyPkcs8Base64 -Encoding UTF8
Set-Content -LiteralPath (Join-Path $KeyDir "dev-public-key.spki.base64") -Value $keypair.publicKeySpkiBase64 -Encoding UTF8

Write-Host "Development license keypair written to: $KeyDir"
Write-Host "Public key:"
Write-Host $keypair.publicKeySpkiBase64
