import { agentApi } from "../api/client";
import type { AgentConfig, HealthBody } from "../api/types";
import { desktopAgentApi, type AgentProcessStatus } from "./desktopAgent";

export type AgentStatusAction = "start" | "stop" | "restart";

export type AgentStatus = {
  kind:
    | "running-managed"
    | "running-external"
    | "running-preview"
    | "starting"
    | "stopped-ready"
    | "stopped-preview"
    | "stopped-unavailable"
    | "unhealthy";
  title: string;
  detail: string;
  tone: "good" | "warn" | "bad" | "idle";
  actionKind: AgentStatusAction | null;
  actionLabel: string;
  actionEnabled: boolean;
  reachable: boolean;
  healthy: boolean;
  controllable: boolean;
  running: boolean;
  error: string;
};

export type AgentStatusSnapshot = {
  health: HealthBody | null;
  processStatus: AgentProcessStatus;
  status: AgentStatus;
};

export async function loadAgentStatus(config: AgentConfig): Promise<AgentStatusSnapshot> {
  const processStatus = await desktopAgentApi.status();
  let health: HealthBody | null = null;
  let healthError = "";

  try {
    health = await agentApi.health(config);
  } catch (ex) {
    healthError = ex instanceof Error ? ex.message : String(ex);
  }

  return {
    health,
    processStatus,
    status: resolveAgentStatus(processStatus, health, healthError)
  };
}

export function resolveAgentStatus(
  processStatus: AgentProcessStatus | null,
  health: HealthBody | null,
  healthError = ""
): AgentStatus {
  const process = processStatus ?? emptyProcessStatus();
  const launchReady = Boolean(process.jarExists) && Boolean(process.configExists);
  const desktopAvailable = Boolean(process.available);
  const apiReachable = Boolean(health);
  const healthy = Boolean(health?.ok);
  const running = healthy || Boolean(process.running);
  const error = firstNonEmpty(process.lastError, health?.error, healthError);
  const canControlRunningService = Boolean(process.running) && Boolean(process.managed);

  if (healthy) {
    if (!desktopAvailable) {
      return {
        kind: "running-preview",
        title: "后端服务运行中",
        detail: "当前是浏览器预览，只能查看状态，不能控制服务。",
        tone: "good",
        actionKind: null,
        actionLabel: "",
        actionEnabled: false,
        reachable: true,
        healthy: true,
        controllable: false,
        running,
        error
      };
    }

    if (process.running) {
      return {
        kind: "running-managed",
        title: "后端服务运行中",
        detail: process.pid
          ? `由当前桌面窗口管理，进程 #${process.pid}。`
          : "后端服务正在运行，可以直接停止。",
        tone: "good",
        actionKind: "stop",
        actionLabel: "停止",
        actionEnabled: canControlRunningService,
        reachable: true,
        healthy: true,
        controllable: canControlRunningService,
        running,
        error
      };
    }

    return {
      kind: "running-external",
        title: "后端服务运行中",
      detail: "当前服务已可用，但不是由这个桌面窗口拉起的进程。",
      tone: "good",
      actionKind: null,
      actionLabel: "",
      actionEnabled: false,
      reachable: true,
      healthy: true,
      controllable: false,
      running,
      error
    };
  }

  if (apiReachable) {
    return {
      kind: "unhealthy",
      title: "后端服务异常",
      detail: firstNonEmpty(
        health?.error,
        "服务已响应，但健康检查未通过，请检查数据库连接或初始化状态。"
      ),
      tone: process.running ? "warn" : "bad",
      actionKind: canControlRunningService ? "stop" : launchReady ? "start" : null,
      actionLabel: canControlRunningService ? "停止" : "启动",
      actionEnabled: canControlRunningService || launchReady,
      reachable: true,
      healthy: false,
      controllable: canControlRunningService || launchReady,
      running,
      error
    };
  }

  if (!desktopAvailable) {
    return {
      kind: "stopped-preview",
      title: "后端服务不可连接",
      detail: "当前是浏览器预览，不能启动或停止后端服务。",
      tone: "bad",
      actionKind: null,
      actionLabel: "",
      actionEnabled: false,
      reachable: false,
      healthy: false,
      controllable: false,
      running,
      error: firstNonEmpty(error, healthError)
    };
  }

  if (process.running) {
    return {
      kind: "starting",
      title: "后端服务启动中",
      detail: firstNonEmpty(
        error,
        "桌面已拉起本机进程，正在等待服务恢复。"
      ),
      tone: "warn",
      actionKind: canControlRunningService ? "stop" : "restart",
      actionLabel: canControlRunningService ? "停止" : "重启",
      actionEnabled: canControlRunningService || launchReady,
      reachable: false,
      healthy: false,
      controllable: canControlRunningService || launchReady,
      running,
      error
    };
  }

  if (launchReady) {
    return {
      kind: "stopped-ready",
      title: "后端服务未运行",
      detail: "桌面已具备启动资源，可以直接启动后端服务。",
      tone: "warn",
      actionKind: "start",
      actionLabel: "启动",
      actionEnabled: true,
      reachable: false,
      healthy: false,
      controllable: true,
      running,
      error
    };
  }

  return {
    kind: "stopped-unavailable",
    title: "后端服务未就绪",
    detail: firstNonEmpty(
      error,
      "桌面壳未找到后端服务启动资源，请检查 Jar 或配置文件。"
    ),
    tone: "bad",
    actionKind: null,
    actionLabel: "",
    actionEnabled: false,
    reachable: false,
    healthy: false,
    controllable: false,
    running,
    error
  };
}

function firstNonEmpty(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function emptyProcessStatus(): AgentProcessStatus {
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
    message: "",
    lastError: null
  };
}
