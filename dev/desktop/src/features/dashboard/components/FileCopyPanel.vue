<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { DirectoryCopyTaskDraft, FileCopyRequest, FileEntry, FileListing, SyncRun, TaskCard } from "../../../app/api/types";
import { systemFileOpenApi } from "../../../app/runtime/systemFileOpen";
import { displayTime, formatBytes, formatNumber, stateTone } from "../../../shared/formatters";

type PaneSide = "left" | "right";
type ConflictPolicy = FileCopyRequest["conflictPolicy"];
type CompareFilter = "ALL" | "DIFF" | "MISSING" | "EXISTS";

type ComparedEntry = FileEntry & {
  target?: FileEntry;
  compareText: string;
  compareTone: "good" | "warn" | "bad" | "idle";
};

type CopyDragPayload = {
  sourceId: string;
  paths: string[];
};

type CopyNotice = {
  key: string;
  title: string;
  message: string;
  tone: "info" | "warning";
};

type DragFeedbackTone = "info" | "warning";

type CopyProgressDetail = {
  label: string;
  value: string;
  path?: boolean;
};

type PointerCopyDrag = {
  payload: CopyDragPayload;
  paths: string[];
  pointerId: number;
  startX: number;
  startY: number;
  x: number;
  y: number;
  active: boolean;
  moved: boolean;
};

const FILEBRIDGE_DRAG_TYPE = "application/x-filebridge-copy-paths";
const POINTER_DRAG_THRESHOLD = 6;
const POINTER_LONG_PRESS_MS = 420;

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
const leftCompareFilter = ref<CompareFilter>("ALL");
const draggingPaths = ref<string[]>([]);
const draggingPayload = ref<CopyDragPayload | null>(null);
const dragOverTarget = ref(false);
const dragOverDirectoryPath = ref("");
const pendingDropTargetDirectory = ref("");
const dragOriginPaths = ref<Set<string>>(new Set());
const dropHandled = ref(false);
const dragFeedbackMessage = ref("");
const dragFeedbackTone = ref<DragFeedbackTone>("info");
const targetPaneRef = ref<HTMLElement | null>(null);
const pointerDrag = ref<PointerCopyDrag | null>(null);
const suppressSourceClick = ref(false);
const selectedRightPath = ref("");
const visibleNoticeKeys = ref<Set<string>>(new Set());
const autoShownCacheSides = ref<Set<PaneSide>>(new Set());
const dismissedCopyProgressKey = ref("");
const copyProgressSessionActive = ref(Boolean(props.copying || props.activeRun?.state === "RUNNING"));
const noticeTimers = new Map<string, ReturnType<typeof setTimeout>>();
let dragClearTimer: ReturnType<typeof setTimeout> | null = null;
let dragFeedbackTimer: ReturnType<typeof setTimeout> | null = null;
let sourceClickSuppressTimer: ReturnType<typeof setTimeout> | null = null;
let sourceLongPressTimer: ReturnType<typeof setTimeout> | null = null;

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
  .filter((entry) => matchesCompareFilter(entry)));
