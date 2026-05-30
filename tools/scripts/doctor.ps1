param(
  [switch]$RequireDesktop
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Test-Command($Name) {
  $command = Get-Command $Name -ErrorAction SilentlyContinue
  return $null -ne $command
}

function Write-Check($Name, $Ok, $Detail = "") {
  $mark = if ($Ok) { "[OK]" } else { "[!!]" }
  $line = "$mark $Name"
  if ($Detail) {
    $line = "$line - $Detail"
  }
  if ($Ok) {
    Write-Host $line -ForegroundColor Green
  } else {
    Write-Host $line -ForegroundColor Yellow
  }
}

function Get-CommandOutput($CommandLine) {
  try {
    return (cmd.exe /c "$CommandLine 2>&1" | Select-Object -First 1)
  } catch {
    return ""
  }
}

function Test-Port($Port) {
  @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-ProjectVersion {
  $pomPath = Join-Path $Root "pom.xml"
  if (!(Test-Path $pomPath)) {
    return ""
  }
  try {
    $pom = [xml](Get-Content -LiteralPath $pomPath -Raw)
    return [string]$pom.project.version
  } catch {
    return ""
  }
}

function Find-AgentJar($ProjectVersion) {
  $targetDir = Join-Path $Root "package\target"
  if ($ProjectVersion) {
    $expectedJar = Join-Path $targetDir "filebridge-agent-$ProjectVersion-jar-with-dependencies.jar"
    if (Test-Path $expectedJar) {
      return Get-Item -LiteralPath $expectedJar
    }
  }
  Get-ChildItem -LiteralPath $targetDir -Filter "*-jar-with-dependencies.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}

$hasJava = Test-Command "java"
$hasMaven = Test-Command "mvn.cmd"
if (-not $hasMaven) {
  $hasMaven = Test-Command "mvn"
}
$hasNode = Test-Command "node"
$hasNpm = Test-Command "npm.cmd"
if (-not $hasNpm) {
  $hasNpm = Test-Command "npm"
}
$hasCargo = Test-Command "cargo"

Write-Host "FileBridge Agent environment check" -ForegroundColor Cyan
Write-Host "Root: $Root"
Write-Host ""

$javaDetail = if ($hasJava) { Get-CommandOutput "java -version" } else { "java not found" }
$nodeDetail = if ($hasNode) { Get-CommandOutput "node --version" } else { "node not found" }
$npmDetail = if ($hasNpm) { Get-CommandOutput "npm --version" } else { "npm not found" }
$cargoDetail = if ($hasCargo) { Get-CommandOutput "cargo --version" } else { "cargo not found; only required for Tauri desktop build" }

Write-Check "Java runtime" $hasJava $javaDetail
Write-Check "Maven" $hasMaven $(if ($hasMaven) { "available" } else { "mvn not found" })
Write-Check "Node.js" $hasNode $nodeDetail
Write-Check "npm" $hasNpm $npmDetail
Write-Check "Cargo" $hasCargo $cargoDetail

$configPath = Join-Path $Root "dev\agent\config\agent-config.json"
$sampleConfigPath = Join-Path $Root "dev\agent\config\agent-config.sample.json"
$projectVersion = Get-ProjectVersion
$expectedJarPath = if ($projectVersion) { Join-Path $Root "package\target\filebridge-agent-$projectVersion-jar-with-dependencies.jar" } else { "" }
$jarItem = Find-AgentJar $projectVersion
$jarPath = if ($jarItem) { $jarItem.FullName } else { Join-Path $Root "package\target\*-jar-with-dependencies.jar" }
$jarMatchesVersion = $jarItem -and $expectedJarPath -and ($jarItem.FullName -ieq $expectedJarPath)
$distPath = Join-Path $Root "dev\desktop\dist\index.html"
$nodeModulesPath = Join-Path $Root "dev\desktop\node_modules"
$nssmPath = Join-Path $Root "tools\bin\nssm.exe"
$serviceInstallDir = Join-Path $env:ProgramFiles "FileBridge"
$serviceConfigPath = Join-Path $serviceInstallDir "config\agent-config.json"
$service = Get-Service -Name "FileBridgeAgent" -ErrorAction SilentlyContinue

Write-Check "Agent config" (Test-Path $configPath) $(if (Test-Path $configPath) { $configPath } else { "missing; copy from $sampleConfigPath" })
Write-Check "Agent jar" $jarMatchesVersion $(if ($jarMatchesVersion) { $jarPath } elseif ($jarItem) { "found stale jar: $jarPath; run tools\scripts\build-all.cmd" } else { "missing; run tools\scripts\build-all.cmd" })
Write-Check "Frontend dist" (Test-Path $distPath) $(if (Test-Path $distPath) { $distPath } else { "missing; run tools\scripts\build-all.cmd" })
Write-Check "Desktop dependencies" (Test-Path $nodeModulesPath) $(if (Test-Path $nodeModulesPath) { $nodeModulesPath } else { "missing; build script will run npm install" })
Write-Check "NSSM" (Test-Path $nssmPath) $(if (Test-Path $nssmPath) { $nssmPath } else { "missing; put nssm.exe under tools\bin" })
Write-Check "Windows Service" ($null -ne $service) $(if ($service) { "$($service.Name) $($service.Status)" } else { "not installed; run tools\scripts\install-service.cmd from elevated cmd" })
Write-Check "Service config" (Test-Path $serviceConfigPath) $(if (Test-Path $serviceConfigPath) { $serviceConfigPath } else { "not found under Program Files" })

$agentPort = Test-Port 18090
$vitePort = Test-Port 1420
Write-Check "Port 18090" ($agentPort.Count -eq 0) $(if ($agentPort.Count -eq 0) { "free" } else { "listening pid(s): " + (($agentPort | Select-Object -ExpandProperty OwningProcess -Unique) -join ", ") })
Write-Check "Port 1420" ($vitePort.Count -eq 0) $(if ($vitePort.Count -eq 0) { "free" } else { "listening pid(s): " + (($vitePort | Select-Object -ExpandProperty OwningProcess -Unique) -join ", ") })

if ($RequireDesktop -and -not $hasCargo) {
  throw "Cargo is required for desktop packaging. Install Rust toolchain first."
}

Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  Production installer:"
Write-Host "    tools\scripts\build-windows-installer.cmd -SkipTests"
Write-Host "    package\output\FileBridge Setup 1.0.1.exe"
Write-Host "  Development:"
Write-Host "    tools\scripts\build-all.cmd"
Write-Host "    tools\scripts\start-agent.cmd -Restart"
Write-Host "    cd dev\desktop; npm.cmd run dev"
