declare global {
  interface Window {
    __TAURI__?: unknown;
    __TAURI_INTERNALS__?: unknown;
  }
}

function isDesktopRuntime(): boolean {
  return Boolean(window.__TAURI_INTERNALS__ || window.__TAURI__);
}

async function invokeDesktop<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isDesktopRuntime()) {
    throw new Error("打开系统文件需要在桌面端使用。");
  }
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke<T>(command, args);
}

export const systemFileOpenApi = {
  open(path: string): Promise<void> {
    return invokeDesktop<void>("open_file_path", { path });
  }
};
