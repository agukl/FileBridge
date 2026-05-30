# 目录复制任务与定期执行设计

本文档收敛传输业务模型：本质是普通目录复制，在复制前加目录比对，并支持手动执行或定期执行。

```text
1. 按目录比对规则得到实际要处理的目录和文件。
2. 按比对结果执行传输，并把过程写入操作记录和事件日志。
```

不单独暴露“传输规则”和“传输计划”，内部也不持久化计划明细。

## 任务模型

目录复制任务建立在文件源基础上，一次配置完整业务。

```text
DirectoryCopyTask
  name
  sourceFileSourceId
  sourcePaths
  targetFileSourceId
  targetDirectory
  compareRule
  conflictPolicy
  schedule
  enabled
```

文件源只负责访问能力：

```text
LOCAL / REMOTE_FTP
host / port / credentialsRef
rootPath
permission
remoteDirectoryCacheEnabled
```

目录复制任务负责业务意图：

```text
从哪个文件源的哪些目录
复制到哪个文件源的哪个目录
怎么判断相同或不同
遇到冲突怎么处理
是否定期执行
```

## 执行器职责

`TransferExecutor` 只关心两个阶段。

```text
TransferExecutor
  -> compareDirectories(runSpec)
  -> executeTransfer(compareResult)
```

其他信息都是固定输入：

```text
来源文件源
目标文件源
来源路径
目标路径
目录比对规则
冲突策略
运行策略
```

执行器不保存计划明细表，不维护复杂工作流状态。运行过程通过 `file_operation` 和 `file_operation_log` 表达。

## 第一步：目录比对

目录比对根据任务配置生成本次运行的内存结果。

输入：

```text
sourceFileSource
targetFileSource
sourcePaths
targetDirectory
compareRule
conflictPolicy
```

输出：

```text
directoriesToCreate
filesToCopy
filesToSkip
conflicts
summary
```

这些结果只存在于本次执行内存中，不落明细表。

### 快照来源

远程文件源开启目录缓存：

```text
使用 file_source_directory_cache.entries_json
```

远程文件源未开启目录缓存：

```text
执行时实时扫描目录
```

本地文件源：

```text
执行时实时扫描本地目录
```

本地文件源卡片统计缓存只服务展示，不参与目录复制任务比对。

### 比对主键

比对以 `relativePath` 为主键。

```text
复制来源根：/fileroot/A
来源文件：/fileroot/A/B/C.txt
relativePath：B/C.txt
目标文件：D:\target\B\C.txt
```

`relativePath` 使用 `/`，不允许以 `/` 开头，不允许包含 `..` 越界。

### 默认比对规则

默认 `compareMode = FAST`：

```text
relativePath + type + size + modifiedAt
```

文件相同：

```text
类型都是 FILE
大小相同
mtime 在容差范围内
```

目录相同：

```text
类型都是 DIRECTORY
relativePath 相同
```

mtime 默认容差建议 2 到 5 秒。

第一阶段不默认做 checksum。FTP/FTPS 远端不一定支持 hash，强行 hash 可能退化为读取完整文件。

### 比对结果

```text
来源目录存在，目标目录不存在
  -> directoriesToCreate

来源文件存在，目标文件不存在
  -> filesToCopy: COPY_NEW

来源文件和目标文件大小或 mtime 不同
  -> filesToCopy: COPY_CHANGED

来源文件和目标文件相同
  -> filesToSkip: SKIP_SAME

目标已存在且冲突策略为 SKIP
  -> filesToSkip: SKIP_EXISTS

来源和目标类型不同
  -> conflicts: TYPE_CONFLICT
```

第一阶段不做目标多余文件删除。

## 第二步：执行传输

执行器拿到比对结果后，按固定顺序执行。

```text
1. 创建缺失目录。
2. 复制需要传输的文件。
3. 记录跳过项。
4. 记录冲突项。
5. 汇总结果。
```

执行阶段不重新做完整目录比对，但必须处理真实变化。

```text
来源文件不存在
  -> SOURCE_MISSING
  -> 写事件日志，继续后续文件

目标文件执行时已存在
  -> 按 conflictPolicy 处理

目标目录创建失败
  -> MKDIR_FAILED

传输失败
  -> FILE_COPY_FAILED
  -> 按固定重试策略处理
```

## 运行策略

运行策略固定在执行器里，不需要让任务配置暴露太多选项。第一阶段只保留必要的默认策略。

固定策略：

```text
parallelism = 1
verificationPolicy = BYTES
retryPolicy = maxAttempts 3, exponential backoff
resumePolicy = SAFE 先预留，可先只做 .part 安全写入
```

退避重试只在当前运行内进行，不单独落明细表。

适合重试：

```text
网络临时中断
FTP 4xx 临时错误
数据连接超时
控制连接断开
```

不适合重试：

