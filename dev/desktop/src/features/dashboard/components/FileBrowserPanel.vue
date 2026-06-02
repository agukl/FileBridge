<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { FileEntry, FileListing, FileMoveRequest, TaskCard } from "../../../app/api/types";
import { systemFileClipboardApi } from "../../../app/runtime/systemFileClipboard";
import { systemFileOpenApi } from "../../../app/runtime/systemFileOpen";
import { displayTime, formatBytes } from "../../../shared/formatters";

type SortKey = "name" | "type" | "size" | "modifiedAt";
type ViewMode = "details" | "tiles";
type ContextMenuState = {
  open: boolean;
  x: number;
  y: number;
  entry: FileEntry | null;
};
type BrowserDragPayload = {
  sourceId: string;
  paths: string[];
};
type BrowserPointerDrag = {
  payload: BrowserDragPayload;
  entry: FileEntry;
  pointerId: number;
  startX: number;
  startY: number;
  x: number;
  y: number;
  active: boolean;
  moved: boolean;
};
type BrowserNotice = {
  key: string;
  title: string;
  message: string;
  tone: "info" | "warning";
};

const BROWSER_DRAG_THRESHOLD = 6;
const BROWSER_LONG_PRESS_MS = 420;

const props = defineProps<{
  sources: TaskCard[];
  selectedSourceId: string;
  listing: FileListing | null;
  loading: boolean;
  error: string;
}>();

const emit = defineEmits<{
  selectSource: [sourceId: string];
  openPath: [path: string];
  parent: [];
  refresh: [];
  moveFiles: [payload: FileMoveRequest];
}>();

const selectedSource = computed(() => (
  props.sources.find((source) => source.task.taskId === props.selectedSourceId) ?? null
));

const searchText = ref("");
const addressText = ref("");
const selectedEntryPath = ref("");
const sortKey = ref<SortKey>("name");
const sortDirection = ref<"asc" | "desc">("asc");
const viewMode = ref<ViewMode>("details");
const cacheTooltipVisible = ref(false);
const refreshHintArmed = ref(false);
const clipboardBusy = ref(false);
const clipboardMessage = ref("");
const clipboardMessageTone = ref<BrowserNotice["tone"]>("info");
const contextMenu = ref<ContextMenuState>({ open: false, x: 0, y: 0, entry: null });
const fileAreaRef = ref<HTMLElement | null>(null);
const browserDrag = ref<BrowserPointerDrag | null>(null);
const dragOriginPaths = ref<Set<string>>(new Set());
const dragOverTargetPath = ref("");
const suppressEntryClick = ref(false);
let cacheTooltipTimer: ReturnType<typeof setTimeout> | null = null;
let clipboardMessageTimer: ReturnType<typeof setTimeout> | null = null;
let entryClickSuppressTimer: ReturnType<typeof setTimeout> | null = null;
let refreshAfterDragTimer: ReturnType<typeof setTimeout> | null = null;
let browserLongPressTimer: ReturnType<typeof setTimeout> | null = null;

