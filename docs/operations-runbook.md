# 生产运维手册

FileBridge 的生产主体是 `FileBridgeAgent` Windows Service。桌面端只作为控制台，用于展示状态、管理文件源、发起操作和导入许可证。

## 运行边界

| 项目 | 说明 |
| --- | --- |
| 服务名 | `FileBridgeAgent` |
| 服务账号 | `NT AUTHORITY\LocalService` |
| API 地址 | `127.0.0.1:18090`，由配置文件控制 |
| 数据库 | 本机 SQLite |
| 外部依赖 | 不依赖 MySQL，不要求客户预装 Java |

## 关键路径

| 内容 | 路径 |
| --- | --- |
| 安装目录 | `C:\Program Files\FileBridge` |
| 配置文件 | `C:\Program Files\FileBridge\config\agent-config.json` |
| 许可证 | `C:\Program Files\FileBridge\config\license\license.json` |
| Agent 日志 | `C:\Program Files\FileBridge\logs\filebridge-agent.log` |
| SQLite 数据库 | `C:\ProgramData\FileBridge\data\filebridge.sqlite` |
| 安装日志 | `C:\ProgramData\FileBridge\logs\install-service.log` |
| 启动诊断 | `C:\ProgramData\FileBridge\logs\service-start-diagnostics.log` |

## 日常操作

查看服务状态：

```cmd
tools\scripts\service-status.cmd
```

启动服务：

```cmd
tools\scripts\start-service.cmd
```

停止服务：

```cmd
tools\scripts\stop-service.cmd
```

重新安装服务：

```cmd
tools\scripts\install-service.cmd -Force -Start
```

## 故障排查顺序

1. 确认服务是否存在并运行。
2. 查看 `C:\ProgramData\FileBridge\logs\install-service.log`。
3. 查看 `C:\ProgramData\FileBridge\logs\service-start-diagnostics.log`。
4. 查看 `C:\Program Files\FileBridge\logs\filebridge-agent.log`。
5. 检查 `127.0.0.1:18090` 是否被占用。
6. 检查许可证是否存在、设备 ID 是否匹配、是否过期。
7. 检查 SMB 文件源是否配置 UNC 路径、用户名和密码引用。

## 备份建议

升级或迁移前备份：

```text
C:\Program Files\FileBridge\config\
C:\ProgramData\FileBridge\data\filebridge.sqlite
```

日志通常不需要备份，除非正在定位客户现场问题。

## 客户现场交付口径

- 不要求客户安装数据库。
- 不要求客户安装 Java。
- 不要求客户修改 Windows Service 账号。
- 访问共享目录时，在 FileBridge 中新增 SMB 文件源并填写账号。
- 桌面端关闭不影响后端服务运行。
