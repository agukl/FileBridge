param(
  [switch]$Restart,
  [string]$Config = "dev\agent\config\agent-config.json"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ConfigPath = Join-Path $Root $Config
$SampleConfigPath = Join-Path $Root "dev\agent\config\agent-config.sample.json"
$TargetDir = Join-Path $Root "package\target"
$LogDir = Join-Path $Root "package\logs\desktop"
$OutLog = Join-Path $LogDir "agent.out.log"
$ErrLog = Join-Path $LogDir "agent.err.log"
$Port = 18090

function Find-AgentJar {
  $jar = Get-ChildItem -LiteralPath $TargetDir -Filter "*-jar-with-dependencies.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if (-not $jar) {
    throw "Agent jar not found under $TargetDir. Run tools\scripts\build-all.cmd first."
  }
  return $jar.FullName
}

function Get-ListeningPids($Port) {
  @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)
}

function Get-ProcessCommandLine($Pid) {
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$Pid" -ErrorAction SilentlyContinue
  if ($process) {
    return [string]$process.CommandLine
  }
  return ""
}

function Test-ManagedAgentProcess($Pid) {
  $commandLine = (Get-ProcessCommandLine $Pid).ToLowerInvariant()
  return ($commandLine.Contains("filebridge-agent") -or $commandLine.Contains("ftp-sync-agent")) -and $commandLine.Contains($Root.ToLowerInvariant())
}

if (!(Test-Path $ConfigPath)) {
  if (!(Test-Path $SampleConfigPath)) {
    throw "Config file is missing and sample config was not found: $SampleConfigPath"
  }
  New-Item -ItemType Directory -Path (Split-Path $ConfigPath -Parent) -Force | Out-Null
  Copy-Item -LiteralPath $SampleConfigPath -Destination $ConfigPath
  Write-Host "Created config from sample: $ConfigPath" -ForegroundColor Yellow
}

$JarPath = Find-AgentJar
if (!(Test-Path $JarPath)) {
  throw "Agent jar not found: $JarPath. Run tools\scripts\build-all.cmd first."
}

$pids = Get-ListeningPids $Port
if ($pids.Count -gt 0) {
  $managed = @($pids | Where-Object { Test-ManagedAgentProcess $_ })
  $unmanaged = @($pids | Where-Object { -not (Test-ManagedAgentProcess $_) })
  if ($unmanaged.Count -gt 0) {
    $details = $unmanaged | ForEach-Object { "pid=$_ command=$(Get-ProcessCommandLine $_)" }
    throw "Port $Port is occupied by an unmanaged process: $($details -join '; ')"
  }
  if (-not $Restart) {
    Write-Host "Agent already listening on port $Port. Use -Restart to restart it." -ForegroundColor Green
    exit 0
  }
  foreach ($pid in $managed) {
    Write-Host "Stopping old Agent process pid=$pid" -ForegroundColor Yellow
    Stop-Process -Id $pid -ErrorAction SilentlyContinue
  }
  $deadline = (Get-Date).AddSeconds(8)
  while ((Get-Date) -lt $deadline -and (Get-ListeningPids $Port).Count -gt 0) {
    Start-Sleep -Milliseconds 250
  }
  if ((Get-ListeningPids $Port).Count -gt 0) {
    throw "Port $Port is still occupied after stopping old Agent process."
  }
}

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$javaArgs = @("-jar", $JarPath, "--config", $ConfigPath)
$process = Start-Process -FilePath "java" -ArgumentList $javaArgs -WorkingDirectory $Root -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog -WindowStyle Hidden -PassThru
Write-Host "Started Agent pid=$($process.Id)" -ForegroundColor Green
Write-Host "Health URL: http://127.0.0.1:$Port/api/agent/health"
Write-Host "Logs:"
Write-Host "  $OutLog"
Write-Host "  $ErrLog"
