param(
  [string]$InstallDir = (Join-Path $(if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }) "FileBridge"),
  [string]$ServiceName = "FileBridgeAgent",
  [string]$NssmPath = "",
  [string]$PackageRoot = "",
  [ValidateSet("Automatic", "Manual")]
  [string]$StartupType = "Automatic",
  [switch]$Start,
  [switch]$Force,
  [switch]$OverwriteConfig,
  [switch]$OverwriteLicense
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$PackageRootPath = ""
if ($PackageRoot) {
  $PackageRootPath = (Resolve-Path $PackageRoot -ErrorAction Stop).Path
}

function Assert-Administrator {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = [Security.Principal.WindowsPrincipal]::new($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Please run tools\scripts\install-service.cmd from an elevated cmd session."
  }
}

function Resolve-Nssm {
  if ($NssmPath) {
    $resolved = Resolve-Path $NssmPath -ErrorAction Stop
    return $resolved.Path
  }

  if ($PackageRootPath) {
    $packageCandidates = @(
      (Join-Path $PackageRootPath "service\nssm.exe"),
      (Join-Path $PackageRootPath "nssm.exe")
    )
    foreach ($candidate in $packageCandidates) {
      if (Test-Path $candidate) {
        return (Resolve-Path $candidate).Path
      }
    }
  }

  $candidate = Join-Path $Root "tools\bin\nssm.exe"
  if (Test-Path $candidate) {
    return (Resolve-Path $candidate).Path
  }
  throw "NSSM not found. Put nssm.exe under tools\bin\, pass -NssmPath, or provide a package root containing service\nssm.exe."
}

function Get-AgentJar {
  if ($PackageRootPath) {
    $jar = Join-Path $PackageRootPath "agent\filebridge-agent.jar"
    if (!(Test-Path $jar)) {
      throw "Packaged Agent jar not found: $jar"
    }
    return (Resolve-Path $jar).Path
  }

  $jar = Get-ChildItem -LiteralPath (Join-Path $Root "package\target") -Filter "*-jar-with-dependencies.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if (-not $jar) {
    throw "Agent jar not found. Run tools\scripts\build-all.cmd -SkipTests first."
  }
  return $jar.FullName
}

function Get-AgentConfigTemplate {
  if ($PackageRootPath) {
    $template = Join-Path $PackageRootPath "agent\agent-config.json"
    if (!(Test-Path $template)) {
      throw "Packaged Agent config template not found: $template"
    }
    return (Resolve-Path $template).Path
  }

  $samplePath = Join-Path $Root "dev\agent\config\agent-config.sample.json"
  if (!(Test-Path $samplePath)) {
    throw "Sample config not found: $samplePath"
  }
  return (Resolve-Path $samplePath).Path
}

function Get-InitSqlPath {
  if ($PackageRootPath) {
    $initSql = Join-Path $PackageRootPath "agent\sql\sqlite-init.sql"
    if (!(Test-Path $initSql)) {
      throw "Packaged init SQL not found: $initSql"
    }
    return (Resolve-Path $initSql).Path
  }

  $initSql = Join-Path $Root "dev\agent\sql\sqlite-init.sql"
  if (!(Test-Path $initSql)) {
    throw "Init SQL not found: $initSql"
  }
  return (Resolve-Path $initSql).Path
}

function Get-PackagedLicensePath {
  if (-not $PackageRootPath) {
    return ""
  }
  $license = Join-Path $PackageRootPath "agent\license\license.json"
  if (Test-Path $license) {
    return (Resolve-Path $license).Path
  }
  return ""
}

function Get-EmbeddedJrePath {
  if ($PackageRootPath) {
    $jre = Join-Path $PackageRootPath "jre"
    if (Test-Path $jre) {
      return (Resolve-Path $jre).Path
    }
    return ""
  }

  $jre = Join-Path $Root "dev\desktop\src-tauri\resources\embedded-runtime\jre"
  if (Test-Path $jre) {
    return (Resolve-Path $jre).Path
  }
  return ""
}

