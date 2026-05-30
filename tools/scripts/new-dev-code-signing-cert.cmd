@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%new-dev-code-signing-cert.ps1" %*
exit /b %ERRORLEVEL%
