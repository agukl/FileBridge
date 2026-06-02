# FileBridge

FileBridge 是一个单机文件源管理和文件复制工具。后端 Agent 作为 Windows Service 稳定运行，桌面端用于展示运行状态、管理文件源、浏览文件和发起复制任务。

## 目录结构

```text
dev/
  agent/                 Java Agent 源码、SQL、开发配置和授权文件
  desktop/               Vue 3 + Tauri 桌面端
tools/
  scripts/               构建、诊断、授权和服务控制脚本
  bin/                   nssm.exe 等二进制工具
package/
  installer/inno/        Inno Setup 安装工程
  target/                Maven 构建输出
  stage/                 安装包 staging
  output/                最终安装包输出
docs/                    架构、实施、授权和归档文档
```

## 构建安装包

```cmd
tools\scripts\doctor.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

输出：

```text
package\output\FileBridge Setup 1.0.2.exe
```

正式出包建议使用发布检查入口：

```cmd
tools\scripts\software-behavior-check.cmd
tools\scripts\release-check.cmd
```

## 开发运行

```cmd
tools\scripts\build-all.cmd -SkipTests
tools\scripts\start-agent.cmd -Restart
cd dev\desktop
npm.cmd run dev
```

## 生产安装

生产优先使用安装包。安装后主要路径：

```text
C:\Program Files\FileBridge
C:\ProgramData\FileBridge
```

Windows Service 名称：

```text
FileBridgeAgent
```

服务诊断：

```cmd
tools\scripts\service-status.cmd
tools\scripts\start-service.cmd
tools\scripts\stop-service.cmd
```

安装使用步骤见 [docs/install-and-use.md](docs/install-and-use.md)。
发布和生产运维见 [docs/release-process.md](docs/release-process.md) 和 [docs/operations-runbook.md](docs/operations-runbook.md)。
版本维护和升版复核见 [docs/versioning.md](docs/versioning.md)。
Windows 可信发布和代码签名见 [docs/windows-trust.md](docs/windows-trust.md)。
软件安装、运行、联网、日志和卸载行为规范见 [docs/software-behavior-policy.md](docs/software-behavior-policy.md)。

## 授权

项目采用离线公私钥许可证。客户提供设备 ID，授权后台使用开发私钥签发 `license.json`，客户再导入桌面端。

授权操作见 [docs/license-activation.md](docs/license-activation.md)。

## 正规化推进

当前项目已收束为开发目录、工具目录、打包输出目录三块。后续商业化交付前，请按 [docs/productization-roadmap.md](docs/productization-roadmap.md) 检查代码签名、安装验收、授权网站、第三方许可和版本发布记录。
