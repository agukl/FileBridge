<script setup lang="ts">
import { computed, reactive, watch } from "vue";
import type {
  DirectoryCopyConflictPolicy,
  DirectoryCopyScheduleType,
  DirectoryCopyTask,
  DirectoryCopyTaskDraft,
  TaskCard
} from "../../../app/api/types";
import { formatNumber } from "../../../shared/formatters";

const props = defineProps<{
  open: boolean;
  draft: DirectoryCopyTaskDraft | null;
  task: DirectoryCopyTask | null;
  sources: TaskCard[];
  saving: boolean;
  error: string;
}>();

const emit = defineEmits<{
  close: [];
  submit: [payload: DirectoryCopyTask];
}>();

const form = reactive({
  name: "",
  conflictPolicy: "SKIP" as DirectoryCopyConflictPolicy,
  scheduleType: "INTERVAL" as DirectoryCopyScheduleType,
  scheduleIntervalMinutes: 60,
  scheduleTimeOfDay: "02:00",
  enabled: true
});

const sourceById = computed(() => new Map(props.sources.map((source) => [source.task.taskId, source.task])));
const sourceTask = computed(() => props.draft ? sourceById.value.get(props.draft.sourceFileSourceId) ?? null : null);
const targetTask = computed(() => props.draft ? sourceById.value.get(props.draft.targetFileSourceId) ?? null : null);
const sourcePathsText = computed(() => {
  const paths = props.draft?.sourcePaths ?? [];
  if (!paths.length) {
    return "-";
  }
  if (paths.length <= 3) {
    return paths.join("、");
  }
  return `${paths.slice(0, 3).join("、")} 等 ${formatNumber(paths.length)} 项`;
});
const invalidReason = computed(() => {
  if (!props.draft) {
    return "缺少目录复制配置";
  }
  if (!form.name.trim()) {
    return "任务名称必填";
  }
  if (!props.draft.sourceFileSourceId || !props.draft.sourcePaths.length) {
    return "来源目录不能为空";
  }
  if (!props.draft.targetFileSourceId || !props.draft.targetDirectory) {
    return "目标目录不能为空";
  }
  if (form.scheduleType === "INTERVAL" && form.scheduleIntervalMinutes <= 0) {
    return "间隔分钟必须大于 0";
  }
  return "";
});

watch(
  () => [props.open, props.draft, props.task],
  () => {
    if (!props.open || !props.draft) {
      return;
    }
    form.name = props.task?.name || defaultTaskName();
    form.conflictPolicy = props.task?.conflictPolicy || props.draft.conflictPolicy || "SKIP";
    form.scheduleType = (props.task?.scheduleType as DirectoryCopyScheduleType) || "INTERVAL";
    form.scheduleIntervalMinutes = props.task?.scheduleIntervalMinutes || 60;
    form.scheduleTimeOfDay = props.task?.scheduleTimeOfDay || "02:00";
    form.enabled = props.task?.enabled ?? true;
  },
  { immediate: true }
);

