@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SERVICE_NAME=FileBridgeAgent"
if /I "%~1"=="-ServiceName" set "SERVICE_NAME=%~2"

sc query "%SERVICE_NAME%" >nul 2>nul
if errorlevel 1 (
  echo Service is not installed: %SERVICE_NAME%
  exit /b 1
)

call :get_service_state "%SERVICE_NAME%"
if /I "!SERVICE_STATE!"=="STOPPED" goto print_status

sc stop "%SERVICE_NAME%" >nul 2>nul
call :wait_for_state "%SERVICE_NAME%" STOPPED 20
if errorlevel 1 (
  echo Failed to stop service: %SERVICE_NAME%
  sc query "%SERVICE_NAME%"
  exit /b 1
)

:print_status
sc query "%SERVICE_NAME%"
exit /b 0

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

:get_service_state
set "SERVICE_STATE=NOT_FOUND"
for /f "tokens=4" %%S in ('sc query "%~1" 2^>nul ^| findstr /I "STATE"') do set "SERVICE_STATE=%%S"
exit /b 0
