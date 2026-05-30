<script setup lang="ts">
import { computed } from "vue";
import type { DirectoryCopyTask, TaskCard } from "../../../app/api/types";
import { displayTime, formatNumber, stateTone } from "../../../shared/formatters";

const props = defineProps<{
  tasks: DirectoryCopyTask[];
  sources: TaskCard[];
  loading: boolean;
  error: string;
  message: string;
  busy: Record<number, string>;
}>();

const emit = defineEmits<{
  refresh: [];
  runTask: [taskId: number];
  cancelTask: [task: DirectoryCopyTask];
  toggleTask: [task: DirectoryCopyTask, enabled: boolean];
  editTask: [task: DirectoryCopyTask];
  deleteTask: [task: DirectoryCopyTask];
}>();

const sourceById = computed(() => new Map(props.sources.map((source) => [source.task.taskId, source.task])));
const enabledCount = computed(() => props.tasks.filter((task) => task.enabled).length);
const scheduledCount = computed(() => props.tasks.filter((task) => task.enabled && task.scheduleEnabled).length);

function sourceName(sourceId: string) {
  const source = sourceById.value.get(sourceId);
  return source?.taskName || sourceId || "-";
}

function sourceLocation(sourceId: string) {
  const source = sourceById.value.get(sourceId);
  if (!source) {
    return sourceId || "-";
  }
  if (source.sourceType === "LOCAL" || source.sourceType === "SMB") {
    return source.sourcePath;
  }
  return `${source.ftpHost}:${source.ftpPort} · ${source.sourcePath}`;
}

function taskTone(task: DirectoryCopyTask) {
  if (!task.enabled) {
    return "idle";
  }
  return stateTone(task.lastStatus || "IDLE");
}

function statusText(task: DirectoryCopyTask) {
  const status = (task.lastStatus || "").toUpperCase();
  const map: Record<string, string> = {
    RUNNING: "执行中",
    SUCCESS: "成功",
    FAILED: "失败",
    CANCELLED: "已取消",
    SKIPPED: "已跳过"
  };
  return map[status] ?? "未执行";
}

function scheduleText(task: DirectoryCopyTask) {
  if (!task.enabled) {
    return "已停用";
  }
  if (!task.scheduleEnabled || task.scheduleType === "MANUAL_ONLY") {
    return "手动执行";
  }
  if (task.scheduleType === "INTERVAL") {
    return `每 ${formatNumber(task.scheduleIntervalMinutes || 1)} 分钟`;
  }
  if (task.scheduleType === "DAILY") {
    return `每天 ${task.scheduleTimeOfDay || "02:00"}`;
  }
  return task.scheduleType || "未知周期";
}

function nextRunText(task: DirectoryCopyTask) {
  if (!task.enabled) {
    return "停用中";
  }
  if (!task.scheduleEnabled || task.scheduleType === "MANUAL_ONLY") {
    return "仅手动";
  }
  return displayTime(task.nextRunAt);
}

function pathSummary(task: DirectoryCopyTask) {
  const paths = task.sourcePaths || [];
  if (!paths.length) {
    return "-";
  }
  if (paths.length <= 2) {
    return paths.join("、");
  }
  return `${paths.slice(0, 2).join("、")} 等 ${formatNumber(paths.length)} 项`;
}

function conflictText(task: DirectoryCopyTask) {
  const map: Record<string, string> = {
    SKIP: "跳过",
    OVERWRITE: "覆盖",
    FAIL: "报错停止"
  };
  return map[(task.conflictPolicy || "").toUpperCase()] ?? task.conflictPolicy;
}

function compareText(task: DirectoryCopyTask) {
  return `${task.compareMode || "FAST"} · mtime ±${formatNumber(task.mtimeToleranceSeconds || 0)} 秒`;
}

function lastRunText(task: DirectoryCopyTask) {
  const time = task.lastFinishedAt || task.lastStartedAt;
  if (!time && !task.lastOperationId) {
    return "暂无执行记录";
  }
  const idText = task.lastOperationId ? `#${task.lastOperationId}` : "最近一次";
  return `${idText} · ${displayTime(time)}`;
}

function isRunning(task: DirectoryCopyTask) {
  return (task.lastStatus || "").toUpperCase() === "RUNNING";
}

function busyText(task: DirectoryCopyTask) {
  const action = props.busy[task.id];
  if (action === "run") {
    return "提交中";
  }
  if (action === "cancel") {
    return "停止中";
  }
  if (action === "toggle") {
    return "保存中";
  }
  if (action === "delete") {
    return "删除中";
  }
  return "";
}
</script>