function sourceLabel(sourceId: string) {
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

function defaultTaskName() {
  if (!props.draft) {
    return "目录复制任务";
  }
  const sourceName = sourceLabel(props.draft.sourceFileSourceId);
  const targetName = sourceLabel(props.draft.targetFileSourceId);
  const firstPath = props.draft.sourcePaths[0] || "";
  const pathName = firstPath.split(/[\\/]/).filter(Boolean).pop() || "当前目录";
  return `${sourceName} 到 ${targetName}：${pathName}`;
}

function scheduleHint() {
  if (form.scheduleType === "MANUAL_ONLY") {
    return "仅创建任务，不自动执行；可在任务列表里手动点击立即执行。";
  }
  if (form.scheduleType === "INTERVAL") {
    return `保存后约每 ${formatNumber(form.scheduleIntervalMinutes || 1)} 分钟自动执行一次。`;
  }
  return `保存后每天 ${form.scheduleTimeOfDay || "02:00"} 自动执行一次。`;
}

function submit() {
  if (invalidReason.value || !props.draft) {
    return;
  }
  const scheduleEnabled = form.scheduleType !== "MANUAL_ONLY";
  const current = props.task;
  emit("submit", {
    id: current?.id || 0,
    name: form.name.trim(),
    sourceFileSourceId: props.draft.sourceFileSourceId,
    sourcePaths: props.draft.sourcePaths,
    targetFileSourceId: props.draft.targetFileSourceId,
    targetDirectory: props.draft.targetDirectory,
    compareMode: current?.compareMode || "FAST",
    mtimeToleranceSeconds: current?.mtimeToleranceSeconds || 5,
    conflictPolicy: form.conflictPolicy,
    scheduleEnabled,
    scheduleType: form.scheduleType,
    scheduleIntervalMinutes: form.scheduleType === "INTERVAL" ? Math.max(1, Math.trunc(form.scheduleIntervalMinutes)) : 0,
    scheduleTimeOfDay: form.scheduleType === "DAILY" ? form.scheduleTimeOfDay : "",
    scheduleTimezone: current?.scheduleTimezone || "Asia/Shanghai",
    nextRunAt: "",
    enabled: form.enabled,
    lastOperationId: current?.lastOperationId ?? null,
    lastStatus: current?.lastStatus || "",
    lastStartedAt: current?.lastStartedAt || "",
    lastFinishedAt: current?.lastFinishedAt || "",
    lastMessage: current?.lastMessage || ""
  });
}
</script>

<template>
  <aside v-if="open" class="task-editor-drawer directory-task-editor-drawer">
    <header>
      <div>
        <span class="eyebrow">Directory Job</span>
        <h2>{{ task ? "修改定期任务" : "保存为定期任务" }}</h2>
      </div>
      <button class="ghost-button" type="button" @click="$emit('close')">关闭</button>
    </header>

    <section class="source-editor-form">
      <section class="editor-section">
        <div class="editor-section-head">
          <strong>复制范围</strong>
        </div>
        <div class="directory-task-preview">
          <div>
            <span>来源</span>
            <strong :title="sourceLocation(draft?.sourceFileSourceId || '')">
              {{ sourceTask?.taskName || draft?.sourceFileSourceId || "-" }}
            </strong>
          </div>
          <div>
            <span>来源路径</span>
            <strong :title="sourcePathsText">{{ sourcePathsText }}</strong>
          </div>
          <div>
            <span>目标</span>
            <strong :title="sourceLocation(draft?.targetFileSourceId || '')">
              {{ targetTask?.taskName || draft?.targetFileSourceId || "-" }}
            </strong>
          </div>
          <div>
            <span>目标目录</span>
            <strong :title="draft?.targetDirectory || '-'">{{ draft?.targetDirectory || "-" }}</strong>
          </div>
        </div>
      </section>

      <section class="editor-section">
        <div class="editor-section-head">
          <strong>执行规则</strong>
        </div>
        <div class="form-grid two">
          <label>
            任务名称
            <input v-model.trim="form.name" placeholder="日报目录自动复制" />
          </label>
          <label>
            冲突策略
            <select v-model="form.conflictPolicy">
              <option value="SKIP">跳过已存在</option>
              <option value="OVERWRITE">覆盖目标文件</option>
              <option value="FAIL">遇到冲突时报错停止</option>
            </select>
          </label>
          <label>
            调度方式
            <select v-model="form.scheduleType">
              <option value="MANUAL_ONLY">仅手动执行</option>
              <option value="INTERVAL">按间隔执行</option>
              <option value="DAILY">每天固定时间</option>
            </select>
          </label>
          <label v-if="form.scheduleType === 'INTERVAL'">
            间隔分钟
            <input v-model.number="form.scheduleIntervalMinutes" type="number" min="1" step="1" />
          </label>
          <label v-if="form.scheduleType === 'DAILY'">
            每天时间
            <input v-model="form.scheduleTimeOfDay" type="time" />
          </label>
          <label class="switch-row">
            <span>启用任务</span>
            <input v-model="form.enabled" type="checkbox" />
          </label>
        </div>
        <p class="form-warning">{{ scheduleHint() }}</p>
      </section>
    </section>

    <p v-if="error || invalidReason" class="form-error">
      {{ error || invalidReason }}
    </p>

    <footer>
      <button class="ghost-button" type="button" @click="$emit('close')">取消</button>
      <button class="solid-button" type="button" :disabled="saving || Boolean(invalidReason)" @click="submit">
        {{ saving ? "保存中" : task ? "保存修改" : "保存任务" }}
      </button>
    </footer>
  </aside>
</template>
