@echo off
setlocal EnableExtensions

set "ROOT=%~dp0..\.."
pushd "%ROOT%" >nul || exit /b 1

set "FAILED=0"

echo == FileBridge software behavior check ==

call :must_contain "dev\agent\config\agent-config.sample.json" "127.0.0.1" "Agent sample config binds API to loopback"
call :must_not_contain "dev\agent\config\agent-config.sample.json" "0.0.0.0" "Agent sample config must not bind all interfaces"
call :must_contain "dev\desktop\public\agent-client-config.json" "127.0.0.1" "Desktop runtime config points to loopback API"
call :must_contain "tools\scripts\install-service.cmd" "NT AUTHORITY\LocalService" "Windows Service runs as LocalService"
call :must_contain "tools\scripts\install-service.cmd" "ServiceUser is no longer supported" "Service account override is blocked"
call :must_contain "package\installer\inno\FileBridge.iss" "PrivilegesRequired=admin" "Installer elevation is explicit"
call :must_contain "package\installer\inno\FileBridge.iss" "FullCleanUninstall" "Uninstall full clean requires explicit flow"
call :must_contain "dev\agent\src\main\java\com\acme\ftpsync\agent\AgentApiServer.java" "Agent API host must be loopback only" "Agent refuses non-loopback API hosts"
call :must_contain "dev\desktop\src\app\api\client.ts" "Authorization" "Desktop sends configured API token"
call :must_contain "dev\agent\src\main\java\com\acme\ftpsync\util\SecretRedactor.java" "plain:***" "Secret redaction is available"
call :must_contain "dev\agent\src\main\java\com\acme\ftpsync\agent\AgentMain.java" "10 * 1024 * 1024, 5" "Agent file log is rotated"

echo.
echo == Verify private keys are not staged ==
set "STAGED_PRIVATE_KEY="
for /f "delims=" %%F in ('dir /s /b "package\stage\*private*key*" 2^>nul') do (
  set "STAGED_PRIVATE_KEY=%%F"
)
if defined STAGED_PRIVATE_KEY (
  echo [FAIL] Private key material is staged: %STAGED_PRIVATE_KEY%
  set "FAILED=1"
) else (
  echo [OK] No private key material found under package\stage
)

if "%FAILED%"=="1" (
  echo.
  echo Software behavior check failed.
  popd >nul
  exit /b 1
)

echo.
echo Software behavior check passed.
popd >nul
exit /b 0

:must_contain
findstr /I /C:"%~2" "%~1" >nul 2>nul
if errorlevel 1 (
  echo [FAIL] %~3
  echo        Missing "%~2" in %~1
  set "FAILED=1"
) else (
  echo [OK] %~3
)
exit /b 0

:must_not_contain
findstr /I /C:"%~2" "%~1" >nul 2>nul
if errorlevel 1 (
  echo [OK] %~3
) else (
  echo [FAIL] %~3
  echo        Found "%~2" in %~1
  set "FAILED=1"
)
exit /b 0
