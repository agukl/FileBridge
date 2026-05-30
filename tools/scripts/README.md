# 脚本说明

脚本入口统一放在 `tools\scripts\`。对外命令使用 `.cmd`；Windows Service 脚本为纯 cmd，构建/授权等开发脚本的同名 `.ps1` 只作为内部实现保留。

## 环境检查

| 脚本 | 用途 |
| --- | --- |
| `doctor.cmd` | 检查 Java、Maven、Node、npm、Cargo、NSSM、配置、jar、端口和 Windows Service 状态。 |
| `software-behavior-check.cmd` | 检查 API 监听、服务账号、卸载边界、token、日志轮转和私钥 staging。 |
| `release-check.cmd` | 正式发布前的统一检查入口，会诊断、构建安装包并检查私钥是否误入 staging。 |

## 构建

| 脚本 | 用途 |
| --- | --- |
| `build-all.cmd` | 构建 Java Agent 和前端 dist。 |
| `build-windows-installer.cmd` | 正式 Windows 安装包入口。 |
| `build-inno-installer.cmd` | Inno Setup 具体构建脚本，通常由 `build-windows-installer.cmd` 调用。 |
| `build-signed-installer.cmd` | 读取本机签名配置并执行签名发布检查。 |

常用命令：

```cmd
tools\scripts\doctor.cmd
tools\scripts\software-behavior-check.cmd
tools\scripts\release-check.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

正式发布如需代码签名：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign -SignCertSubject "公司名称"
```

本机自签名开发证书：

```cmd
tools\scripts\new-dev-code-signing-cert.cmd
tools\scripts\build-signed-installer.cmd
```

也支持环境变量：

```cmd
set FILEBRIDGE_SIGN=1
set FILEBRIDGE_SIGN_CERT_SUBJECT=公司名称
tools\scripts\release-check.cmd
```

## 开发运行

| 脚本 | 用途 |
| --- | --- |
| `start-agent.cmd` | 开发态启动本地 Java Agent，不用于正式生产。 |

```cmd
tools\scripts\start-agent.cmd -Restart
```

## Windows Service

| 脚本 | 用途 |
| --- | --- |
| `install-service.cmd` | 安装或更新 `FileBridgeAgent` Windows Service。 |
| `uninstall-service.cmd` | 卸载 `FileBridgeAgent` Windows Service，可选删除服务文件。 |
| `service-status.cmd` | 查看服务和 NSSM 参数。 |
| `start-service.cmd` | 启动服务。 |
| `stop-service.cmd` | 停止服务。 |

生产环境优先使用 Inno 安装包，不直接要求客户运行这些脚本。

`install-service.cmd` 固定将 `FileBridgeAgent` 配置为低权限 `NT AUTHORITY\LocalService`。访问网络共享目录时，不再改服务账号；请在前端新增 `SMB 文件源`，并在文件源中填写 UNC 路径、用户名和密码引用。

`install-service.cmd` 默认保留已有 `agent-config.json` 和 `license.json`。只有显式传入 `-OverwriteConfig` 或 `-OverwriteLicense` 时才会替换对应文件。

`uninstall-service.cmd -RemoveFiles -PreserveConfig` 会移除服务运行文件并保留 `config` 目录；完全清理时不要传 `-PreserveConfig`。

## 授权

| 脚本 | 用途 |
| --- | --- |
| `generate-dev-license.cmd` | 使用开发私钥签发许可证。 |
| `new-dev-license-keypair.cmd` | 生成或轮换开发密钥对。 |
| `new-dev-code-signing-cert.cmd` | 生成本机开发用自签名代码签名证书。 |

开发私钥在：

```text
dev\agent\config\dev\license\dev-private-key.pkcs8.base64
```

不要把私钥放进安装包、客户交付包或公共源码仓库。
