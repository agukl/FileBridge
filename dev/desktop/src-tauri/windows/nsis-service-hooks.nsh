!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "Installing FileBridge Agent Windows Service..."
  ExecWait '"$INSTDIR\resources\embedded-runtime\service\install-service.cmd" -PackageRoot "$INSTDIR\resources\embedded-runtime" -ServiceName "FileBridgeAgent" -Force -Start' $0
  StrCmp $0 0 filebridge_agent_service_install_ok
    MessageBox MB_ICONEXCLAMATION|MB_OK "FileBridge Agent service installation failed. The desktop app was installed, but the backend service may need to be installed manually from the embedded service scripts."
  filebridge_agent_service_install_ok:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  DetailPrint "Removing FileBridge Agent Windows Service..."
  ExecWait '"$INSTDIR\resources\embedded-runtime\service\uninstall-service.cmd" -ServiceName "FileBridgeAgent" -RemoveFiles' $0
!macroend
