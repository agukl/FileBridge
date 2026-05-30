# 安装包工程

当前安装包使用 Inno Setup 6。

主脚本：

```text
package\installer\inno\FileBridge.iss
```

正式构建入口：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

输出：

```text
package\output\FileBridge Setup 1.0.1.exe
```

构建过程会生成 `package\stage\`，该目录可删除重建，不要手工维护。

安装过程不选择服务账号。`FileBridgeAgent` 固定使用低权限 `NT AUTHORITY\LocalService` 运行；访问 UNC/SMB 共享目录时，在前端新增 `SMB 文件源`，并在文件源中填写共享路径、用户名和密码引用。

重复安装默认保留已有 Agent 配置和许可证。普通卸载也会保留 `C:\Program Files\FileBridge\config`；只有卸载时选择“完全清理”才会删除全部残留。

## 授权打包

默认安装包不携带客户许可证。需要客户专属安装包时，先生成：

```text
dev\agent\config\license\license.json
```

再执行：

```cmd
tools\scripts\build-windows-installer.cmd -SkipTests -BundleLicense
```

该模式只携带客户许可证，不会携带 `dev\agent\config\dev\license\` 下的开发私钥。
