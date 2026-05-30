@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%new-dev-license-keypair.ps1" %*
exit /b %ERRORLEVEL%
