<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { DirectoryCopyTaskDraft, FileCopyRequest, FileEntry, FileListing, SyncRun, TaskCard } from "../../../app/api/types";
import { displayTime, formatBytes, formatNumber, stateTone } from "../../../shared/formatters";

type PaneSide = "left" | "right";
type ConflictPolicy = FileCopyRequest["conflictPolicy"];
type CompareFilter = "ALL" | "DIFF" | "MISSING" | "EXISTS";

type ComparedEntry = FileEntry & {
  target?: FileEntry;
  compareText: string;
  compareTone: "good" | "warn" | "bad" | "idle";
};

type CopyNotice = {
  key: string;
  title: string;
  message: string;
  tone: "info" | "warning";
};

const props = defineProps<{
  sources: TaskCard[];
  leftSourceId: string;
  rightSourceId: string;
  leftListing: FileListing | null;
  rightListing: FileListing | null;
  leftLoading: boolean;
  rightLoading: boolean;
  leftError: string;
  rightError: string;
  copying: boolean;
  copyMessage: string;
  activeRun: SyncRun | null;
}>();

const emit = defineEmits<{
  selectPaneSource: [side: PaneSide, sourceId: string];
  openPanePath: [side: PaneSide, path?: string];
  copyFiles: [payload: FileCopyRequest];
  cancelCopy: [operationId: number];
  saveDirectoryTask: [draft: DirectoryCopyTaskDraft];
}>();

const selectedPaths = ref<Set<string>>(new Set());
const conflictPolicy = ref<ConflictPolicy>("SKIP");
const leftQuery = ref("");
const rightQuery = ref("");
const leftCompareFilter = ref<CompareFilter>("ALL");
const draggingPaths = ref<string[]>([]);
const dragOverTarget = ref(false);
const dragOverDirectoryPath = ref("");
const dragOriginPaths = ref<Set<string>>(new Set());
const visibleNoticeKeys = ref<Set<string>>(new Set());
const autoShownCacheSides = ref<Set<PaneSide>>(new Set());
const lastShownCopyMessage = ref("");
const noticeTimers = new Map<string, ReturnType<typeof setTimeout>>();

const leftSource = computed(() => props.sources.find((source) => source.task.taskId === props.leftSourceId) ?? null);
const targetSourceOptions = computed(() => props.sources.filter((source) => source.task.permission?.canWrite));
const rightSource = computed(() => targetSourceOptions.value.find((source) => source.task.taskId === props.rightSourceId) ?? null);
const rightByName = computed(() => new Map((props.rightListing?.entries ?? []).map((entry) => [entry.name, entry])));
const leftEntries = computed<ComparedEntry[]>(() => (props.leftListing?.entries ?? [])
  .slice()
  .sort(compareEntries)
  .map((entry) => compareEntry(entry)));
const rightEntries = computed(() => (props.rightListing?.entries ?? []).slice().sort(compareEntries));
const visibleLeftEntries = computed(() => leftEntries.value
  .filter((entry) => matchesCompareFilter(entry))
  .filter((entry) => matchesEntryQuery(entry, leftQuery.value)));
const visibleRightEntries = computed(() => rightEntries.value
  .filter((entry) => matchesEntryQuery(entry, rightQuery.value)));
const selectedCount = computed(() => selectedPaths.value.size);
const selectedBytes = computed(() => leftEntries.value
  .filter((entry) => selectedPaths.value.has(entry.path))
  .reduce((sum, entry) => sum + (entry.type === "FILE" ? entry.size : 0), 0));