const currentPath = computed(() => props.listing?.path || selectedSource.value?.task.sourcePath || "/");
const canGoParent = computed(() => Boolean(props.listing?.parentPath));
const isLocalSource = computed(() => ["LOCAL", "SMB"].includes(selectedSource.value?.task.sourceType ?? ""));
const sourceRootPath = computed(() => selectedSource.value?.task.sourcePath || "/");
const breadcrumbs = computed(() => buildBreadcrumbs());
const childDirectories = computed(() => (
  (props.listing?.entries ?? [])
    .filter((entry) => entry.type === "DIRECTORY")
    .slice()
    .sort((left, right) => left.name.localeCompare(right.name, "zh-Hans-CN"))
));
const visibleEntries = computed(() => {
  const text = searchText.value.trim().toLocaleLowerCase();
  const entries = props.listing?.entries ?? [];
  const filtered = text
    ? entries.filter((entry) => entryMatches(entry, text))
    : entries;
  return filtered.slice().sort(compareEntries);
});
const directoryCount = computed(() => visibleEntries.value.filter((entry) => entry.type === "DIRECTORY").length);
const fileCount = computed(() => visibleEntries.value.filter((entry) => entry.type === "FILE").length);
const selectedEntry = computed(() => (
  visibleEntries.value.find((entry) => entry.path === selectedEntryPath.value) ?? null
));
const contextMenuStyle = computed(() => ({
  left: `${contextMenu.value.x}px`,
  top: `${contextMenu.value.y}px`
}));
const browserDragStyle = computed(() => {
  const session = browserDrag.value;
  return session
    ? { transform: `translate3d(${session.x + 14}px, ${session.y + 14}px, 0)` }
    : {};
});
const browserDragTitle = computed(() => {
  const session = browserDrag.value;
  if (!session) {
    return "";
  }
  return session.entry.name || session.entry.path;
});
const browserDragHint = computed(() => (
  dragOverTargetPath.value
    ? `移动到 ${dragOverTargetPath.value}`
    : "拖到文件夹中移动"
));
const browserNotices = computed<BrowserNotice[]>(() => {
  const notices: BrowserNotice[] = [];
  const pageError = props.error.trim();
  if (pageError) {
    notices.push({
      key: `browser-error:${pageError}`,
      title: "文件浏览错误",
      message: pageError,
      tone: "warning"
    });
  }
  if (clipboardMessage.value) {
    notices.push({
      key: `browser-message:${clipboardMessageTone.value}:${clipboardMessage.value}`,
      title: clipboardMessageTone.value === "warning" ? "文件操作失败" : "文件操作提示",
      message: clipboardMessage.value,
      tone: clipboardMessageTone.value
    });
  }
  return notices;
});
const statusText = computed(() => {
  if (!props.listing) {
    return "";
  }
  const base = `${visibleEntries.value.length} 项`;
  const detail = `文件夹 ${directoryCount.value} · 文件 ${fileCount.value}`;
  const selected = selectedEntry.value ? ` · 已选择 ${selectedEntry.value.name}` : "";
  if (visibleEntries.value.length === props.listing.entries.length) {
    return `${base} · ${detail}${selected}`;
  }
  return `${base} / ${props.listing.entries.length} 项 · ${detail}${selected}`;
});

function cacheNotice(listing: FileListing | null) {
  if (!listing?.cacheUsed) {
    return "";
  }
  const scannedAt = listing.cacheScannedAt ? `缓存时间：${displayTime(listing.cacheScannedAt)}。` : "";
  return `${listing.cacheMessage || "当前目录来自缓存，可能不是最新。"}${scannedAt}`;
}

function onRefreshClick() {
  refreshHintArmed.value = true;
  cacheTooltipVisible.value = false;
  emit("refresh");
}

function showCacheTooltip() {
  cacheTooltipVisible.value = true;
  if (cacheTooltipTimer) {
    window.clearTimeout(cacheTooltipTimer);
  }
  cacheTooltipTimer = window.setTimeout(() => {
    cacheTooltipVisible.value = false;
    cacheTooltipTimer = null;
  }, 3600);
}

watch(
  () => [props.selectedSourceId, currentPath.value],
  () => {
    searchText.value = "";
    selectedEntryPath.value = "";
    addressText.value = currentPath.value;
  },
  { immediate: true }
);

watch(
  () => [props.loading, props.listing?.path, props.listing?.cacheUsed, props.listing?.cacheMessage, props.listing?.cacheScannedAt],
  () => {
    if (props.loading || !refreshHintArmed.value) {
      return;
    }
    refreshHintArmed.value = false;
    if (props.listing?.cacheUsed) {
      showCacheTooltip();
    }
  }
);

onBeforeUnmount(() => {
  if (cacheTooltipTimer) {
    window.clearTimeout(cacheTooltipTimer);
  }
  if (clipboardMessageTimer) {
    window.clearTimeout(clipboardMessageTimer);
  }
  if (entryClickSuppressTimer) {
    window.clearTimeout(entryClickSuppressTimer);
  }
  if (refreshAfterDragTimer) {
    window.clearTimeout(refreshAfterDragTimer);
  }
  clearBrowserLongPressTimer();
  removeBrowserDragListeners();
});

function onSourceChange(event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (target?.value) {
    emit("selectSource", target.value);
  }
}

function submitAddress() {
  const nextPath = addressText.value.trim();
  if (nextPath && nextPath !== currentPath.value) {
    emit("openPath", nextPath);
  }
}

function selectEntry(entry: FileEntry) {
  selectedEntryPath.value = entry.path;
}

async function activateEntry(entry: FileEntry) {
  selectEntry(entry);
  if (entry.type === "DIRECTORY") {
    emit("openPath", entry.path);
    return;
  }
  await openEntryFile(entry);
}