function Copy-Directory($Source, $Destination) {
  if ([string]::IsNullOrWhiteSpace($Source) -or !(Test-Path $Source)) {
    return
  }
  if (Test-Path $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
  }
  New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Write-AgentConfig($ConfigPath) {
  if ((Test-Path $ConfigPath) -and -not $OverwriteConfig) {
    return
  }

  $samplePath = Get-AgentConfigTemplate
  New-Item -ItemType Directory -Path (Split-Path $ConfigPath -Parent) -Force | Out-Null
  Copy-Item -LiteralPath $samplePath -Destination $ConfigPath -Force

  $loadedConfig = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

  $dataDir = Join-Path $env:ProgramData "FileBridge\data"
  New-Item -ItemType Directory -Path $dataDir -Force | Out-Null

  $apiConfig = if ($loadedConfig.api) {
    $loadedConfig.api
  } else {
    [pscustomobject]@{
      host = "127.0.0.1"
      port = 18090
      token = ""
    }
  }
  $retryConfig = if ($loadedConfig.retry) {
    $loadedConfig.retry
  } else {
    [pscustomobject]@{
      maxAttempts = 3
      backoffMillis = 500
    }
  }

  $config = [ordered]@{
    api = $apiConfig
    sqlite = [ordered]@{
      databasePath = Join-Path $dataDir "filebridge.sqlite"
    }
    paths = [ordered]@{
      initSqlPath = Join-Path $InstallDir "sql\sqlite-init.sql"
      logFile = Join-Path $InstallDir "logs\filebridge-agent.log"
      checkpointDir = Join-Path $InstallDir "checkpoint"
    }
    retry = $retryConfig
  }

  $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
}

function Invoke-Nssm($NssmExe, [string[]]$Arguments) {
  & $NssmExe @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "NSSM command failed: $NssmExe $($Arguments -join ' ')"
  }
}

function Get-ServiceImagePath($Name) {
  $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction SilentlyContinue
  if ($service) {
    return [string]$service.PathName
  }
  return ""
}

function Test-NssmService($Name) {
  $imagePath = (Get-ServiceImagePath $Name).ToLowerInvariant()
  $parametersPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name\Parameters"
  $application = (Get-ItemProperty -Path $parametersPath -Name "Application" -ErrorAction SilentlyContinue).Application
  return $imagePath.Contains("nssm.exe") -and -not [string]::IsNullOrWhiteSpace($application)
}

function Remove-ExistingService($Name) {
  $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
  if (-not $service) {
    return
  }
  if ($service.Status -ne "Stopped") {
    Stop-Service -Name $Name -ErrorAction Stop
    $service.WaitForStatus("Stopped", "00:00:20")
  }
  sc.exe delete $Name | Out-Host
  $deadline = (Get-Date).AddSeconds(15)
  while ((Get-Date) -lt $deadline) {
    if (-not (Get-Service -Name $Name -ErrorAction SilentlyContinue)) {
      return
    }
    Start-Sleep -Milliseconds 300
  }
  throw "Service deletion did not finish in time: $Name"
}

function Set-NssmParameter($Name, $Parameter, $Value) {
  $parametersPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name\Parameters"
  if (!(Test-Path $parametersPath)) {
    New-Item -Path $parametersPath -Force | Out-Null
  }
  if (Get-ItemProperty -Path $parametersPath -Name $Parameter -ErrorAction SilentlyContinue) {
    Set-ItemProperty -Path $parametersPath -Name $Parameter -Value $Value
  } else {
    New-ItemProperty -Path $parametersPath -Name $Parameter -Value $Value -PropertyType String -Force | Out-Null
  }
  Write-Host "Set NSSM ${Parameter}: $Value"
}

function Resolve-JavaExe($BundledJava) {
  if (Test-Path $BundledJava) {
    return (Resolve-Path $BundledJava).Path
  }
  $java = Get-Command "java.exe" -ErrorAction SilentlyContinue
  if ($java) {
    return $java.Source
  }
  throw "java.exe not found and bundled runtime is unavailable."
}

