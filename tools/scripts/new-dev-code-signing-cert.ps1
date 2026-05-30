param(
  [string]$Subject = "CN=FileBridge Development Code Signing",
  [string]$CertDir = "",
  [int]$ValidYears = 3,
  [string]$PfxPassword = "",
  [switch]$TrustCurrentUser,
  [switch]$NoTrustCurrentUser
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($CertDir)) {
  $CertDir = Join-Path $Root "dev\agent\config\dev\codesign"
}

function New-HexPassword([int]$Bytes = 24) {
  $buffer = New-Object byte[] $Bytes
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($buffer)
  } finally {
    $rng.Dispose()
  }
  return -join ($buffer | ForEach-Object { $_.ToString("x2") })
}

function Add-CurrentUserCertificateStore([string]$StoreName, [string]$CertificatePath) {
  & certutil.exe -user -addstore -f $StoreName $CertificatePath | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "certutil addstore failed for $StoreName with exit code $LASTEXITCODE"
  }
}

New-Item -ItemType Directory -Path $CertDir -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($PfxPassword)) {
  $PfxPassword = New-HexPassword
}

$cert = New-SelfSignedCertificate `
  -Type CodeSigningCert `
  -Subject $Subject `
  -CertStoreLocation "Cert:\CurrentUser\My" `
  -KeyAlgorithm RSA `
  -KeyLength 3072 `
  -HashAlgorithm SHA256 `
  -NotAfter (Get-Date).AddYears($ValidYears)

$securePassword = ConvertTo-SecureString -String $PfxPassword -AsPlainText -Force
$pfxPath = Join-Path $CertDir "filebridge-dev-code-signing.pfx"
$cerPath = Join-Path $CertDir "filebridge-dev-code-signing.cer"
$passwordPath = Join-Path $CertDir "filebridge-dev-code-signing.password.txt"
$thumbprintPath = Join-Path $CertDir "filebridge-dev-code-signing.thumbprint.txt"
$envPath = Join-Path $CertDir "signing-env.cmd"

Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $securePassword -Force | Out-Null
Export-Certificate -Cert $cert -FilePath $cerPath -Force | Out-Null
Set-Content -LiteralPath $passwordPath -Value $PfxPassword -Encoding UTF8
Set-Content -LiteralPath $thumbprintPath -Value $cert.Thumbprint -Encoding UTF8

$escapedPfxPath = $pfxPath.Replace("^", "^^")
$envContent = @"
@echo off
rem Local development code-signing config. This directory is ignored by git.
rem Replace FILEBRIDGE_SIGN_PFX and FILEBRIDGE_SIGN_PFX_PASSWORD here when switching to a real code-signing certificate.
rem Remove FILEBRIDGE_SIGN_SKIP_VERIFY when switching to a real trusted certificate.
set "FILEBRIDGE_SIGN=1"
set "FILEBRIDGE_SIGN_PFX=$escapedPfxPath"
set "FILEBRIDGE_SIGN_PFX_PASSWORD=$PfxPassword"
set "FILEBRIDGE_SIGN_CERT_SUBJECT="
set "FILEBRIDGE_SIGN_CERT_SHA1="
set "FILEBRIDGE_SIGN_STORE_LOCATION=CurrentUser"
set "FILEBRIDGE_TIMESTAMP_URL=http://timestamp.digicert.com"
set "FILEBRIDGE_SIGN_SKIP_VERIFY=1"
"@
Set-Content -LiteralPath $envPath -Value $envContent -Encoding ASCII

if ($TrustCurrentUser -and -not $NoTrustCurrentUser) {
  Add-CurrentUserCertificateStore "Root" $cerPath
  Add-CurrentUserCertificateStore "TrustedPublisher" $cerPath
}

Write-Host "Development code-signing certificate created."
Write-Host "Subject:     $Subject"
Write-Host "Thumbprint:  $($cert.Thumbprint)"
Write-Host "PFX:         $pfxPath"
Write-Host "CER:         $cerPath"
Write-Host "Env file:    $envPath"
if ($TrustCurrentUser -and -not $NoTrustCurrentUser) {
  Write-Host "Trusted in CurrentUser Root and TrustedPublisher stores."
} else {
  Write-Host "Certificate was not imported into trusted root stores. Development builds skip strict signature verification."
}
Write-Host ""
Write-Host "Signed build:"
Write-Host "  tools\scripts\build-signed-installer.cmd"
