import { ref, type Ref } from "vue";
import { directoryCopyTasksApi } from "../../../app/api/client";
import type { AgentConfig, DirectoryCopyTask, DirectoryCopyTaskDraft } from "../../../app/api/types";

export function useDirectoryTasks(
  config: Ref<AgentConfig>,
  refreshRuns: () => Promise<void>,
  showScheduledPage: () => void
) {
  const directoryTasks = ref<DirectoryCopyTask[]>([]);
  const directoryTasksLoading = ref(false);
  const directoryTasksError = ref("");
  const directoryTaskMessage = ref("");
  const directoryTaskBusy = ref<Record<number, string>>({});
  const submittedOperations = ref<Record<number, number>>({});
  const directoryTaskEditorOpen = ref(false);
  const directoryTaskDraft = ref<DirectoryCopyTaskDraft | null>(null);
  const directoryTaskEditing = ref<DirectoryCopyTask | null>(null);
  const directoryTaskSaving = ref(false);
  const directoryTaskEditorError = ref("");

  async function refreshDirectoryTasks() {
    directoryTasksLoading.value = true;
    directoryTasksError.value = "";
    try {
      const result = await directoryCopyTasksApi.list(config.value);
      const nextTasks = Array.isArray(result.tasks) ? result.tasks : [];
      directoryTasks.value = nextTasks;
      reconcileSubmittedOperations(nextTasks);
    } catch (ex) {
      directoryTasksError.value = ex instanceof Error ? ex.message : "无法读取定期任务";
    } finally {
      directoryTasksLoading.value = false;
    }
  }

  async function runDirectoryTask(taskId: number) {
    directoryTaskBusy.value = { ...directoryTaskBusy.value, [taskId]: "run" };
    directoryTasksError.value = "";
    directoryTaskMessage.value = "";
    try {
      const result = await directoryCopyTasksApi.run(config.value, taskId);
      submittedOperations.value = { ...submittedOperations.value, [taskId]: result.operationId };
      directoryTaskMessage.value = `任务已提交：#${result.operationId}，后台正在比对并传输。`;
      await Promise.all([refreshDirectoryTasks(), refreshRuns()]);
    } catch (ex) {
      directoryTasksError.value = ex instanceof Error ? ex.message : "执行定期任务失败";
    } finally {
      clearBusy(taskId);
    }
  }

  async function cancelDirectoryTask(task: DirectoryCopyTask) {
    directoryTaskBusy.value = { ...directoryTaskBusy.value, [task.id]: "cancel" };
    directoryTasksError.value = "";
    directoryTaskMessage.value = "";
    try {
      const result = await directoryCopyTasksApi.cancel(config.value, task.id);
      directoryTaskMessage.value = result.ok
        ? `已请求停止任务：#${result.operationId}`
        : result.message || "任务当前没有可停止的运行记录";
      await Promise.all([refreshDirectoryTasks(), refreshRuns()]);
    } catch (ex) {
      directoryTasksError.value = ex instanceof Error ? ex.message : "停止定期任务失败";
    } finally {
      clearBusy(task.id);
    }
  }

  async function toggleDirectoryTask(task: DirectoryCopyTask, enabled: boolean) {
    directoryTaskBusy.value = { ...directoryTaskBusy.value, [task.id]: "toggle" };
    directoryTasksError.value = "";
    directoryTaskMessage.value = "";
    try {
      await directoryCopyTasksApi.update(config.value, task.id, {
        ...task,
        enabled,
        nextRunAt: enabled ? "" : task.nextRunAt
      });
      directoryTaskMessage.value = enabled
        ? "任务已启用，下次执行时间已重新计算。"
        : "任务已停用，调度器不会自动提交它。";
      await refreshDirectoryTasks();
    } catch (ex) {
      directoryTasksError.value = ex instanceof Error ? ex.message : "保存定期任务失败";
    } finally {
      clearBusy(task.id);
    }
  }

  async function deleteDirectoryTask(task: DirectoryCopyTask) {
    if ((task.lastStatus || "").toUpperCase() === "RUNNING") {
      directoryTasksError.value = "任务正在执行，不能删除。";
      return;
    }
    const confirmed = window.confirm(`删除定期任务「${task.name}」？\n\n只删除任务配置，历史操作记录和事件日志会保留。`);
    if (!confirmed) {
      return;
    }
    directoryTaskBusy.value = { ...directoryTaskBusy.value, [task.id]: "delete" };
    directoryTasksError.value = "";
    directoryTaskMessage.value = "";
    try {
      await directoryCopyTasksApi.delete(config.value, task.id);
      directoryTaskMessage.value = `已删除定期任务：${task.name}`;
      await refreshDirectoryTasks();
    } catch (ex) {
      directoryTasksError.value = ex instanceof Error ? ex.message : "删除定期任务失败";
    } finally {
      clearBusy(task.id);
    }
  }

  function openDirectoryTaskEditor(draft: DirectoryCopyTaskDraft) {
    directoryTaskDraft.value = draft;
    directoryTaskEditing.value = null;
    directoryTaskEditorError.value = "";
    directoryTaskEditorOpen.value = true;
  }

  function editDirectoryTask(task: DirectoryCopyTask) {
    directoryTaskDraft.value = {
      sourceFileSourceId: task.sourceFileSourceId,
      sourcePaths: task.sourcePaths,
      targetFileSourceId: task.targetFileSourceId,
      targetDirectory: task.targetDirectory,
      conflictPolicy: task.conflictPolicy
    };
    directoryTaskEditing.value = task;
    directoryTaskEditorError.value = "";
    directoryTaskEditorOpen.value = true;
  }

  function closeDirectoryTaskEditor() {
    directoryTaskEditorOpen.value = false;
    directoryTaskEditing.value = null;
    directoryTaskEditorError.value = "";
  }

  async function createDirectoryTask(payload: DirectoryCopyTask) {
    directoryTaskSaving.value = true;
    directoryTaskEditorError.value = "";
    directoryTaskMessage.value = "";
    try {
      const editing = directoryTaskEditing.value;
      const result = editing
        ? await directoryCopyTasksApi.update(config.value, editing.id, { ...payload, id: editing.id })
        : await directoryCopyTasksApi.create(config.value, payload);
      directoryTaskMessage.value = editing
        ? `已更新定期任务：${result.task.name}`
        : `已创建定期任务：${result.task.name}`;
      directoryTaskEditorOpen.value = false;
      directoryTaskEditing.value = null;
      await refreshDirectoryTasks();
      showScheduledPage();
    } catch (ex) {
      directoryTaskEditorError.value = ex instanceof Error ? ex.message : "保存定期任务失败";
    } finally {
      directoryTaskSaving.value = false;
    }
  }

  return {
    directoryTasks,
    directoryTasksLoading,
    directoryTasksError,
    directoryTaskMessage,
    directoryTaskBusy,
    directoryTaskEditorOpen,
    directoryTaskDraft,
    directoryTaskEditing,
    directoryTaskSaving,
    directoryTaskEditorError,
    refreshDirectoryTasks,
    runDirectoryTask,
    cancelDirectoryTask,
    toggleDirectoryTask,
    deleteDirectoryTask,
    openDirectoryTaskEditor,
    editDirectoryTask,
    closeDirectoryTaskEditor,
    createDirectoryTask
  };

  function clearBusy(taskId: number) {
    const next = { ...directoryTaskBusy.value };
    delete next[taskId];
    directoryTaskBusy.value = next;
  }

  function reconcileSubmittedOperations(tasks: DirectoryCopyTask[]) {
    const pending = submittedOperations.value;
    const nextPending = { ...pending };
    let changed = false;
    for (const task of tasks) {
      const operationId = pending[task.id];
      if (!operationId || task.lastOperationId !== operationId) {
        continue;
      }
      const status = (task.lastStatus || "").toUpperCase();
      if (!status || status === "RUNNING") {
        continue;
      }
      delete nextPending[task.id];
      changed = true;
      directoryTaskMessage.value = submittedOperationMessage(operationId, status);
    }
    if (changed) {
      submittedOperations.value = nextPending;
    }
  }

  function submittedOperationMessage(operationId: number, status: string) {
    if (status === "SUCCESS") {
      return `任务已完成：#${operationId}`;
    }
    if (status === "FAILED") {
      return `任务执行失败：#${operationId}`;
    }
    if (status === "CANCELLED") {
      return `任务已取消：#${operationId}`;
    }
    return `任务已结束：#${operationId}（${status}）`;
  }
}
