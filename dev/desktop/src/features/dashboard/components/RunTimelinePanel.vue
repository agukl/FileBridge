<script setup lang="ts">
import { computed } from "vue";
import type { FtpSyncEvent, StatusCodeDictionary, SyncRun } from "../../../app/api/types";
import { displayTime, formatDuration, formatNumber, stateTone } from "../../../shared/formatters";

const props = defineProps<{
  runs: SyncRun[];
  events: FtpSyncEvent[];
  total: number | null;
  page: number;
  pageSize: number;
  statusCodes: StatusCodeDictionary;
  loading: boolean;
  error: string;
}>();

const emit = defineEmits<{
  pageChange: [page: number];
  pageSizeChange: [pageSize: number];
}>();

const PAGE_SIZES = [100, 200, 500];
const runById = computed(() => new Map(props.runs.map((run) => [run.id, run])));

function codeText(group: string, value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  const code = String(value).trim();
  const normalized = code.toUpperCase();
  return props.statusCodes[group]?.[code] ?? props.statusCodes[group]?.[normalized] ?? code;
}

function eventTone(event: FtpSyncEvent) {
  const level = (event.level ?? "").toUpperCase();
  if (level === "ERROR") {
    return "bad";
  }
  if (level.includes("WARN")) {
    return "warn";
  }
  return stateTone(event.errorCategory || event.eventType || event.level);
}

function runText(event: FtpSyncEvent) {
  if (event.runId === null || event.runId === undefined) {
    return "-";
  }
  const run = runById.value.get(event.runId);
  return run ? `#${run.id}` : `#${event.runId}`;
}

function eventMessage(event: FtpSyncEvent) {
  return event.message || event.detailsText || "无消息正文";
}

function eventPath(event: FtpSyncEvent) {
  if (event.itemPath && event.itemPath !== event.sourcePath) {
    return `${event.sourcePath || "-"} -> ${event.itemPath}`;
  }
  return event.sourcePath || event.itemPath || "-";
}

function evidenceText(event: FtpSyncEvent) {
  const parts = [];
  if (event.replyCode) {
    parts.push(`FTP ${event.replyCode}`);
  }
  if (event.durationMs) {
    parts.push(formatDuration(event.durationMs));
  }
  if (event.replyText) {
    parts.push(event.replyText);
  }
  return parts.join(" · ") || "-";
}

const totalKnown = computed(() => props.total !== null
  && props.total !== undefined
  && Number.isFinite(Number(props.total)));
const totalCount = computed(() => totalKnown.value ? Math.max(0, Number(props.total)) : props.events.length);
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
  const end = Math.min(totalCount.value, start + Math.max(0, props.events.length - 1));
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
</script>

<template>
  <section class="run-log-panel">
    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-else-if="loading" class="loading-note">正在读取事件日志...</p>

    <div v-if="totalCount" class="table-pagination" aria-label="事件日志分页">
      <div class="table-pagination-meta">
        <span v-if="totalKnown">共 {{ formatNumber(totalCount) }} 条</span>
        <span v-else>当前页 {{ formatNumber(events.length) }} 条</span>
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
      <table class="ops-table event-table" aria-label="事件日志">
        <colgroup>
          <col class="col-time" />
          <col class="col-state" />
          <col class="col-run" />
          <col class="col-source" />
          <col class="col-event" />
          <col class="col-action" />
          <col class="col-path" />
          <col class="col-message" />
          <col class="col-evidence" />
        </colgroup>
        <thead>
          <tr>
            <th>时间</th>
            <th>级别</th>
            <th>运行</th>
            <th>文件源</th>
            <th>事件</th>
            <th>阶段/操作</th>
            <th>路径</th>
            <th>消息</th>
            <th>证据</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in events" :key="event.id">
            <td class="nowrap" :title="displayTime(event.occurredAt)">{{ displayTime(event.occurredAt) }}</td>
            <td>
              <span class="pill" :class="`tone-${eventTone(event)}`">{{ codeText("eventLevels", event.level) }}</span>
            </td>
            <td class="nowrap" :title="runText(event)">{{ runText(event) }}</td>
            <td class="truncate" :title="event.taskId">{{ event.taskId }}</td>
            <td class="truncate" :title="codeText('eventTypes', event.eventType)">
              {{ codeText("eventTypes", event.eventType) }}
            </td>
            <td class="truncate" :title="`${codeText('phases', event.phase)} / ${codeText('operations', event.operationName)}`">
              {{ codeText("phases", event.phase) }} / {{ codeText("operations", event.operationName) }}
            </td>
            <td class="truncate mono-cell" :title="eventPath(event)">{{ eventPath(event) }}</td>
            <td class="truncate" :class="{ 'text-bad': event.errorCategory }" :title="eventMessage(event)">
              {{ eventMessage(event) }}
            </td>
            <td class="truncate" :title="evidenceText(event)">{{ evidenceText(event) }}</td>
          </tr>
        </tbody>
      </table>

      <p v-if="!events.length && !loading" class="empty-text">暂无事件日志。</p>
    </div>
  </section>
</template>
