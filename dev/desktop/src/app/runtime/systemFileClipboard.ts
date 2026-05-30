export type SystemFileClipboardStatus = {
  available: boolean;
  paths: string[];
  cut: boolean;
  message: string;
};

export type SystemFilePasteResult = {
  ok: boolean;
  pastedPaths: string[];
  fileCount: number;
  directoryCount: number;
  moved: boolean;
  message: string;
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

async function invokeDesktop<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isDesktopRuntime()) {
    throw new Error("系统文件剪贴板需要在桌面端使用。");
  }
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke<T>(command, args);
}

export const systemFileClipboardApi = {
  read(): Promise<SystemFileClipboardStatus> {
    return invokeDesktop<SystemFileClipboardStatus>("file_clipboard_read");
  },

  write(paths: string[], cut = false): Promise<SystemFileClipboardStatus> {
    return invokeDesktop<SystemFileClipboardStatus>("file_clipboard_write", { paths, cut });
  },

  paste(targetDirectory: string): Promise<SystemFilePasteResult> {
    return invokeDesktop<SystemFilePasteResult>("file_clipboard_paste", { targetDirectory });
  }
};
