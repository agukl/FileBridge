# 归档交接

## 保留源码

```text
pom.xml
readme.md
docs\
CHANGELOG.md
docs\versioning.md
dev\agent\src\
dev\agent\sql\
dev\agent\config\agent-config.sample.json
dev\desktop\
tools\
package\installer\
```

## 排除运行态和生成物

```text
dev\agent\config\agent-config.json
dev\agent\config\license\
dev\agent\config\dev\
dev\desktop\node_modules\
dev\desktop\dist\
dev\desktop\src-tauri\target\
dev\desktop\src-tauri\resources\embedded-runtime\
package\target\
package\stage\
package\output\
package\logs\
package\data\
package\checkpoint\
```

这些规则已经写入 `.archiveignore`。

## 重新构建

```cmd
tools\scripts\doctor.cmd
tools\scripts\release-check.cmd
tools\scripts\build-windows-installer.cmd -SkipTests
```

输出：

```text
package\output\FileBridge Setup 1.0.2.exe
```

## 授权注意

开发私钥位于：

```text
dev\agent\config\dev\license\
dev\agent\config\dev\codesign\
```

这些目录只给开发、授权网站和本机开发签名使用，不进入客户交付包或归档包。