const leftStatsText = computed(() => `${formatNumber(visibleLeftEntries.value.length)} / ${formatNumber(leftEntries.value.length)} 项 · 已选 ${formatNumber(selectedCount.value)} 项`);
const rightStatsText = computed(() => `${formatNumber(visibleRightEntries.value.length)} / ${formatNumber(rightEntries.value.length)} 项`);
const targetDirectory = computed(() => props.rightListing?.path || rightSource.value?.task.sourcePath || "/");
const canDropCopy = computed(() => Boolean(
  leftSource.value
  && rightSource.value
  && props.leftListing
  && props.rightListing
  && leftSource.value.task.permission?.canRead
  && rightSource.value.task.permission?.canWrite
  && !props.copying
));
const dropDisabledReason = computed(() => {
  if (!targetSourceOptions.value.length) {
    return "没有可写目标文件源。";
  }
  if (!leftSource.value?.task.permission?.canRead) {
    return "左侧来源没有读取权限。";
  }
  if (!rightSource.value) {
    return "请选择可写目标文件源。";
  }
  if (!props.leftListing || !props.rightListing) {
    return "请先读取左右两个目录。";
  }
  return "";
});
const dragSummaryText = computed(() => {
  if (draggingPaths.value.length) {
    return `正在拖动 ${formatNumber(draggingPaths.value.length)} 项`;
  }
  return selectedCount.value
    ? `已选 ${formatNumber(selectedCount.value)} 项，拖动任意已选项即可复制`
    : "从左侧拖动文件或目录到右侧即可复制";
});
const dropTargetText = computed(() => dragOverDirectoryPath.value || targetDirectory.value);
const taskSourcePaths = computed(() => {
  const selected = Array.from(selectedPaths.value);
  if (selected.length) {
    return selected;
  }
  const currentPath = props.leftListing?.path || leftSource.value?.task.sourcePath || "";
  return currentPath ? [currentPath] : [];
});
const taskSourceText = computed(() => (
  selectedCount.value
    ? `已选 ${formatNumber(selectedCount.value)} 项`
    : `当前目录 ${props.leftListing?.path || leftSource.value?.task.sourcePath || "/"}`
));
const allLeftSelected = computed(() => visibleLeftEntries.value.length > 0
  && visibleLeftEntries.value.every((entry) => selectedPaths.value.has(entry.path)));
const activeRunText = computed(() => {
  if (!props.activeRun) {
    return "";
  }
  const run = props.activeRun;
  return `#${run.id} ${run.state} · 文件 ${formatNumber(run.fileCount)} · 目录 ${formatNumber(run.directoryCount)} · ${formatBytes(run.totalBytes)}`;
});
const leftCacheNotice = computed(() => cacheTopNotice("left", "来源目录", props.leftListing));
const rightCacheNotice = computed(() => cacheTopNotice("right", "目标目录", props.rightListing));
const copyMessageNotice = computed<CopyNotice | null>(() => {
  const message = props.copyMessage.trim();
  if (!message) {
    return null;
  }
  return {
    key: `copy-message:${message}`,
    title: "复制提示",
    message,
    tone: /失败|错误|取消|跳过/.test(message) ? "warning" : "info"
  };
});
const topNotices = computed(() => [leftCacheNotice.value, rightCacheNotice.value, copyMessageNotice.value]
  .filter((notice): notice is CopyNotice => Boolean(notice))
  .filter((notice) => visibleNoticeKeys.value.has(notice.key)));

watch(
  () => [props.leftSourceId, props.leftListing?.path],
  () => {
    selectedPaths.value = new Set();
    leftQuery.value = "";
    leftCompareFilter.value = "ALL";
  }
);

watch(
  () => [props.rightSourceId, props.rightListing?.path],
  () => {
    rightQuery.value = "";
  }
);

watch(
  () => [
    props.leftLoading,
    props.leftSourceId,
    props.leftListing?.path,
    props.leftListing?.cacheUsed,
    props.leftListing?.cacheMessage,
    props.leftListing?.cacheScannedAt,
    props.rightLoading,
    props.rightSourceId,
    props.rightListing?.path,
    props.rightListing?.cacheUsed,
    props.rightListing?.cacheMessage,
    props.rightListing?.cacheScannedAt
  ],
  () => {
    if (!props.leftLoading && leftCacheNotice.value && !autoShownCacheSides.value.has("left")) {
      showNotice(leftCacheNotice.value.key);
      autoShownCacheSides.value = new Set(autoShownCacheSides.value).add("left");
    }
    if (!props.rightLoading && rightCacheNotice.value && !autoShownCacheSides.value.has("right")) {
      showNotice(rightCacheNotice.value.key);
      autoShownCacheSides.value = new Set(autoShownCacheSides.value).add("right");
    }
  },
  { immediate: true }
);

