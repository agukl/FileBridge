# 变更记录

## 1.0.1 - 2026-05-29

- 规范软件运行边界：后端 API 固定 loopback，前端通过配置读取本机 API token。
- 增加 API token 校验、日志敏感信息脱敏和 Agent 日志轮转。
- 增加发布前软件行为检查，覆盖端口、安全配置、私钥 staging 和卸载边界。
- 增加 Windows 可信发布说明、自签名开发证书脚本和签名安装包构建入口。
- 统一版本号、安装包名称和归档文档，补充版本维护说明，方便后续迭代。

## 1.0.0 - 2026-05-29

- 产品名统一为 `FileBridge`。
- Windows Service 统一为 `FileBridgeAgent`。
- 安装目录统一为 `C:\Program Files\FileBridge`。
- 数据目录统一为 `C:\ProgramData\FileBridge`。
- 数据库切换为内置 SQLite，取消外部 MySQL 依赖。
- 安装包切换为 Inno Setup，支持安装、卸载和完全清理。
- 支持离线公私钥许可证。
- 文件源支持本地、FTP/FTPS 和 SMB/UNC 场景。
- 操作记录改为从运行日志抽象摘要，提升可读性。
- 日志记录和操作记录分页改为后端分页。
- 前端加入主题体系、文件浏览器样式和文件复制页样式适配。
- 构建、授权、服务控制脚本收束到 `tools\scripts\`。
