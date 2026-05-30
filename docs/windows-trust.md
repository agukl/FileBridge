# Windows 信任与代码签名

目标是让 FileBridge 在 Windows 上呈现为可信发布的软件：安装包和可执行文件都有可信发布者，签名可验证，下载和运行逐步积累 SmartScreen 信誉。

## 先明确现实边界

Windows 信任不是只买一个证书就立刻完成。微软当前 SmartScreen 会看两个信号：

- 发布者信誉：文件是否签名，签名证书是否来自可信发布者。
- 文件哈希信誉：这个具体文件是否已有足够干净下载和运行记录。

微软文档说明，新的已签名二进制文件仍可能出现 SmartScreen 提示，直到文件哈希或发布者证书积累正向信誉；EV 证书也不再天然绕过 SmartScreen。

参考：

- https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation
- https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool
- https://learn.microsoft.com/en-us/azure/artifact-signing/overview

## 推荐路线

### 方案 A：Microsoft Store

最容易避开 SmartScreen 下载警告，但当前 FileBridge 是 Inno 安装包加 Windows Service，更适合企业/现场交付。Store 路线后续需要重新评估打包和服务安装模型。

### 方案 B：Microsoft Artifact Signing

微软推荐的非 Store 代码签名服务。优点是证书生命周期和私钥保护由 Azure 管理，适合后续接 CI/CD。缺点是需要 Azure 账号、身份验证和网络签名流程。

### 方案 C：传统 OV/EV Code Signing 证书

适合继续使用本地 Inno 安装包。建议优先考虑 OV 证书；EV 证书仍有企业采购身份背书价值，但不要只为了绕过 SmartScreen 而买 EV。

## FileBridge 当前签名点

构建脚本已支持两个签名点：

1. `package\stage\app\filebridge.exe`
2. `package\output\FileBridge Setup x.y.z.exe`

签名命令使用：

```text
signtool sign /fd SHA256 /tr <timestamp-url> /td SHA256 ...
signtool verify /pa /v ...
```

时间戳很重要。没有时间戳时，证书过期后签名更容易失去长期有效性。

## 使用 PFX 签名

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignPfxPath "D:\certs\filebridge-code-signing.pfx" ^
  -SignPfxPassword "证书密码"
```

也可以使用环境变量：

```cmd
set FILEBRIDGE_SIGN=1
set FILEBRIDGE_SIGN_PFX=D:\certs\filebridge-code-signing.pfx
set FILEBRIDGE_SIGN_PFX_PASSWORD=证书密码
tools\scripts\release-check.cmd
```

PFX 方式简单，但不建议长期把密码写在脚本、仓库、日志或共享目录里。

## 本机自签名证书

自签名证书只用于本机开发和验证签名流程，不会让其他客户机器天然信任。

生成本机开发证书：

```cmd
tools\scripts\new-dev-code-signing-cert.cmd
```

输出目录：

```text
dev\agent\config\dev\codesign\
```

该目录被 git 忽略。脚本会生成：

```text
filebridge-dev-code-signing.pfx
filebridge-dev-code-signing.cer
filebridge-dev-code-signing.password.txt
filebridge-dev-code-signing.thumbprint.txt
signing-env.cmd
```

生成后可直接构建签名安装包：

```cmd
tools\scripts\build-signed-installer.cmd
```

自签名证书默认会在 `signing-env.cmd` 中设置：

```cmd
set "FILEBRIDGE_SIGN_SKIP_VERIFY=1"
```

这是为了让未受信任根的本机开发证书先跑通签名流程。换成正式可信证书后，请删除该行或改为 `0`，构建脚本会恢复 `signtool verify /pa /v` 严格验签。

后续替换成正式证书时，不需要改构建脚本，只需要替换或编辑：

```text
dev\agent\config\dev\codesign\signing-env.cmd
```

把 `FILEBRIDGE_SIGN_PFX` 和 `FILEBRIDGE_SIGN_PFX_PASSWORD` 改为正式证书即可。也可以改用证书存储方式，设置 `FILEBRIDGE_SIGN_CERT_SUBJECT` 或 `FILEBRIDGE_SIGN_CERT_SHA1`。

## 使用证书存储签名

如果证书已安装到 Windows 证书存储，可按证书主题：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignCertSubject "公司名称"
```

或按证书 SHA1 指纹：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignCertSha1 "证书SHA1指纹"
```

如果证书在本机计算机存储：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -Sign ^
  -SignCertSha1 "证书SHA1指纹" ^
  -SignStoreLocation LocalMachine
```

## 推荐发布习惯

- 每个正式版本都签名，不要有的版本签、有的版本不签。
- 安装包和内部 exe 都签。
- 签名后不要再修改文件。
- 统一发布者主体，不要频繁更换证书主体。
- 下载页使用 HTTPS，并展示发布者名称、版本号、SHA256 摘要。
- 保留每个正式版本安装包和哈希，方便客户校验。
- 对企业客户，提供签名证书主题、安装包 SHA256、服务名、安装路径和日志路径。

## 正规化下一步

1. 确认公司主体，用该主体申请代码签名能力。
2. 选择 Artifact Signing 或传统 OV/EV 证书。
3. 在构建机安装 Windows SDK，确保 `signtool.exe` 可用。
4. 使用 `tools\scripts\release-check.cmd` 作为唯一正式出包入口。
5. 发布页固定下载地址，不频繁变更域名和文件名规则。
6. 对外发布时收集真实下载和安装信誉，逐步降低 SmartScreen 提示概率。
