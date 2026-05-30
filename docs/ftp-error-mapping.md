# FTP 通用错误映射设计

FTP 运行日志使用结构化事件作为主数据源，错误分类和处理建议来自代码内置字典 `FtpErrorPolicyCatalog`。

```text
file_operation_log
  -> FtpErrorPolicyCatalog.classify
  -> errorCategory / handlingAction / healthImpact
  -> 运行日志/预检报告展示
```

错误分类不做数据库策略表，也不提前写入聚合表。需要统计时，从 `file_operation` 和 `file_operation_log` 查询生成。

## 典型映射

| 原始码/文本 | 分类 | 处理动作 | 健康影响 |
|---|---|---|---|
| `Connection refused`, timeout, DNS | `CONNECTION_ERROR` | `RETRY_THEN_ALERT` | `NEEDS_NETWORK_CHECK` |
| `530`, login failed | `AUTH_ERROR` | `FAIL_FAST` | `NEEDS_CONFIG_FIX` |
| `534`, SSL handshake, fingerprint | `TLS_ERROR` | `FAIL_FAST` | `NEEDS_TLS_CONFIRMATION` |
| `425`, `426` | `DATA_CHANNEL_ERROR` | `RETRY_THEN_ALERT` | `NEEDS_NETWORK_CHECK` |
| `550`, `553`, path unavailable | `REMOTE_PATH_ERROR` | `SKIP_OR_FAIL_BY_PHASE` | `NEEDS_REMOTE_PATH_CHECK` |
| `421`, `450`, `451` | `REMOTE_TEMPORARY_ERROR` | `RETRY_WITH_BACKOFF` | `TEMPORARY_DEGRADED` |
| local disk full | `LOCAL_CAPACITY_ERROR` | `FAIL_AND_ALERT` | `NEEDS_DISK_CLEANUP` |
| checkpoint failure | `CHECKPOINT_ERROR` | `FAIL_AND_ALERT` | `NEEDS_STATE_REPAIR` |

## 550 的处理原则

`550` 是 FTP 返回码，不是系统展示维度。它只说明某次操作的原始事实；业务展示要落到：

- 远端路径、权限或编码问题：`REMOTE_PATH_ERROR`
- 扫描目录场景：通常是 `WARN`，跳过并保留路径证据
- 下载文件场景：通常是 `ERROR` 或本轮失败，取决于同步策略
- 证据字段：`replyCode`、`replyText`、`remotePath`、`eventType`、`message`