const visibleRightEntries = computed(() => rightEntries.value);
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
  && !props.leftLoading
  && !props.rightLoading
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
const pointerDragStyle = computed(() => {
  const session = pointerDrag.value;
  return session
    ? { transform: `translate3d(${session.x + 14}px, ${session.y + 14}px, 0)` }
    : {};
});
const pointerDragHintText = computed(() => (
  pendingDropTargetDirectory.value
    ? `释放到 ${pendingDropTargetDirectory.value}`
    : "拖到右侧目标目录"
));
const copyProgressKey = computed(() => {
  if (props.activeRun) {
    return `run:${props.activeRun.id}`;
  }
  const message = props.copyMessage.trim();
  return message ? `message:${message}` : props.copying ? "copying" : "";
});
const copyProgressVisible = computed(() => Boolean(
  copyProgressSessionActive.value
    && copyProgressKey.value
    && copyProgressKey.value !== dismissedCopyProgressKey.value
));
const copyProgressRunning = computed(() => Boolean(props.copying || props.activeRun?.state === "RUNNING"));
const copyProgressTone = computed(() => stateTone(props.activeRun?.state || (props.copyMessage.includes("失败") ? "FAILED" : "")));
const copyProgressOperationText = computed(() => copyOperationText(props.activeRun));
const copyProgressPhaseText = computed(() => copyRunPhaseText(props.activeRun, props.copying));
const copyProgressTitle = computed(() => {
  const operation = copyProgressOperationText.value;
  if (copyProgressRunning.value) {
    return copyProgressPhaseText.value || `正在${operation}`;
  }
  if (/失败|错误/.test(props.copyMessage)) {
    return `${operation}失败`;
  }
  if (/取消/.test(props.copyMessage)) {
    return `${operation}已取消`;
  }
  return props.copyMessage ? `${operation}完成` : operation;
});
const copyProgressSubtitle = computed(() => {
  const run = props.activeRun;
  if (run) {
    return `${run.sourcePath || "来源"} -> ${run.targetPath || "目标"}`;
  }
  return props.copyMessage.trim() || "正在提交复制请求";
});
const copyProgressStatsText = computed(() => {
  const run = props.activeRun;
  if (!run) {
    return props.copying ? "正在创建复制任务" : "等待下一次复制";
  }
  return `文件 ${formatNumber(run.fileCount)} · 目录 ${formatNumber(run.directoryCount)} · ${formatBytes(run.totalBytes)}`;
});
const copyProgressCurrentText = computed(() => {
  const run = props.activeRun;
  if (!run) {
    return props.copyMessage.trim() || "正在提交复制请求";
  }
  const eventType = (run.summaryEventType || "").toUpperCase();
  const eventPath = run.summarySourcePath || run.summaryTargetPath;
  if (eventPath && !eventType.startsWith("DIRECTORY_COMPARE") && !eventType.startsWith("DIRECTORY_COPY_TASK")) {
    return lastPathSegment(eventPath);
  }
  return copyProgressPhaseText.value;
});
const copyProgressSpeedText = computed(() => {
  const run = props.activeRun;
  if (!run || run.totalBytes <= 0) {
    return copyProgressRunning.value ? "计算中" : "-";
  }
  const startedAt = Date.parse(run.startedAt || "");
  const finishedAt = Date.parse(run.finishedAt || "");
  const endAt = Number.isFinite(finishedAt) && finishedAt > startedAt ? finishedAt : Date.now();
  if (!Number.isFinite(startedAt) || endAt <= startedAt) {
    return "计算中";
  }
  const bytesPerSecond = run.totalBytes / Math.max(1, (endAt - startedAt) / 1000);
  return `${formatBytes(bytesPerSecond)}/s`;
});
const copyProgressEventDetailText = computed(() => formatRunDetails(props.activeRun?.summaryDetailsText || ""));
const copyProgressDetails = computed<CopyProgressDetail[]>(() => {
  const run = props.activeRun;
  if (!run) {
    return props.copyMessage.trim()
      ? [{ label: "状态", value: props.copyMessage.trim() }]
      : [{ label: "状态", value: "正在创建复制任务" }];
  }
  const rows: CopyProgressDetail[] = [
    { label: "阶段", value: copyProgressPhaseText.value || copyProgressOperationText.value },
    { label: "当前", value: copyProgressCurrentText.value, path: true },
    { label: "来源", value: run.summarySourcePath || run.sourcePath || "-", path: true },
    { label: "目标", value: run.summaryTargetPath || run.targetPath || "-", path: true },
    { label: "已处理", value: copyProgressStatsText.value },
    { label: "速度", value: copyProgressSpeedText.value }
  ];
  if (copyProgressEventDetailText.value) {
    rows.push({ label: "详情", value: copyProgressEventDetailText.value });
  }
  if (run.errorCount > 0 || run.lastErrorMessage) {
    rows.push({ label: "错误", value: run.lastErrorMessage || `${formatNumber(run.errorCount)} 项错误` });
  } else if (run.warningCount > 0) {
    rows.push({ label: "跳过", value: `${formatNumber(run.warningCount)} 项` });
  }
  return rows.filter((row) => row.value && row.value !== "-");
});
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
const leftCacheNotice = computed(() => cacheTopNotice("left", "来源目录", props.leftListing));
const rightCacheNotice = computed(() => cacheTopNotice("right", "目标目录", props.rightListing));
const topNotices = computed(() => [leftCacheNotice.value, rightCacheNotice.value]
  .filter((notice): notice is CopyNotice => Boolean(notice))
  .filter((notice) => visibleNoticeKeys.value.has(notice.key)));

watch(
  () => [props.leftSourceId, props.leftListing?.path],
  () => {
    selectedPaths.value = new Set();
    leftCompareFilter.value = "ALL";
  }
);

