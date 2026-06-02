import { computed, ref, shallowRef, watch, type ComputedRef, type Ref } from "vue";
import { fileOperationsApi, fileSourcesApi, runsApi } from "../../../app/api/client";
import type { AgentConfig, FileCopyRequest, FileListing, FileMoveRequest, SyncRun, TaskCard } from "../../../app/api/types";
import { formatBytes, formatNumber } from "../../../shared/formatters";

type CopyPaneSide = "left" | "right";

const COPY_PANE_LIST_LIMIT = 1000;
const COPY_PANE_CACHE_LIMIT = 80;

export function useFileCopy(
  config: Ref<AgentConfig>,
  fileSources: ComputedRef<TaskCard[]>,
  selectedSourceId: Ref<string>,
  runs: Ref<SyncRun[]>,
  refreshRuns: () => Promise<void>,
  fileError: Ref<string>
) {
  const copyLeftSourceId = ref("");
  const copyRightSourceId = ref("");
  const copyLeftListing = shallowRef<FileListing | null>(null);
  const copyRightListing = shallowRef<FileListing | null>(null);
  const copyLeftLoading = ref(false);
  const copyRightLoading = ref(false);
  const copyLeftError = ref("");
  const copyRightError = ref("");
  const copyMessage = ref("");
  const fileCopying = ref(false);
  const trackedCopyOperationId = ref<number | null>(null);
  const refreshedCopyOperationIds = new Set<number>();
  const listingCache = new Map<string, FileListing>();
  const paneRequestSeq: Record<CopyPaneSide, number> = {
    left: 0,
    right: 0
  };

  const activeCopyRun = computed(() => (
    runs.value.find((run) => isCopyProgressRun(run) && run.state === "RUNNING") ?? null
  ));

  function ensureCopySources() {
    if (!fileSources.value.length) {
      copyLeftSourceId.value = "";
      copyRightSourceId.value = "";
      copyLeftListing.value = null;
      copyRightListing.value = null;
      return;
    }
    if (!fileSources.value.some((source) => source.task.taskId === copyLeftSourceId.value)) {
      copyLeftSourceId.value = selectedSourceId.value || fileSources.value[0].task.taskId;
      copyLeftListing.value = null;
    }
    const writableSources = fileSources.value.filter((source) => source.task.permission?.canWrite);
    if (!writableSources.length) {
      if (copyRightSourceId.value) {
        copyRightSourceId.value = "";
        copyRightListing.value = null;
      }
      copyRightError.value = "没有可写目标文件源";
      return;
    }
    if (!writableSources.some((source) => source.task.taskId === copyRightSourceId.value)) {
      const writableDifferent = writableSources.find((source) => (
        source.task.taskId !== copyLeftSourceId.value && source.task.permission?.canWrite
      ));
      copyRightSourceId.value = (writableDifferent ?? writableSources[0]).task.taskId;
      copyRightListing.value = null;
    }
  }

  async function ensureCopyPaneLoaded() {
    ensureCopySources();
    await Promise.all([
      copyLeftSourceId.value && !copyLeftListing.value
        ? loadCopyPane("left", copyLeftSourceId.value)
        : Promise.resolve(),
      copyRightSourceId.value && !copyRightListing.value
        ? loadCopyPane("right", copyRightSourceId.value)
        : Promise.resolve()
    ]);
  }

  async function selectCopyPaneSource(side: CopyPaneSide, sourceId: string) {
    const source = fileSources.value.find((item) => item.task.taskId === sourceId);
    if (side === "right" && !source?.task.permission?.canWrite) {
      copyRightError.value = "目标只能选择可写文件源";
      return;
    }
    if (side === "left") {
      copyLeftSourceId.value = sourceId;
      copyLeftListing.value = null;
    } else {
      copyRightSourceId.value = sourceId;
      copyRightListing.value = null;
    }
    await loadCopyPane(side, sourceId);
  }

  async function loadCopyPane(side: CopyPaneSide, sourceId: string, path?: string) {
    const source = fileSources.value.find((item) => item.task.taskId === sourceId)?.task;
    if (!source) {
      if (side === "left") {
        copyLeftError.value = "来源文件源不存在";
      } else {
        copyRightError.value = "目标文件源不存在";
      }
      return;
    }
    const currentListing = side === "left" ? copyLeftListing.value : copyRightListing.value;
    const nextPath = path || currentListing?.path || source.sourcePath || "/";
    const cacheKey = copyListingCacheKey(sourceId, nextPath);
    const cachedListing = listingCache.get(cacheKey) ?? null;
    const requestId = ++paneRequestSeq[side];
    if (cachedListing) {
      setCopyListing(side, cachedListing);
    }
    if (side === "left") {
      copyLeftLoading.value = true;
      copyLeftError.value = "";
    } else {
      copyRightLoading.value = true;
      copyRightError.value = "";
    }
    try {
      const result = await fileSourcesApi.files(config.value, sourceId, nextPath, COPY_PANE_LIST_LIMIT);
      if (requestId !== paneRequestSeq[side]) {
        return;
      }
      rememberCopyListing(cacheKey, result.listing);
      setCopyListing(side, result.listing);
    } catch (ex) {
      if (requestId !== paneRequestSeq[side]) {
        return;
      }
      const message = ex instanceof Error ? ex.message : "无法读取目录";
      if (side === "left") {
        copyLeftError.value = message;
        if (!cachedListing) {
          copyLeftListing.value = null;
        }
      } else {
        copyRightError.value = message;
        if (!cachedListing) {
          copyRightListing.value = null;
        }
      }
    } finally {
      if (requestId !== paneRequestSeq[side]) {
        return;
      }
      if (side === "left") {
        copyLeftLoading.value = false;
      } else {
        copyRightLoading.value = false;
      }
    }
  }

  async function loadCopyPaneLegacy(side: CopyPaneSide, sourceId: string, path?: string) {
    const source = fileSources.value.find((item) => item.task.taskId === sourceId)?.task;
    if (!source) {
      if (side === "left") {
        copyLeftError.value = "来源文件源不存在";
      } else {
        copyRightError.value = "目标文件源不存在";
      }
      return;
    }
    if (side === "left") {
      copyLeftLoading.value = true;
      copyLeftError.value = "";
    } else {
      copyRightLoading.value = true;
      copyRightError.value = "";
    }
    try {
      const result = await fileSourcesApi.files(config.value, sourceId, path || source.sourcePath || "/", 200);
      if (side === "left") {
        copyLeftListing.value = result.listing;
      } else {
        copyRightListing.value = result.listing;
      }
    } catch (ex) {
      if (side === "left") {
        copyLeftError.value = ex instanceof Error ? ex.message : "无法读取来源目录";
        copyLeftListing.value = null;
      } else {
        copyRightError.value = ex instanceof Error ? ex.message : "无法读取目标目录";
        copyRightListing.value = null;
      }
    } finally {
      if (side === "left") {
        copyLeftLoading.value = false;
      } else {
        copyRightLoading.value = false;
      }
    }
  }

  async function openCopyPanePath(side: CopyPaneSide, path?: string) {
    const sourceId = side === "left" ? copyLeftSourceId.value : copyRightSourceId.value;
    if (!sourceId) {
      return;
    }
    await loadCopyPane(side, sourceId, path);
  }

  async function copyFiles(payload: FileCopyRequest) {
    fileCopying.value = true;
    fileError.value = "";
    copyMessage.value = "";
    try {
      const result = await fileOperationsApi.copy(config.value, payload);
      trackedCopyOperationId.value = result.operationId;
      copyMessage.value = `复制已提交：#${result.operationId}，正在执行。`;
      await refreshRuns();
      await followCopyOperation(result.operationId);
    } catch (ex) {
      copyMessage.value = ex instanceof Error ? ex.message : "复制文件失败";
      fileError.value = copyMessage.value;
      await refreshRuns();
    } finally {
      fileCopying.value = false;
    }
  }

  async function moveFiles(payload: FileMoveRequest) {
    fileCopying.value = true;
    fileError.value = "";
    copyMessage.value = "";
    try {
      const result = await fileOperationsApi.move(config.value, payload);
      trackedCopyOperationId.value = result.operationId;
      copyMessage.value = `\u79fb\u52a8\u5df2\u63d0\u4ea4\uff1a#${result.operationId}\uff0c\u6b63\u5728\u6267\u884c\u3002`;
      await refreshRuns();
      await followCopyOperation(result.operationId);
    } catch (ex) {
      copyMessage.value = ex instanceof Error ? ex.message : "\u79fb\u52a8\u6587\u4ef6\u5931\u8d25";
      fileError.value = copyMessage.value;
      await refreshRuns();
    } finally {
      fileCopying.value = false;
    }
  }

  async function cancelCopyOperation(operationId: number) {
    try {
      await fileOperationsApi.cancel(config.value, operationId);
      copyMessage.value = `已请求取消复制任务：#${operationId}`;
      await refreshRuns();
    } catch (ex) {
      copyMessage.value = ex instanceof Error ? ex.message : "取消复制失败";
    }
  }

  watch(
    () => runs.value.map((run) => `${run.id}:${run.state}:${run.finalHealth}:${run.fileCount}:${run.directoryCount}:${run.totalBytes}:${run.warningCount}:${run.errorCount}`).join("|"),
    () => {
      if (!trackedCopyOperationId.value) {
        return;
      }
      const run = findRun(trackedCopyOperationId.value);
      if (run) {
        applyTrackedCopyRun(run);
      }
    }
  );

  return {
    copyLeftSourceId,
    copyRightSourceId,
    copyLeftListing,
    copyRightListing,
    copyLeftLoading,
    copyRightLoading,
    copyLeftError,
    copyRightError,
    copyMessage,
    fileCopying,
    activeCopyRun,
    ensureCopySources,
    ensureCopyPaneLoaded,
    selectCopyPaneSource,
    openCopyPanePath,
    copyFiles,
    moveFiles,
    cancelCopyOperation
  };

  async function followCopyOperation(operationId: number) {
    for (let attempt = 0; attempt < 500; attempt++) {
      const run = findRun(operationId) ?? await fetchRecentOperationRun(operationId);
      if (run) {
        applyTrackedCopyRun(run);
        if (isTerminalRun(run)) {
          return;
        }
      }
      await sleep(attempt < 8 ? 650 : 1200);
      await refreshRuns();
    }
  }

  async function fetchRecentOperationRun(operationId: number) {
    const result = await runsApi.list(config.value, {
      page: 1,
      pageSize: 100,
      scope: "operations",
      includeTotal: false
    });
    return (result.runs ?? []).find((run) => run.id === operationId) ?? null;
  }

  function findRun(operationId: number) {
    return runs.value.find((run) => run.id === operationId) ?? null;
  }

  function applyTrackedCopyRun(run: SyncRun) {
    copyMessage.value = copyRunMessage(run);
    if (!isTerminalRun(run) || refreshedCopyOperationIds.has(run.id)) {
      return;
    }
    refreshedCopyOperationIds.add(run.id);
    if (copyRightSourceId.value && run.targetTaskId === copyRightSourceId.value) {
      void loadCopyPane("right", copyRightSourceId.value, copyRightListing.value?.path || run.targetPath);
    }
  }

  function setCopyListing(side: CopyPaneSide, listing: FileListing) {
    if (side === "left") {
      copyLeftListing.value = listing;
    } else {
      copyRightListing.value = listing;
    }
  }

  function copyListingCacheKey(sourceId: string, path: string) {
    return `${sourceId}\u0000${path || "/"}`;
  }

  function rememberCopyListing(key: string, listing: FileListing) {
    listingCache.delete(key);
    listingCache.set(key, listing);
    while (listingCache.size > COPY_PANE_CACHE_LIMIT) {
      const oldestKey = listingCache.keys().next().value;
      if (!oldestKey) {
        return;
      }
      listingCache.delete(oldestKey);
    }
  }
}

