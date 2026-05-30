# 安装和使用

生产环境中，FileBridge Agent 作为 Windows Service 稳定运行，FileBridge 桌面端只负责展示状态、管理文件源和发起操作。客户机器不需要外部数据库，也不需要预装 Java。

## 安装

1. 使用管理员权限运行安装包。
2. 安装包会复制桌面端、内置 JRE、Agent、NSSM 和 WebView2 离线安装器。
3. 安装进度页会注册并启动 `FileBridgeAgent` 服务。
4. 服务固定使用低权限 `NT AUTHORITY\LocalService` 运行。SMB/UNC 共享目录的账号密码在 SMB 文件源中配置。

安装包位置：

```cmd
package\output\FileBridge Setup 1.0.1.exe
```

## 生产路径

| 内容 | 路径 |
| --- | --- |
| 桌面端和 Agent | `C:\Program Files\FileBridge` |
| Windows Service | `FileBridgeAgent` |
| Agent 配置 | `C:\Program Files\FileBridge\config\agent-config.json` |
| 许可证 | `C:\Program Files\FileBridge\config\license\license.json` |
| Agent jar | `C:\Program Files\FileBridge\agent\filebridge-agent.jar` |
| SQLite 数据库 | `C:\ProgramData\FileBridge\data\filebridge.sqlite` |
| 安装日志 | `C:\ProgramData\FileBridge\logs\install-service.log` |
| 启动诊断 | `C:\ProgramData\FileBridge\logs\service-start-diagnostics.log` |
| Agent 日志 | `C:\Program Files\FileBridge\logs\filebridge-agent.log` |

## 升级和卸载

重复安装默认保留已有配置：

- 不覆盖 `C:\Program Files\FileBridge\config\agent-config.json`。
- 不覆盖 `C:\Program Files\FileBridge\config\license\license.json`。
- 不删除 `C:\ProgramData\FileBridge\data\filebridge.sqlite`。

普通卸载会移除 Windows Service 和运行文件，但保留 Agent 配置目录。卸载时选择“完全清理”才会删除 ProgramData、用户缓存和安装目录。

从旧命名升级时，安装程序会尝试停止并移除旧的 `FtpSyncAgent` 服务，避免占用 `127.0.0.1:18090`。

## 手动诊断

```cmd
tools\scripts\service-status.cmd
tools\scripts\start-service.cmd
tools\scripts\stop-service.cmd
```

重新安装服务：

```cmd
tools\scripts\install-service.cmd -Force -Start
```

常见安装失败先看：

```text
C:\ProgramData\FileBridge\logs\install-service.log
C:\ProgramData\FileBridge\logs\service-start-diagnostics.log
```

重点检查：

- 是否以管理员身份运行安装包。
- `FileBridgeAgent` 或旧的 `FtpSyncAgent` 是否占用服务名或端口。
- `127.0.0.1:18090` 是否被其他进程占用。
- `C:\Program Files\FileBridge` 是否被安全软件拦截。
- SMB 文件源是否配置了远端共享账号，而不是依赖当前桌面登录会话。
- 授权文件是否和当前设备 ID 匹配。

## 构建安装包

开发机需要 JDK 17、Maven、Node.js / npm、Rust / Cargo、Inno Setup 6 和 `tools\bin\nssm.exe`。

```cmd
tools\scripts\doctor.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

客户专属授权安装包：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -BundleLicense
```