function Get-ShortPath($Path) {
  $fullPath = [IO.Path]::GetFullPath($Path)
  $fso = New-Object -ComObject Scripting.FileSystemObject
  if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
    return $fso.GetFile($fullPath).ShortPath
  }
  if (Test-Path -LiteralPath $fullPath -PathType Container) {
    return $fso.GetFolder($fullPath).ShortPath
  }
  return $fullPath
}

function Write-DiagnosticLine($Path, $Text) {
  Add-Content -LiteralPath $Path -Value $Text -Encoding UTF8
}

function Copy-DiagnosticFile($Source, $DestinationDir, $DestinationName) {
  if ([string]::IsNullOrWhiteSpace($Source) -or !(Test-Path -LiteralPath $Source)) {
    return
  }
  Copy-Item -LiteralPath $Source -Destination (Join-Path $DestinationDir $DestinationName) -Force -ErrorAction SilentlyContinue
}

function Test-TcpPort($HostName, [int]$Port) {
  $client = [Net.Sockets.TcpClient]::new()
  try {
    $async = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne(800)) {
      return $false
    }
    $client.EndConnect($async)
    return $true
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}

function Write-ServiceStartupDiagnostics($ErrorRecord) {
  $diagnosticDir = Join-Path $env:ProgramData "FileBridge\logs"
  New-Item -ItemType Directory -Path $diagnosticDir -Force | Out-Null
  $diagnosticPath = Join-Path $diagnosticDir "service-start-diagnostics.log"
  Set-Content -LiteralPath $diagnosticPath -Encoding UTF8 -Value @(
    "START $(Get-Date -Format o)",
    "ServiceName=$ServiceName",
    "InstallRoot=$installRoot",
    "Error=$($ErrorRecord.Exception.Message)"
  )

  foreach ($pathToCheck in @($nssmExe, $javaExe, $agentJar, $configPath, $serviceOutLog, $serviceErrLog)) {
    Write-DiagnosticLine $diagnosticPath ("Path {0} exists={1}" -f $pathToCheck, (Test-Path -LiteralPath $pathToCheck))
  }

  $serviceSnapshot = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
  if ($serviceSnapshot) {
    Write-DiagnosticLine $diagnosticPath ("Service Status={0} StartType={1}" -f $serviceSnapshot.Status, $serviceSnapshot.StartType)
  } else {
    Write-DiagnosticLine $diagnosticPath "Service is not visible to Get-Service."
  }

  $win32Service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'" -ErrorAction SilentlyContinue
  if ($win32Service) {
    Write-DiagnosticLine $diagnosticPath ("Win32_Service State={0} StartMode={1} ExitCode={2} ServiceSpecificExitCode={3}" -f $win32Service.State, $win32Service.StartMode, $win32Service.ExitCode, $win32Service.ServiceSpecificExitCode)
    Write-DiagnosticLine $diagnosticPath ("Win32_Service PathName={0}" -f $win32Service.PathName)
  }

  $parametersPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName\Parameters"
  $parameters = Get-ItemProperty -Path $parametersPath -ErrorAction SilentlyContinue
  if ($parameters) {
    Write-DiagnosticLine $diagnosticPath ("NSSM Application={0}" -f $parameters.Application)
    Write-DiagnosticLine $diagnosticPath ("NSSM AppParameters={0}" -f $parameters.AppParameters)
    Write-DiagnosticLine $diagnosticPath ("NSSM AppDirectory={0}" -f $parameters.AppDirectory)
    Write-DiagnosticLine $diagnosticPath ("NSSM AppStdout={0}" -f $parameters.AppStdout)
    Write-DiagnosticLine $diagnosticPath ("NSSM AppStderr={0}" -f $parameters.AppStderr)
  }

  if (Test-Path -LiteralPath $configPath) {
    try {
      $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
      $sanitizedConfigPath = Join-Path $diagnosticDir "agent-config.sanitized.json"
      $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $sanitizedConfigPath -Encoding UTF8
      Write-DiagnosticLine $diagnosticPath "SanitizedConfig=$sanitizedConfigPath"

      if ($config.api) {
        $apiHost = if ($config.api.host) { $config.api.host } else { "127.0.0.1" }
        $apiOpen = Test-TcpPort $apiHost ([int]$config.api.port)
        Write-DiagnosticLine $diagnosticPath ("TcpCheck api {0}:{1} open={2}" -f $apiHost, $config.api.port, $apiOpen)
      }
      if ($config.sqlite -and $config.sqlite.databasePath) {
        Write-DiagnosticLine $diagnosticPath ("SQLite databasePath={0} exists={1}" -f $config.sqlite.databasePath, (Test-Path -LiteralPath $config.sqlite.databasePath))
      }
    } catch {
      Write-DiagnosticLine $diagnosticPath ("Config diagnostic failed: {0}" -f $_.Exception.Message)
    }
  }

  Copy-DiagnosticFile $serviceOutLog $diagnosticDir "service.out.log"
  Copy-DiagnosticFile $serviceErrLog $diagnosticDir "service.err.log"
  Copy-DiagnosticFile (Join-Path $logDir "filebridge-agent.log") $diagnosticDir "filebridge-agent.log"

  try {
    $foregroundOut = Join-Path $diagnosticDir "agent-foreground.out.log"
    $foregroundErr = Join-Path $diagnosticDir "agent-foreground.err.log"
    Remove-Item -LiteralPath $foregroundOut, $foregroundErr -Force -ErrorAction SilentlyContinue
    if ((Test-Path -LiteralPath $javaExe) -and (Test-Path -LiteralPath $agentJar) -and (Test-Path -LiteralPath $configPath)) {
      $probe = Start-Process -FilePath $javaExe `
        -ArgumentList @("-jar", $agentJar, "--config", $configPath) `
        -WorkingDirectory $installRoot `
        -RedirectStandardOutput $foregroundOut `
        -RedirectStandardError $foregroundErr `
        -WindowStyle Hidden `
        -PassThru
      Start-Sleep -Seconds 6
      if ($probe.HasExited) {
        Write-DiagnosticLine $diagnosticPath ("ForegroundProbe exited=true exitCode={0}" -f $probe.ExitCode)
      } else {
        Write-DiagnosticLine $diagnosticPath ("ForegroundProbe exited=false pid={0}; terminating after diagnostic probe" -f $probe.Id)
        Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue
      }
    }
  } catch {
    Write-DiagnosticLine $diagnosticPath ("Foreground probe failed: {0}" -f $_.Exception.Message)
  }

  try {
    Get-WinEvent -FilterHashtable @{ LogName = "System"; ProviderName = "Service Control Manager"; StartTime = (Get-Date).AddMinutes(-10) } -MaxEvents 20 -ErrorAction Stop |
      Format-List TimeCreated, Id, LevelDisplayName, ProviderName, Message |
      Out-File -LiteralPath (Join-Path $diagnosticDir "service-control-manager-events.log") -Encoding UTF8
  } catch {
    Write-DiagnosticLine $diagnosticPath ("Windows event diagnostic failed: {0}" -f $_.Exception.Message)
  }

  Write-DiagnosticLine $diagnosticPath "END $(Get-Date -Format o)"
  return $diagnosticPath
}

