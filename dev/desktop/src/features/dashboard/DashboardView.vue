<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import {
  dashboardApi,
  loadConfig,
  loadRuntimeConfig,
  statusCodesApi,
} from "../../app/api/client";
import type {
  AgentConfig,
  DashboardOverview,
  StatusCodeDictionary,
} from "../../app/api/types";
import { loadSettings, type ConsoleSettings } from "../../app/settings/consoleSettings";
import { formatNumber } from "../../shared/formatters";
import AgentStatusStrip from "./components/AgentStatusStrip.vue";
import DirectoryTaskEditorDrawer from "./components/DirectoryTaskEditorDrawer.vue";
import SettingsDrawer from "./components/SettingsDrawer.vue";
import TaskEditorDrawer from "./components/TaskEditorDrawer.vue";
import { useConsoleRuntime } from "./composables/useConsoleRuntime";
import { useBoardOverview } from "./composables/useBoardOverview";
import { useDashboardNavigation } from "./composables/useDashboardNavigation";
import { useDirectoryTasks } from "./composables/useDirectoryTasks";
import { useFileBrowser } from "./composables/useFileBrowser";
import { useFileCopy } from "./composables/useFileCopy";
import { useRunHistory } from "./composables/useRunHistory";
import { useSourceManagement } from "./composables/useSourceManagement";
import BoardPage from "./pages/BoardPage.vue";
import CopyPage from "./pages/CopyPage.vue";
import EventLogsPage from "./pages/EventLogsPage.vue";
import FilesPage from "./pages/FilesPage.vue";
import OperationsPage from "./pages/OperationsPage.vue";
import ScheduledPage from "./pages/ScheduledPage.vue";
import SourcesPage from "./pages/SourcesPage.vue";

type PageKey = "board" | "sources" | "files" | "copy" | "scheduled" | "operations" | "logs";
type BoardLinkFilters = {
  state?: string;
  taskId?: string;
  q?: string;
};

const pageKeys: PageKey[] = ["board", "sources", "files", "copy", "scheduled", "operations", "logs"];
const legacyPageAliases: Record<string, PageKey> = {
  dashboard: "board",
  board: "board",
  lines: "sources",
  remote: "files",
  transfer: "copy",
  jobs: "scheduled",
  records: "operations",
  runs: "logs"
};

const config = ref<AgentConfig>(loadConfig());
const settings = ref<ConsoleSettings>(loadSettings());
const overview = ref<DashboardOverview | null>(null);
const {
  activePage,
  setPage,
  clearLegacyHashFromLocation
} = useDashboardNavigation<PageKey>(pageKeys, legacyPageAliases, "board", handlePageActivated);
const statusCodes = ref<StatusCodeDictionary>({});
const loading = ref(false);
const error = ref("");

const fileSources = computed(() => overview.value?.tasks ?? []);
const {
  agentStatus,
  settingsOpen,
  licenseStatus,
  licenseDeviceId,
  licenseError,
  licenseImporting,
  processStatus,
  processBusy,
  processError,
  refreshAgentRuntime,
  refreshLicense,
  importLicense,
  runProcessAction,
  openSettings,
  closeSettings,
  updateSettings,
  resetSettings,
  applyCurrentTheme,
  scheduleRefreshTimer,
  clearRefreshTimer
} = useConsoleRuntime(config, settings, () => refresh());
const {
  board,
  boardLoading,
  boardError,
  boardRangeHours,
  boardRangeOptions,
  refreshBoard
} = useBoardOverview(config);
const {
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
} = useSourceManagement(
  config,
  overview,
  () => refresh(),
  () => setPage("sources"),
  (message) => {
    error.value = message;
  }
);
const {
  selectedSourceId,
  selectedSource,
  fileListing,
  fileLoading,
  fileError,
  ensureSelectedSource,
  loadFiles,
  selectFileSource,
  loadParentPath
} = useFileBrowser(config, fileSources);
const {
  runs,
  operationRuns,
  operationTotal,
  operationPage,
  operationPageSize,
  events,
  eventTotal,
  eventPage,
  eventPageSize,
  runsLoading,
  runsError,
  operationBusy,
  runStateFilter,
  runTaskFilter,
  runTextFilter,
  runTaskOptions,
  hasRunFilters,
  clearRunFilters,
  refreshRuns,
  refreshOperations,
  refreshEvents,
  changeOperationPage,
  changeOperationPageSize,
  changeEventPage,
  changeEventPageSize,
  cancelRunOperation
} = useRunHistory(config, settings, fileSources);
const {
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
} = useDirectoryTasks(config, refreshRuns, () => setPage("scheduled"));
const {
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
} = useFileCopy(config, fileSources, selectedSourceId, runs, refreshRuns, fileError);
const eventLogCountText = computed(() => (
  eventTotal.value === null
    ? "待统计"
    : `${formatNumber(eventTotal.value)} 条日志`
));
const operationRunCountText = computed(() => (
  operationTotal.value === null
    ? "待统计"
    : `${formatNumber(operationTotal.value)} 次操作`
));
const directoryTaskCountText = computed(() => `${formatNumber(directoryTasks.value.length)} 个任务`);
const navItems = computed(() => [
  {
    key: "board" as PageKey,
    label: "看板",
    detail: board.value ? `最近 ${boardRangeHours.value} 小时态势` : "运行态势总览"
  },
  {
    key: "sources" as PageKey,
    label: "文件源",
    detail: `${formatNumber(fileSources.value.length)} 个来源`
  },
  {
    key: "files" as PageKey,
    label: "文件浏览",
    detail: selectedSource.value?.task.taskName || selectedSource.value?.task.taskId || "浏览文件"
  },
  {
    key: "copy" as PageKey,
    label: "文件复制",
    detail: "左右目录对比"
  },
  {
    key: "scheduled" as PageKey,
    label: "定期任务",
    detail: directoryTaskCountText.value
  },
  {
    key: "operations" as PageKey,
    label: "操作记录",
    detail: operationRunCountText.value
  },
  {
    key: "logs" as PageKey,
    label: "日志记录",
    detail: eventLogCountText.value
  }
]);
function handlePageActivated(page: PageKey) {
  if (page === "board" && !board.value) {
    void refreshBoard();
  }
  if (page === "files" && !fileListing.value && fileSources.value.length) {
    void loadFiles();
  }
  if (page === "copy") {
    void ensureCopyPaneLoaded();
  }
  if (page === "scheduled" && !directoryTasks.value.length) {
    void refreshDirectoryTasks();
  }
}

