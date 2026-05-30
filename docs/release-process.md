# 发布流程

这份流程用于生成可交付给客户的 Windows 安装包。发布动作统一从项目根目录执行。

## 发布前检查

1. 确认版本号一致：

```text
pom.xml
dev\desktop\package.json
dev\desktop\package-lock.json
dev\desktop\src-tauri\Cargo.toml
dev\desktop\src-tauri\Cargo.lock
dev\desktop\src-tauri\tauri.conf.json
package\installer\inno\FileBridge.iss
docs\versioning.md
```

2. 确认开发私钥不进入交付包：

```text
dev\agent\config\dev\license\
```

3. 确认生产安装路径和服务名：

```text
C:\Program Files\FileBridge
C:\ProgramData\FileBridge
FileBridgeAgent
```

## 标准发布命令

优先使用统一检查入口：

```cmd
tools\scripts\release-check.cmd
```

该命令会执行环境检查、软件行为检查、构建安装包、验证安装包存在，并检查 staging 目录中是否误带私钥文件。

只构建安装包时执行：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

输出文件：

```text
package\output\FileBridge Setup 1.0.1.exe
```

## 代码签名发布

正式商业发布建议启用代码签名。构建脚本会签名内部桌面程序和最终安装包：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignCertSubject "公司名称"
```

也可以用 PFX：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignPfxPath "D:\certs\filebridge-code-signing.pfx" ^
  -SignPfxPassword "证书密码"
```

更多说明见 [windows-trust.md](windows-trust.md)。

本机开发验证签名链路：

```cmd
tools\scripts\new-dev-code-signing-cert.cmd
tools\scripts\build-signed-installer.cmd
```

## 客户专属许可证发布

默认发布包不携带许可证。需要给客户交付带授权的安装包时，先生成许可证：

```cmd
tools\scripts\generate-dev-license.cmd ^
  -DeviceId FSA1-ABCD-EF12-3456-7890-ABCD ^
  -LicenseId LIC-2026-0001 ^
  -Customer "客户名称" ^
  -Edition "professional"
```

再构建客户专属安装包：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -BundleLicense
```

客户专属安装包只允许携带 `dev\agent\config\license\license.json`，不允许携带 `dev\agent\config\dev\license\` 下的私钥。

## 发布验收

每个正式版本至少做这些验收：

- 全新安装：管理员运行安装包，确认 `FileBridgeAgent` 自动启动。
- 签名验证：正式发布包确认 `filebridge.exe` 和安装包签名有效。
- 重复安装：确认不覆盖 `agent-config.json` 和 `license.json`。
- 升级安装：确认旧 `FtpSyncAgent` 会被移除，新 `FileBridgeAgent` 正常启动。
- 普通卸载：确认服务和运行文件删除，配置按预期保留。
- 完全清理卸载：确认 `C:\Program Files\FileBridge` 和 `C:\ProgramData\FileBridge` 按预期清理。
- 授权验证：无许可证、设备 ID 不匹配、有效许可证三种状态都能给出可读提示。
- SMB 文件源：确认账号密码在 SMB 文件源内配置，不依赖服务账号。

## 归档内容

发布后至少保留：

```text
package\output\FileBridge Setup 1.0.1.exe
docs\release-process.md
docs\versioning.md
docs\install-and-use.md
docs\license-activation.md
docs\operations-runbook.md
```

不要归档客户机器上的数据库、日志和私钥材料。