async function openEntryFile(entry: FileEntry) {
  if (entry.type !== "FILE") {
    return;
  }
  if (!isLocalSource.value) {
    setClipboardMessage("远端 FTP 文件需要先复制到本地后打开。", true);
    return;
  }
  try {
    await systemFileOpenApi.open(entry.path);
    setClipboardMessage(`已打开：${entry.name}`);
  } catch (ex) {
    setClipboardMessage(ex instanceof Error ? ex.message : "打开文件失败。", true);
  }
}

function onEntryClick(event: MouseEvent, entry: FileEntry) {
  if (suppressEntryClick.value) {
    event.preventDefault();
    event.stopPropagation();
    suppressEntryClick.value = false;
    return;
  }
  selectEntry(entry);
}

function onBrowserEntryPointerDown(entry: FileEntry, event: PointerEvent) {
  if (shouldIgnoreBrowserDrag(event)) {
    return;
  }
  const source = selectedSource.value;
  if (!source || !props.listing) {
    return;
  }
  hideContextMenu();
  selectEntry(entry);
  browserDrag.value = {
    payload: {
      sourceId: source.task.taskId,
      paths: [entry.path]
    },
    entry,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    x: event.clientX,
    y: event.clientY,
    active: false,
    moved: false
  };
  (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
  scheduleBrowserLongPress();
  window.addEventListener("pointermove", onBrowserEntryPointerMove);
  window.addEventListener("pointerup", onBrowserEntryPointerUp);
  window.addEventListener("pointercancel", onBrowserEntryPointerCancel);
}

function shouldIgnoreBrowserDrag(event: PointerEvent) {
  if (event.button !== 0) {
    return true;
  }
  const target = event.target;
  return target instanceof Element && Boolean(target.closest("input, select, textarea, .explorer-context-menu"));
}

function onBrowserEntryPointerMove(event: PointerEvent) {
  const session = browserDrag.value;
  if (!session || event.pointerId !== session.pointerId) {
    return;
  }
  const distance = Math.hypot(event.clientX - session.startX, event.clientY - session.startY);
  if (!session.active && distance < BROWSER_DRAG_THRESHOLD) {
    browserDrag.value = {
      ...session,
      x: event.clientX,
      y: event.clientY
    };
    return;
  }
  clearBrowserLongPressTimer();
  if (!session.active && !startBrowserDrag(session, event.clientX, event.clientY, true)) {
    event.preventDefault();
    return;
  }
  const activeSession = browserDrag.value;
  if (!activeSession || event.pointerId !== activeSession.pointerId) {
    return;
  }
  const targetPath = resolveBrowserDropTargetAtPoint(event.clientX, event.clientY, activeSession.payload);
  dragOverTargetPath.value = targetPath;
  browserDrag.value = {
    ...activeSession,
    active: true,
    x: event.clientX,
    y: event.clientY,
    moved: activeSession.moved || distance >= BROWSER_DRAG_THRESHOLD
  };
  event.preventDefault();
}

function scheduleBrowserLongPress() {
  clearBrowserLongPressTimer();
  browserLongPressTimer = window.setTimeout(() => {
    browserLongPressTimer = null;
    const session = browserDrag.value;
    if (!session || session.active) {
      return;
    }
    startBrowserDrag(session, session.x, session.y, false);
  }, BROWSER_LONG_PRESS_MS);
}

function clearBrowserLongPressTimer() {
  if (!browserLongPressTimer) {
    return;
  }
  window.clearTimeout(browserLongPressTimer);
  browserLongPressTimer = null;
}

function startBrowserDrag(session: BrowserPointerDrag, clientX: number, clientY: number, moved: boolean) {
  const source = selectedSource.value;
  if (!source?.task.permission?.canRead || !source.task.permission?.canWrite) {
    setClipboardMessage("当前文件源需要同时具备读取和写入权限，才能拖动移动。", true);
    removeBrowserDragListeners();
    browserDrag.value = null;
    clearBrowserDragState();
    return false;
  }
  dragOriginPaths.value = new Set(session.payload.paths);
  dragOverTargetPath.value = resolveBrowserDropTargetAtPoint(clientX, clientY, session.payload);
  browserDrag.value = {
    ...session,
    active: true,
    x: clientX,
    y: clientY,
    moved: session.moved || moved
  };
  return true;
}

function onBrowserEntryPointerUp(event: PointerEvent) {
  finishBrowserDrag(event, false);
}

function onBrowserEntryPointerCancel(event: PointerEvent) {
  finishBrowserDrag(event, true);
}

function finishBrowserDrag(event: PointerEvent, cancelled: boolean) {
  const session = browserDrag.value;
  if (!session || event.pointerId !== session.pointerId) {
    return;
  }
  clearBrowserLongPressTimer();
  removeBrowserDragListeners();
  browserDrag.value = null;
  if (!session.active) {
    clearBrowserDragState();
    return;
  }
  if (!session.moved) {
    clearBrowserDragState();
    event.preventDefault();
    return;
  }
  suppressNextEntryClick();
  const targetPath = cancelled
    ? ""
    : dragOverTargetPath.value || resolveBrowserDropTargetAtPoint(event.clientX, event.clientY, session.payload);
  if (targetPath) {
    emit("moveFiles", {
      sourceId: session.payload.sourceId,
      sourcePaths: session.payload.paths,
      targetDirectory: targetPath,
      conflictPolicy: "SKIP"
    });
    setClipboardMessage(`已提交拖拽移动：${session.entry.name} -> ${targetPath}`);
    scheduleRefreshAfterDrag();
  } else if (!cancelled) {
    setClipboardMessage("拖拽未完成：请释放到文件夹、目录树或面包屑上。", true);
  }
  clearBrowserDragState();
  event.preventDefault();
}

function removeBrowserDragListeners() {
  clearBrowserLongPressTimer();
  window.removeEventListener("pointermove", onBrowserEntryPointerMove);
  window.removeEventListener("pointerup", onBrowserEntryPointerUp);
  window.removeEventListener("pointercancel", onBrowserEntryPointerCancel);
}

function clearBrowserDragState() {
  dragOriginPaths.value = new Set();
  dragOverTargetPath.value = "";
}

function suppressNextEntryClick() {
  suppressEntryClick.value = true;
  if (entryClickSuppressTimer) {
    window.clearTimeout(entryClickSuppressTimer);
  }
  entryClickSuppressTimer = window.setTimeout(() => {
    suppressEntryClick.value = false;
    entryClickSuppressTimer = null;
  }, 140);
}

function resolveBrowserDropTargetAtPoint(clientX: number, clientY: number, payload: BrowserDragPayload) {
  if (!Number.isFinite(clientX) || !Number.isFinite(clientY)) {
    return "";
  }
  const hit = document.elementFromPoint(clientX, clientY);
  const target = hit instanceof Element
    ? hit.closest<HTMLElement>("[data-browser-drop-path]")
    : null;
  const path = target?.dataset.browserDropPath || "";
  return isValidBrowserDropTarget(path, payload) ? path : "";
}

function isValidBrowserDropTarget(targetPath: string, payload: BrowserDragPayload) {
  if (!targetPath) {
    return false;
  }
  return payload.paths.every((sourcePath) => !isSameOrNestedPath(targetPath, sourcePath));
}

function isSameOrNestedPath(targetPath: string, sourcePath: string) {
  const target = normalizeComparablePath(targetPath);
  const source = normalizeComparablePath(sourcePath);
  return target === source || target.startsWith(`${source}/`);
}

function normalizeComparablePath(path: string) {
  return path.replace(/\\/g, "/").replace(/\/+$/g, "").toLocaleLowerCase() || "/";
}

function scheduleRefreshAfterDrag() {
  if (refreshAfterDragTimer) {
    window.clearTimeout(refreshAfterDragTimer);
  }
  refreshAfterDragTimer = window.setTimeout(() => {
    refreshAfterDragTimer = null;
    emit("refresh");
  }, 1800);
}

function openEntryContextMenu(event: MouseEvent, entry: FileEntry) {
  event.preventDefault();
  event.stopPropagation();
  selectEntry(entry);
  showContextMenu(event, entry);
}

function openBlankContextMenu(event: MouseEvent) {
  event.preventDefault();
  const target = event.target as HTMLElement | null;
  if (target?.closest(".explorer-row, .explorer-tile, .explorer-details-header")) {
    return;
  }
  showContextMenu(event, null);
}

function showContextMenu(event: MouseEvent, entry: FileEntry | null) {
  const anchor = (event.currentTarget as HTMLElement | null)?.closest(".explorer-file-area")
    ?? (event.currentTarget as HTMLElement | null);
  const rect = anchor?.getBoundingClientRect();
  const localX = rect ? event.clientX - rect.left + (anchor?.scrollLeft ?? 0) : event.offsetX;
  const localY = rect ? event.clientY - rect.top + (anchor?.scrollTop ?? 0) : event.offsetY;
  contextMenu.value = {
    open: true,
    x: Math.max(10, localX + 8),
    y: Math.max(10, localY + 8),
    entry
  };
}

function hideContextMenu() {
  contextMenu.value = { ...contextMenu.value, open: false };
}

function selectedOrContextEntry() {
  return contextMenu.value.entry ?? selectedEntry.value;
}

async function copyEntryToSystem(cut = false) {
  const entry = selectedOrContextEntry();
  hideContextMenu();
  if (!entry) {
    setClipboardMessage("请先选择一个文件或文件夹。", true);
    return;
  }
  if (!isLocalSource.value) {
    setClipboardMessage("系统文件剪贴板只支持本地或 SMB 文件源。", true);
    return;
  }
  clipboardBusy.value = true;
  try {
    const status = await systemFileClipboardApi.write([entry.path], cut);
    setClipboardMessage(status.message);
  } catch (ex) {
    setClipboardMessage(ex instanceof Error ? ex.message : "写入系统剪贴板失败。", true);
  } finally {
    clipboardBusy.value = false;
  }
}

async function pasteFromSystem() {
  hideContextMenu();
  if (!isLocalSource.value) {
    setClipboardMessage("系统粘贴只支持本地或 SMB 文件源。", true);
    return;
  }
  clipboardBusy.value = true;
  try {
    const result = await systemFileClipboardApi.paste(currentPath.value);
    setClipboardMessage(result.message);
    emit("refresh");
  } catch (ex) {
    setClipboardMessage(ex instanceof Error ? ex.message : "从系统剪贴板粘贴失败。", true);
  } finally {
    clipboardBusy.value = false;
  }
}

async function copyPathText() {
  const entry = selectedOrContextEntry();
  hideContextMenu();
  const path = entry?.path || currentPath.value;
  try {
    await navigator.clipboard.writeText(path);
    setClipboardMessage("已复制路径。");
  } catch {
    setClipboardMessage("复制路径失败。", true);
  }
}

function setClipboardMessage(message: string, isError = false) {
  clipboardMessageTone.value = isError ? "warning" : "info";
  clipboardMessage.value = isError ? `操作失败：${message}` : message;
  if (clipboardMessageTimer) {
    window.clearTimeout(clipboardMessageTimer);
  }
  clipboardMessageTimer = window.setTimeout(() => {
    clipboardMessage.value = "";
    clipboardMessageTimer = null;
  }, 3600);
}

function setSort(key: SortKey) {
  if (sortKey.value === key) {
    sortDirection.value = sortDirection.value === "asc" ? "desc" : "asc";
    return;
  }
  sortKey.value = key;
  sortDirection.value = "asc";
}

function sortIndicator(key: SortKey) {
  if (sortKey.value !== key) {
    return "";
  }
  return sortDirection.value === "asc" ? "↑" : "↓";
}

function entryMatches(entry: FileEntry, text: string) {
  return [
    entry.name,
    entry.path,
    typeText(entry),
    fileTypeText(entry)
  ].some((value) => value.toLocaleLowerCase().includes(text));
}

function compareEntries(left: FileEntry, right: FileEntry) {
  const direction = sortDirection.value === "asc" ? 1 : -1;
  const leftDir = left.type === "DIRECTORY";
  const rightDir = right.type === "DIRECTORY";
  if (leftDir !== rightDir) {
    return leftDir ? -1 : 1;
  }
  const result = compareBySortKey(left, right);
  return result === 0 ? left.name.localeCompare(right.name, "zh-Hans-CN") : result * direction;
}

function compareBySortKey(left: FileEntry, right: FileEntry) {
  if (sortKey.value === "type") {
    return fileTypeText(left).localeCompare(fileTypeText(right), "zh-Hans-CN");
  }
  if (sortKey.value === "size") {
    return left.size - right.size;
  }
  if (sortKey.value === "modifiedAt") {
    return timeValue(left.modifiedAt) - timeValue(right.modifiedAt);
  }
  return left.name.localeCompare(right.name, "zh-Hans-CN");
}

function timeValue(value: string) {
  const parsed = Date.parse(value || "");
  return Number.isFinite(parsed) ? parsed : 0;
}

function typeText(entry: FileEntry) {
  if (entry.type === "DIRECTORY") {
    return "文件夹";
  }
  if (entry.type === "FILE") {
    return "文件";
  }
  return "其他";
}

function fileTypeText(entry: FileEntry) {
  if (entry.type === "DIRECTORY") {
    return "文件夹";
  }
  if (entry.type === "OTHER") {
    return "其他项目";
  }
  const extension = entry.name.includes(".")
    ? entry.name.split(".").pop()?.trim()
    : "";
  return extension ? `${extension.toLocaleUpperCase()} 文件` : "文件";
}

function buildBreadcrumbs() {
  const source = selectedSource.value?.task;
  if (!source) {
    return [];
  }
  const rootPath = sourceRootPath.value;
  const rootLabel = source.taskName || source.taskId || lastPathSegment(rootPath) || "根目录";
  if (isLocalSource.value) {
    return buildLocalBreadcrumbs(rootPath, currentPath.value, rootLabel);
  }
  return buildRemoteBreadcrumbs(rootPath, currentPath.value, rootLabel);
}

function buildRemoteBreadcrumbs(rootPath: string, path: string, rootLabel: string) {
  const root = normalizeRemotePath(rootPath);
  const current = normalizeRemotePath(path);
  const rootParts = pathParts(root, "/");
  const currentParts = pathParts(current, "/");
  const relativeParts = currentParts.slice(rootParts.length);
  const crumbs = [{ label: rootLabel, path: root, current: current === root }];
  let nextPath = root;
  for (const part of relativeParts) {
    nextPath = nextPath === "/" ? `/${part}` : `${nextPath}/${part}`;
    crumbs.push({ label: part, path: nextPath, current: nextPath === current });
  }
  return crumbs;
}

function buildLocalBreadcrumbs(rootPath: string, path: string, rootLabel: string) {
  const root = normalizeLocalPath(rootPath);
  const current = normalizeLocalPath(path);
  const prefix = root.toLocaleLowerCase();
  const currentCompare = current.toLocaleLowerCase();
  const relative = currentCompare.startsWith(`${prefix}\\`)
    ? current.slice(root.length).replace(/^\\+/, "")
    : "";
  const crumbs = [{ label: rootLabel, path: root, current: currentCompare === prefix }];
  let nextPath = root;
  for (const part of pathParts(relative, "\\")) {
    nextPath = `${nextPath}\\${part}`;
    crumbs.push({ label: part, path: nextPath, current: nextPath.toLocaleLowerCase() === currentCompare });
  }
  return crumbs;
}

function normalizeRemotePath(path: string) {
  const normalized = (path || "/").replace(/\\/g, "/").replace(/\/+$/g, "");
  return normalized ? (normalized.startsWith("/") ? normalized : `/${normalized}`) : "/";
}

function normalizeLocalPath(path: string) {
  return (path || "").replace(/\//g, "\\").replace(/\\+$/g, "");
}

function pathParts(path: string, separator: "/" | "\\") {
  return path.split(separator).filter(Boolean);
}

function lastPathSegment(path: string) {
  const parts = path.replace(/\\/g, "/").split("/").filter(Boolean);
  return parts[parts.length - 1] || path;
}
</script>

<template>
  <section class="file-browser-panel">
    <div v-if="browserNotices.length" class="copy-toast-list explorer-toast-list" role="status" aria-live="polite">
      <article v-for="notice in browserNotices" :key="notice.key" class="copy-toast" :class="`tone-${notice.tone}`">
        <strong>{{ notice.title }}</strong>
        <p>{{ notice.message }}</p>
      </article>
    </div>

    <section v-if="!sources.length" class="empty-board">
      <span class="eyebrow">File Browser</span>
      <h2>还没有文件源</h2>
      <p>先创建一个文件源，再浏览目录和文件。远程 FTP 与本机目录都会在这里统一展示。</p>
    </section>

    <template v-else>
      <div class="explorer-shell">
        <aside class="explorer-sidebar" aria-label="文件源与目录">
          <section class="explorer-side-section">
            <h3>文件源</h3>
            <button
              v-for="source in sources"
              :key="source.task.taskId"
              type="button"
              class="explorer-source-item"
              :class="{ selected: source.task.taskId === selectedSourceId }"
              @click="$emit('selectSource', source.task.taskId)"
            >
              <span class="explorer-source-icon" :class="{ remote: source.task.sourceType === 'REMOTE_FTP' }"></span>
              <span>
                <strong>{{ source.task.taskName || source.task.taskId }}</strong>
                <small>{{ source.task.sourceType === "LOCAL" || source.task.sourceType === "SMB" ? source.task.sourcePath : `${source.task.ftpHost}:${source.task.ftpPort}` }}</small>
              </span>
            </button>
          </section>

          <section v-if="listing" class="explorer-side-section">
            <h3>目录</h3>
            <button
              v-for="(crumb, index) in breadcrumbs"
              :key="crumb.path"
              type="button"
              class="explorer-tree-item"
              :class="{ selected: crumb.current, 'drop-active': dragOverTargetPath === crumb.path }"
              :style="{ paddingLeft: `${10 + index * 18}px` }"
              :data-browser-drop-path="crumb.current ? undefined : crumb.path"
              :disabled="loading || crumb.current"
              @click="$emit('openPath', crumb.path)"
            >
              <span class="entry-icon directory"></span>
              <span>{{ crumb.label }}</span>
            </button>
            <button
              v-for="folder in childDirectories"
              :key="folder.path"
              type="button"
              class="explorer-tree-item child"
              :class="{ 'drop-active': dragOverTargetPath === folder.path }"
              :style="{ paddingLeft: `${10 + breadcrumbs.length * 18}px` }"
              :data-browser-drop-path="folder.path"
              :disabled="loading"
              @click="$emit('openPath', folder.path)"
            >
              <span class="entry-icon directory"></span>
              <span>{{ folder.name }}</span>
            </button>
          </section>
        </aside>

        <article class="explorer-main" aria-label="文件列表">
          <header class="explorer-command-bar">
            <div class="explorer-nav-buttons">
              <button class="icon-button" type="button" title="返回上级" :disabled="loading || !canGoParent" @click="$emit('parent')">
                ↑
              </button>
              <span class="tooltip-anchor">
                <button class="icon-button" type="button" title="刷新" :disabled="loading" @click="onRefreshClick">
                  ↻
                </button>
                <span v-if="cacheTooltipVisible" class="cache-refresh-tooltip" role="status">
                  {{ cacheNotice(listing) }}
                </span>
              </span>
            </div>

            <form class="explorer-address-form" @submit.prevent="submitAddress">
              <nav v-if="breadcrumbs.length" class="explorer-breadcrumbs" aria-label="当前路径">
                <button
                  v-for="crumb in breadcrumbs"
                  :key="crumb.path"
                  type="button"
                  :class="{ 'drop-active': dragOverTargetPath === crumb.path }"
                  :data-browser-drop-path="crumb.current ? undefined : crumb.path"
                  :disabled="loading || crumb.current"
                  @click="$emit('openPath', crumb.path)"
                >
                  {{ crumb.label }}
                </button>
              </nav>
              <input
                v-model="addressText"
                type="text"
                aria-label="地址栏"
                spellcheck="false"
              />
            </form>

            <select class="filter-select explorer-source-select" :value="selectedSourceId" aria-label="文件源" @change="onSourceChange">
              <option v-for="source in sources" :key="source.task.taskId" :value="source.task.taskId">
                {{ source.task.taskName || source.task.taskId }}
              </option>
            </select>
          </header>

          <section class="explorer-subbar">
            <div class="explorer-tools">
              <input
                v-model.trim="searchText"
                class="filter-input"
                type="search"
                placeholder="搜索当前文件夹"
                aria-label="搜索当前文件夹"
              />
              <div class="segmented-control" aria-label="视图">
                <button type="button" :class="{ selected: viewMode === 'details' }" title="详细信息" @click="viewMode = 'details'">
                  ≡
                </button>
                <button type="button" :class="{ selected: viewMode === 'tiles' }" title="平铺" @click="viewMode = 'tiles'">
                  ▦
                </button>
              </div>
            </div>
          </section>

          <p v-if="loading" class="loading-note">正在读取文件...</p>

          <section
            v-if="listing && !loading"
            ref="fileAreaRef"
            class="explorer-file-area"
            tabindex="0"
            @click="hideContextMenu"
            @contextmenu="openBlankContextMenu"
            @keydown.ctrl.c.prevent.stop="copyEntryToSystem(false)"
            @keydown.ctrl.x.prevent.stop="copyEntryToSystem(true)"
            @keydown.ctrl.v.prevent.stop="pasteFromSystem"
          >
            <div v-if="viewMode === 'details'" class="explorer-details-view">
              <div class="explorer-details-header">
                <button type="button" @click="setSort('name')">名称 {{ sortIndicator("name") }}</button>
                <button type="button" @click="setSort('modifiedAt')">修改日期 {{ sortIndicator("modifiedAt") }}</button>
                <button type="button" @click="setSort('type')">类型 {{ sortIndicator("type") }}</button>
                <button type="button" @click="setSort('size')">大小 {{ sortIndicator("size") }}</button>
              </div>

              <div
                v-for="entry in visibleEntries"
                :key="entry.path"
                class="explorer-row"
                :class="{ selected: selectedEntryPath === entry.path, directory: entry.type === 'DIRECTORY', 'drag-origin': dragOriginPaths.has(entry.path), 'drop-active': dragOverTargetPath === entry.path }"
                :data-browser-drop-path="entry.type === 'DIRECTORY' ? entry.path : undefined"
                :title="entry.path"
                role="button"
                tabindex="0"
                @click="onEntryClick($event, entry)"
                @contextmenu="openEntryContextMenu($event, entry)"
                @dblclick="activateEntry(entry)"
                @pointerdown="onBrowserEntryPointerDown(entry, $event)"
                @keydown.enter.prevent="activateEntry(entry)"
              >
                <span class="explorer-name-cell">
                  <span class="entry-icon" :class="entry.type.toLocaleLowerCase()"></span>
                  <strong>{{ entry.name }}</strong>
                </span>
                <span>{{ displayTime(entry.modifiedAt) }}</span>
                <span>{{ fileTypeText(entry) }}</span>
                <span>{{ entry.type === "FILE" ? formatBytes(entry.size) : "" }}</span>
              </div>
            </div>

            <div v-else class="explorer-tiles-view">
              <button
                v-for="entry in visibleEntries"
                :key="entry.path"
                type="button"
                class="explorer-tile"
                :class="{ selected: selectedEntryPath === entry.path, 'drag-origin': dragOriginPaths.has(entry.path), 'drop-active': dragOverTargetPath === entry.path }"
                :data-browser-drop-path="entry.type === 'DIRECTORY' ? entry.path : undefined"
                :title="entry.path"
                @click="onEntryClick($event, entry)"
                @contextmenu="openEntryContextMenu($event, entry)"
                @dblclick="activateEntry(entry)"
                @pointerdown="onBrowserEntryPointerDown(entry, $event)"
              >
                <span class="entry-icon large" :class="entry.type.toLocaleLowerCase()"></span>
                <strong>{{ entry.name }}</strong>
                <small>{{ entry.type === "FILE" ? formatBytes(entry.size) : fileTypeText(entry) }}</small>
              </button>
            </div>

            <p v-if="!visibleEntries.length" class="empty-text">
              {{ listing.entries.length ? "没有匹配的文件。" : "当前文件夹为空。" }}
            </p>

            <div
              v-if="contextMenu.open"
              class="explorer-context-menu"
              :style="contextMenuStyle"
              @click.stop
              @contextmenu.prevent.stop
            >
              <button
                v-if="contextMenu.entry"
                type="button"
                @click="contextMenu.entry && activateEntry(contextMenu.entry)"
              >
                打开
              </button>
              <button
                type="button"
                :disabled="clipboardBusy || !contextMenu.entry || !isLocalSource"
                @click="copyEntryToSystem(false)"
              >
                复制
              </button>
              <button
                type="button"
                :disabled="clipboardBusy || !contextMenu.entry || !isLocalSource"
                @click="copyEntryToSystem(true)"
              >
                剪切
              </button>
              <button type="button" :disabled="clipboardBusy" @click="copyPathText">
                复制路径
              </button>
              <span class="context-menu-separator"></span>
              <button type="button" :disabled="clipboardBusy || !isLocalSource" @click="pasteFromSystem">
                粘贴
              </button>
              <button type="button" :disabled="loading" @click="hideContextMenu(); $emit('refresh')">
                刷新
              </button>
            </div>
          </section>

          <div v-if="browserDrag?.active" class="explorer-drag-ghost" :style="browserDragStyle" aria-hidden="true">
            <strong>{{ browserDragTitle }}</strong>
            <span>{{ browserDragHint }}</span>
          </div>

          <footer v-if="listing" class="explorer-status-bar">
            <span>{{ statusText }}</span>
            <span v-if="listing.cacheUsed">{{ cacheNotice(listing) }}</span>
          </footer>
        </article>
      </div>
    </template>
  </section>
</template>