function openBoardOperations(filters: BoardLinkFilters = {}) {
  runStateFilter.value = filters.state ?? "ALL";
  runTaskFilter.value = filters.taskId ?? "ALL";
  runTextFilter.value = filters.q ?? "";
  setPage("operations");
  void refreshOperations();
}

function openBoardLogs(filters: BoardLinkFilters = {}) {
  runStateFilter.value = filters.state ?? "ALL";
  runTaskFilter.value = filters.taskId ?? "ALL";
  runTextFilter.value = filters.q ?? "";
  setPage("logs");
  void refreshEvents();
}

async function refresh() {
  loading.value = true;
  error.value = "";
  await refreshAgentRuntime();
  await refreshLicense();
  try {
    const [nextOverview, nextStatusCodes] = await Promise.all([
      dashboardApi.overview(config.value, settings.value.lineLimit),
      statusCodesApi.list(config.value)
    ]);
    overview.value = nextOverview;
    statusCodes.value = nextStatusCodes;
    ensureSelectedSource();
    ensureCopySources();
    await refreshRuns();
    if (activePage.value === "board" || board.value) {
      await refreshBoard();
    }
    if (activePage.value === "files" && !fileListing.value && fileSources.value.length) {
      void loadFiles();
    }
    if (activePage.value === "copy") {
      void ensureCopyPaneLoaded();
    }
    await refreshDirectoryTasks();
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : "无法连接 Agent";
  } finally {
    loading.value = false;
  }
}

async function initializeDashboard() {
  config.value = await loadRuntimeConfig();
  await refresh();
  scheduleRefreshTimer();
}

onMounted(() => {
  applyCurrentTheme();
  clearLegacyHashFromLocation();
  void initializeDashboard();
});

onUnmounted(() => {
  clearRefreshTimer();
});
</script>

