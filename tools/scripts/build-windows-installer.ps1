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
$innoScript = Join-Path $Root "tools\scripts\build-inno-installer.ps1"

$parameters = @{}
if ($SkipTests) {
  $parameters.SkipTests = $true
}
if ($SkipTauriBuild) {
  $parameters.SkipTauriBuild = $true
}
if ($NoCompile) {
  $parameters.NoCompile = $true
}
if ($BundleLicense) {
  $parameters.BundleLicense = $true
}
if ($InnoCompilerPath) {
  $parameters.InnoCompilerPath = $InnoCompilerPath
}
if ($Sign) {
  $parameters.Sign = $true
}
if ($SignToolPath) {
  $parameters.SignToolPath = $SignToolPath
}
if ($SignPfxPath) {
  $parameters.SignPfxPath = $SignPfxPath
}
if ($SignPfxPassword) {
  $parameters.SignPfxPassword = $SignPfxPassword
}
if ($SignCertSubject) {
  $parameters.SignCertSubject = $SignCertSubject
}
if ($SignCertSha1) {
  $parameters.SignCertSha1 = $SignCertSha1
}
if ($SignStoreLocation) {
  $parameters.SignStoreLocation = $SignStoreLocation
}
if ($TimestampUrl) {
  $parameters.TimestampUrl = $TimestampUrl
}
if ($SkipSignVerify) {
  $parameters.SkipSignVerify = $true
}

& $innoScript @parameters
