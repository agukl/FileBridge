# 后端架构：单机 FTP 远程文件管理 Agent

当前后端服务一条产品主线：文件源 -> 远程文件 -> 文件操作 -> 操作记录/运行日志。

## 核心链路

```text
Desktop Shell
  -> localhost API
  -> 文件源配置
  -> 远程文件浏览
  -> 文件操作服务
  -> FTP 传输引擎
  -> 操作记录 / 结构化事件 / 运行快照
  -> Desktop Shell
```

## 分层职责

- `agent`：HTTP API 边界和健康检查。
- `task`：当前承载文件源配置，只保存连接、凭据引用和默认路径信息。
- `diagnostic`：文件源预检、远程目录浏览和远程文件列表。
- `run`：当前承载文件操作、取消、启动恢复和操作批次收口。
- `engine`：FTP/FTPS 连接、扫描、下载和 checkpoint。
- `event`：结构化运行日志记录。
- `snapshot`：运行中进度快照。
- `error`：代码内置错误分类、处理动作、健康影响。
- `dashboard`：面向桌面端的文件源卡片概览。

## API 边界

文件管理语义 API：

```text
GET  /api/file-sources
GET  /api/file-sources/{sourceId}
POST /api/file-sources
PUT  /api/file-sources/{sourceId}
POST /api/file-sources/{sourceId}/preflight
GET  /api/file-sources/{sourceId}/remote-files?path=/path
POST /api/file-operations
```

兼容旧同步语义 API：

```text
GET  /api/agent/health
GET  /api/status-codes
GET  /api/dashboard/overview
GET  /api/tasks
GET  /api/tasks/{taskId}
POST /api/tasks
PUT  /api/tasks/{taskId}
POST /api/tasks/{taskId}/run
POST /api/tasks/{taskId}/cancel
POST /api/tasks/{taskId}/preflight
POST /api/tasks/preflight-draft
POST /api/ftp/remote-directories
GET  /api/runs
GET  /api/events
GET  /api/runs/{runId}/events
```

## 数据模型

当前初始化到内置 SQLite 数据库，表名已经按文件管理语义收敛：

- `file_source`：当前承载文件源信息。
- `file_operation`：当前承载每次文件操作的结果和汇总指标。
- `file_operation_log`：当前承载每次文件操作的结构化事件时间线。
- `file_operation_snapshot`：运行中的进度快照。
- `file_source_diagnostic`：文件源预检报告。
- `file_source_diagnostic_step`：文件源预检步骤明细。

操作记录页使用 `file_operation` 做分页、计数和运行汇总，当前页再按 `run_id` 从 `file_operation_log` 读取关键事件和最近告警，生成面向用户的操作摘要。运行日志页展示 `file_operation_log` 明细。
`/api/dashboard/overview` 只返回文件源卡片所需的 `checkedAt` 和 `tasks`，不返回全局统计聚合。

字段名仍保留 `task_id`、`run_id` 作为代码兼容层，避免在同一轮内扩大重构风险。

## 执行策略

- 第一版只支持用户手动触发“同步到本地”。
- 同一文件源在当前进程内只能有一个活动操作。
- 取消为协作式取消，通过 `CancellationToken` 和 FTP 连接关闭回调尽快中断阻塞操作。
- 启动恢复只收敛遗留 `RUNNING` 批次，不自动重新发起操作。

## Checkpoint

Checkpoint 使用本机 JSON 文件，目录由 `paths.checkpointDir` 配置。

- 远端扫描完成后保存远端文件清单。
- 每个文件下载成功后保存完成标记。
- 本地文件已存在且大小一致时也标记为完成。
- 同文件源、同远端路径、同本地路径才复用 checkpoint。
- 本轮完整成功后清理 checkpoint。

## 配置

开发配置位于 `dev\agent\config\agent-config.json`。生产安装后配置位于 `C:\Program Files\FileBridge\config\agent-config.json`。

配置当前只保留：

- `api`
- `sqlite`
- `paths.initSqlPath`
- `paths.logFile`
- `paths.checkpointDir`
- `retry`

