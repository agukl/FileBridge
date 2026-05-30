# 授权操作

项目使用离线公私钥许可证。开发私钥只保存在开发配置目录，用于后续授权网站签发客户许可证；安装包只内置公钥。

## 密钥位置

开发私钥：

```text
dev\agent\config\dev\license\dev-private-key.pkcs8.base64
```

开发公钥：

```text
dev\agent\config\dev\license\dev-public-key.spki.base64
```

生产公钥写在：

```text
dev\agent\src\main\java\com\acme\ftpsync\license\LicenseKeys.java
```

不要把开发私钥打进客户安装包。

## 生成许可证

```cmd
tools\scripts\generate-dev-license.cmd ^
  -DeviceId FSA1-ABCD-EF12-3456-7890-ABCD ^
  -LicenseId LIC-2026-0001 ^
  -Customer "客户名称" ^
  -Edition "professional" ^
  -ExpiresAt 2027-05-25T00:00:00Z ^
  -Features "FILE_SOURCE_MANAGE,REMOTE_FTP,FILE_COPY,DIRECTORY_CACHE" ^
  -MaxFileSources 20 ^
  -MaxConcurrentOperations 2 ^
  -GraceDays 7 ^
  -OutPath "dev\agent\config\license\license.json"
```

默认输出：

```text
dev\agent\config\license\license.json
```

## 客户激活

1. 客户在桌面端复制设备 ID。
2. 授权网站用开发私钥生成 `license.json`。
3. 客户在桌面端导入许可证。
4. Agent 校验签名、设备 ID、有效期和功能位。

生产安装后许可证保存到：

```text
C:\Program Files\FileBridge\config\license\license.json
```

## 客户专属安装包

先生成：

```text
dev\agent\config\license\license.json
```

再打包：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -BundleLicense
```

默认构建不会携带许可证；只有加 `-BundleLicense` 才会携带客户许可证。
