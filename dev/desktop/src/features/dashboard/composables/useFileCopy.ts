import { computed, ref, watch, type ComputedRef, type Ref } from "vue";
import { fileOperationsApi, fileSourcesApi, runsApi } from "../../../app/api/client";
import type { AgentConfig, FileCopyRequest, FileListing, SyncRun, TaskCard } from "../../../app/api/types";
import { formatBytes, formatNumber } from "../../../shared/formatters";

type CopyPaneSide = "left" | "right";

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
  const copyLeftListing = ref<FileListing | null>(null);
  const copyRightListing = ref<FileListing | null>(null);
  const copyLeftLoading = ref(false);
  const copyRightLoading = ref(false);
  const copyLeftError = ref("");
  const copyRightError = ref("");
  const copyMessage = ref("");
  const fileCopying = ref(false);
  const trackedCopyOperationId = ref<number | null>(null);
  const refreshedCopyOperationIds = new Set<number>();

  const activeCopyRun = computed(() => (
    runs.value.find((run) => run.operationType === "FILE_COPY" && run.state === "RUNNING") ?? null
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
    cancelCopyOperation
  };

  async function followCopyOperation(operationId: number) {
    for (let attempt = 0; attempt < 8; attempt++) {
      const run = findRun(operationId) ?? await fetchRecentOperationRun(operationId);
      if (run) {
        applyTrackedCopyRun(run);
        if (isTerminalRun(run)) {
          return;
        }
      }
      await sleep(650);
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
}

function copyRunMessage(run: SyncRun) {
  const summary = `文件 ${formatNumber(run.fileCount)} · 目录 ${formatNumber(run.directoryCount)} · ${formatBytes(run.totalBytes)}`;
  if (run.state === "RUNNING") {
    return `复制进行中：#${run.id}，${summary}`;
  }
  if (run.state === "SUCCESS") {
    if (run.errorCount > 0 || run.finalHealth === "COMPLETED_WITH_ERRORS") {
      return `复制完成但有错误：#${run.id}，${summary}，错误 ${formatNumber(run.errorCount)} 项`;
    }
    if (run.warningCount > 0 || run.finalHealth === "COMPLETED_WITH_WARNINGS") {
      return `复制完成但有跳过：#${run.id}，${summary}，跳过 ${formatNumber(run.warningCount)} 项`;
    }
    return `复制完成：#${run.id}，${summary}`;
  }
  if (run.state === "CANCELLED") {
    return `复制已取消：#${run.id}，${summary}`;
  }
  return `复制失败：#${run.id}，${run.lastErrorMessage || summary}`;
}

function isTerminalRun(run: SyncRun) {
  return run.state !== "RUNNING";
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
