# 版本维护说明

当前归档版本：`1.0.1`

## 版本号位置

每次升版时同步检查这些文件：

```text
pom.xml
dev\desktop\package.json
dev\desktop\package-lock.json
dev\desktop\src-tauri\Cargo.toml
dev\desktop\src-tauri\Cargo.lock
dev\desktop\src-tauri\tauri.conf.json
package\installer\inno\FileBridge.iss
CHANGELOG.md
docs\*.md
package\installer\README.md
```

桌面端开发兜底启动时，Agent jar 名称从 `Cargo.toml` 的 `CARGO_PKG_VERSION` 推导；正常升版不需要再手工修改 Rust 代码里的 jar 文件名。

## 发布命令

先做行为检查：

```cmd
tools\scripts\software-behavior-check.cmd
```

已有本机开发签名配置时，使用签名构建入口：

```cmd
tools\scripts\build-signed-installer.cmd
```

只做普通发布检查时：

```cmd
tools\scripts\release-check.cmd
```

## 升版复核

升版后用 `rg` 查旧版本号，只关注源码、脚本和文档，忽略 `node_modules`、`package\target`、`package\stage`、`package\output`、`dev\desktop\dist` 和 `dev\desktop\src-tauri\target` 里的构建产物。

```cmd
rg -n "1\.0\.0|FileBridge Setup 1\.0\.0" ^
  -g "!dev/desktop/node_modules/**" ^
  -g "!dev/desktop/dist/**" ^
  -g "!dev/desktop/src-tauri/target/**" ^
  -g "!dev/desktop/src-tauri/resources/embedded-runtime/**" ^
  -g "!package/target/**" ^
  -g "!package/stage/**" ^
  -g "!package/output/**" ^
  .
```

正常情况下只应剩下 `CHANGELOG.md` 的历史版本和 `productization-roadmap.md` 的版本策略说明。

输出目录最终只保留当前版本安装包：

```text
package\output\FileBridge Setup 1.0.1.exe
```

## 归档注意

- `dev\agent\config\dev\license` 和 `dev\agent\config\dev\codesign` 属于本机开发私有配置，不进入归档包。
- 客户安装包只应包含许可证公钥，不应包含许可证私钥或签名证书私钥。
- 正式证书上线时优先替换 `dev\agent\config\dev\codesign\signing-env.cmd` 中的签名环境变量，不需要改构建脚本。