function copyRunMessage(run: SyncRun) {
  const action = (run.operationType || "").toUpperCase() === "FILE_MOVE" ? "移动" : "复制";
  const summary = `文件 ${formatNumber(run.fileCount)} · 目录 ${formatNumber(run.directoryCount)} · ${formatBytes(run.totalBytes)}`;
  if (run.state === "RUNNING") {
    return `${action}进行中：#${run.id}，${summary}`;
  }
  if (run.state === "SUCCESS") {
    if (run.errorCount > 0 || run.finalHealth === "COMPLETED_WITH_ERRORS") {
      return `${action}完成但有错误：#${run.id}，${summary}，错误 ${formatNumber(run.errorCount)} 项`;
    }
    if (run.warningCount > 0 || run.finalHealth === "COMPLETED_WITH_WARNINGS") {
      return `${action}完成但有跳过：#${run.id}，${summary}，跳过 ${formatNumber(run.warningCount)} 项`;
    }
    return `${action}完成：#${run.id}，${summary}`;
  }
  if (run.state === "CANCELLED") {
    return `${action}已取消：#${run.id}，${summary}`;
  }
  return `${action}失败：#${run.id}，${run.lastErrorMessage || summary}`;
}

function isTerminalRun(run: SyncRun) {
  return run.state !== "RUNNING";
}

function isCopyProgressRun(run: SyncRun) {
  const operation = (run.operationType || "").toUpperCase();
  return operation === "FILE_COPY" || operation === "DIRECTORY_COPY_TASK";
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
