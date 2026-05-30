@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "ROOT=%%~fI"

set "INSTALL_DIR="
set "SERVICE_NAME=FileBridgeAgent"
set "NSSM_PATH="
set "PACKAGE_ROOT="
set "STARTUP_TYPE=Automatic"
set "START_SERVICE=0"
set "FORCE=0"
set "OVERWRITE_CONFIG=0"
set "OVERWRITE_LICENSE=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="-InstallDir" (
  set "INSTALL_DIR=%~2"
  shift
  shift
  goto parse_args
)
if /I "%~1"=="-ServiceName" (
  set "SERVICE_NAME=%~2"
  shift
  shift
  goto parse_args
)
if /I "%~1"=="-NssmPath" (
  set "NSSM_PATH=%~2"
  shift
  shift
  goto parse_args
)
if /I "%~1"=="-PackageRoot" (
  set "PACKAGE_ROOT=%~2"
  shift
  shift
  goto parse_args
)
if /I "%~1"=="-StartupType" (
  set "STARTUP_TYPE=%~2"
  shift
  shift
  goto parse_args
)
if /I "%~1"=="-Start" (
  set "START_SERVICE=1"
  shift
  goto parse_args
)
if /I "%~1"=="-Force" (
  set "FORCE=1"
  shift
  goto parse_args
)
if /I "%~1"=="-OverwriteConfig" (
  set "OVERWRITE_CONFIG=1"
  shift
  goto parse_args
)
if /I "%~1"=="-OverwriteLicense" (
  set "OVERWRITE_LICENSE=1"
  shift
  goto parse_args
)
if /I "%~1"=="-ServiceUser" (
  echo -ServiceUser is no longer supported. Configure SMB credentials on the SMB file source.
  exit /b 2
)
if /I "%~1"=="-ServicePassword" (
  echo -ServicePassword is no longer supported. Configure SMB credentials on the SMB file source.
  exit /b 2
)
echo Unknown argument: %~1
exit /b 2

:args_done
if not defined INSTALL_DIR (
  if defined ProgramW6432 (
    set "INSTALL_DIR=%ProgramW6432%\FileBridge"
  ) else (
    set "INSTALL_DIR=%ProgramFiles%\FileBridge"
  )
)
for %%I in ("%INSTALL_DIR%") do set "INSTALL_DIR=%%~fI"
if defined PACKAGE_ROOT for %%I in ("%PACKAGE_ROOT%") do set "PACKAGE_ROOT=%%~fI"
if defined NSSM_PATH for %%I in ("%NSSM_PATH%") do set "NSSM_PATH=%%~fI"

call :assert_admin || exit /b 1
call :resolve_nssm || exit /b 1
call :resolve_agent_files || exit /b 1

set "AGENT_DIR=%INSTALL_DIR%\agent"
set "CONFIG_DIR=%INSTALL_DIR%\config"
set "SQL_DIR=%INSTALL_DIR%\sql"
set "LOG_DIR=%INSTALL_DIR%\logs"
set "CHECKPOINT_DIR=%INSTALL_DIR%\checkpoint"
set "RUNTIME_DIR=%INSTALL_DIR%\runtime\jre"
set "INSTALLED_NSSM=%INSTALL_DIR%\nssm.exe"
set "CONFIG_PATH=%CONFIG_DIR%\agent-config.json"
set "AGENT_JAR=%AGENT_DIR%\filebridge-agent.jar"
set "SERVICE_OUT_LOG=%LOG_DIR%\service.out.log"
set "SERVICE_ERR_LOG=%LOG_DIR%\service.err.log"
set "DATA_DIR=%ProgramData%\FileBridge\data"
set "COMMON_LOG_DIR=%ProgramData%\FileBridge\logs"

call :ensure_service_is_replaceable || exit /b 1
call :prepare_files || exit /b 1
call :resolve_java || exit /b 1
call :install_or_update_service || exit /b 1

if "%START_SERVICE%"=="1" (
  call :start_service || exit /b 1
)