```text
认证失败
权限不足
路径越界
来源文件不存在
类型冲突
目标已存在且策略为 FAIL
```

## 临时文件与续传

文件传输默认先写临时文件：

```text
targetPath + ".part"
```

完成后：

```text
校验字节数
rename .part -> final
记录 FILE_COPIED
```

失败或取消时 `.part` 可保留给后续安全续传。

断点续传依赖端点能力：

```text
来源支持 offset read
目标支持 append 或 offset write
.part 文件大小可信
```

不满足条件时，从头传输。

## 定期执行

定期执行只负责“到点触发一次目录复制任务”，不参与比对和传输细节。

推荐第一阶段支持三种模式：

```text
MANUAL_ONLY
INTERVAL
DAILY
```

调度配置：

```text
schedule_enabled
schedule_type
schedule_interval_minutes
schedule_time_of_day
schedule_timezone
next_run_at
```

调度器行为：

```text
每隔固定时间扫描 enabled=true 且 schedule_enabled=true 的任务
找到 next_run_at <= now 的任务
如果任务当前没有运行中的 operation，则提交一次执行
提交成功后计算下一次 next_run_at
```

如果上一次还没执行完：

```text
默认跳过本次触发
写一条 WARN 事件或更新 last_message
next_run_at 推到下一周期
```

这样可以避免同一个目录复制任务并发跑两份。

## 操作记录和事件日志

每次手动或定期执行任务，都创建一条 `file_operation`。

```text
operation_type = DIRECTORY_COPY_TASK
task_id = sourceFileSourceId
target_task_id = targetFileSourceId
source_path = sourcePaths summary
target_path = targetDirectory
conflict_policy = conflictPolicy
```

汇总统计写回操作记录：

```text
item_count
file_count
directory_count
total_bytes
warning_count
error_count
final_health
message
```

事件日志是运行明细来源。

建议事件类型：

```text
DIRECTORY_COPY_TASK_STARTED
DIRECTORY_COMPARE_STARTED
DIRECTORY_COMPARE_FINISHED
DIRECTORY_CREATED
FILE_COPY_STARTED
FILE_COPIED
FILE_SKIPPED
FILE_RETRY_SCHEDULED
FILE_RETRY_STARTED
FILE_COPY_FAILED
SOURCE_MISSING
TYPE_CONFLICT
DIRECTORY_COPY_TASK_CANCELLED
DIRECTORY_COPY_TASK_FINISHED
DIRECTORY_COPY_TASK_FAILED
```

事件至少记录：

```text
operation_id
seq
event_type
level
source_path
target_path
relative_path
item_size
message
details
created_at
```

## API 草案

创建或更新目录复制任务：

```text
POST /api/directory-copy-tasks
PUT /api/directory-copy-tasks/{taskId}
```

执行目录复制任务：

```text
POST /api/directory-copy-tasks/{taskId}/run
```

返回：

```json
{
  "ok": true,
  "operationId": 2291,
  "state": "RUNNING",
  "message": "Directory copy task submitted."
}
```

即时复制可以继续走：

```text
POST /api/file-operations/copy
```

后端内部转换为一次性的 `TransferRunSpec`，交给同一个 `TransferExecutor`。

## 数据表草案

只新增目录复制任务表，不新增传输计划表，不新增传输明细表。

```text
directory_copy_task
  id
  name
  source_file_source_id
  source_paths_json
  target_file_source_id
  target_directory
  compare_mode
  mtime_tolerance_seconds
  conflict_policy
  schedule_enabled
  schedule_type
  schedule_interval_minutes
  schedule_time_of_day
  schedule_timezone
  next_run_at
  enabled
  created_at
  updated_at
  last_operation_id
  last_status
  last_started_at
  last_finished_at
  last_message
```

继续复用：

```text
file_operation
file_operation_log
file_source_directory_cache
```

## 取舍

收益：

```text
模型简单
用户一次填完任务
执行器只有比对和传输两步
定期执行只是触发器
不需要维护计划明细表
日志体系继续复用
即时复制和目录复制任务共用同一个执行器
```

代价：

```text
不能跨进程保存每个文件的精确执行队列
失败项重试依赖重新运行任务并重新比对
运行明细主要从事件日志查看
```

这个取舍可以接受。重新运行任务时会再次比对，已经成功且相同的文件会被 `SKIP_SAME` 跳过。

## 推荐落地顺序

```text
1. 新增 directory_copy_task 表和后端 CRUD。
2. 定义 TransferRunSpec。
3. 新增 TransferExecutor，两阶段：compareDirectories + executeTransfer。
4. 执行器接入 file_operation 和 file_operation_log。
5. 即时复制改为复用 TransferExecutor。
6. 新增轻量调度器，按 next_run_at 触发任务。
7. 增加运行内退避重试。
8. 增加 .part 安全写入。
9. 后续再增强 SAFE 断点续传。
```
