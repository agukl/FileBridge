export type AgentProcessStatus = {
  available: boolean;
  running: boolean;
  managed: boolean;
  pid: number | null;
  startedAtEpochMs: number | null;
  exitedAtEpochMs: number | null;
  exitCode: number | null;
  workspaceRoot: string;
  javaCommand: string;
  jarPath: string;
  configPath: string;
  stdoutLog: string;
  stderrLog: string;
  jarExists: boolean;
  configExists: boolean;
  serviceInstalled: boolean;
  serviceName: string;
  serviceState: string;
  keepAgentOnClose: boolean;
  message: string;
  lastError: string | null;
};

declare global {
  interface Window {
    __TAURI__?: unknown;
    __TAURI_INTERNALS__?: unknown;
  }
}

function isDesktopRuntime(): boolean {
  return Boolean(window.__TAURI_INTERNALS__ || window.__TAURI__);
}

function browserPreviewStatus(): AgentProcessStatus {
  return {
    available: false,
    running: false,
    managed: false,
    pid: null,
    startedAtEpochMs: null,
    exitedAtEpochMs: null,
    exitCode: null,
    workspaceRoot: "",
    javaCommand: "java",
    jarPath: "",
    configPath: "",
    stdoutLog: "",
    stderrLog: "",
    jarExists: false,
    configExists: false,
    serviceInstalled: false,
    serviceName: "",
    serviceState: "",
    keepAgentOnClose: false,
    message: "当前是浏览器预览模式，启动/停止需要在 Tauri 桌面壳中使用。",
    lastError: null
  };
}

async function invokeDesktop<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isDesktopRuntime()) {
    throw new Error("当前不是桌面壳运行环境");
  }
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke<T>(command, args);
}

export const desktopAgentApi = {
  async status(): Promise<AgentProcessStatus> {
    if (!isDesktopRuntime()) {
      return browserPreviewStatus();
    }
    try {
      return await invokeDesktop<AgentProcessStatus>("agent_process_status");
    } catch (ex) {
      return {
        ...browserPreviewStatus(),
        available: true,
        message: "桌面壳已加载，但读取后端服务状态失败。",
        lastError: ex instanceof Error ? ex.message : String(ex)
      };
    }
  },

  start(): Promise<AgentProcessStatus> {
    return invokeDesktop<AgentProcessStatus>("agent_process_start");
  },

  stop(): Promise<AgentProcessStatus> {
    return invokeDesktop<AgentProcessStatus>("agent_process_stop");
  },

  restart(): Promise<AgentProcessStatus> {
    return invokeDesktop<AgentProcessStatus>("agent_process_restart");
  },

  setClosePolicy(keepAgentOnClose: boolean): Promise<AgentProcessStatus> {
    if (!isDesktopRuntime()) {
      return Promise.resolve({ ...browserPreviewStatus(), keepAgentOnClose });
    }
    return invokeDesktop<AgentProcessStatus>("agent_process_set_close_policy", { keepAgentOnClose });
  }
};
