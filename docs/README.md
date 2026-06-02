# 文档索引

| 文档 | 用途 |
| --- | --- |
| [project-layout.md](project-layout.md) | 新目录结构和清理边界。 |
| [install-and-use.md](install-and-use.md) | 安装、启动、卸载和常见问题。 |
| [implementation-playbook.md](implementation-playbook.md) | 开发和实施流程。 |
| [software-behavior-policy.md](software-behavior-policy.md) | 安装、运行、联网、日志、凭据和卸载行为规范。 |
| [release-process.md](release-process.md) | 正式发布流程、验收清单和客户专属授权包。 |
| [versioning.md](versioning.md) | 当前版本、升版位置和归档复核清单。 |
| [windows-trust.md](windows-trust.md) | Windows 代码签名、SmartScreen 信誉和可信发布路线。 |
| [operations-runbook.md](operations-runbook.md) | 生产运维、日志路径、故障排查和备份建议。 |
| [license-activation.md](license-activation.md) | 离线许可证签发和导入。 |
| [backend-architecture.md](backend-architecture.md) | 后端运行方式和架构说明。 |
| [productization-roadmap.md](productization-roadmap.md) | 软件正规化推进清单。 |
| [archive-handoff.md](archive-handoff.md) | 归档交接清单。 |

常用命令：

```cmd
tools\scripts\doctor.cmd
tools\scripts\software-behavior-check.cmd
tools\scripts\release-check.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

安装包输出：

```text
package\output\FileBridge Setup 1.0.2.exe
```
