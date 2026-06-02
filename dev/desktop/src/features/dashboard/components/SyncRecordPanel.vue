<script setup lang="ts">
import { computed } from "vue";
import type { StatusCodeDictionary, SyncRun, TaskCard } from "../../../app/api/types";
import { displayTime, formatBytes, formatDuration, formatNumber, stateTone } from "../../../shared/formatters";

const props = defineProps<{
  runs: SyncRun[];
  total: number | null;
  page: number;
  pageSize: number;
  lines: TaskCard[];
  statusCodes: StatusCodeDictionary;
  loading: boolean;
  error: string;
  operationBusy: Record<number, string>;
}>();

const emit = defineEmits<{
  pageChange: [page: number];
  pageSizeChange: [pageSize: number];
  cancelRun: [operationId: number];
}>();

const PAGE_SIZES = [100, 200, 500];

const lineByTaskId = computed(() => new Map(props.lines.map((card) => [card.task.taskId, card.task])));

function codeText(group: string, value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  const code = String(value).trim();
  const normalized = code.toUpperCase();
  return props.statusCodes[group]?.[code] ?? props.statusCodes[group]?.[normalized] ?? code;
}

function lineName(run: SyncRun) {
  const task = lineByTaskId.value.get(run.taskId);
  const sourceName = task?.taskName || run.taskId;
  if (run.targetTaskId) {
    const target = lineByTaskId.value.get(run.targetTaskId);
    return `${sourceName} -> ${target?.taskName || run.targetTaskId}`;
  }
  return sourceName;
}

function runTone(run: SyncRun) {
  return stateTone(run.state || run.finalHealth);
}

function runTime(run: SyncRun) {
  return displayTime(run.finishedAt || run.startedAt);
}

function normalized(value: string | null | undefined) {
  return (value || "").trim().toUpperCase();
}

function operationTitle(run: SyncRun) {
  const operation = (run.operationType || "").toUpperCase();
  const eventType = normalized(run.summaryEventType);
  if (operation === "DIRECTORY_COPY_TASK" || eventType.startsWith("DIRECTORY_COPY") || eventType.startsWith("DIRECTORY_COMPARE")) {
    return "目录复制";
  }
  if (operation === "FILE_MOVE" || eventType.startsWith("FILE_MOVE") || eventType === "FILE_MOVED") {
    return "文件移动";
  }
  if (operation === "FILE_COPY" || eventType.startsWith("FILE_COPY") || eventType === "FILE_COPIED" || eventType === "FILE_SKIPPED") {
    return "文件复制";
  }
  if (operation === "DIRECTORY_CACHE_REFRESH" || eventType === "DIRECTORY_CACHE_REFRESHED") {
    return "刷新目录缓存";
  }
  return codeText("triggerTypes", operation || "FILE_OPERATION");
}

function operationPath(run: SyncRun) {
  const source = run.summarySourcePath || run.sourcePath || lineByTaskId.value.get(run.taskId)?.sourcePath || "";
  const target = run.summaryTargetPath || run.targetPath || "";
  if (source && target && source !== target) {
    return `${source} -> ${target}`;
  }
  return source || target || "-";
}

function operationSummary(run: SyncRun) {
  const state = normalized(run.state);
  const action = operationTitle(run);
  const stats = compactStats(run);
  const latest = latestEventText(run);
  if (state === "RUNNING") {
    const eventText = latest ? `，最近：${latest}` : "";
    return `${action}进行中：${stats}${eventText}`;
  }
  if (state === "FAILED") {
    const reason = run.alertMessage || run.lastErrorMessage || latest || "需要查看日志确认原因";
    return `${action}失败：${reason}`;
  }
  if (state === "CANCELLED") {
    return `${action}已取消：已处理 ${stats}`;
  }
  const issue = issueText(run);
  if (issue) {
    return `${action}完成：${stats}，${issue}`;
  }
  return `${action}完成：${stats}`;
}

function operationStats(run: SyncRun) {
  const issue = issueText(run);
  return issue ? `${compactStats(run)} · ${issue}` : compactStats(run);
}

function errorSummary(run: SyncRun) {
  if (!run.lastErrorCategory && !run.lastErrorMessage && !run.alertEventType && !run.alertMessage) {
    return "无";
  }
  const alertType = run.alertEventType ? `${codeText("eventTypes", run.alertEventType)}：` : "";
  const category = !alertType && run.lastErrorCategory ? `${codeText("errorCategories", run.lastErrorCategory)}：` : "";
  return `${alertType || category}${run.alertMessage || run.lastErrorMessage || "暂无日志正文"}`;
}

function compactStats(run: SyncRun) {
  const parts = [];
  if (run.fileCount > 0) {
    parts.push(`文件 ${formatNumber(run.fileCount)}`);
  }
  if (run.directoryCount > 0) {
    parts.push(`目录 ${formatNumber(run.directoryCount)}`);
  }
  if (run.totalBytes > 0) {
    parts.push(formatBytes(run.totalBytes));
  }
  if (!parts.length) {
    return run.itemCount > 0 ? `项目 ${formatNumber(run.itemCount)}` : "暂无处理量";
  }
  return parts.join("、");
}

function issueText(run: SyncRun) {
  const parts = [];
  if (run.warningCount > 0) {
    parts.push(`警告 ${formatNumber(run.warningCount)} 项`);
  }
  if (run.errorCount > 0) {
    parts.push(`错误 ${formatNumber(run.errorCount)} 项`);
  }
  return parts.join("，");
}

