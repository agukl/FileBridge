import { ref, type Ref } from "vue";
import { licenseApi } from "../../../app/api/client";
import type { AgentConfig, LicenseStatus } from "../../../app/api/types";
import { loadAgentStatus, resolveAgentStatus, type AgentStatus } from "../../../app/runtime/agentStatus";
import { desktopAgentApi, type AgentProcessStatus } from "../../../app/runtime/desktopAgent";
import {
  applyConsoleTheme,
  defaultSettings,
  saveSettings,
  type ConsoleSettings
} from "../../../app/settings/consoleSettings";

type ProcessAction = "start" | "stop" | "restart";

export function useConsoleRuntime(
  config: Ref<AgentConfig>,
  settings: Ref<ConsoleSettings>,
  refresh: () => Promise<void>
) {
  const agentStatus = ref<AgentStatus>(resolveAgentStatus(null, null));
  const settingsOpen = ref(false);
  const licenseStatus = ref<LicenseStatus | null>(null);
  const licenseDeviceId = ref("");
  const licenseError = ref("");
  const licenseImporting = ref(false);
  const processStatus = ref<AgentProcessStatus | null>(null);
  const processBusy = ref("");
  const processError = ref("");
  let timer: number | undefined;

  async function refreshAgentRuntime() {
    const snapshot = await loadAgentStatus(config.value);
    processStatus.value = snapshot.processStatus;
    agentStatus.value = snapshot.status;
  }

  async function refreshLicense() {
    licenseError.value = "";
    try {
      const [status, device] = await Promise.all([
        licenseApi.status(config.value),
        licenseApi.deviceId(config.value)
      ]);
      licenseStatus.value = status;
      licenseDeviceId.value = device.deviceId || status.deviceId;
    } catch (ex) {
      licenseError.value = ex instanceof Error ? ex.message : "无法读取授权状态";
    }
  }

  async function importLicense(licenseText: string) {
    licenseImporting.value = true;
    licenseError.value = "";
    try {
      licenseStatus.value = await licenseApi.importLicense(config.value, licenseText);
      licenseDeviceId.value = licenseStatus.value.deviceId;
      await refresh();
    } catch (ex) {
      licenseError.value = ex instanceof Error ? ex.message : "导入许可证失败";
    } finally {
      licenseImporting.value = false;
    }
  }

  async function runProcessAction(action: ProcessAction) {
    processBusy.value = action;
    processError.value = "";
    try {
      if (action === "start") {
        processStatus.value = await desktopAgentApi.start();
      } else if (action === "stop") {
        processStatus.value = await desktopAgentApi.stop();
      } else {
        processStatus.value = await desktopAgentApi.restart();
      }
      agentStatus.value = resolveAgentStatus(processStatus.value, null);
      await new Promise((resolve) => window.setTimeout(resolve, 1200));
      await refresh();
    } catch (ex) {
      processError.value = ex instanceof Error ? ex.message : "服务操作失败";
      await refreshAgentRuntime();
    } finally {
      processBusy.value = "";
    }
  }

  function openSettings() {
    settingsOpen.value = true;
  }

  function closeSettings() {
    settingsOpen.value = false;
  }

  function updateSettings(nextSettings: ConsoleSettings) {
    settings.value = nextSettings;
    applyConsoleTheme(nextSettings.theme);
    saveSettings(nextSettings);
    scheduleRefreshTimer();
    settingsOpen.value = false;
    void refresh();
  }

  function resetSettings() {
    settings.value = defaultSettings;
    applyConsoleTheme(defaultSettings.theme);
    saveSettings(defaultSettings);
    scheduleRefreshTimer();
    void refresh();
  }

  function applyCurrentTheme() {
    applyConsoleTheme(settings.value.theme);
  }

  function scheduleRefreshTimer() {
    if (timer) {
      window.clearInterval(timer);
      timer = undefined;
    }
    if (!settings.value.autoRefresh) {
      return;
    }
    timer = window.setInterval(() => {
      if (!document.hidden) {
        void refresh();
      }
    }, settings.value.refreshIntervalSeconds * 1000);
  }

  function clearRefreshTimer() {
    if (timer) {
      window.clearInterval(timer);
      timer = undefined;
    }
  }

  return {
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
  };
}
