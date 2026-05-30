@echo off
setlocal EnableExtensions

set "INSTALL_DIR="
set "SERVICE_NAME=FileBridgeAgent"

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
set "NSSM_EXE=%INSTALL_DIR%\nssm.exe"

sc query "%SERVICE_NAME%" >nul 2>nul
if errorlevel 1 (
  echo Service is not installed: %SERVICE_NAME%
) else (
  sc query "%SERVICE_NAME%"
  echo.
  sc qc "%SERVICE_NAME%"
  if exist "%NSSM_EXE%" (
    echo.
    echo NSSM application:
    "%NSSM_EXE%" get "%SERVICE_NAME%" Application
    echo NSSM parameters:
    "%NSSM_EXE%" get "%SERVICE_NAME%" AppParameters
    echo NSSM account:
    "%NSSM_EXE%" get "%SERVICE_NAME%" ObjectName
  )
)

echo.
echo InstallDir: %INSTALL_DIR%
echo Config:     %INSTALL_DIR%\config\agent-config.json
echo Jar:        %INSTALL_DIR%\agent\filebridge-agent.jar
echo Logs:       %INSTALL_DIR%\logs
exit /b 0
