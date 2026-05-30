@echo off
setlocal

set "VSDEVCMD=D:\Environment\Microsoft\Common7\Tools\VsDevCmd.bat"
set "CARGO_HOME=D:\Environment\Rust\cargo"
set "RUSTUP_HOME=D:\Environment\Rust\rustup"

if not exist "%VSDEVCMD%" (
  echo Visual Studio Build Tools environment was not found: %VSDEVCMD%
  exit /b 1
)

call "%VSDEVCMD%" -arch=x64
if errorlevel 1 exit /b %ERRORLEVEL%

set "PATH=%CARGO_HOME%\bin;%PATH%"

if "%~1"=="--check" (
  pushd "%~dp0src-tauri"
  cargo check
  set "EXIT_CODE=%ERRORLEVEL%"
  popd
  exit /b %EXIT_CODE%
)

npm.cmd run tauri:build -- %*
