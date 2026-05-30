import { ref, type Ref } from "vue";
import { fileSourcesApi, taskActionsApi } from "../../../app/api/client";
import type {
  AgentConfig,
  DashboardOverview,
  TaskConfigView,
  TaskPayload
} from "../../../app/api/types";

export type SourceType = "REMOTE_FTP" | "LOCAL" | "SMB";
export type SourceAction = "cancel" | "preflight" | "refresh-cache";

export function useSourceManagement(
  config: Ref<AgentConfig>,
  overview: Ref<DashboardOverview | null>,
  refresh: () => Promise<void>,
  showSourcesPage: () => void,
  setGlobalError: (message: string) => void
) {
  const editingTask = ref<TaskConfigView | null>(null);
  const createSourceType = ref<SourceType>("REMOTE_FTP");
  const taskEditorOpen = ref(false);
  const taskEditorSaving = ref(false);
  const taskEditorError = ref("");
  const busy = ref<Record<string, string>>({});

  function openCreateTask(sourceType: SourceType = "REMOTE_FTP") {
    showSourcesPage();
    createSourceType.value = sourceType;
    editingTask.value = null;
    taskEditorError.value = "";
    taskEditorOpen.value = true;
  }

  async function openEditTask(taskId: string) {
    showSourcesPage();
    taskEditorError.value = "";
    try {
      editingTask.value = await fileSourcesApi.get(config.value, taskId);
    } catch (ex) {
      setGlobalError(ex instanceof Error ? ex.message : "无法读取文件源");
      const fallback = overview.value?.tasks.find((card) => card.task.taskId === taskId)?.task ?? null;
      editingTask.value = fallback as TaskConfigView | null;
    }
    taskEditorOpen.value = true;
  }

  function closeTaskEditor() {
    taskEditorOpen.value = false;
    taskEditorError.value = "";
  }

  async function saveTask(payload: TaskPayload, _runPreflight: boolean) {
    taskEditorSaving.value = true;
    taskEditorError.value = "";
    try {
      if (editingTask.value) {
        await fileSourcesApi.update(config.value, editingTask.value.taskId, payload);
      } else {
        await fileSourcesApi.create(config.value, payload);
      }
      await refresh();
      taskEditorOpen.value = false;
    } catch (ex) {
      taskEditorError.value = ex instanceof Error ? ex.message : "保存文件源失败";
    } finally {
      taskEditorSaving.value = false;
    }
  }

  async function runSourceAction(sourceId: string, action: SourceAction) {
    busy.value = { ...busy.value, [sourceId]: action };
    setGlobalError("");
    try {
      if (action === "cancel") {
        await taskActionsApi.cancel(config.value, sourceId);
      } else if (action === "refresh-cache") {
        await fileSourcesApi.refreshCache(config.value, sourceId);
      } else {
        await fileSourcesApi.preflight(config.value, sourceId);
      }
      await refresh();
    } catch (ex) {
      if (action === "refresh-cache" && ex && typeof ex === "object" && "status" in ex && ex.status === 404) {
        setGlobalError("当前后端服务尚未加载刷新目录缓存接口，请重启服务后再试。");
      } else {
        setGlobalError(ex instanceof Error ? ex.message : "操作失败");
      }
    } finally {
      const next = { ...busy.value };
      delete next[sourceId];
      busy.value = next;
    }
  }

  async function deleteSource(sourceId: string) {
    const source = overview.value?.tasks.find((card) => card.task.taskId === sourceId)?.task ?? null;
    const name = source?.taskName || source?.taskId || sourceId;
    const confirmed = window.confirm(`删除文件源「${name}」？\n\n只删除文件源配置和目录缓存，历史操作记录和日志会保留。`);
    if (!confirmed) {
      return;
    }
    busy.value = { ...busy.value, [sourceId]: "delete" };
    setGlobalError("");
    try {
      await fileSourcesApi.delete(config.value, sourceId);
      await refresh();
    } catch (ex) {
      setGlobalError(ex instanceof Error ? ex.message : "删除文件源失败");
    } finally {
      const next = { ...busy.value };
      delete next[sourceId];
      busy.value = next;
    }
  }

  return {
    editingTask,
    createSourceType,
    taskEditorOpen,
    taskEditorSaving,
    taskEditorError,
    busy,
    openCreateTask,
    openEditTask,
    closeTaskEditor,
    saveTask,
    runSourceAction,
    deleteSource
  };
}
