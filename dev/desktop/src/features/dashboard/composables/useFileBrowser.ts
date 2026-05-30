import { computed, ref, type ComputedRef, type Ref } from "vue";
import { fileSourcesApi } from "../../../app/api/client";
import type { AgentConfig, FileListing, TaskCard } from "../../../app/api/types";

export function useFileBrowser(
  config: Ref<AgentConfig>,
  fileSources: ComputedRef<TaskCard[]>
) {
  const selectedSourceId = ref("");
  const fileListing = ref<FileListing | null>(null);
  const fileLoading = ref(false);
  const fileError = ref("");

  const selectedSource = computed(() => (
    fileSources.value.find((source) => source.task.taskId === selectedSourceId.value) ?? null
  ));

  function ensureSelectedSource() {
    if (!fileSources.value.length) {
      selectedSourceId.value = "";
      fileListing.value = null;
      return;
    }
    if (!fileSources.value.some((source) => source.task.taskId === selectedSourceId.value)) {
      selectedSourceId.value = fileSources.value[0].task.taskId;
      fileListing.value = null;
    }
  }

  async function loadFiles(path?: string) {
    ensureSelectedSource();
    const source = selectedSource.value;
    if (!source) {
      fileError.value = "请先创建文件源";
      return;
    }
    fileLoading.value = true;
    fileError.value = "";
    try {
      const nextPath = path || fileListing.value?.path || source.task.sourcePath || "/";
      const result = await fileSourcesApi.files(config.value, source.task.taskId, nextPath, 1000);
      fileListing.value = result.listing;
    } catch (ex) {
      fileError.value = ex instanceof Error ? ex.message : "无法读取文件";
    } finally {
      fileLoading.value = false;
    }
  }

  function selectFileSource(sourceId: string) {
    selectedSourceId.value = sourceId;
    fileListing.value = null;
    void loadFiles(fileSources.value.find((source) => source.task.taskId === sourceId)?.task.sourcePath || "/");
  }

  function loadParentPath() {
    if (!fileListing.value?.parentPath) {
      return;
    }
    void loadFiles(fileListing.value.parentPath);
  }

  return {
    selectedSourceId,
    selectedSource,
    fileListing,
    fileLoading,
    fileError,
    ensureSelectedSource,
    loadFiles,
    selectFileSource,
    loadParentPath
  };
}