echo Installed service assets under: %INSTALL_DIR%
echo NSSM: %INSTALLED_NSSM%
echo Config: %CONFIG_PATH%
echo Logs: %LOG_DIR%
exit /b 0

:assert_admin
net session >nul 2>nul
if errorlevel 1 (
  echo Please run tools\scripts\install-service.cmd from an elevated cmd session.
  exit /b 1
)
exit /b 0

:resolve_nssm
if defined NSSM_PATH (
  if exist "%NSSM_PATH%" (
    set "NSSM_SOURCE=%NSSM_PATH%"
    exit /b 0
  )
  echo NSSM not found: %NSSM_PATH%
  exit /b 1
)

if defined PACKAGE_ROOT (
  if exist "%PACKAGE_ROOT%\service\nssm.exe" (
    set "NSSM_SOURCE=%PACKAGE_ROOT%\service\nssm.exe"
    exit /b 0
  )
  if exist "%PACKAGE_ROOT%\nssm.exe" (
    set "NSSM_SOURCE=%PACKAGE_ROOT%\nssm.exe"
    exit /b 0
  )
)

if exist "%ROOT%\tools\bin\nssm.exe" (
  set "NSSM_SOURCE=%ROOT%\tools\bin\nssm.exe"
  exit /b 0
)

echo NSSM not found. Put nssm.exe under tools\bin\, pass -NssmPath, or provide a package root containing service\nssm.exe.
exit /b 1

:resolve_agent_files
if defined PACKAGE_ROOT (
  set "SOURCE_JAR=%PACKAGE_ROOT%\agent\filebridge-agent.jar"
  set "SOURCE_CONFIG=%PACKAGE_ROOT%\agent\agent-config.json"
  set "SOURCE_SQL=%PACKAGE_ROOT%\agent\sql\sqlite-init.sql"
  set "SOURCE_JRE=%PACKAGE_ROOT%\jre"
  set "SOURCE_LICENSE=%PACKAGE_ROOT%\agent\license\license.json"
  if not exist "!SOURCE_JAR!" (
    echo Packaged Agent jar not found: !SOURCE_JAR!
    exit /b 1
  )
  if not exist "!SOURCE_CONFIG!" (
    echo Packaged Agent config template not found: !SOURCE_CONFIG!
    exit /b 1
  )
  if not exist "!SOURCE_SQL!" (
    echo Packaged init SQL not found: !SOURCE_SQL!
    exit /b 1
  )
  exit /b 0
)

set "SOURCE_JAR="
for /f "delims=" %%F in ('dir /b /a:-d /o:-d "%ROOT%\package\target\*-jar-with-dependencies.jar" 2^>nul') do (
  set "SOURCE_JAR=%ROOT%\package\target\%%F"
  goto found_repo_jar
)
:found_repo_jar
if not defined SOURCE_JAR (
  echo Agent jar not found. Run tools\scripts\build-all.cmd -SkipTests first.
  exit /b 1
)

set "SOURCE_CONFIG=%ROOT%\dev\agent\config\agent-config.sample.json"
set "SOURCE_SQL=%ROOT%\dev\agent\sql\sqlite-init.sql"
set "SOURCE_JRE=%ROOT%\dev\desktop\src-tauri\resources\embedded-runtime\jre"
set "SOURCE_LICENSE="
if not exist "%SOURCE_CONFIG%" (
  echo Sample config not found: %SOURCE_CONFIG%
  exit /b 1
)
if not exist "%SOURCE_SQL%" (
  echo Init SQL not found: %SOURCE_SQL%
  exit /b 1
)
exit /b 0

:ensure_service_is_replaceable
set "EXISTING_SERVICE=0"
sc query "%SERVICE_NAME%" >nul 2>nul
if not errorlevel 1 set "EXISTING_SERVICE=1"

if "%EXISTING_SERVICE%"=="0" exit /b 0