function latestEventText(run: SyncRun) {
  const eventType = run.summaryEventType;
  if (!eventType) {
    return "";
  }
  const label = codeText("eventTypes", eventType);
  if (!label || label === "-") {
    return "";
  }
  return label;
}

const totalKnown = computed(() => props.total !== null
  && props.total !== undefined
  && Number.isFinite(Number(props.total)));
const totalCount = computed(() => totalKnown.value ? Math.max(0, Number(props.total)) : props.runs.length);
const safePageSize = computed(() => normalizePageSize(props.pageSize));
const totalPages = computed(() => Math.max(1, Math.ceil(totalCount.value / safePageSize.value)));
const currentPage = computed(() => {
  const page = Number(props.page);
  const normalized = Number.isFinite(page) && page > 0 ? Math.trunc(page) : 1;
  return Math.min(Math.max(1, normalized), totalPages.value);
});
const pageRangeText = computed(() => {
  if (!totalCount.value) {
    return "0-0 / 0";
  }
  const start = (currentPage.value - 1) * safePageSize.value + 1;
  const end = Math.min(totalCount.value, start + Math.max(0, props.runs.length - 1));
  const totalText = totalKnown.value ? formatNumber(totalCount.value) : "未知";
  return `${formatNumber(start)}-${formatNumber(end)} / ${totalText}`;
});

function normalizePageSize(value: unknown) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return PAGE_SIZES[0];
  }
  const pageSize = Math.trunc(numeric);
  return PAGE_SIZES.includes(pageSize)
    ? pageSize
    : PAGE_SIZES.find((size) => size >= pageSize) ?? PAGE_SIZES[PAGE_SIZES.length - 1];
}

function updatePageSize(event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (!target) {
    return;
  }
  const nextSize = Number.parseInt(target.value, 10);
  if (!PAGE_SIZES.includes(nextSize)) {
    return;
  }
  emit("pageSizeChange", nextSize);
}

function previousPage() {
  if (currentPage.value > 1) {
    emit("pageChange", currentPage.value - 1);
  }
}

function nextPage() {
  if (currentPage.value < totalPages.value) {
    emit("pageChange", currentPage.value + 1);
  }
}

function canCancel(run: SyncRun) {
  return (run.state || "").toUpperCase() === "RUNNING";
}
</script>

<template>
  <section class="operation-record-panel">
    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-else-if="loading" class="loading-note">正在读取操作记录...</p>

    <div v-if="totalCount" class="table-pagination" aria-label="操作记录分页">
      <div class="table-pagination-meta">
        <span v-if="totalKnown">共 {{ formatNumber(totalCount) }} 条</span>
        <span v-else>当前页 {{ formatNumber(runs.length) }} 条</span>
        <span>显示 {{ pageRangeText }}</span>
      </div>
      <div class="table-pagination-controls">
        <label class="table-pagination-size">
          每页
          <select class="filter-select" :value="safePageSize" :disabled="loading" @change="updatePageSize">
            <option v-for="size in PAGE_SIZES" :key="size" :value="size">{{ size }}</option>
          </select>
        </label>
        <button class="ghost-button compact-button" :disabled="loading || currentPage <= 1" @click="previousPage">上一页</button>
        <span class="table-pagination-page">{{ currentPage }} / {{ totalPages }}</span>
        <button class="ghost-button compact-button" :disabled="loading || currentPage >= totalPages" @click="nextPage">下一页</button>
      </div>
    </div>

    <div class="table-shell">
      <table class="ops-table" aria-label="操作记录">
        <colgroup>
          <col class="col-time" />
          <col class="col-state" />
          <col class="col-source" />
          <col class="col-action" />
          <col class="col-path" />
          <col class="col-summary" />
          <col class="col-duration" />
          <col class="col-error" />
        </colgroup>
        <thead>
          <tr>
            <th>时间</th>
            <th>结果</th>
            <th>文件源</th>
            <th>操作摘要</th>
            <th>路径</th>
            <th>统计</th>
            <th>耗时</th>
            <th>错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td class="nowrap" :title="runTime(run)">#{{ run.id }} · {{ runTime(run) }}</td>
            <td>
              <span class="pill" :class="`tone-${runTone(run)}`">{{ codeText("runStates", run.state) }}</span>
              <button
                v-if="canCancel(run)"
                class="ghost-button compact-button danger-button table-inline-button"
                type="button"
                :disabled="Boolean(operationBusy[run.id])"
                @click="$emit('cancelRun', run.id)"
              >
                {{ operationBusy[run.id] === "cancel" ? "停止中" : "停止" }}
              </button>
            </td>
            <td class="truncate" :title="`${lineName(run)} · ${run.taskId}`">{{ lineName(run) }}</td>
            <td class="operation-cell" :title="`${operationTitle(run)} · ${operationSummary(run)}`">
              <div class="operation-cell-content">
                <strong>{{ operationTitle(run) }}</strong>
                <span>{{ operationSummary(run) }}</span>
              </div>
            </td>
            <td class="truncate mono-cell" :title="operationPath(run)">{{ operationPath(run) }}</td>
            <td class="truncate" :title="operationStats(run)">{{ operationStats(run) }}</td>
            <td class="nowrap">{{ formatDuration(run.durationMs) }}</td>
            <td
              class="truncate"
              :class="{ 'text-bad': run.lastErrorCategory || run.lastErrorMessage || run.alertEventType || run.alertMessage }"
              :title="errorSummary(run)"
            >
              {{ errorSummary(run) }}
            </td>
          </tr>
        </tbody>
      </table>

      <p v-if="!runs.length && !loading" class="empty-text">暂无操作记录。</p>
    </div>
  </section>
</template>