watch(
  () => [props.rightSourceId, props.rightListing?.path],
  () => {
    selectedRightPath.value = "";
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
  () => Boolean(props.copying || props.activeRun?.state === "RUNNING"),
  (isLive) => {
    if (isLive) {
      copyProgressSessionActive.value = true;
    }
  },
  { immediate: true }
);

watch(copyProgressKey, (key, previousKey) => {
  if (key && key !== previousKey) {
    dismissedCopyProgressKey.value = "";
  }
});

onBeforeUnmount(() => {
  for (const timer of noticeTimers.values()) {
    window.clearTimeout(timer);
  }
  noticeTimers.clear();
  if (dragClearTimer) {
    window.clearTimeout(dragClearTimer);
    dragClearTimer = null;
  }
  if (dragFeedbackTimer) {
    window.clearTimeout(dragFeedbackTimer);
    dragFeedbackTimer = null;
  }
  if (sourceClickSuppressTimer) {
    window.clearTimeout(sourceClickSuppressTimer);
    sourceClickSuppressTimer = null;
  }
  clearSourceLongPressTimer();
  removePointerDragListeners();
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

function selectSourceEntry(entry: FileEntry, event: MouseEvent) {
  if (shouldIgnoreSourceSelection(event)) {
    return;
  }
  if (event.ctrlKey || event.metaKey) {
    toggleEntry(entry.path, !selectedPaths.value.has(entry.path));
    return;
  }
  selectedPaths.value = new Set([entry.path]);
}

function activateSourceEntry(entry: FileEntry, event: MouseEvent) {
  if (shouldIgnoreSourceSelection(event)) {
    return;
  }
  selectedPaths.value = new Set([entry.path]);
  if (entry.type === "DIRECTORY") {
    openPath("left", entry.path);
    return;
  }
  void openPaneFile(leftSource.value, entry);
}

function selectTargetEntry(entry: FileEntry, event: MouseEvent) {
  if (shouldIgnoreSourceSelection(event)) {
    return;
  }
  selectedRightPath.value = entry.path;
}

function activateTargetEntry(entry: FileEntry, event: MouseEvent) {
  if (shouldIgnoreSourceSelection(event)) {
    return;
  }
  selectedRightPath.value = entry.path;
  if (entry.type === "DIRECTORY") {
    openPath("right", entry.path);
    return;
  }
  void openPaneFile(rightSource.value, entry);
}

async function openPaneFile(source: TaskCard | null, entry: FileEntry) {
  if (entry.type !== "FILE") {
    return;
  }
  if (!isLocalTaskSource(source)) {
    showDragFeedback("远端 FTP 文件需要先复制到本地后打开。", "warning");
    return;
  }
  try {
    await systemFileOpenApi.open(entry.path);
    showDragFeedback(`已打开：${entry.name}`, "info");
  } catch (ex) {
    showDragFeedback(ex instanceof Error ? ex.message : "打开文件失败。", "warning");
  }
}

function isLocalTaskSource(source: TaskCard | null) {
  return ["LOCAL", "SMB"].includes(source?.task.sourceType ?? "");
}

function shouldIgnoreSourceSelection(event: MouseEvent) {
  const target = event.target;
  return target instanceof Element && Boolean(target.closest("input, label, select, textarea"));
}

function emitCopy(payload: CopyDragPayload, targetDirectoryPath: string) {
  const source = props.sources.find((item) => item.task.taskId === payload.sourceId) ?? leftSource.value;
  if (!canDropCopy.value || !source || !rightSource.value || !payload.paths.length) {
    showDragFeedback(dropDisabledReason.value || "复制提交失败：缺少来源、目标或文件路径。", "warning");
    return false;
  }
  emit("copyFiles", {
    sourceId: source.task.taskId,
    sourcePaths: payload.paths,
    targetId: rightSource.value.task.taskId,
    targetDirectory: targetDirectoryPath,
    conflictPolicy: conflictPolicy.value
  });
  return true;
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

function onSourcePointerDown(entry: FileEntry, event: PointerEvent) {
  if (shouldIgnoreSourcePointer(event)) {
    return;
  }
  if (!leftSource.value) {
    return;
  }
  const paths = dragPathsFor(entry);
  const payload: CopyDragPayload = {
    sourceId: leftSource.value.task.taskId,
    paths
  };
  cancelScheduledDragClear();
  clearDragFeedback();
  pointerDrag.value = {
    payload,
    paths,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    x: event.clientX,
    y: event.clientY,
    active: false,
    moved: false
  };
  const sourceRow = event.currentTarget as HTMLElement | null;
  sourceRow?.setPointerCapture?.(event.pointerId);
  scheduleSourceLongPress();
  window.addEventListener("pointermove", onSourcePointerMove);
  window.addEventListener("pointerup", onSourcePointerUp);
  window.addEventListener("pointercancel", onSourcePointerCancel);
}

function shouldIgnoreSourcePointer(event: PointerEvent) {
  if (event.button !== 0) {
    return true;
  }
  const target = event.target;
  return target instanceof Element && Boolean(target.closest("button, input, label, select, textarea"));
}

function onSourcePointerMove(event: PointerEvent) {
  const session = pointerDrag.value;
  if (!session || event.pointerId !== session.pointerId) {
    return;
  }
  const distance = Math.hypot(event.clientX - session.startX, event.clientY - session.startY);
  if (!session.active && distance < POINTER_DRAG_THRESHOLD) {
    pointerDrag.value = {
      ...session,
      x: event.clientX,
      y: event.clientY
    };
    return;
  }
  clearSourceLongPressTimer();
  if (!session.active && !startPointerDrag(session, event.clientX, event.clientY, true)) {
    event.preventDefault();
    return;
  }
  const activeSession = pointerDrag.value;
  if (!activeSession || event.pointerId !== activeSession.pointerId) {
    return;
  }
  const targetDirectoryPath = resolveDropTargetAtPoint(event.clientX, event.clientY);
  dragOverTarget.value = Boolean(targetDirectoryPath);
  dragOverDirectoryPath.value = targetDirectoryPath === targetDirectory.value ? "" : targetDirectoryPath;
  pendingDropTargetDirectory.value = targetDirectoryPath;
  pointerDrag.value = {
    ...activeSession,
    active: true,
    x: event.clientX,
    y: event.clientY,
    moved: activeSession.moved || distance >= POINTER_DRAG_THRESHOLD
  };
  event.preventDefault();
}

function scheduleSourceLongPress() {
  clearSourceLongPressTimer();
  sourceLongPressTimer = window.setTimeout(() => {
    sourceLongPressTimer = null;
    const session = pointerDrag.value;
    if (!session || session.active) {
      return;
    }
    startPointerDrag(session, session.x, session.y, false);
  }, POINTER_LONG_PRESS_MS);
}

function clearSourceLongPressTimer() {
  if (!sourceLongPressTimer) {
    return;
  }
  window.clearTimeout(sourceLongPressTimer);
  sourceLongPressTimer = null;
}

function startPointerDrag(session: PointerCopyDrag, clientX: number, clientY: number, moved: boolean) {
  if (!canDropCopy.value || !leftSource.value) {
    showDragFeedback(dropDisabledReason.value || "当前不能开始复制，请确认左右目录已经读取且目标可写。", "warning");
    removePointerDragListeners();
    pointerDrag.value = null;
    clearDragState();
    return false;
  }
  beginPointerDrag(session);
  pointerDrag.value = {
    ...session,
    active: true,
    x: clientX,
    y: clientY,
    moved: session.moved || moved
  };
  return true;
}

function beginPointerDrag(session: PointerCopyDrag) {
  draggingPaths.value = session.paths;
  draggingPayload.value = session.payload;
  dragOriginPaths.value = new Set(session.paths);
  pendingDropTargetDirectory.value = "";
  dropHandled.value = false;
  showDragFeedback("正在拖动，释放到右侧目标目录即可复制。", "info", false);
}

function onSourcePointerUp(event: PointerEvent) {
  finishPointerDrag(event, false);
}

function onSourcePointerCancel(event: PointerEvent) {
  finishPointerDrag(event, true);
}

function finishPointerDrag(event: PointerEvent, cancelled: boolean) {
  const session = pointerDrag.value;
  if (!session || event.pointerId !== session.pointerId) {
    return;
  }
  clearSourceLongPressTimer();
  removePointerDragListeners();
  pointerDrag.value = null;
  if (!session.active) {
    return;
  }
  if (!session.moved) {
    scheduleDragStateClear();
    event.preventDefault();
    return;
  }
  suppressNextSourceClick();
  if (cancelled) {
    showDragFeedback("拖动已取消。", "warning");
    scheduleDragStateClear();
    return;
  }
  const targetDirectoryPath = pendingDropTargetDirectory.value || resolveDropTargetAtPoint(event.clientX, event.clientY);
  if (targetDirectoryPath) {
    submitCopyPayload(session.payload, targetDirectoryPath);
  } else {
    showDragFeedback("未提交复制：请把文件释放到右侧目标目录区域。", "warning");
  }
  scheduleDragStateClear();
  event.preventDefault();
}

function removePointerDragListeners() {
  clearSourceLongPressTimer();
  window.removeEventListener("pointermove", onSourcePointerMove);
  window.removeEventListener("pointerup", onSourcePointerUp);
  window.removeEventListener("pointercancel", onSourcePointerCancel);
}

function suppressNextSourceClick() {
  suppressSourceClick.value = true;
  if (sourceClickSuppressTimer) {
    window.clearTimeout(sourceClickSuppressTimer);
  }
  sourceClickSuppressTimer = window.setTimeout(() => {
    suppressSourceClick.value = false;
    sourceClickSuppressTimer = null;
  }, 120);
}

function onSourceClickCapture(event: MouseEvent) {
  if (!suppressSourceClick.value) {
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  suppressSourceClick.value = false;
}

function onLeftDragStart(entry: FileEntry, event: DragEvent) {
  if (!canDropCopy.value || !leftSource.value) {
    showDragFeedback(dropDisabledReason.value || "当前不能开始复制，请确认左右目录已经读取且目标可写。", "warning");
    event.preventDefault();
    return;
  }
  cancelScheduledDragClear();
  clearDragFeedback();
  const paths = dragPathsFor(entry);
  const payload: CopyDragPayload = {
    sourceId: leftSource.value.task.taskId,
    paths
  };
  draggingPaths.value = paths;
  draggingPayload.value = payload;
  dragOriginPaths.value = new Set(paths);
  pendingDropTargetDirectory.value = "";
  dropHandled.value = false;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "copy";
    const serialized = JSON.stringify(payload);
    event.dataTransfer.setData(FILEBRIDGE_DRAG_TYPE, serialized);
    event.dataTransfer.setData("application/json", serialized);
    event.dataTransfer.setData("text/plain", paths.join("\n"));
    event.dataTransfer.setDragImage(createDragGhost(entry, paths), 24, 22);
  } else {
    showDragFeedback("当前环境没有提供拖拽数据，将使用页面内拖动状态继续尝试。", "warning");
  }
}

function onLeftDragEnd(event: DragEvent) {
  submitPendingDropFromDragEnd(event);
  if (!dropHandled.value && draggingPayload.value && !pendingDropTargetDirectory.value) {
    showDragFeedback("未提交复制：请把文件释放到右侧目标目录区域。", "warning");
  }
  scheduleDragStateClear();
}

function onTargetDragOver(event: DragEvent, targetPath = "") {
  if (!canDropCopy.value) {
    showDragFeedback(dropDisabledReason.value || "当前目标目录不能接收复制。", "warning");
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = "copy";
  }
  dragOverTarget.value = true;
  dragOverDirectoryPath.value = targetPath;
  pendingDropTargetDirectory.value = targetPath || targetDirectory.value;
  const prefix = hasCopyDragData(event) ? "释放后将复制到" : "已进入目标区，释放后尝试复制到";
  showDragFeedback(`${prefix}：${pendingDropTargetDirectory.value}`, "info", false);
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
  event.preventDefault();
  event.stopPropagation();
  cancelScheduledDragClear();
  if (dropHandled.value) {
    clearDragState();
    return;
  }
  const payload = readCopyDragPayload(event);
  if (!canDropCopy.value) {
    showDragFeedback(dropDisabledReason.value || "释放失败：当前目标目录不能接收复制。", "warning");
    clearDragState();
    return;
  }
  if (!payload) {
    showDragFeedback("释放失败：没有读取到复制 payload，请从左侧文件行重新拖动。", "warning");
    clearDragState();
    return;
  }
  submitCopyPayload(payload, targetPath || targetDirectory.value);
  clearDragState();
}

function submitPendingDropFromDragEnd(event: DragEvent) {
  if (dropHandled.value || !draggingPayload.value) {
    return;
  }
  const targetDirectoryPath = pendingDropTargetDirectory.value || resolveDropTargetFromPoint(event);
  if (!targetDirectoryPath) {
    return;
  }
  showDragFeedback("未收到释放事件，已按最后进入的目标目录提交复制。", "warning");
  submitCopyPayload(draggingPayload.value, targetDirectoryPath);
}

function submitCopyPayload(payload: CopyDragPayload, targetDirectoryPath: string) {
  dropHandled.value = true;
  if (emitCopy(payload, targetDirectoryPath)) {
    showDragFeedback(`已提交复制：${formatNumber(payload.paths.length)} 项 -> ${targetDirectoryPath}`, "info");
  }
}

function hasCopyDragData(event: DragEvent) {
  if (draggingPayload.value?.paths.length || draggingPaths.value.length) {
    return true;
  }
  const types = Array.from(event.dataTransfer?.types ?? []);
  return types.includes(FILEBRIDGE_DRAG_TYPE)
    || types.includes("application/json")
    || types.includes("text/plain");
}

function readCopyDragPayload(event: DragEvent): CopyDragPayload | null {
  if (draggingPayload.value?.paths.length) {
    return draggingPayload.value;
  }
  if (draggingPaths.value.length) {
    return leftSource.value ? {
      sourceId: leftSource.value.task.taskId,
      paths: draggingPaths.value
    } : null;
  }
  const raw = event.dataTransfer?.getData(FILEBRIDGE_DRAG_TYPE)
    || event.dataTransfer?.getData("application/json")
    || "";
  if (raw) {
    try {
      const payload = normalizeCopyDragPayload(JSON.parse(raw));
      if (payload) {
        return payload;
      }
    } catch {
      return null;
    }
  }
  const text = event.dataTransfer?.getData("text/plain") || "";
  if (!text.trim()) {
    return null;
  }
  return leftSource.value ? {
    sourceId: leftSource.value.task.taskId,
    paths: text.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
  } : null;
}

function resolveDropTargetFromPoint(event: DragEvent) {
  return resolveDropTargetAtPoint(event.clientX, event.clientY);
}

function resolveDropTargetAtPoint(clientX: number, clientY: number) {
  const pane = targetPaneRef.value;
  if (!pane || !Number.isFinite(clientX) || !Number.isFinite(clientY)) {
    return "";
  }
  const hit = document.elementFromPoint(clientX, clientY);
  if (hit && pane.contains(hit)) {
    const row = hit instanceof Element ? hit.closest<HTMLElement>("[data-copy-target-path]") : null;
    if (row?.dataset.copyTargetPath) {
      return row.dataset.copyTargetPath;
    }
    return targetDirectory.value;
  }
  const bounds = pane.getBoundingClientRect();
  return clientX >= bounds.left
    && clientX <= bounds.right
    && clientY >= bounds.top
    && clientY <= bounds.bottom
    ? targetDirectory.value
    : "";
}

function normalizeCopyDragPayload(value: unknown): CopyDragPayload | null {
  if (Array.isArray(value)) {
    const paths = value.filter((item): item is string => typeof item === "string" && Boolean(item.trim()));
    return leftSource.value && paths.length ? {
      sourceId: leftSource.value.task.taskId,
      paths
    } : null;
  }
  if (!value || typeof value !== "object") {
    return null;
  }
  const partial = value as Partial<CopyDragPayload>;
  const paths = Array.isArray(partial.paths)
    ? partial.paths.filter((item): item is string => typeof item === "string" && Boolean(item.trim()))
    : [];
  const sourceId = typeof partial.sourceId === "string" ? partial.sourceId.trim() : "";
  return sourceId && paths.length ? {
    sourceId,
    paths
  } : null;
}

function clearDragState() {
  cancelScheduledDragClear();
  draggingPaths.value = [];
  draggingPayload.value = null;
  dragOverTarget.value = false;
  dragOverDirectoryPath.value = "";
  pendingDropTargetDirectory.value = "";
  dragOriginPaths.value = new Set();
  dropHandled.value = false;
}

function scheduleDragStateClear() {
  cancelScheduledDragClear();
  dragClearTimer = window.setTimeout(() => {
    dragClearTimer = null;
    clearDragState();
  }, 160);
}

function cancelScheduledDragClear() {
  if (!dragClearTimer) {
    return;
  }
  window.clearTimeout(dragClearTimer);
  dragClearTimer = null;
}

function showDragFeedback(message: string, tone: DragFeedbackTone, autoHide = true) {
  dragFeedbackMessage.value = message;
  dragFeedbackTone.value = tone;
  if (dragFeedbackTimer) {
    window.clearTimeout(dragFeedbackTimer);
    dragFeedbackTimer = null;
  }
  if (autoHide) {
    dragFeedbackTimer = window.setTimeout(() => {
      dragFeedbackTimer = null;
      dragFeedbackMessage.value = "";
    }, 5200);
  }
}

function clearDragFeedback() {
  if (dragFeedbackTimer) {
    window.clearTimeout(dragFeedbackTimer);
    dragFeedbackTimer = null;
  }
  dragFeedbackMessage.value = "";
}

function dismissCopyProgress() {
  dismissedCopyProgressKey.value = copyProgressKey.value;
  copyProgressSessionActive.value = false;
}

function copyOperationText(run: SyncRun | null) {
  const operation = (run?.operationType || "").toUpperCase();
  if (operation === "DIRECTORY_COPY_TASK") {
    return "目录复制";
  }
  if (operation === "FILE_MOVE") {
    return "文件移动";
  }
  return "文件复制";
}

function copyRunPhaseText(run: SyncRun | null, submitting: boolean) {
  if (!run) {
    return submitting ? "正在提交复制请求" : "";
  }
  const state = (run.state || "").toUpperCase();
  const eventType = (run.summaryEventType || "").toUpperCase();
  if (state === "FAILED") {
    return "任务失败";
  }
  if (state === "CANCELLED") {
    return "任务已取消";
  }
  if (state === "SUCCESS") {
    return "任务已完成";
  }
  const phaseMap: Record<string, string> = {
    FILE_COPY_STARTED: "正在启动复制任务",
    DIRECTORY_COPY_TASK_STARTED: "正在启动目录复制",
    DIRECTORY_COMPARE_STARTED: "正在比对目录",
    DIRECTORY_COMPARE_FINISHED: "比对完成，准备传输",
    DIRECTORY_CREATED: "正在准备目标目录",
    FILE_COPYING: "正在传输文件",
    FILE_COPIED: "正在传输文件",
    FILE_SKIPPED: "正在处理跳过项",
    FILE_COPY_ITEM_FAILED: "复制单项失败",
    TYPE_CONFLICT: "发现类型冲突",
    FILE_COPY_FINISHED: "复制完成",
    DIRECTORY_COPY_TASK_FINISHED: "目录复制完成"
  };
  return phaseMap[eventType] || run.summaryMessage || copyOperationText(run);
}

function formatRunDetails(detailsText: string) {
  const details = detailsText.trim();
  if (!details) {
    return "";
  }
  return details
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const index = part.indexOf("=");
      if (index < 0) {
        return part;
      }
      const key = part.slice(0, index).trim();
      const value = part.slice(index + 1).trim();
      return `${detailLabel(key)} ${detailValue(key, value)}`;
    })
    .join(" · ");
}

function detailLabel(key: string) {
  const labels: Record<string, string> = {
    action: "动作",
    bytes: "体积",
    compareMode: "比对",
    conflicts: "冲突",
    directories: "目录",
    directoriesToCreate: "待建目录",
    errors: "错误",
    files: "文件",
    filesToCopy: "待传文件",
    mode: "模式",
    name: "名称",
    policy: "策略",
    relativePath: "相对路径",
    skipped: "跳过",
    targetSource: "目标源",
    type: "类型"
  };
  return labels[key] || key;
}

function detailValue(key: string, value: string) {
  if (key === "bytes") {
    const bytes = Number(value);
    return Number.isFinite(bytes) ? formatBytes(bytes) : value;
  }
  if (key === "action") {
    return copyActionText(value);
  }
  if (key === "policy") {
    return conflictPolicyText(value);
  }
  if (key === "mode" || key === "compareMode") {
    return value === "FAST" ? "快速" : value;
  }
  return value;
}

function copyActionText(value: string) {
  const actions: Record<string, string> = {
    COPY_CHANGED: "复制变更文件",
    COPY_NEW: "复制新文件"
  };
  return actions[value] || value;
}

function conflictPolicyText(value: string) {
  const policies: Record<string, string> = {
    FAIL: "报错停止",
    OVERWRITE: "覆盖",
    SKIP: "跳过"
  };
  return policies[value] || value;
}

function lastPathSegment(path: string) {
  const normalized = path.replace(/\\/g, "/").replace(/\/+$/g, "");
  const parts = normalized.split("/").filter(Boolean);
  return parts[parts.length - 1] || normalized || path;
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
              <button class="ghost-button compact-button" type="button" :disabled="!parentPath(leftListing)" @click="openPath('left', parentPath(leftListing))">上级</button>
              <button class="ghost-button compact-button" type="button" @click="openPath('left')">{{ leftLoading ? "读取中" : "刷新" }}</button>
            </div>
          </div>

          <p v-if="leftError" class="form-error">{{ leftError }}</p>
          <p v-else-if="leftLoading && !leftListing" class="loading-note">正在读取来源目录...</p>

          <div v-if="leftListing" class="copy-pane-tools" :class="{ 'is-loading': leftLoading }">
            <select v-model="leftCompareFilter" class="filter-select copy-filter-select" aria-label="来源对比筛选">
              <option value="ALL">全部</option>
              <option value="DIFF">仅差异</option>
              <option value="MISSING">目标缺失</option>
              <option value="EXISTS">已存在</option>
            </select>
            <span>{{ leftStatsText }}</span>
          </div>

          <div v-if="leftListing" class="copy-file-list" :class="{ 'is-loading': leftLoading }">
            <div v-if="leftLoading" class="copy-list-loading-cover" role="status">正在读取来源目录...</div>
            <div class="copy-file-row copy-file-head source-row" aria-hidden="true">
              <span>名称</span>
              <span>类型</span>
              <span>大小</span>
              <span>对比</span>
            </div>
            <div
              v-for="entry in visibleLeftEntries"
              :key="entry.path"
              class="copy-file-row source-row draggable-source-row"
              :class="{ selected: selectedPaths.has(entry.path), 'drag-origin': dragOriginPaths.has(entry.path), 'drag-disabled': !canDropCopy }"
              :title="canDropCopy ? '拖动到右侧目标目录开始复制' : dropDisabledReason"
              @click.capture="onSourceClickCapture"
              @click="selectSourceEntry(entry, $event)"
              @dblclick="activateSourceEntry(entry, $event)"
              @pointerdown="onSourcePointerDown(entry, $event)"
            >
              <strong
                class="file-browser-name-static"
              >
                {{ entry.name }}
              </strong>
              <span class="file-browser-type">{{ entry.type === "DIRECTORY" ? "目录" : entry.type === "FILE" ? "文件" : "其他" }}</span>
              <span>{{ entry.type === "FILE" ? formatBytes(entry.size) : "-" }}</span>
              <span class="pill" :class="`tone-${entry.compareTone}`">{{ entry.compareText }}</span>
            </div>
            <p v-if="!visibleLeftEntries.length" class="empty-text">当前筛选下没有可显示的文件。</p>
          </div>
        </article>

        <article
          ref="targetPaneRef"
          class="copy-pane target-drop-pane"
          data-copy-target-root="true"
          :class="{ 'drop-ready': draggingPaths.length && canDropCopy, 'drop-active': dragOverTarget && !dragOverDirectoryPath }"
          @dragenter="onTargetDragOver"
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
              <button class="ghost-button compact-button" type="button" :disabled="!parentPath(rightListing)" @click="openPath('right', parentPath(rightListing))">上级</button>
              <button class="ghost-button compact-button" type="button" @click="openPath('right')">{{ rightLoading ? "读取中" : "刷新" }}</button>
            </div>
          </div>

          <p v-if="rightError" class="form-error">{{ rightError }}</p>
          <p v-else-if="rightLoading && !rightListing" class="loading-note">正在读取目标目录...</p>

          <div v-if="draggingPaths.length && canDropCopy" class="copy-drop-banner" :class="{ active: dragOverTarget }">
            <strong>{{ dragOverDirectoryPath ? "松手复制到此目录" : "拖到这里复制" }}</strong>
            <span>{{ formatNumber(draggingPaths.length) }} 项 -> {{ dropTargetText }}</span>
          </div>

          <div v-if="rightListing" class="copy-pane-tools" :class="{ 'is-loading': rightLoading }">
            <span>{{ rightStatsText }}</span>
          </div>

          <div v-if="rightListing" class="copy-file-list" :class="{ 'is-loading': rightLoading }">
            <div v-if="rightLoading" class="copy-list-loading-cover" role="status">正在读取目标目录...</div>
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
              :class="{ selected: selectedRightPath === entry.path, 'directory-drop-row': entry.type === 'DIRECTORY', 'drop-active': dragOverDirectoryPath === entry.path }"
              :data-copy-target-path="entry.type === 'DIRECTORY' ? entry.path : undefined"
              @click="selectTargetEntry(entry, $event)"
              @dblclick="activateTargetEntry(entry, $event)"
              @dragenter.stop="entry.type === 'DIRECTORY' ? onTargetDragOver($event, entry.path) : onTargetDragOver($event)"
              @dragover.stop="entry.type === 'DIRECTORY' ? onTargetDragOver($event, entry.path) : onTargetDragOver($event)"
              @dragleave.stop="entry.type === 'DIRECTORY' ? onTargetDragLeave($event, entry.path) : onTargetDragLeave($event)"
              @drop.stop="entry.type === 'DIRECTORY' ? onTargetDrop($event, entry.path) : onTargetDrop($event)"
            >
              <strong class="file-browser-name-static">{{ entry.name }}</strong>
              <span class="file-browser-type">{{ entry.type === "DIRECTORY" ? "目录" : entry.type === "FILE" ? "文件" : "其他" }}</span>
              <span>{{ entry.type === "FILE" ? formatBytes(entry.size) : "-" }}</span>
              <span>{{ displayTime(entry.modifiedAt) }}</span>
              <span v-if="entry.type === 'DIRECTORY' && dragOverDirectoryPath === entry.path" class="drop-row-hint">松手复制到此</span>
            </div>
            <p v-if="!visibleRightEntries.length" class="empty-text">当前筛选下没有可显示的文件。</p>
          </div>
        </article>
      </section>

      <div v-if="pointerDrag?.active" class="copy-pointer-ghost" :style="pointerDragStyle" aria-hidden="true">
        <strong>{{ pointerDrag.paths.length > 1 ? `拖动 ${formatNumber(pointerDrag.paths.length)} 项` : pointerDrag.paths[0] }}</strong>
        <span>{{ pointerDragHintText }}</span>
      </div>

      <section class="copy-action-bar">
        <div class="copy-action-main">
          <strong>拖动复制</strong>
          <span>目标：{{ targetDirectory }} · {{ dragSummaryText }} · 已选文件大小 {{ formatBytes(selectedBytes) }}</span>
          <small v-if="dragFeedbackMessage" class="copy-drag-feedback" :class="`tone-${dragFeedbackTone}`">
            {{ dragFeedbackMessage }}
          </small>
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

      <aside
        v-if="copyProgressVisible"
        class="copy-progress-popup"
        :class="[`tone-${copyProgressTone}`, { running: copyProgressRunning }]"
        role="dialog"
        aria-label="复制进度"
        aria-live="polite"
      >
        <header>
          <div>
            <strong>{{ copyProgressTitle }}</strong>
            <span>{{ copyProgressSubtitle }}</span>
          </div>
          <button class="copy-progress-close" type="button" aria-label="关闭复制进度" @click="dismissCopyProgress">×</button>
        </header>
        <section class="copy-progress-body">
          <div class="copy-progress-file-icon" aria-hidden="true">
            <span></span>
          </div>
          <div class="copy-progress-main">
            <p class="copy-progress-stage">{{ copyProgressCurrentText }}</p>
            <div class="copy-progress-bar" aria-hidden="true">
              <span></span>
            </div>
            <dl class="copy-progress-details">
              <div
                v-for="detail in copyProgressDetails"
                :key="detail.label"
                class="copy-progress-detail-row"
                :class="{ path: detail.path }"
              >
                <dt>{{ detail.label }}</dt>
                <dd>{{ detail.value }}</dd>
              </div>
            </dl>
          </div>
        </section>
        <footer>
          <span v-if="activeRun">任务 #{{ activeRun.id }} · {{ copyProgressOperationText }}</span>
          <span v-else>{{ copyMessage || "正在提交" }}</span>
          <button
            v-if="activeRun"
            class="ghost-button compact-button danger-button"
            type="button"
            @click="$emit('cancelCopy', activeRun.id)"
          >
            取消
          </button>
        </footer>
      </aside>
    </template>
  </section>
</template>