reg query "HKLM\SYSTEM\CurrentControlSet\Services\%SERVICE_NAME%\Parameters" /v Application >nul 2>nul
if errorlevel 1 (
  if not "%FORCE%"=="1" (
    echo Service already exists but is not managed by NSSM. Re-run with -Force to replace it.
    exit /b 1
  )
  call :remove_existing_service || exit /b 1
  set "EXISTING_SERVICE=0"
  exit /b 0
)

call :stop_if_running "%SERVICE_NAME%" || exit /b 1
exit /b 0

:prepare_files
if not exist "%AGENT_DIR%" mkdir "%AGENT_DIR%" || exit /b 1
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%" || exit /b 1
if not exist "%SQL_DIR%" mkdir "%SQL_DIR%" || exit /b 1
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" || exit /b 1
if not exist "%CHECKPOINT_DIR%" mkdir "%CHECKPOINT_DIR%" || exit /b 1
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%" || exit /b 1
if not exist "%COMMON_LOG_DIR%" mkdir "%COMMON_LOG_DIR%" || exit /b 1

copy /Y "%SOURCE_JAR%" "%AGENT_JAR%" >nul || exit /b 1
copy /Y "%SOURCE_SQL%" "%SQL_DIR%\sqlite-init.sql" >nul || exit /b 1
copy /Y "%NSSM_SOURCE%" "%INSTALLED_NSSM%" >nul || exit /b 1

if "%OVERWRITE_CONFIG%"=="1" if exist "%CONFIG_PATH%" del /f /q "%CONFIG_PATH%" >nul 2>nul
if not exist "%CONFIG_PATH%" (
  call :write_agent_config || exit /b 1
)

if exist "%SOURCE_LICENSE%" (
  set "LICENSE_DIR=%CONFIG_DIR%\license"
  set "LICENSE_PATH=!LICENSE_DIR!\license.json"
  if not exist "!LICENSE_DIR!" mkdir "!LICENSE_DIR!" || exit /b 1
  if "%OVERWRITE_LICENSE%"=="1" copy /Y "%SOURCE_LICENSE%" "!LICENSE_PATH!" >nul
  if not exist "!LICENSE_PATH!" copy /Y "%SOURCE_LICENSE%" "!LICENSE_PATH!" >nul
)

if exist "%SOURCE_JRE%" (
  call :mirror_directory "%SOURCE_JRE%" "%RUNTIME_DIR%" || exit /b 1
)

call :grant_runtime_permissions || exit /b 1

if not exist "%SERVICE_OUT_LOG%" type nul > "%SERVICE_OUT_LOG%"
if not exist "%SERVICE_ERR_LOG%" type nul > "%SERVICE_ERR_LOG%"
exit /b 0

:write_agent_config
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%" || exit /b 1
set "JSON_DB=%DATA_DIR%\filebridge.sqlite"
set "JSON_INIT=%INSTALL_DIR%\sql\sqlite-init.sql"
set "JSON_LOG=%INSTALL_DIR%\logs\filebridge-agent.log"
set "JSON_CHECKPOINT=%INSTALL_DIR%\checkpoint"
set "JSON_DB=!JSON_DB:\=\\!"
set "JSON_INIT=!JSON_INIT:\=\\!"
set "JSON_LOG=!JSON_LOG:\=\\!"
set "JSON_CHECKPOINT=!JSON_CHECKPOINT:\=\\!"
> "%CONFIG_PATH%" (
  echo {
  echo   "api": {
  echo     "host": "127.0.0.1",
  echo     "port": 18090,
  echo     "token": ""
  echo   },
  echo   "sqlite": {
  echo     "databasePath": "!JSON_DB!"
  echo   },
  echo   "paths": {
  echo     "initSqlPath": "!JSON_INIT!",
  echo     "logFile": "!JSON_LOG!",
  echo     "checkpointDir": "!JSON_CHECKPOINT!"
  echo   },
  echo   "retry": {
  echo     "maxAttempts": 3,
  echo     "backoffMillis": 500
  echo   }
  echo }
)
exit /b 0