<template>
  <main class="console-shell">
    <nav class="page-nav" aria-label="主功能">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-card"
        :class="{ selected: activePage === item.key }"
        @click="setPage(item.key)"
      >
        <strong>{{ item.label }}</strong>
        <span>{{ item.detail }}</span>
      </button>

      <AgentStatusStrip
        :agent-status="agentStatus"
        :process-busy="processBusy"
        :process-error="processError"
        @run-primary-action="runProcessAction"
        @open-settings="openSettings"
      />
    </nav>

    <section class="workspace-panel">
      <p v-if="error" class="form-error">{{ error }}</p>

      <BoardPage
        v-if="activePage === 'board'"
        v-model:range-hours="boardRangeHours"
        :board="board"
        :loading="boardLoading"
        :error="boardError"
        :range-options="boardRangeOptions"
        :status-codes="statusCodes"
        @refresh="refreshBoard"
        @open-operations="openBoardOperations"
        @open-logs="openBoardLogs"
      />

      <SourcesPage
        v-if="activePage === 'sources'"
        :sources="fileSources"
        :busy="busy"
        @create-local="openCreateTask('LOCAL')"
        @create-smb="openCreateTask('SMB')"
        @create-remote="openCreateTask('REMOTE_FTP')"
        @edit="openEditTask"
        @delete="deleteSource"
        @refresh-cache="(sourceId) => runSourceAction(sourceId, 'refresh-cache')"
      />

      <FilesPage
        v-if="activePage === 'files'"
        :sources="fileSources"
        :selected-source-id="selectedSourceId"
        :listing="fileListing"
        :loading="fileLoading"
        :error="fileError"
        @select-source="selectFileSource"
        @open-path="loadFiles"
        @parent="loadParentPath"
        @refresh="loadFiles"
        @move-files="moveFiles"
      />

      <CopyPage
        v-if="activePage === 'copy'"
        :sources="fileSources"
        :left-source-id="copyLeftSourceId"
        :right-source-id="copyRightSourceId"
        :left-listing="copyLeftListing"
        :right-listing="copyRightListing"
        :left-loading="copyLeftLoading"
        :right-loading="copyRightLoading"
        :left-error="copyLeftError"
        :right-error="copyRightError"
        :copying="fileCopying"
        :copy-message="copyMessage"
        :active-run="activeCopyRun"
        @select-pane-source="selectCopyPaneSource"
        @open-pane-path="openCopyPanePath"
        @copy-files="copyFiles"
        @cancel-copy="cancelCopyOperation"
        @save-directory-task="openDirectoryTaskEditor"
      />

      <ScheduledPage
        v-if="activePage === 'scheduled'"
        :tasks="directoryTasks"
        :sources="fileSources"
        :loading="directoryTasksLoading"
        :error="directoryTasksError"
        :message="directoryTaskMessage"
        :busy="directoryTaskBusy"
        @refresh="refreshDirectoryTasks"
        @run-task="runDirectoryTask"
        @cancel-task="cancelDirectoryTask"
        @toggle-task="toggleDirectoryTask"
        @edit-task="editDirectoryTask"
        @delete-task="deleteDirectoryTask"
      />

      <OperationsPage
        v-if="activePage === 'operations'"
        v-model:state-filter="runStateFilter"
        v-model:task-filter="runTaskFilter"
        v-model:text-filter="runTextFilter"
        :runs="operationRuns"
        :total="operationTotal"
        :page="operationPage"
        :page-size="operationPageSize"
        :sources="fileSources"
        :status-codes="statusCodes"
        :loading="runsLoading"
        :error="runsError"
        :operation-busy="operationBusy"
        :task-options="runTaskOptions"
        :has-filters="hasRunFilters"
        @clear-filters="clearRunFilters"
        @refresh="refreshOperations"
        @page-change="changeOperationPage"
        @page-size-change="changeOperationPageSize"
        @cancel-run="cancelRunOperation"
      />

      <EventLogsPage
        v-if="activePage === 'logs'"
        v-model:state-filter="runStateFilter"
        v-model:task-filter="runTaskFilter"
        v-model:text-filter="runTextFilter"
        :runs="runs"
        :events="events"
        :total="eventTotal"
        :page="eventPage"
        :page-size="eventPageSize"
        :status-codes="statusCodes"
        :loading="runsLoading"
        :error="runsError"
        :task-options="runTaskOptions"
        :has-filters="hasRunFilters"
        @clear-filters="clearRunFilters"
        @refresh="refreshEvents"
        @page-change="changeEventPage"
        @page-size-change="changeEventPageSize"
      />
    </section>

    <TaskEditorDrawer
      :open="taskEditorOpen"
      :config="config"
      :task="editingTask"
      :initial-source-type="createSourceType"
      :saving="taskEditorSaving"
      :error="taskEditorError"
      @close="closeTaskEditor"
      @submit="saveTask"
    />

    <DirectoryTaskEditorDrawer
      :open="directoryTaskEditorOpen"
      :draft="directoryTaskDraft"
      :task="directoryTaskEditing"
      :sources="fileSources"
      :saving="directoryTaskSaving"
      :error="directoryTaskEditorError"
      @close="closeDirectoryTaskEditor"
      @submit="createDirectoryTask"
    />

    <SettingsDrawer
      :open="settingsOpen"
      :settings="settings"
      :process-status="processStatus"
      :license-status="licenseStatus"
      :license-device-id="licenseDeviceId"
      :license-error="licenseError"
      :license-importing="licenseImporting"
      @close="closeSettings"
      @save="updateSettings"
      @reset="resetSettings"
      @refresh-process="refreshAgentRuntime"
      @refresh-license="refreshLicense"
      @import-license="importLicense"
    />
  </main>
</template>