watch(
  () => props.copyMessage.trim(),
  (message, previousMessage) => {
    if (!message || message === previousMessage || message === lastShownCopyMessage.value) {
      return;
    }
    if (copyMessageNotice.value) {
      showNotice(copyMessageNotice.value.key);
      lastShownCopyMessage.value = message;
    }
  }
);

onBeforeUnmount(() => {
  for (const timer of noticeTimers.values()) {
    window.clearTimeout(timer);
  }
  noticeTimers.clear();
});

function selectSource(side: PaneSide, event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (target?.value) {
    emit("selectPaneSource", side, target.value);
  }
}

function openPath(side: PaneSide, path?: string) {
  emit("openPanePath", side, path);
}

function toggleEntry(path: string, checked: boolean) {
  const next = new Set(selectedPaths.value);
  if (checked) {
    next.add(path);
  } else {
    next.delete(path);
  }
  selectedPaths.value = next;
}

function onEntryCheck(path: string, event: Event) {
  const target = event.target as HTMLInputElement | null;
  toggleEntry(path, target?.checked === true);
}

function toggleAllLeft() {
  const next = new Set(selectedPaths.value);
  if (allLeftSelected.value) {
    for (const entry of visibleLeftEntries.value) {
      next.delete(entry.path);
    }
  } else {
    for (const entry of visibleLeftEntries.value) {
      next.add(entry.path);
    }
  }
  selectedPaths.value = next;
}

function emitCopy(paths: string[], targetDirectoryPath: string) {
  if (!canDropCopy.value || !leftSource.value || !rightSource.value || !paths.length) {
    return;
  }
  emit("copyFiles", {
    sourceId: leftSource.value.task.taskId,
    sourcePaths: paths,
    targetId: rightSource.value.task.taskId,
    targetDirectory: targetDirectoryPath,
    conflictPolicy: conflictPolicy.value
  });
}

function saveDirectoryTask() {
  if (!canDropCopy.value || !leftSource.value || !rightSource.value || !taskSourcePaths.value.length) {
    return;
  }
  emit("saveDirectoryTask", {
    sourceFileSourceId: leftSource.value.task.taskId,
    sourcePaths: taskSourcePaths.value,
    targetFileSourceId: rightSource.value.task.taskId,
    targetDirectory: targetDirectory.value,
    conflictPolicy: conflictPolicy.value
  });
}

function dragPathsFor(entry: FileEntry) {
  return selectedPaths.value.has(entry.path)
    ? Array.from(selectedPaths.value)
    : [entry.path];
}

function onLeftDragStart(entry: FileEntry, event: DragEvent) {
  if (!canDropCopy.value) {
    event.preventDefault();
    return;
  }
  const paths = dragPathsFor(entry);
  draggingPaths.value = paths;
  dragOriginPaths.value = new Set(paths);
  event.dataTransfer?.setData("application/json", JSON.stringify(paths));
  event.dataTransfer?.setData("text/plain", paths.join("\n"));
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "copy";
    event.dataTransfer.setDragImage(createDragGhost(entry, paths), 24, 22);
  }
}

function onLeftDragEnd() {
  clearDragState();
}

function onTargetDragOver(event: DragEvent, targetPath = "") {
  if (!canDropCopy.value || !dragPathsFromEvent(event).length) {
    return;
  }
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = "copy";
  }
  dragOverTarget.value = true;
  dragOverDirectoryPath.value = targetPath;
}