:grant_runtime_permissions
call :grant_modify "%CONFIG_DIR%" || exit /b 1
call :grant_modify "%LOG_DIR%" || exit /b 1
call :grant_modify "%CHECKPOINT_DIR%" || exit /b 1
call :grant_modify "%DATA_DIR%" || exit /b 1
call :grant_modify "%COMMON_LOG_DIR%" || exit /b 1
exit /b 0

:grant_modify
if not exist "%~1" mkdir "%~1" || exit /b 1
icacls "%~1" /grant "*S-1-5-19:(OI)(CI)M" /T /C >nul
if errorlevel 1 (
  echo Failed to grant LocalService modify permission on: %~1
  exit /b 1
)
exit /b 0

:mirror_directory
if not exist "%~1" exit /b 0
robocopy "%~1" "%~2" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 exit /b %ERRORLEVEL%
exit /b 0

:resolve_java
set "JAVA_EXE="
if exist "%RUNTIME_DIR%\bin\java.exe" set "JAVA_EXE=%RUNTIME_DIR%\bin\java.exe"
if not defined JAVA_EXE (
  for /f "delims=" %%J in ('where java.exe 2^>nul') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%J"
  )
)
if not defined JAVA_EXE (
  echo java.exe not found and bundled runtime is unavailable.
  exit /b 1
)
exit /b 0

:install_or_update_service
if "%EXISTING_SERVICE%"=="0" (
  "%INSTALLED_NSSM%" install "%SERVICE_NAME%" "%JAVA_EXE%"
  if errorlevel 1 (
    echo NSSM install failed: %SERVICE_NAME%
    exit /b 1
  )
) else (
  echo Updating NSSM service: %SERVICE_NAME%
)

set "STARTUP_VALUE=SERVICE_AUTO_START"
if /I "%STARTUP_TYPE%"=="Manual" set "STARTUP_VALUE=SERVICE_DEMAND_START"

call :run_nssm set "%SERVICE_NAME%" Application "%JAVA_EXE%" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppParameters "-jar agent\filebridge-agent.jar --config config\agent-config.json" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppDirectory "%INSTALL_DIR%" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" DisplayName "FileBridge Agent" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" Description "FileBridge backend service." || exit /b 1
call :run_nssm set "%SERVICE_NAME%" Start "%STARTUP_VALUE%" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppStdout "%SERVICE_OUT_LOG%" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppStderr "%SERVICE_ERR_LOG%" || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppRotateFiles 1 || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppRotateOnline 1 || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppRotateBytes 10485760 || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppThrottle 1500 || exit /b 1
call :run_nssm set "%SERVICE_NAME%" AppExit Default Restart || exit /b 1
call :configure_service_account || exit /b 1

del /f /q "%INSTALL_DIR%\%SERVICE_NAME%.exe" "%INSTALL_DIR%\%SERVICE_NAME%.xml" >nul 2>nul
exit /b 0

:configure_service_account
call :run_nssm set "%SERVICE_NAME%" ObjectName "NT AUTHORITY\LocalService" || exit /b 1
exit /b 0

:run_nssm
"%INSTALLED_NSSM%" %*
if errorlevel 1 (
  echo NSSM command failed: "%INSTALLED_NSSM%" %*
  exit /b 1
)
exit /b 0

:start_service
sc start "%SERVICE_NAME%" >nul 2>nul
call :wait_for_state "%SERVICE_NAME%" RUNNING 20
if errorlevel 1 (
  call :write_start_diagnostics "Unable to start service"
  echo Unable to start service '%SERVICE_NAME%'. Diagnostics were saved to: %ProgramData%\FileBridge\logs\service-start-diagnostics.log
  exit /b 1
)
exit /b 0

:remove_existing_service
call :stop_if_running "%SERVICE_NAME%" || exit /b 1
sc delete "%SERVICE_NAME%" >nul 2>nul
call :wait_until_service_gone "%SERVICE_NAME%" 15 || exit /b 1
exit /b 0

