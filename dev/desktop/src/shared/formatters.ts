const numberFormatter = new Intl.NumberFormat("zh-CN");

export function formatNumber(value: number | null | undefined): string {
  return numberFormatter.format(value ?? 0);
}

export function formatBytes(value: number | null | undefined): string {
  const bytes = value ?? 0;
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB", "TB"];
  let size = bytes / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[unit]}`;
}

export function formatDuration(value: number | null | undefined): string {
  const ms = value ?? 0;
  if (ms <= 0) {
    return "-";
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes < 60) {
    return `${minutes} min ${rest} s`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return `${hours} h ${restMinutes} min`;
}

export function displayTime(value: string | null | undefined): string {
  if (!value) {
    return "暂无";
  }
  return value.replace("T", " ").replace(".000", "");
}

export function displayEpochMs(value: number | null | undefined): string {
  if (!value) {
    return "暂无";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

export function stateTone(state: string | null | undefined): "good" | "warn" | "bad" | "idle" | "run" {
  const text = (state ?? "").toUpperCase();
  if (text.includes("RUNNING")) {
    return "run";
  }
  if (text.includes("HEALTHY") || text.includes("SUCCESS")) {
    return "good";
  }
  if (text.includes("WARN") || text.includes("TEMPORARY") || text.includes("CANCEL")) {
    return "warn";
  }
  if (text.includes("ERROR") || text.includes("FAILED") || text.includes("NEEDS_")) {
    return "bad";
  }
  return "idle";
}

export function actionText(action: string | null | undefined): string {
  const value = (action ?? "").toUpperCase();
  const map: Record<string, string> = {
    RETRY_THEN_ALERT: "先重试，仍失败就检查网络/服务",
    RETRY_WITH_BACKOFF: "等待后重试",
    FAIL_FAST: "先修正配置，再重新执行",
    FAIL_AND_ALERT: "立即处理后重试",
    FAIL_OR_RETRY_BY_CAUSE: "按具体原因处理后重试",
    SKIP_OR_FAIL_BY_PHASE: "检查路径权限后处理",
    MARK_FAILED_AND_REVIEW: "标记失败并复查中断原因",
    STOP_AND_KEEP_STATE: "已停止，保留当前进度",
    CAPTURE_CONTEXT_AND_ALERT: "保留上下文并人工排查",
    NEEDS_NETWORK_CHECK: "检查网络、端口、防火墙或 FTP 服务",
    NEEDS_CONFIG_FIX: "修正账号、密码引用或登录权限",
    NEEDS_TLS_CONFIRMATION: "确认 FTPS 模式和证书指纹",
    NEEDS_REMOTE_PATH_CHECK: "检查远端路径和账号权限",
    NEEDS_LOCAL_PERMISSION_CHECK: "检查本地目录权限、文件锁或路径长度",
    NEEDS_DISK_CLEANUP: "清理本地磁盘空间或配额",
    NEEDS_SERVER_MAINTENANCE: "检查 FTP 服务端容量或状态",
    NEEDS_STATE_REPAIR: "检查断点目录权限和状态",
    NEEDS_AGENT_RECOVERY_REVIEW: "确认 Agent 中断原因",
    NEEDS_INVESTIGATION: "需要人工排查",
    TEMPORARY_DEGRADED: "临时异常，观察并重试",
    USER_CANCELLED: "用户已取消"
  };
  return map[value] ?? (value || "暂无建议");
}
