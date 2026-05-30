# 开发和实施流程

## 开发机

检查环境：

```cmd
tools\scripts\doctor.cmd
```

构建后端和前端：

```cmd
tools\scripts\build-all.cmd -SkipTests
```

启动本地 Agent：

```cmd
tools\scripts\start-agent.cmd -Restart
```

启动前端：

```cmd
cd dev\desktop
npm.cmd run dev
```

## 打包

正式出包建议先跑完整发布检查：

```cmd
tools\scripts\release-check.cmd
```

只构建安装包：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

输出：

```text
package\output\
```

构建脚本会完成：

- Maven 输出到 `package\target\`。
- Tauri runtime 准备到 `dev\desktop\src-tauri\resources\embedded-runtime\`。
- Inno staging 到 `package\stage\`。
- 安装包输出到 `package\output\`。

## 实施

实施优先交付安装包，不要求客户手动执行脚本。

安装后检查：

```cmd
tools\scripts\service-status.cmd
```

生产运行边界：

- Agent 主体是 `FileBridgeAgent` Windows Service。
- 桌面端只展示运行状态和发起本机管理操作。
- 数据库使用本机 SQLite，不依赖 MySQL 或其他外部数据库。
- 授权文件由 Agent 保存到服务配置目录。

## 常用脚本

| 脚本 | 用途 |
| --- | --- |
| `tools\scripts\doctor.cmd` | 环境和产物诊断。 |
| `tools\scripts\release-check.cmd` | 发布前统一检查和安装包构建。 |
| `tools\scripts\build-all.cmd` | 构建 Agent 和前端。 |
| `tools\scripts\build-windows-installer.cmd` | 生成正式安装包。 |
| `tools\scripts\install-service.cmd` | 开发机手动安装服务。 |
| `tools\scripts\start-agent.cmd` | 开发态启动本地 Agent。 |

## 配置位置

开发配置：

```text
dev\agent\config\agent-config.json
```

生产配置：

```text
C:\Program Files\FileBridge\config\agent-config.json
```

前端 API 配置：

```text
dev\desktop\public\agent-client-config.json
dev\desktop\dist\agent-client-config.json
```