<template>
  <section class="directory-task-panel">
    <header class="directory-task-hero">
      <div>
        <span class="eyebrow">Directory Jobs</span>
        <h2>定期任务</h2>
        <p>任务按“目录比对 -> 执行传输”运行。这里负责查看任务、立即执行，以及启用或停用定期调度。</p>
      </div>
      <div class="directory-task-hero-actions">
        <span>{{ formatNumber(tasks.length) }} 个任务 · {{ formatNumber(enabledCount) }} 个启用 · {{ formatNumber(scheduledCount) }} 个定期</span>
        <button class="ghost-button" type="button" :disabled="loading" @click="emit('refresh')">
          {{ loading ? "刷新中" : "刷新任务" }}
        </button>
      </div>
    </header>

    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-else-if="message" class="task-message">{{ message }}</p>

    <section v-if="!tasks.length && !loading" class="empty-board">
      <span class="eyebrow">No Scheduled Jobs</span>
      <h2>还没有定期任务</h2>
      <p>任务会复用文件源和目录复制内核。下一步可以从“文件复制”页把当前左右目录配置保存成一个定期任务。</p>
    </section>

    <section v-else class="directory-task-list" aria-label="定期任务列表">
      <article
        v-for="task in tasks"
        :key="task.id"
        class="directory-task-card"
        :class="{ disabled: !task.enabled, running: isRunning(task) }"
      >
        <header>
          <div>
            <span class="eyebrow">#{{ task.id }} · {{ scheduleText(task) }}</span>
            <h3>{{ task.name }}</h3>
          </div>
          <div class="directory-task-badges">
            <span class="pill" :class="`tone-${task.enabled ? 'good' : 'idle'}`">{{ task.enabled ? "已启用" : "已停用" }}</span>
            <span class="pill" :class="`tone-${taskTone(task)}`">{{ statusText(task) }}</span>
          </div>
        </header>

        <div class="directory-task-route">
          <div>
            <span>来源</span>
            <strong :title="`${sourceLocation(task.sourceFileSourceId)} · ${pathSummary(task)}`">
              {{ sourceName(task.sourceFileSourceId) }} · {{ pathSummary(task) }}
            </strong>
          </div>
          <div>
            <span>目标</span>
            <strong :title="`${sourceLocation(task.targetFileSourceId)} · ${task.targetDirectory}`">
              {{ sourceName(task.targetFileSourceId) }} · {{ task.targetDirectory }}
            </strong>
          </div>
        </div>

        <div class="directory-task-meta-grid">
          <div>
            <span>冲突策略</span>
            <strong>{{ conflictText(task) }}</strong>
          </div>
          <div>
            <span>比对规则</span>
            <strong>{{ compareText(task) }}</strong>
          </div>
          <div>
            <span>下次执行</span>
            <strong>{{ nextRunText(task) }}</strong>
          </div>
          <div>
            <span>上次结果</span>
            <strong>{{ lastRunText(task) }}</strong>
          </div>
        </div>

        <p v-if="task.lastMessage" class="directory-task-note">{{ task.lastMessage }}</p>

        <footer>
          <button
            class="ghost-button"
            type="button"
            :disabled="Boolean(busy[task.id]) || isRunning(task)"
            @click="emit('editTask', task)"
          >
            修改
          </button>
          <button
            class="solid-button"
            type="button"
            :disabled="Boolean(busy[task.id]) || isRunning(task)"
            @click="emit('runTask', task.id)"
          >
            {{ busy[task.id] === "run" ? busyText(task) : "立即执行" }}
          </button>
          <button
            v-if="isRunning(task)"
            class="ghost-button danger-button"
            type="button"
            :disabled="Boolean(busy[task.id])"
            @click="emit('cancelTask', task)"
          >
            {{ busy[task.id] === "cancel" ? busyText(task) : "停止" }}
          </button>
          <button
            class="ghost-button"
            type="button"
            :class="{ 'danger-button': task.enabled }"
            :disabled="Boolean(busy[task.id]) || isRunning(task)"
            @click="emit('toggleTask', task, !task.enabled)"
          >
            {{ busy[task.id] === "toggle" ? busyText(task) : task.enabled ? "停用" : "启用" }}
          </button>
          <button
            class="ghost-button danger-button"
            type="button"
            :disabled="Boolean(busy[task.id]) || isRunning(task)"
            title="删除任务配置，历史日志会保留"
            @click="emit('deleteTask', task)"
          >
            {{ busy[task.id] === "delete" ? busyText(task) : "删除" }}
          </button>
        </footer>
      </article>
    </section>
  </section>
</template>
