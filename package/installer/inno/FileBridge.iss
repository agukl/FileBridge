#define MyAppName "FileBridge"
#define MyAppVersion "1.0.1"
#define MyAppPublisher "acme"
#define MyAppExeName "filebridge.exe"
#define MyServiceName "FileBridgeAgent"
#define LegacyServiceName "FtpSyncAgent"
#define WebView2AppGuid "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
#define StageDir "..\..\stage"
#define WebView2InstallerPath StageDir + "\webview2\MicrosoftEdgeWebView2RuntimeInstallerX64.exe"

[Setup]
AppId={{F3B3D166-4951-4CC8-A1BA-B76854F5F844}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=no
OutputDir=..\..\output
OutputBaseFilename=FileBridge Setup {#MyAppVersion}
SetupIconFile=..\..\..\dev\desktop\src-tauri\icons\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
AllowNoIcons=yes
AlwaysRestart=no
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#StageDir}\app\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StageDir}\resources\*"; DestDir: "{app}\resources"; Flags: ignoreversion recursesubdirs createallsubdirs
#if FileExists(WebView2InstallerPath)
Source: "{#WebView2InstallerPath}"; DestDir: "{tmp}"; DestName: "MicrosoftEdgeWebView2RuntimeInstallerX64.exe"; Flags: deleteafterinstall
#endif

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent unchecked; Check: ShouldLaunchApp

[Code]
var
  CustomSetupExitCode: Integer;
  LastCommandExitCode: Integer;
  LastCommandLogPath: String;
  FullCleanUninstall: Boolean;
  PostInstallSucceeded: Boolean;

function HasBundledWebView2Installer(): Boolean;
begin
  #if FileExists(WebView2InstallerPath)
    Result := True;
  #else
    Result := False;
  #endif
end;

function IsWebView2RuntimeInstalled(): Boolean;
var
  Version: String;
begin
  Result :=
    RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{#WebView2AppGuid}', 'pv', Version) or
    RegQueryStringValue(HKLM64, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{#WebView2AppGuid}', 'pv', Version) or
    RegQueryStringValue(HKCU, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{#WebView2AppGuid}', 'pv', Version);
  Result := Result and (Version <> '');
end;

function GetInstallerLogPath(FileName: String): String;
var
  LogDir: String;
begin
  LogDir := ExpandConstant('{commonappdata}\FileBridge\logs');
  ForceDirectories(LogDir);
  Result := LogDir + '\' + FileName;
end;

function IsTruthyParam(Value: String): Boolean;
begin
  Value := LowerCase(Value);
  Result := (Value = '1') or (Value = 'true') or (Value = 'yes') or (Value = 'y') or (Value = 'on');
end;

function ShouldLaunchApp(): Boolean;
begin
  Result := PostInstallSucceeded and FileExists(ExpandConstant('{app}\{#MyAppExeName}'));
end;

function RunCmdScript(ScriptPath: String; ScriptArguments: String; StatusText: String; LogFileName: String; FailHard: Boolean): Boolean;
var
  ResultCode: Integer;
  Parameters: String;
  LogPath: String;
begin
  ResultCode := -1;
  LogPath := GetInstallerLogPath(LogFileName);
  Parameters := '/D /C ""' + ScriptPath + '" ' + ScriptArguments + ' > "' + LogPath + '" 2>&1"';
  if StatusText <> '' then
    WizardForm.StatusLabel.Caption := StatusText;
  Result := Exec(ExpandConstant('{sys}\cmd.exe'), Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
  LastCommandExitCode := ResultCode;
  LastCommandLogPath := LogPath;

  if (not Result) and FailHard then
  begin
    CustomSetupExitCode := ResultCode;
    if CustomSetupExitCode = 0 then
      CustomSetupExitCode := 1;
  end;
end;

procedure InstallWebView2RuntimeIfNeeded();
var
  ResultCode: Integer;
begin
  if IsWebView2RuntimeInstalled() then
    exit;

  if not HasBundledWebView2Installer() then
  begin
    MsgBox('WebView2 Runtime was not detected and the offline installer was not bundled. Install WebView2 Runtime before starting the desktop app.', mbError, MB_OK);
    CustomSetupExitCode := 1;
    Abort;
  end;

  WizardForm.StatusLabel.Caption := 'Installing WebView2 Runtime...';
  if (not Exec(ExpandConstant('{tmp}\MicrosoftEdgeWebView2RuntimeInstallerX64.exe'), '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, ResultCode)) or (ResultCode <> 0) then
  begin
    MsgBox('WebView2 Runtime installation failed. Setup will stop. Exit code: ' + IntToStr(ResultCode), mbError, MB_OK);
    CustomSetupExitCode := ResultCode;
    if CustomSetupExitCode = 0 then
      CustomSetupExitCode := 1;
    Abort;
  end;
end;

procedure RemoveAgentService();
var
  ScriptPath: String;
  Arguments: String;
begin
  ScriptPath := ExpandConstant('{app}\resources\embedded-runtime\service\uninstall-service.cmd');
  if not FileExists(ScriptPath) then
    exit;

  Arguments := '-ServiceName "{#MyServiceName}" -RemoveFiles';
  if not FullCleanUninstall then
    Arguments := Arguments + ' -PreserveConfig';
  RunCmdScript(ScriptPath, Arguments, 'Removing FileBridge Agent service', 'uninstall-service.log', False);
end;

procedure RemoveLegacyAgentService(PreserveConfig: Boolean);
var
  ScriptPath: String;
  Arguments: String;
begin
  ScriptPath := ExpandConstant('{app}\resources\embedded-runtime\service\uninstall-service.cmd');
  if not FileExists(ScriptPath) then
    exit;

  Arguments := '-ServiceName "{#LegacyServiceName}" -InstallDir "' + ExpandConstant('{autopf}\FtpSyncAgent') + '" -RemoveFiles';
  if PreserveConfig then
    Arguments := Arguments + ' -PreserveConfig';
  RunCmdScript(ScriptPath, Arguments, 'Removing legacy FtpSyncAgent service', 'uninstall-legacy-service.log', False);
end;

procedure RemoveAllUserAppData();
var
  ScriptPath: String;
  Parameters: String;
  ScriptContent: String;
  ResultCode: Integer;
begin
  ScriptPath := ExpandConstant('{tmp}\ftp-full-clean-user-data.cmd');
  ScriptContent :=
    '@echo off' + #13#10 +
    'setlocal' + #13#10 +
    'set "APPDATAID=com.acme.filebridge"' + #13#10 +
    'set "LEGACYAPPDATAID=com.acme.ftpfiles.manager"' + #13#10 +
    'for /d %%U in ("%SystemDrive%\Users\*") do (' + #13#10 +
    '  if /I not "%%~nxU"=="All Users" if /I not "%%~nxU"=="Default" if /I not "%%~nxU"=="Default User" if /I not "%%~nxU"=="Public" (' + #13#10 +
    '    rd /s /q "%%~fU\AppData\Local\%APPDATAID%" 2>nul' + #13#10 +
    '    rd /s /q "%%~fU\AppData\Roaming\%APPDATAID%" 2>nul' + #13#10 +
    '    rd /s /q "%%~fU\AppData\Local\%LEGACYAPPDATAID%" 2>nul' + #13#10 +
    '    rd /s /q "%%~fU\AppData\Roaming\%LEGACYAPPDATAID%" 2>nul' + #13#10 +
    '  )' + #13#10 +
    ')' + #13#10 +
    'exit /b 0' + #13#10;
  SaveStringToFile(ScriptPath, ScriptContent, False);
  Parameters := '/D /C ""' + ScriptPath + '""';
  Exec(ExpandConstant('{sys}\cmd.exe'), Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure RemoveFullCleanArtifacts();
begin
  if not FullCleanUninstall then
    exit;

  RemoveAllUserAppData();
  DelTree(ExpandConstant('{commonappdata}\FileBridge'), True, True, True);
  DelTree(ExpandConstant('{commonappdata}\FTP Remote File Manager'), True, True, True);
  DelTree(ExpandConstant('{localappdata}\com.acme.filebridge'), True, True, True);
  DelTree(ExpandConstant('{userappdata}\com.acme.filebridge'), True, True, True);
  DelTree(ExpandConstant('{localappdata}\com.acme.ftpfiles.manager'), True, True, True);
  DelTree(ExpandConstant('{userappdata}\com.acme.ftpfiles.manager'), True, True, True);
  DelTree(ExpandConstant('{autopf}\FtpSyncAgent'), True, True, True);
  DelTree(ExpandConstant('{app}'), True, True, True);
  DelTree(ExpandConstant('{group}'), True, True, True);
  DeleteFile(ExpandConstant('{autodesktop}\{#MyAppName}.lnk'));
end;

procedure InstallAgentService();
var
  ScriptPath: String;
  PackageRoot: String;
  Arguments: String;
begin
  ScriptPath := ExpandConstant('{app}\resources\embedded-runtime\service\install-service.cmd');
  PackageRoot := ExpandConstant('{app}\resources\embedded-runtime');
  Arguments := '-PackageRoot "' + PackageRoot + '" -ServiceName "{#MyServiceName}" -Force -Start';
  RemoveLegacyAgentService(True);
  WizardForm.StatusLabel.Caption := 'Installing FileBridge Agent service...';
  if not RunCmdScript(ScriptPath, Arguments, 'Installing FileBridge Agent service', 'install-service.log', True) then
  begin
    MsgBox('Installing FileBridge Agent service failed. Setup will stop. Exit code: ' + IntToStr(LastCommandExitCode) + #13#10 + 'Log: ' + LastCommandLogPath + #13#10 + 'Diagnostics: ' + ExpandConstant('{commonappdata}\FileBridge\logs\service-start-diagnostics.log'), mbError, MB_OK);
    RemoveAgentService();
    DelTree(ExpandConstant('{group}'), True, True, True);
    DeleteFile(ExpandConstant('{autodesktop}\{#MyAppName}.lnk'));
    RegDeleteKeyIncludingSubkeys(HKLM64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{F3B3D166-4951-4CC8-A1BA-B76854F5F844}_is1');
    RegDeleteKeyIncludingSubkeys(HKLM32, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{F3B3D166-4951-4CC8-A1BA-B76854F5F844}_is1');
    DelTree(ExpandConstant('{app}'), True, True, True);
    Abort;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    InstallWebView2RuntimeIfNeeded();
    InstallAgentService();
    PostInstallSucceeded := True;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    RemoveAgentService();
    RemoveLegacyAgentService(not FullCleanUninstall);
  end;

  if CurUninstallStep = usPostUninstall then
    RemoveFullCleanArtifacts();
end;

function InitializeUninstall(): Boolean;
var
  FullCleanParam: String;
begin
  Result := True;
  FullCleanUninstall := False;

  FullCleanParam := ExpandConstant('{param:FULLCLEAN|}');
  if FullCleanParam <> '' then
  begin
    FullCleanUninstall := IsTruthyParam(FullCleanParam);
    exit;
  end;

  if UninstallSilent then
    exit;

  FullCleanUninstall :=
    MsgBox(
      '是否完全清理 FileBridge？' + #13#10 + #13#10 +
      '选择“是”会额外删除 ProgramData 日志、用户缓存和残留安装目录。' + #13#10 +
      '选择“否”仅卸载程序、快捷方式和 Windows Service。',
      mbConfirmation,
      MB_YESNO
    ) = IDYES;
end;

function GetCustomSetupExitCode: Integer;
begin
  Result := CustomSetupExitCode;
end;
