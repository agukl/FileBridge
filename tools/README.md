# 工具目录

`tools\` 放开发、构建、诊断和安装服务会用到的工具。

| 路径 | 用途 |
| --- | --- |
| `tools\scripts\` | 对外命令入口，统一使用 `.cmd`。 |
| `tools\bin\nssm.exe` | 注册 Java Agent 为 Windows Service。 |

常用入口：

```cmd
tools\scripts\doctor.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```
