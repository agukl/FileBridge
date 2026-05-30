@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "ROOT=%%~fI"

set "INSTALL_DIR="
set "SERVICE_NAME=FileBridgeAgent"
set "REMOVE_FILES=0"
set "PRESERVE_CONFIG=0"

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
if /I "%~1"=="-RemoveFiles" (
  set "REMOVE_FILES=1"
  shift
  goto parse_args
)
if /I "%~1"=="-PreserveConfig" (
  set "PRESERVE_CONFIG=1"
  shift
  goto parse_args
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

call :assert_admin || exit /b 1
call :resolve_nssm

sc query "%SERVICE_NAME%" >nul 2>nul
if errorlevel 1 (
  echo Service is not installed: %SERVICE_NAME%
) else (
  call :stop_if_running "%SERVICE_NAME%" || exit /b 1
  if defined NSSM_EXE (
    "%NSSM_EXE%" remove "%SERVICE_NAME%" confirm
    if errorlevel 1 (
      echo NSSM remove failed: %SERVICE_NAME%
      exit /b 1
    )
  ) else (
    sc delete "%SERVICE_NAME%" >nul
  )
  call :wait_until_service_gone "%SERVICE_NAME%" 15 || exit /b 1
  echo Service removed: %SERVICE_NAME%
)

if "%REMOVE_FILES%"=="1" (
  if exist "%INSTALL_DIR%" (
    if "%PRESERVE_CONFIG%"=="1" (
      call :remove_files_preserving_config || exit /b 1
      echo Removed service binaries and preserved config: %INSTALL_DIR%
    ) else (
      rd /s /q "%INSTALL_DIR%"
      if errorlevel 1 exit /b 1
      echo Removed files: %INSTALL_DIR%
    )
  )
)
exit /b 0

:remove_files_preserving_config
if exist "%INSTALL_DIR%\agent" rd /s /q "%INSTALL_DIR%\agent" || exit /b 1
if exist "%INSTALL_DIR%\runtime" rd /s /q "%INSTALL_DIR%\runtime" || exit /b 1
if exist "%INSTALL_DIR%\sql" rd /s /q "%INSTALL_DIR%\sql" || exit /b 1
if exist "%INSTALL_DIR%\nssm.exe" del /f /q "%INSTALL_DIR%\nssm.exe" >nul 2>nul
if exist "%INSTALL_DIR%\%SERVICE_NAME%.exe" del /f /q "%INSTALL_DIR%\%SERVICE_NAME%.exe" >nul 2>nul
if exist "%INSTALL_DIR%\%SERVICE_NAME%.xml" del /f /q "%INSTALL_DIR%\%SERVICE_NAME%.xml" >nul 2>nul
exit /b 0

:assert_admin
net session >nul 2>nul
if errorlevel 1 (
  echo Please run tools\scripts\uninstall-service.cmd from an elevated cmd session.
  exit /b 1
)
exit /b 0

:resolve_nssm
set "NSSM_EXE="
if exist "%INSTALL_DIR%\nssm.exe" (
  set "NSSM_EXE=%INSTALL_DIR%\nssm.exe"
  exit /b 0
)
if exist "%ROOT%\tools\bin\nssm.exe" (
  set "NSSM_EXE=%ROOT%\tools\bin\nssm.exe"
)
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
