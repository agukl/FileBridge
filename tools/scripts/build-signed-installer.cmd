@echo off
setlocal EnableExtensions

set "ROOT=%~dp0..\.."
set "SIGNING_ENV=%ROOT%\dev\agent\config\dev\codesign\signing-env.cmd"

if exist "%SIGNING_ENV%" (
  call "%SIGNING_ENV%"
) else (
  echo Signing config not found: %SIGNING_ENV%
  echo Generate a local development certificate:
  echo   tools\scripts\new-dev-code-signing-cert.cmd
  echo.
  echo Or set FILEBRIDGE_SIGN_PFX / FILEBRIDGE_SIGN_PFX_PASSWORD for a real certificate.
  exit /b 1
)

if not "%FILEBRIDGE_SIGN%"=="1" set "FILEBRIDGE_SIGN=1"

if "%FILEBRIDGE_SIGN_PFX%"=="" if "%FILEBRIDGE_SIGN_CERT_SUBJECT%"=="" if "%FILEBRIDGE_SIGN_CERT_SHA1%"=="" (
  echo No signing certificate configured. Edit:
  echo   %SIGNING_ENV%
  exit /b 1
)

call "%ROOT%\tools\scripts\release-check.cmd" %*
exit /b %ERRORLEVEL%