Assert-Administrator

$nssmSource = Resolve-Nssm
$jar = Get-AgentJar
$installRoot = [IO.Path]::GetFullPath($InstallDir)
$agentDir = Join-Path $installRoot "agent"
$configDir = Join-Path $installRoot "config"
$sqlDir = Join-Path $installRoot "sql"
$logDir = Join-Path $installRoot "logs"
$runtimeDir = Join-Path $installRoot "runtime\jre"
$nssmExe = Join-Path $installRoot "nssm.exe"
$legacyServiceExe = Join-Path $installRoot "$ServiceName.exe"
$legacyServiceXml = Join-Path $installRoot "$ServiceName.xml"
$configPath = Join-Path $configDir "agent-config.json"

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
  if (-not (Test-NssmService $ServiceName)) {
    if (-not $Force) {
      throw "Service already exists but is not managed by NSSM. Re-run with -Force to replace it."
    }
    Remove-ExistingService $ServiceName
    $existing = $null
  }
}

if ($existing -and $existing.Status -ne "Stopped") {
  Stop-Service -Name $ServiceName -ErrorAction Stop
  $existing.WaitForStatus("Stopped", "00:00:20")
  $existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Path $agentDir, $configDir, $sqlDir, $logDir -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $agentDir "filebridge-agent.jar") -Force