function onTargetDragLeave(event: DragEvent, targetPath = "") {
  const current = event.currentTarget as HTMLElement | null;
  const related = event.relatedTarget as Node | null;
  if (current && related && current.contains(related)) {
    return;
  }
  if (!targetPath || dragOverDirectoryPath.value === targetPath) {
    dragOverTarget.value = false;
    dragOverDirectoryPath.value = "";
  }
}

function onTargetDrop(event: DragEvent, targetPath = "") {
  const paths = dragPathsFromEvent(event);
  if (!canDropCopy.value || !paths.length) {
    clearDragState();
    return;
  }
  event.preventDefault();
  emitCopy(paths, targetPath || targetDirectory.value);
  clearDragState();
}

function dragPathsFromEvent(event: DragEvent) {
  if (draggingPaths.value.length) {
    return draggingPaths.value;
  }
  const raw = event.dataTransfer?.getData("application/json") || "";
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function clearDragState() {
  draggingPaths.value = [];
  dragOverTarget.value = false;
  dragOverDirectoryPath.value = "";
  dragOriginPaths.value = new Set();
}

function createDragGhost(entry: FileEntry, paths: string[]) {
  const ghost = document.createElement("div");
  ghost.className = "copy-drag-ghost";
  ghost.innerHTML = `
    <strong>${paths.length > 1 ? `拖动 ${paths.length} 项` : entry.name}</strong>
    <span>${paths.length > 1 ? "批量复制到目标目录" : entry.type === "DIRECTORY" ? "目录复制" : "文件复制"}</span>
  `;
  document.body.appendChild(ghost);
  window.setTimeout(() => ghost.remove(), 0);
  return ghost;
}

function compareEntry(entry: FileEntry): ComparedEntry {
  const target = rightByName.value.get(entry.name);
  if (!target) {
    return { ...entry, compareText: "目标缺失", compareTone: "idle" };
  }
  if (target.type !== entry.type) {
    return { ...entry, target, compareText: "类型不同", compareTone: "bad" };
  }
  if (entry.type === "FILE") {
    if (entry.size === target.size) {
      return { ...entry, target, compareText: "大小相同", compareTone: "good" };
    }
    return { ...entry, target, compareText: "大小不同", compareTone: "warn" };
  }
  return { ...entry, target, compareText: "已存在", compareTone: "good" };
}

function compareEntries(left: FileEntry, right: FileEntry) {
  const leftDir = left.type === "DIRECTORY";
  const rightDir = right.type === "DIRECTORY";
  if (leftDir !== rightDir) {
    return leftDir ? -1 : 1;
  }
  return left.name.localeCompare(right.name, "zh-Hans-CN");
}

function matchesCompareFilter(entry: ComparedEntry) {
  if (leftCompareFilter.value === "ALL") {
    return true;
  }
  if (leftCompareFilter.value === "MISSING") {
    return !entry.target;
  }
  if (leftCompareFilter.value === "EXISTS") {
    return Boolean(entry.target);
  }
  return entry.compareTone !== "good";
}

function matchesEntryQuery(entry: FileEntry, query: string) {
  const text = query.trim().toLocaleLowerCase();
  if (!text) {
    return true;
  }
  return [
    entry.name,
    entry.path,
    entry.type,
    String(entry.size),
    displayTime(entry.modifiedAt)
  ].some((value) => String(value ?? "").toLocaleLowerCase().includes(text));
}

function sourceMeta(source: TaskCard | null) {
  const task = source?.task;
  if (!task) {
    return "未选择文件源";
  }
  if (task.sourceType === "LOCAL" || task.sourceType === "SMB") {
    return task.sourcePath;
  }
  return `${task.ftpHost}:${task.ftpPort} · ${task.sourcePath}`;
}

function parentPath(listing: FileListing | null) {
  return listing?.parentPath || "";
}

function pathText(listing: FileListing | null, source: TaskCard | null) {
  return listing?.path || source?.task.sourcePath || "/";
}

function cacheNotice(side: PaneSide, listing: FileListing | null) {
  if (!listing?.cacheUsed) {
    return "";
  }
  const scannedAt = listing.cacheScannedAt ? `缓存时间：${displayTime(listing.cacheScannedAt)}。` : "";
  const message =
    side === "left"
      ? "当前来源目录来自缓存，复制清单会按缓存目录展开；传输时文件缺失、跳过或失败会记录日志。"
      : "当前目标目录来自缓存，页面展示可能不是最新；冲突处理仍以传输时目标端实际结果为准。";
  return `${message}${scannedAt}`;
}

function cacheTopNotice(side: PaneSide, title: string, listing: FileListing | null): CopyNotice | null {
  const message = cacheNotice(side, listing);
  if (!message) {
    return null;
  }
  return {
    key: `${side}-cache:${listing?.path || ""}:${listing?.cacheMessage || ""}:${listing?.cacheScannedAt || ""}`,
    title,
    message,
    tone: "info"
  };
}

function showNotice(key: string) {
  const next = new Set(visibleNoticeKeys.value);
  next.add(key);
  visibleNoticeKeys.value = next;
  const existingTimer = noticeTimers.get(key);
  if (existingTimer) {
    window.clearTimeout(existingTimer);
  }
  noticeTimers.set(key, window.setTimeout(() => {
    hideNotice(key);
  }, 4200));
}

function hideNotice(key: string) {
  const timer = noticeTimers.get(key);
  if (timer) {
    window.clearTimeout(timer);
    noticeTimers.delete(key);
  }
  const next = new Set(visibleNoticeKeys.value);
  next.delete(key);
  visibleNoticeKeys.value = next;
}
</script>

<template>
  <section class="copy-page-panel">
    <div v-if="topNotices.length" class="copy-toast-list" role="status" aria-live="polite">
      <article v-for="notice in topNotices" :key="notice.key" class="copy-toast" :class="`tone-${notice.tone}`">
        <strong>{{ notice.title }}</strong>
        <p>{{ notice.message }}</p>
      </article>
    </div>

    <section v-if="!sources.length" class="empty-board">
      <span class="eyebrow">File Copy</span>
      <h2>还没有文件源</h2>
      <p>创建至少两个文件源后，可以在这里做左右目录对比和复制。</p>
    </section>

    <template v-else>
      <section class="copy-compare-grid">
        <article class="copy-pane">
          <header>
            <div class="copy-pane-title-line">
              <strong>来源</strong>
              <span :title="sourceMeta(leftSource)">{{ sourceMeta(leftSource) }}</span>
            </div>
            <label class="copy-source-select">
              <span>文件源</span>
              <select class="filter-select" :value="leftSourceId" @change="selectSource('left', $event)">
                <option v-for="source in sources" :key="source.task.taskId" :value="source.task.taskId">
                  {{ source.task.taskName || source.task.taskId }}
                </option>
              </select>
            </label>
          </header>

          <div class="copy-pane-path">
            <strong :title="pathText(leftListing, leftSource)">{{ pathText(leftListing, leftSource) }}</strong>
            <div>
              <button class="ghost-button compact-button" type="button" :disabled="leftLoading || !parentPath(leftListing)" @click="openPath('left', parentPath(leftListing))">上级</button>
              <button class="ghost-button compact-button" type="button" :disabled="leftLoading" @click="openPath('left')">{{ leftLoading ? "读取中" : "刷新" }}</button>
            </div>
          </div>

          <p v-if="leftError" class="form-error">{{ leftError }}</p>
          <p v-else-if="leftLoading" class="loading-note">正在读取来源目录...</p>

          <div v-if="leftListing && !leftLoading" class="copy-pane-tools">
            <input v-model.trim="leftQuery" class="filter-input copy-search-input" type="search" placeholder="搜索来源文件" aria-label="搜索来源文件" />
            <select v-model="leftCompareFilter" class="filter-select copy-filter-select" aria-label="来源对比筛选">
              <option value="ALL">全部</option>
              <option value="DIFF">仅差异</option>
              <option value="MISSING">目标缺失</option>
              <option value="EXISTS">已存在</option>
            </select>
            <button class="ghost-button compact-button" type="button" :disabled="!visibleLeftEntries.length" @click="toggleAllLeft">
              {{ allLeftSelected ? "取消全选" : "全选当前" }}
            </button>
            <span>{{ leftStatsText }}</span>
          </div>

          <div v-if="leftListing && !leftLoading" class="copy-file-list">
            <div class="copy-file-row copy-file-head source-row" aria-hidden="true">
              <span></span>
              <span>名称</span>
              <span>类型</span>
              <span>大小</span>
              <span>对比</span>
            </div>
            <div
              v-for="entry in visibleLeftEntries"
              :key="entry.path"
              class="copy-file-row source-row draggable-source-row"
              :class="{ 'drag-origin': dragOriginPaths.has(entry.path) }"
              draggable="true"
              :title="canDropCopy ? '拖动到右侧目标目录开始复制' : dropDisabledReason"
              @dragstart="onLeftDragStart(entry, $event)"
              @dragend="onLeftDragEnd"
            >
              <label class="file-select-check" :aria-label="`选择 ${entry.name}`">
                <input type="checkbox" :checked="selectedPaths.has(entry.path)" @change="onEntryCheck(entry.path, $event)" />
              </label>
              <button v-if="entry.type === 'DIRECTORY'" type="button" class="file-browser-name" @click="openPath('left', entry.path)">
                {{ entry.name }}
              </button>
              <strong v-else class="file-browser-name-static">{{ entry.name }}</strong>
              <span class="file-browser-type">{{ entry.type === "DIRECTORY" ? "目录" : entry.type === "FILE" ? "文件" : "其他" }}</span>
              <span>{{ entry.type === "FILE" ? formatBytes(entry.size) : "-" }}</span>
              <span class="pill" :class="`tone-${entry.compareTone}`">{{ entry.compareText }}</span>
            </div>
            <p v-if="!visibleLeftEntries.length" class="empty-text">当前筛选下没有可显示的文件。</p>
          </div>
        </article>

        <article
          class="copy-pane target-drop-pane"
          :class="{ 'drop-ready': draggingPaths.length && canDropCopy, 'drop-active': dragOverTarget && !dragOverDirectoryPath }"
          @dragover="onTargetDragOver"
          @dragleave="onTargetDragLeave"
          @drop="onTargetDrop"
        >
          <header>
            <div class="copy-pane-title-line">
              <strong>目标</strong>
              <span :title="sourceMeta(rightSource)">{{ sourceMeta(rightSource) }}</span>
            </div>
            <label class="copy-source-select">
              <span>文件源</span>
              <select class="filter-select" :value="rightSourceId" :disabled="!targetSourceOptions.length" @change="selectSource('right', $event)">
                <option v-if="!targetSourceOptions.length" value="" disabled>无可写文件源</option>
                <option v-for="source in targetSourceOptions" :key="source.task.taskId" :value="source.task.taskId">
                  {{ source.task.taskName || source.task.taskId }}
                </option>
              </select>
            </label>
          </header>

          <div class="copy-pane-path">
            <strong :title="pathText(rightListing, rightSource)">{{ pathText(rightListing, rightSource) }}</strong>
            <div>
              <button class="ghost-button compact-button" type="button" :disabled="rightLoading || !parentPath(rightListing)" @click="openPath('right', parentPath(rightListing))">上级</button>
              <button class="ghost-button compact-button" type="button" :disabled="rightLoading" @click="openPath('right')">{{ rightLoading ? "读取中" : "刷新" }}</button>
            </div>
          </div>

          <p v-if="rightError" class="form-error">{{ rightError }}</p>
          <p v-else-if="rightLoading" class="loading-note">正在读取目标目录...</p>

          <div v-if="draggingPaths.length && canDropCopy" class="copy-drop-banner" :class="{ active: dragOverTarget }">
            <strong>{{ dragOverDirectoryPath ? "松手复制到此目录" : "拖到这里复制" }}</strong>
            <span>{{ formatNumber(draggingPaths.length) }} 项 -> {{ dropTargetText }}</span>
          </div>

          <div v-if="rightListing && !rightLoading" class="copy-pane-tools">
            <input v-model.trim="rightQuery" class="filter-input copy-search-input" type="search" placeholder="搜索目标文件" aria-label="搜索目标文件" />
            <span>{{ rightStatsText }}</span>
          </div>

          <div v-if="rightListing && !rightLoading" class="copy-file-list">
            <div class="copy-file-row copy-file-head target-row" aria-hidden="true">
              <span>名称</span>
              <span>类型</span>
              <span>大小</span>
              <span>修改时间</span>
            </div>
            <div
              v-for="entry in visibleRightEntries"
              :key="entry.path"
              class="copy-file-row target-row"
              :class="{ 'directory-drop-row': entry.type === 'DIRECTORY', 'drop-active': dragOverDirectoryPath === entry.path }"
              @dragover.stop="entry.type === 'DIRECTORY' ? onTargetDragOver($event, entry.path) : onTargetDragOver($event)"
              @dragleave.stop="entry.type === 'DIRECTORY' ? onTargetDragLeave($event, entry.path) : onTargetDragLeave($event)"
              @drop.stop="entry.type === 'DIRECTORY' ? onTargetDrop($event, entry.path) : onTargetDrop($event)"
            >
              <button v-if="entry.type === 'DIRECTORY'" type="button" class="file-browser-name" @click="openPath('right', entry.path)">
                {{ entry.name }}
              </button>
              <strong v-else class="file-browser-name-static">{{ entry.name }}</strong>
              <span class="file-browser-type">{{ entry.type === "DIRECTORY" ? "目录" : entry.type === "FILE" ? "文件" : "其他" }}</span>
              <span>{{ entry.type === "FILE" ? formatBytes(entry.size) : "-" }}</span>
              <span>{{ displayTime(entry.modifiedAt) }}</span>
              <span v-if="entry.type === 'DIRECTORY' && dragOverDirectoryPath === entry.path" class="drop-row-hint">松手复制到此</span>
            </div>
            <p v-if="!visibleRightEntries.length" class="empty-text">当前筛选下没有可显示的文件。</p>
          </div>
        </article>
      </section>

      <section class="copy-action-bar">
        <div class="copy-action-main">
          <strong>拖动复制</strong>
          <span>目标：{{ targetDirectory }} · {{ dragSummaryText }} · 已选文件大小 {{ formatBytes(selectedBytes) }}</span>
          <span v-if="activeRun" class="copy-inline-run">
            <span class="pill" :class="`tone-${stateTone(activeRun.state)}`">{{ activeRunText }}</span>
            <button class="ghost-button compact-button danger-button" type="button" @click="$emit('cancelCopy', activeRun.id)">取消复制</button>
          </span>
          <small v-if="dropDisabledReason">{{ dropDisabledReason }}</small>
        </div>
        <div class="copy-action-side">
          <label class="copy-conflict-policy">
            <span>冲突</span>
            <select v-model="conflictPolicy" class="filter-select">
              <option value="SKIP">跳过</option>
              <option value="OVERWRITE">覆盖</option>
              <option value="FAIL">报错停止</option>
            </select>
          </label>
          <button
            class="solid-button compact-button copy-action-save"
            type="button"
            :disabled="!canDropCopy || !taskSourcePaths.length"
            :title="`保存 ${taskSourceText} 到定期任务`"
            @click="saveDirectoryTask"
          >
            保存定期任务
          </button>
        </div>
      </section>
    </template>
  </section>
</template>
