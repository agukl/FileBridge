# 软件行为规范

这份规范用于约束 FileBridge 的安装、运行、联网、权限、日志和卸载行为。目标是让软件在 Windows 和客户现场表现得可解释、可控制、可卸载。

## 安装行为

- 安装包要求管理员权限，只用于写入 `Program Files`、注册 Windows Service、安装 WebView2 Runtime。
- 安装过程不修改系统代理、不修改防火墙、不创建计划任务。
- 安装过程不选择服务账号，`FileBridgeAgent` 固定使用 `NT AUTHORITY\LocalService`。
- 重复安装默认保留 `agent-config.json`、`license.json` 和 SQLite 数据库。
- 安装失败时给出日志路径，不静默吞错。

## 运行行为

- 后端主体是 `FileBridgeAgent` Windows Service，桌面端只是控制台。
- Agent API 只允许监听 loopback 地址：`127.0.0.1`、`localhost` 或 `::1`。
- 默认 API 地址是 `http://127.0.0.1:18090`。
- 如果配置了 API token，所有 API 请求必须携带 `Authorization: Bearer <token>` 或 `X-FileBridge-Token`。
- 服务不依赖当前桌面用户登录会话。

## 网络行为

- 软件不做自动联网激活。
- 授权为离线许可证导入。
- 只在用户配置文件源或发起文件操作时访问目标 FTP/FTPS/SMB 地址。
- SMB 账号密码只属于 SMB 文件源，不通过修改 Windows Service 账号来访问共享目录。

## 凭据和日志

- API 响应不返回 `passwordRef` 明文，只返回是否已配置和凭据类型。
- 日志、事件、诊断错误会对 `plain:` 密码、token、Authorization 等字段脱敏。
- Agent 主日志轮转保存，避免无限增长。
- 开发私钥只放在 `dev\agent\config\dev\license\`，不进入安装包。

## 文件操作

- 文件复制必须由用户显式发起或由用户创建的目录复制任务触发。
- 取消操作采用协作式取消。
- 文件源删除前检查是否被任务引用、是否有运行中的操作。
- 本地和 SMB 文件浏览只在文件源配置路径范围内解析路径。

## 卸载行为

- 普通卸载移除程序文件、快捷方式和 Windows Service。
- 普通卸载保留配置和数据，避免误删客户业务状态。
- 完全清理卸载必须由用户确认，才删除 ProgramData、用户缓存和残留安装目录。

## 发布检查

正式出包前运行：

```cmd
tools\scripts\software-behavior-check.cmd
tools\scripts\release-check.cmd
```

`release-check.cmd` 已包含软件行为检查。