Copy-Item -LiteralPath (Get-InitSqlPath) -Destination (Join-Path $sqlDir "sqlite-init.sql") -Force
if (!(Test-Path $nssmExe) -and [IO.Path]::GetFullPath($nssmSource) -ne [IO.Path]::GetFullPath($nssmExe)) {
  Copy-Item -LiteralPath $nssmSource -Destination $nssmExe -Force
}
Write-AgentConfig $configPath
$packagedLicensePath = Get-PackagedLicensePath
if ($packagedLicensePath) {
  $licenseDir = Join-Path $configDir "license"
  $licensePath = Join-Path $licenseDir "license.json"
  New-Item -ItemType Directory -Path $licenseDir -Force | Out-Null
  if (!(Test-Path $licensePath) -or $OverwriteLicense) {
    Copy-Item -LiteralPath $packagedLicensePath -Destination $licensePath -Force
  }
}

$embeddedJre = Get-EmbeddedJrePath
Copy-Directory $embeddedJre $runtimeDir
$javaExe = Resolve-JavaExe (Join-Path $runtimeDir "bin\java.exe")
$agentJar = Join-Path $agentDir "filebridge-agent.jar"
$serviceOutLog = Join-Path $logDir "service.out.log"
$serviceErrLog = Join-Path $logDir "service.err.log"
New-Item -ItemType File -Path $serviceOutLog, $serviceErrLog -Force | Out-Null
$startupValue = if ($StartupType -eq "Automatic") { "SERVICE_AUTO_START" } else { "SERVICE_DEMAND_START" }
$serviceJavaExe = Get-ShortPath $javaExe
$serviceAgentJar = Get-ShortPath $agentJar
$serviceConfigPath = Get-ShortPath $configPath
$serviceInstallRoot = Get-ShortPath $installRoot
$serviceOutLog = Get-ShortPath $serviceOutLog
$serviceErrLog = Get-ShortPath $serviceErrLog
$appParameters = "-jar `"$agentJar`" --config `"$configPath`""

if ($existing) {
  Write-Host "Updating NSSM service: $ServiceName" -ForegroundColor Yellow
} else {
  Invoke-Nssm $nssmExe @("install", $ServiceName, $serviceJavaExe)
}

Invoke-Nssm $nssmExe @("set", $ServiceName, "Application", $serviceJavaExe)
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppParameters", "-jar $serviceAgentJar --config $serviceConfigPath")
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppDirectory", $serviceInstallRoot)
Invoke-Nssm $nssmExe @("set", $ServiceName, "DisplayName", "FileBridge Agent")
Invoke-Nssm $nssmExe @("set", $ServiceName, "Description", "FTP remote file manager backend service.")
Invoke-Nssm $nssmExe @("set", $ServiceName, "Start", $startupValue)
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppStdout", $serviceOutLog)
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppStderr", $serviceErrLog)
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppRotateFiles", "1")
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppRotateOnline", "1")
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppRotateBytes", "10485760")
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppThrottle", "1500")
Invoke-Nssm $nssmExe @("set", $ServiceName, "AppExit", "Default", "Restart")
Set-NssmParameter $ServiceName "AppParameters" $appParameters
Remove-Item -LiteralPath $legacyServiceExe, $legacyServiceXml -Force -ErrorAction SilentlyContinue

if ($Start) {
  try {
    Start-Service -Name $ServiceName -ErrorAction Stop
    (Get-Service -Name $ServiceName).WaitForStatus("Running", "00:00:20")
  } catch {
    $diagnosticPath = Write-ServiceStartupDiagnostics $_
    throw "Unable to start service '$ServiceName'. Diagnostics were saved to: $diagnosticPath. Original error: $($_.Exception.Message)"
  }
}

Write-Host "Installed service assets under: $installRoot" -ForegroundColor Green
Write-Host "NSSM: $nssmExe"
Write-Host "Config: $configPath"
Write-Host "Logs: $logDir"
