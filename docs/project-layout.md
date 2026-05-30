# 项目目录结构

项目目录收束为三块：开发目录、工具目录、打包/输出目录。

## 开发目录

| 路径 | 说明 |
| --- | --- |
| `dev\agent\src\` | Java Agent 源码。 |
| `dev\agent\sql\sqlite-init.sql` | SQLite 初始化脚本。 |
| `dev\agent\config\agent-config.sample.json` | Agent 配置模板。 |
| `dev\agent\config\agent-config.json` | 本机开发配置，忽略入库。 |
| `dev\agent\config\license\` | 本机许可证，忽略入库。 |
| `dev\agent\config\dev\license\` | 开发公私钥，忽略入库。 |
| `dev\desktop\` | Vue 3 + Tauri 桌面端。 |

## 工具目录

| 路径 | 说明 |
| --- | --- |
| `tools\scripts\` | 构建、诊断、授权、Windows Service 脚本。 |
| `tools\bin\nssm.exe` | Windows Service 注册工具。 |

对外命令统一使用 `tools\scripts\*.cmd`。

## 打包和输出目录

| 路径 | 说明 |
| --- | --- |
| `package\installer\inno\` | Inno Setup 安装工程。 |
| `package\target\` | Maven 输出目录，可删除重建。 |
| `package\stage\` | 安装包 staging，可删除重建。 |
| `package\output\` | 最终安装包输出。 |
| `package\logs\` | 开发态运行日志。 |
| `package\data\` | 开发态 SQLite 数据。 |
| `package\checkpoint\` | 开发态 checkpoint。 |

正式安装包：

```text
package\output\FileBridge Setup 1.0.1.exe
```

## 可清理目录

这些目录均可重建：

```text
package\target\
package\stage\
package\output\
package\logs\
package\data\
package\checkpoint\
dev\desktop\dist\
dev\desktop\node_modules\
dev\desktop\src-tauri\target\
dev\desktop\src-tauri\resources\embedded-runtime\
```

不要清理开发私钥，除非明确轮换：

```text
dev\agent\config\dev\license\
```