:stop_if_running
call :get_service_state "%~1"
if /I "!SERVICE_STATE!"=="NOT_FOUND" exit /b 0
if /I "!SERVICE_STATE!"=="STOPPED" exit /b 0
sc stop "%~1" >nul 2>nul
call :wait_for_state "%~1" STOPPED 20
exit /b %ERRORLEVEL%

:wait_for_state
set "WAIT_SERVICE=%~1"
set "WAIT_TARGET=%~2"
set /a WAIT_LIMIT=%~3
for /L %%I in (1,1,!WAIT_LIMIT!) do (
  call :get_service_state "!WAIT_SERVICE!"
  if /I "!SERVICE_STATE!"=="!WAIT_TARGET!" exit /b 0
  timeout /t 1 /nobreak >nul
)
exit /b 1

:wait_until_service_gone
set "WAIT_SERVICE=%~1"
set /a WAIT_LIMIT=%~2
for /L %%I in (1,1,!WAIT_LIMIT!) do (
  sc query "!WAIT_SERVICE!" >nul 2>nul
  if errorlevel 1 exit /b 0
  timeout /t 1 /nobreak >nul
)
echo Service deletion did not finish in time: !WAIT_SERVICE!
exit /b 1

:get_service_state
set "SERVICE_STATE=NOT_FOUND"
for /f "tokens=4" %%S in ('sc query "%~1" 2^>nul ^| findstr /I "STATE"') do set "SERVICE_STATE=%%S"
exit /b 0

:write_start_diagnostics
set "DIAG_DIR=%ProgramData%\FileBridge\logs"
set "DIAG_PATH=%DIAG_DIR%\service-start-diagnostics.log"
if not exist "%DIAG_DIR%" mkdir "%DIAG_DIR%" >nul 2>nul
> "%DIAG_PATH%" (
  echo START %DATE% %TIME%
  echo ServiceName=%SERVICE_NAME%
  echo InstallRoot=%INSTALL_DIR%
  echo Error=%~1
)
call :write_path_check "%NSSM_SOURCE%" >> "%DIAG_PATH%"
call :write_path_check "%INSTALLED_NSSM%" >> "%DIAG_PATH%"
call :write_path_check "%JAVA_EXE%" >> "%DIAG_PATH%"
call :write_path_check "%AGENT_JAR%" >> "%DIAG_PATH%"
call :write_path_check "%CONFIG_PATH%" >> "%DIAG_PATH%"
call :write_path_check "%SERVICE_OUT_LOG%" >> "%DIAG_PATH%"
call :write_path_check "%SERVICE_ERR_LOG%" >> "%DIAG_PATH%"
(
  echo.
  echo [sc query]
  sc query "%SERVICE_NAME%"
  echo.
  echo [sc qc]
  sc qc "%SERVICE_NAME%"
  echo.
  echo [nssm parameters]
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" Application
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" AppParameters
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" AppDirectory
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" AppStdout
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" AppStderr
  "%INSTALLED_NSSM%" get "%SERVICE_NAME%" ObjectName
  echo.
  echo [config]
  type "%CONFIG_PATH%"
) >> "%DIAG_PATH%" 2>&1
if exist "%SERVICE_OUT_LOG%" copy /Y "%SERVICE_OUT_LOG%" "%DIAG_DIR%\service.out.log" >nul 2>nul
if exist "%SERVICE_ERR_LOG%" copy /Y "%SERVICE_ERR_LOG%" "%DIAG_DIR%\service.err.log" >nul 2>nul
if exist "%LOG_DIR%\filebridge-agent.log" copy /Y "%LOG_DIR%\filebridge-agent.log" "%DIAG_DIR%\filebridge-agent.log" >nul 2>nul
exit /b 0

:write_path_check
if exist "%~1" (
  echo Path %~1 exists=true
) else (
  echo Path %~1 exists=false
)
exit /b 0
