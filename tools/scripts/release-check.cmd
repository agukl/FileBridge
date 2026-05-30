@echo off
setlocal EnableExtensions

set "ROOT=%~dp0..\.."
pushd "%ROOT%" >nul || exit /b 1

set "INSTALLER="

echo == FileBridge release check ==

echo.
echo == Environment and project diagnostics ==
call tools\scripts\doctor.cmd
if errorlevel 1 (
  echo Release check failed during diagnostics.
  popd >nul
  exit /b 1
)

echo.
echo == Software behavior check ==
call tools\scripts\software-behavior-check.cmd
if errorlevel 1 (
  echo Release check failed during software behavior check.
  popd >nul
  exit /b 1
)

echo.
echo == Build installer ==
call tools\scripts\build-windows-installer.cmd -SkipTests %*
if errorlevel 1 (
  echo Release check failed during installer build.
  popd >nul
  exit /b 1
)

echo.
echo == Verify installer output ==
for /f "delims=" %%F in ('dir /b /a-d /o-d "package\output\FileBridge Setup *.exe" 2^>nul') do (
  if not defined INSTALLER set "INSTALLER=package\output\%%F"
)
if not defined INSTALLER (
  echo Expected installer was not found under package\output.
  popd >nul
  exit /b 1
)
for %%F in ("%INSTALLER%") do echo Installer: %%~fF ^(%%~zF bytes^)

echo.
echo == Verify private key is not staged ==
set "FOUND_PRIVATE_KEY="
for /f "delims=" %%F in ('dir /s /b "package\stage\*private*key*" 2^>nul') do (
  set "FOUND_PRIVATE_KEY=%%F"
)
if defined FOUND_PRIVATE_KEY (
  echo Private key material was staged unexpectedly:
  echo %FOUND_PRIVATE_KEY%
  popd >nul
  exit /b 1
)

echo.
echo Release check passed.
popd >nul
exit /b 0
