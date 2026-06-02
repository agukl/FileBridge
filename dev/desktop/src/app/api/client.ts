import type {
  ActionResult,
  AgentConfig,
  BoardOverview,
  DashboardOverview,
  DirectoryCopyTaskDeleteResult,
  DirectoryCopyTask,
  DirectoryCopyTaskListResult,
  DirectoryCopyTaskSaveResult,
  DirectoryCacheRefreshResponse,
  DraftPreflightResult,
  EventPageResult,
  FileBrowseResult,
  FileSourceDeleteResult,
  FileOperationCancelResult,
  FileCopyRequest,
  FileCopyResult,
  FileMoveRequest,
  HealthBody,
  LicenseDeviceIdResult,
  LicenseStatus,
  RemoteDirectoryResult,
  RemoteFileResult,
  RunPageResult,
  StatusCodeDictionary,
  TaskConfigView,
  TaskPayload,
  TaskSaveResult
} from "./types";

const RUNTIME_CONFIG_URL = "agent-client-config.json";

export const defaultConfig: AgentConfig = {
  baseUrl: "http://127.0.0.1:18090",
  token: ""
};

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export function loadConfig(): AgentConfig {
  return { ...defaultConfig };
}

export async function loadRuntimeConfig(): Promise<AgentConfig> {
  try {
    const response = await fetch(`${RUNTIME_CONFIG_URL}?t=${Date.now()}`, {
      cache: "no-store"
    });
    if (!response.ok) {
      return loadConfig();
    }
    const body = await response.json() as Partial<AgentConfig>;
    return {
      ...defaultConfig,
      ...body,
      baseUrl: typeof body.baseUrl === "string" && body.baseUrl.trim()
        ? body.baseUrl.trim()
        : defaultConfig.baseUrl,
      token: typeof body.token === "string" ? body.token : ""
    };
  } catch {
    return loadConfig();
  }
}

async function request<T>(config: AgentConfig, path: string, init: RequestInit = {}): Promise<T> {
  const url = `${config.baseUrl.replace(/\/$/, "")}${path}`;
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (config.token.trim()) {
    headers.set("Authorization", `Bearer ${config.token.trim()}`);
  }
  let response: Response;
  try {
    response = await fetch(url, { ...init, headers });
  } catch {
    throw new ApiError(0, "无法连接后端服务");
  }
  const text = await response.text();
  const body = text ? safeJson(text) : null;
  if (!response.ok) {
    const message = body && typeof body["message"] === "string"
      ? body["message"]
      : `HTTP ${response.status}`;
    throw new ApiError(response.status, message);
  }
  return body as T;
}

