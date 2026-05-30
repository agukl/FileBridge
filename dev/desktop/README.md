# 桌面端

桌面端使用 Vue 3 + Tauri。生产环境中它优先控制已安装的 `FileBridgeAgent` Windows Service；未安装服务时，开发模式会从项目根目录发现 Agent jar 和配置。

## 开发

```cmd
cd dev\desktop
npm.cmd install
npm.cmd run dev
```

Tauri 开发：

```cmd
cd dev\desktop
npm.cmd run tauri:dev
```

## 构建

项目根目录执行：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

构建脚本会：

- 从 `package\target\` 读取 Agent jar。
- 从 `dev\agent\config\agent-config.sample.json` 读取配置模板。
- 从 `dev\agent\sql\sqlite-init.sql` 读取 SQLite 初始化脚本。
- 从 `tools\bin\nssm.exe` 和 `tools\scripts\` 复制服务工具。
- 生成 `dev\desktop\src-tauri\resources\embedded-runtime\`。

## 前端 API 配置

开发配置：

```text
dev\desktop\public\agent-client-config.json
```

构建后配置：

```text
dev\desktop\dist\agent-client-config.json
```

## 本地 Agent 日志

开发态本地 Agent 日志在：

```text
package\logs\desktop\agent.out.log
package\logs\desktop\agent.err.log
```