function safeJson(text: string): Record<string, unknown> {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

export const agentApi = {
  health(config: AgentConfig) {
    return request<HealthBody>(config, "/api/agent/health");
  }
};

export const dashboardApi = {
  overview(config: AgentConfig, limit = 500) {
    return request<DashboardOverview>(config, `/api/dashboard/overview?limit=${limit}`);
  },
  board(config: AgentConfig, options: {
    hours?: number;
    runLimit?: number;
    eventLimit?: number;
  } = {}) {
    const params = new URLSearchParams();
    params.set("hours", String(options.hours ?? 24));
    params.set("runLimit", String(options.runLimit ?? 4000));
    params.set("eventLimit", String(options.eventLimit ?? 6000));
    return request<BoardOverview>(config, `/api/dashboard/board?${params.toString()}`);
  }
};

export const tasksApi = {
  list(config: AgentConfig) {
    return request<{ tasks: TaskConfigView[] }>(config, "/api/tasks");
  },
  get(config: AgentConfig, taskId: string) {
    return request<TaskConfigView>(config, `/api/tasks/${encodeURIComponent(taskId)}`);
  },
  create(config: AgentConfig, payload: TaskPayload) {
    return request<TaskSaveResult>(config, "/api/tasks", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  update(config: AgentConfig, taskId: string, payload: TaskPayload) {
    return request<TaskSaveResult>(config, `/api/tasks/${encodeURIComponent(taskId)}`, {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  }
};

export const fileSourcesApi = {
  list(config: AgentConfig) {
    return request<{ sources: TaskConfigView[] }>(config, "/api/file-sources");
  },
  get(config: AgentConfig, sourceId: string) {
    return request<TaskConfigView>(config, `/api/file-sources/${encodeURIComponent(sourceId)}`);
  },
  create(config: AgentConfig, payload: TaskPayload) {
    return request<{ ok: boolean; sourceId: string }>(config, "/api/file-sources", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  update(config: AgentConfig, sourceId: string, payload: TaskPayload) {
    return request<{ ok: boolean; sourceId: string }>(config, `/api/file-sources/${encodeURIComponent(sourceId)}`, {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  },
  delete(config: AgentConfig, sourceId: string) {
    return request<FileSourceDeleteResult>(
      config,
      `/api/file-sources/${encodeURIComponent(sourceId)}`,
      { method: "DELETE" }
    );
  },
  preflight(config: AgentConfig, sourceId: string) {
    return request<ActionResult>(config, `/api/file-sources/${encodeURIComponent(sourceId)}/preflight`, { method: "POST" });
  },
  refreshCache(config: AgentConfig, sourceId: string) {
    return request<DirectoryCacheRefreshResponse>(
      config,
      `/api/file-sources/${encodeURIComponent(sourceId)}/refresh-cache`,
      { method: "POST" }
    );
  },
  files(config: AgentConfig, sourceId: string, path: string, limit = 300) {
    return request<FileBrowseResult>(
      config,
      `/api/file-sources/${encodeURIComponent(sourceId)}/files?path=${encodeURIComponent(path)}&limit=${limit}`
    );
  },
  remoteFiles(config: AgentConfig, sourceId: string, path: string, limit = 300) {
    return request<RemoteFileResult>(
      config,
      `/api/file-sources/${encodeURIComponent(sourceId)}/remote-files?path=${encodeURIComponent(path)}&limit=${limit}`
    );
  }
};

export const fileOperationsApi = {
  copy(config: AgentConfig, payload: FileCopyRequest) {
    return request<FileCopyResult>(config, "/api/file-operations/copy", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  move(config: AgentConfig, payload: FileMoveRequest) {
    return request<FileCopyResult>(config, "/api/file-operations/move", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  cancel(config: AgentConfig, operationId: number) {
    return request<FileOperationCancelResult>(
      config,
      `/api/file-operations/${encodeURIComponent(String(operationId))}/cancel`,
      { method: "POST" }
    );
  }
};

export const directoryCopyTasksApi = {
  list(config: AgentConfig, limit = 500) {
    return request<DirectoryCopyTaskListResult>(config, `/api/directory-copy-tasks?limit=${limit}`);
  },
  create(config: AgentConfig, payload: DirectoryCopyTask) {
    return request<DirectoryCopyTaskSaveResult>(config, "/api/directory-copy-tasks", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  update(config: AgentConfig, taskId: number, payload: DirectoryCopyTask) {
    return request<DirectoryCopyTaskSaveResult>(config, `/api/directory-copy-tasks/${encodeURIComponent(String(taskId))}`, {
      method: "PUT",
      body: JSON.stringify(payload)
    });
  },
  run(config: AgentConfig, taskId: number) {
    return request<FileCopyResult>(config, `/api/directory-copy-tasks/${encodeURIComponent(String(taskId))}/run`, {
      method: "POST"
    });
  },
  cancel(config: AgentConfig, taskId: number) {
    return request<FileOperationCancelResult>(
      config,
      `/api/directory-copy-tasks/${encodeURIComponent(String(taskId))}/cancel`,
      { method: "POST" }
    );
  },
  delete(config: AgentConfig, taskId: number) {
    return request<DirectoryCopyTaskDeleteResult>(
      config,
      `/api/directory-copy-tasks/${encodeURIComponent(String(taskId))}`,
      { method: "DELETE" }
    );
  }
};

export const taskDraftApi = {
  preflight(config: AgentConfig, payload: TaskPayload) {
    return request<DraftPreflightResult>(config, "/api/tasks/preflight-draft", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  }
};

export const ftpBrowserApi = {
  listDirectories(config: AgentConfig, payload: TaskPayload, path: string, limit = 200) {
    return request<RemoteDirectoryResult>(config, `/api/ftp/remote-directories?limit=${limit}`, {
      method: "POST",
      body: JSON.stringify({ task: payload, path })
    });
  }
};

export const statusCodesApi = {
  list(config: AgentConfig) {
    return request<StatusCodeDictionary>(config, "/api/status-codes");
  }
};

export const licenseApi = {
  status(config: AgentConfig) {
    return request<LicenseStatus>(config, "/api/license/status");
  },
  deviceId(config: AgentConfig) {
    return request<LicenseDeviceIdResult>(config, "/api/license/device-id");
  },
  importLicense(config: AgentConfig, licenseText: string) {
    return request<LicenseStatus>(config, "/api/license/import", {
      method: "POST",
      body: JSON.stringify({ licenseText })
    });
  }
};

export const runsApi = {
  list(config: AgentConfig, options: {
    page?: number;
    pageSize?: number;
    state?: string;
    taskId?: string;
    q?: string;
    scope?: "all" | "operations";
    legacyLimit?: number;
    cursor?: string;
    includeTotal?: boolean;
  } = {}) {
    const params = new URLSearchParams();
    params.set("page", String(options.page ?? 1));
    params.set("pageSize", String(options.pageSize ?? 100));
    params.set("limit", String(options.legacyLimit ?? options.pageSize ?? 100));
    if (options.scope && options.scope !== "all") {
      params.set("scope", options.scope);
    }
    if (options.state && options.state !== "ALL") {
      params.set("state", options.state);
    }
    if (options.taskId && options.taskId !== "ALL") {
      params.set("taskId", options.taskId);
    }
    if (options.q?.trim()) {
      params.set("q", options.q.trim());
    }
    if (options.cursor?.trim()) {
      params.set("cursor", options.cursor.trim());
    }
    if (options.includeTotal === false) {
      params.set("includeTotal", "false");
    }
    return request<RunPageResult>(config, `/api/runs?${params.toString()}`);
  },
  recentEvents(config: AgentConfig, options: {
    page?: number;
    pageSize?: number;
    state?: string;
    taskId?: string;
    q?: string;
    cursor?: string;
    includeTotal?: boolean;
  } = {}) {
    const params = new URLSearchParams();
    params.set("page", String(options.page ?? 1));
    params.set("pageSize", String(options.pageSize ?? 100));
    if (options.state && options.state !== "ALL") {
      params.set("state", options.state);
    }
    if (options.taskId && options.taskId !== "ALL") {
      params.set("taskId", options.taskId);
    }
    if (options.q?.trim()) {
      params.set("q", options.q.trim());
    }
    if (options.cursor?.trim()) {
      params.set("cursor", options.cursor.trim());
    }
    if (options.includeTotal === false) {
      params.set("includeTotal", "false");
    }
    return request<EventPageResult>(config, `/api/events?${params.toString()}`);
  }
};

export const taskActionsApi = {
  cancel(config: AgentConfig, taskId: string) {
    return request<ActionResult>(config, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, { method: "POST" });
  },
  preflight(config: AgentConfig, taskId: string) {
    return request<ActionResult>(config, `/api/tasks/${encodeURIComponent(taskId)}/preflight`, { method: "POST" });
  }
};
