#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::fs::{self, File};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Manager, State, WindowEvent};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

const EMBEDDED_RUNTIME_DIR: &str = "embedded-runtime";
const EMBEDDED_AGENT_DIR: &str = "agent";
const EMBEDDED_AGENT_JAR: &str = "filebridge-agent.jar";
const EMBEDDED_AGENT_CONFIG: &str = "agent-config.json";
const EMBEDDED_INIT_SQL: &str = "sql/sqlite-init.sql";
const EMBEDDED_JRE_DIR: &str = "jre";
const EMBEDDED_RUNTIME_ROOT: &str = "agent-runtime";
const WINDOWS_SERVICE_NAME: &str = "FileBridgeAgent";
const WINDOWS_SERVICE_INSTALL_DIR: &str = "FileBridge";
const DEFAULT_AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Default)]
struct AgentProcessState {
    runtime: Mutex<AgentProcessRuntime>,
    keep_agent_on_close: Mutex<bool>,
}

#[derive(Default)]
struct AgentProcessRuntime {
    child: Option<Child>,
    pid: Option<u32>,
    started_at_epoch_ms: Option<u64>,
    exited_at_epoch_ms: Option<u64>,
    exit_code: Option<i32>,
    last_error: Option<String>,
}

#[derive(Clone)]
struct AgentLaunchPaths {
    workspace_root: PathBuf,
    working_dir: PathBuf,
    java_command: String,
    jar_path: PathBuf,
    config_path: PathBuf,
    api_port: u16,
    stdout_log: PathBuf,
    stderr_log: PathBuf,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentProcessStatus {
    available: bool,
    running: bool,
    managed: bool,
    pid: Option<u32>,
    started_at_epoch_ms: Option<u64>,
    exited_at_epoch_ms: Option<u64>,
    exit_code: Option<i32>,
    workspace_root: String,
    java_command: String,
    jar_path: String,
    config_path: String,
    stdout_log: String,
    stderr_log: String,
    jar_exists: bool,
    config_exists: bool,
    service_installed: bool,
    service_name: String,
    service_state: String,
    keep_agent_on_close: bool,
    message: String,
    last_error: Option<String>,
}

#[cfg(windows)]
struct WindowsServiceProbe {
    state: String,
    running: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FileClipboardStatus {
    available: bool,
    paths: Vec<String>,
    cut: bool,
    message: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FilePasteResult {
    ok: bool,
    pasted_paths: Vec<String>,
    file_count: u64,
    directory_count: u64,
    moved: bool,
    message: String,
}

#[tauri::command]
fn agent_process_status(
    app: AppHandle,
    state: State<'_, AgentProcessState>,
) -> Result<AgentProcessStatus, String> {
    let mut runtime = state
        .runtime
        .lock()
        .map_err(|_| "Agent process state lock poisoned".to_string())?;
    refresh_runtime(&mut runtime);
    let keep_agent_on_close = read_close_policy(&state);
    if let Some(status) = windows_service_status(&runtime, keep_agent_on_close)? {
        return Ok(status);
    }
    match discover_launch_paths(&app) {
        Ok(paths) => Ok(build_status(
            &runtime,
            &paths,
            keep_agent_on_close,
            status_message(&runtime),
        )),
        Err(error) => Ok(build_runtime_error_status(
            &runtime,
            keep_agent_on_close,
            &error,
        )),
    }
}

#[tauri::command]
fn agent_process_start(
    app: AppHandle,
    state: State<'_, AgentProcessState>,
) -> Result<AgentProcessStatus, String> {
    let keep_agent_on_close = read_close_policy(&state);
    if let Some(status) = start_windows_service_if_installed(keep_agent_on_close)? {
        return Ok(status);
    }
    let paths = discover_launch_paths(&app)?;
    let mut runtime = state
        .runtime
        .lock()
        .map_err(|_| "Agent process state lock poisoned".to_string())?;
    refresh_runtime(&mut runtime);
    start_runtime(&mut runtime, &paths, keep_agent_on_close)
}

#[tauri::command]
fn agent_process_stop(
    app: AppHandle,
    state: State<'_, AgentProcessState>,
) -> Result<AgentProcessStatus, String> {
    let mut runtime = state
        .runtime
        .lock()
        .map_err(|_| "Agent process state lock poisoned".to_string())?;
    refresh_runtime(&mut runtime);
    let keep_agent_on_close = read_close_policy(&state);
    if let Some(status) = stop_windows_service_if_installed(&runtime, keep_agent_on_close)? {
        return Ok(status);
    }
    let paths = discover_launch_paths(&app).ok();
    stop_runtime(&mut runtime, paths.as_ref(), keep_agent_on_close)
}

#[tauri::command]
fn agent_process_restart(
    app: AppHandle,
    state: State<'_, AgentProcessState>,
) -> Result<AgentProcessStatus, String> {
    let keep_agent_on_close = read_close_policy(&state);
    if let Some(status) = restart_windows_service_if_installed(keep_agent_on_close)? {
        return Ok(status);
    }
    let paths = discover_launch_paths(&app)?;
    let mut runtime = state
        .runtime
        .lock()
        .map_err(|_| "Agent process state lock poisoned".to_string())?;
    refresh_runtime(&mut runtime);
    if runtime.child.is_some() {
        stop_child(&mut runtime);
    }
    start_runtime(&mut runtime, &paths, keep_agent_on_close)
}

#[tauri::command]
fn agent_process_set_close_policy(
    app: AppHandle,
    state: State<'_, AgentProcessState>,
    keep_agent_on_close: bool,
) -> Result<AgentProcessStatus, String> {
    {
        let mut policy = state
            .keep_agent_on_close
            .lock()
            .map_err(|_| "Agent close policy lock poisoned".to_string())?;
        *policy = keep_agent_on_close;
    }

    let mut runtime = state
        .runtime
        .lock()
        .map_err(|_| "Agent process state lock poisoned".to_string())?;
    refresh_runtime(&mut runtime);

    if let Some(status) = windows_service_status(&runtime, keep_agent_on_close)? {
        return Ok(status);
    }

    match discover_launch_paths(&app) {
        Ok(paths) => Ok(build_status(
            &runtime,
            &paths,
            keep_agent_on_close,
            status_message(&runtime),
        )),
        Err(error) => Ok(build_runtime_error_status(
            &runtime,
            keep_agent_on_close,
            &error,
        )),
    }
}

#[tauri::command]
fn file_clipboard_read() -> Result<FileClipboardStatus, String> {
    read_system_file_clipboard()
}

#[tauri::command]
fn file_clipboard_write(paths: Vec<String>, cut: bool) -> Result<FileClipboardStatus, String> {
    write_system_file_clipboard(paths, cut)
}

#[tauri::command]
fn file_clipboard_paste(target_directory: String) -> Result<FilePasteResult, String> {
    paste_system_file_clipboard(target_directory)
}

#[tauri::command]
fn open_file_path(path: String) -> Result<(), String> {
    open_file_with_default_app(path.trim())
}

fn main() {
    tauri::Builder::default()
        .manage(AgentProcessState::default())
        .invoke_handler(tauri::generate_handler![
            agent_process_status,
            agent_process_start,
            agent_process_stop,
            agent_process_restart,
            agent_process_set_close_policy,
            file_clipboard_read,
            file_clipboard_write,
            file_clipboard_paste,
            open_file_path
        ])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { .. } = event {
                let state = window.state::<AgentProcessState>();
                if read_close_policy_from_state(&state) {
                    return;
                }
                if let Ok(mut runtime) = state.runtime.lock() {
                    if runtime.child.is_some() {
                        stop_child(&mut runtime);
                    }
                };
            }
        })
        .run(tauri::generate_context!())
        .expect("failed to run FileBridge");
}

#[cfg(windows)]
fn open_file_with_default_app(path_text: &str) -> Result<(), String> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::UI::Shell::ShellExecuteW;

    let path = Path::new(path_text);
    if !path.exists() {
        return Err(format!("文件不存在，无法打开：{}", display_path(path)));
    }

    let operation: Vec<u16> = OsStr::new("open").encode_wide().chain(Some(0)).collect();
    let file: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            operation.as_ptr(),
            file.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            1,
        )
    };
    let code = result as isize;
    if code <= 32 {
        return Err(format!("系统无法打开该文件（ShellExecuteW={}）。", code));
    }
    Ok(())
}

#[cfg(not(windows))]
fn open_file_with_default_app(path_text: &str) -> Result<(), String> {
    let path = Path::new(path_text);
    if !path.exists() {
        return Err(format!("文件不存在，无法打开：{}", display_path(path)));
    }
    let program = if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    Command::new(program)
        .arg(path)
        .spawn()
        .map_err(|err| format!("系统无法打开该文件：{err}"))?;
    Ok(())
}

#[cfg(windows)]
fn read_system_file_clipboard() -> Result<FileClipboardStatus, String> {
    use windows_sys::Win32::Foundation::HGLOBAL;
    use windows_sys::Win32::System::DataExchange::{
        GetClipboardData, IsClipboardFormatAvailable,
    };
    use windows_sys::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    use windows_sys::Win32::System::Ole::{CF_HDROP, DROPEFFECT_MOVE};
    use windows_sys::Win32::UI::Shell::DragQueryFileW;

    with_open_clipboard(|| unsafe {
        if IsClipboardFormatAvailable(CF_HDROP as u32) == 0 {
            return Ok(FileClipboardStatus {
                available: false,
                paths: Vec::new(),
                cut: false,
                message: "系统剪贴板里没有文件。".to_string(),
            });
        }

        let hdrop = GetClipboardData(CF_HDROP as u32);
        if hdrop.is_null() {
            return Err("读取系统文件剪贴板失败。".to_string());
        }

        let count = DragQueryFileW(hdrop as _, u32::MAX, std::ptr::null_mut(), 0);
        let mut paths = Vec::with_capacity(count as usize);
        for index in 0..count {
            let len = DragQueryFileW(hdrop as _, index, std::ptr::null_mut(), 0);
            if len == 0 {
                continue;
            }
            let mut buffer = vec![0u16; len as usize + 1];
            let copied = DragQueryFileW(hdrop as _, index, buffer.as_mut_ptr(), len + 1);
            if copied > 0 {
                paths.push(String::from_utf16_lossy(&buffer[..copied as usize]));
            }
        }

        let mut cut = false;
        let drop_effect_format = register_clipboard_format("Preferred DropEffect");
        if drop_effect_format != 0 && IsClipboardFormatAvailable(drop_effect_format) != 0 {
            let effect_handle = GetClipboardData(drop_effect_format) as HGLOBAL;
            if !effect_handle.is_null() {
                let effect_ptr = GlobalLock(effect_handle) as *const u32;
                if !effect_ptr.is_null() {
                    let effect = effect_ptr.read_unaligned();
                    cut = effect & DROPEFFECT_MOVE != 0;
                    GlobalUnlock(effect_handle);
                }
            }
        }

        Ok(FileClipboardStatus {
            available: !paths.is_empty(),
            message: if paths.is_empty() {
                "系统剪贴板里没有文件。".to_string()
            } else {
                format!("系统剪贴板包含 {} 个文件项目。", paths.len())
            },
            paths,
            cut,
        })
    })
}

#[cfg(not(windows))]
fn read_system_file_clipboard() -> Result<FileClipboardStatus, String> {
    Ok(FileClipboardStatus {
        available: false,
        paths: Vec::new(),
        cut: false,
        message: "当前系统暂未支持文件剪贴板。".to_string(),
    })
}

#[cfg(windows)]
fn write_system_file_clipboard(paths: Vec<String>, cut: bool) -> Result<FileClipboardStatus, String> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::System::DataExchange::{EmptyClipboard, SetClipboardData};
    use windows_sys::Win32::System::Memory::{
        GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE, GMEM_ZEROINIT,
    };
    use windows_sys::Win32::System::Ole::{CF_HDROP, DROPEFFECT_COPY, DROPEFFECT_MOVE};
    use windows_sys::Win32::UI::Shell::DROPFILES;

    let normalized_paths: Vec<String> = paths
        .into_iter()
        .map(|path| path.trim().to_string())
        .filter(|path| !path.is_empty())
        .collect();
    if normalized_paths.is_empty() {
        return Err("没有可复制到系统剪贴板的文件。".to_string());
    }
    for path in &normalized_paths {
        if !Path::new(path).exists() {
            return Err(format!("文件不存在，无法写入系统剪贴板：{path}"));
        }
    }

    with_open_clipboard(|| unsafe {
        let mut file_list = Vec::<u16>::new();
        for path in &normalized_paths {
            file_list.extend(OsStr::new(path).encode_wide());
            file_list.push(0);
        }
        file_list.push(0);

        let header_size = std::mem::size_of::<DROPFILES>();
        let total_size = header_size + file_list.len() * std::mem::size_of::<u16>();
        let hdrop = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, total_size);
        if hdrop.is_null() {
            return Err("分配系统剪贴板内存失败。".to_string());
        }
        let hdrop_ptr = GlobalLock(hdrop) as *mut u8;
        if hdrop_ptr.is_null() {
            return Err("锁定系统剪贴板内存失败。".to_string());
        }

        let dropfiles = hdrop_ptr as *mut DROPFILES;
        (*dropfiles).pFiles = header_size as u32;
        (*dropfiles).fWide = 1;
        std::ptr::copy_nonoverlapping(
            file_list.as_ptr() as *const u8,
            hdrop_ptr.add(header_size),
            file_list.len() * std::mem::size_of::<u16>(),
        );
        GlobalUnlock(hdrop);

        let effect_value = if cut { DROPEFFECT_MOVE } else { DROPEFFECT_COPY };
        let effect_handle = GlobalAlloc(
            GMEM_MOVEABLE | GMEM_ZEROINIT,
            std::mem::size_of::<u32>(),
        );
        if effect_handle.is_null() {
            return Err("分配剪贴板操作类型内存失败。".to_string());
        }
        let effect_ptr = GlobalLock(effect_handle) as *mut u32;
        if effect_ptr.is_null() {
            return Err("锁定剪贴板操作类型内存失败。".to_string());
        }
        effect_ptr.write_unaligned(effect_value);
        GlobalUnlock(effect_handle);

        if EmptyClipboard() == 0 {
            return Err("清空系统剪贴板失败。".to_string());
        }
        if SetClipboardData(CF_HDROP as u32, hdrop) == std::ptr::null_mut() {
            return Err("写入系统文件剪贴板失败。".to_string());
        }

        let drop_effect_format = register_clipboard_format("Preferred DropEffect");
        if drop_effect_format != 0 {
            SetClipboardData(drop_effect_format, effect_handle);
        }

        Ok(FileClipboardStatus {
            available: true,
            message: if cut {
                format!("已剪切 {} 个文件项目到系统剪贴板。", normalized_paths.len())
            } else {
                format!("已复制 {} 个文件项目到系统剪贴板。", normalized_paths.len())
            },
            paths: normalized_paths.clone(),
            cut,
        })
    })
}

#[cfg(not(windows))]
fn write_system_file_clipboard(_paths: Vec<String>, _cut: bool) -> Result<FileClipboardStatus, String> {
    Err("当前系统暂未支持文件剪贴板。".to_string())
}

#[cfg(windows)]
fn paste_system_file_clipboard(target_directory: String) -> Result<FilePasteResult, String> {
    let clipboard = read_system_file_clipboard()?;
    if !clipboard.available || clipboard.paths.is_empty() {
        return Err("系统剪贴板里没有可粘贴的文件。".to_string());
    }

    let target_dir = PathBuf::from(target_directory.trim());
    if !target_dir.is_dir() {
        return Err(format!("目标目录不存在：{}", display_path(&target_dir)));
    }

    let mut pasted_paths = Vec::new();
    let mut file_count = 0u64;
    let mut directory_count = 0u64;
    for source_text in &clipboard.paths {
        let source = PathBuf::from(source_text);
        if !source.exists() {
            return Err(format!("剪贴板文件不存在：{}", display_path(&source)));
        }
        let file_name = source
            .file_name()
            .ok_or_else(|| format!("无法识别文件名：{}", display_path(&source)))?;
        let destination = unique_destination(&target_dir.join(file_name));
        if source.is_dir() && destination.starts_with(&source) {
            return Err(format!(
                "不能把目录粘贴到它自身或子目录中：{}",
                display_path(&source)
            ));
        }
        let stats = if clipboard.cut {
            move_path(&source, &destination)?
        } else {
            copy_path(&source, &destination)?
        };
        file_count += stats.files;
        directory_count += stats.directories;
        pasted_paths.push(display_path(&destination));
    }

    Ok(FilePasteResult {
        ok: true,
        pasted_paths,
        file_count,
        directory_count,
        moved: clipboard.cut,
        message: if clipboard.cut {
            format!("已移动 {} 个文件项目。", clipboard.paths.len())
        } else {
            format!("已粘贴 {} 个文件项目。", clipboard.paths.len())
        },
    })
}

#[cfg(not(windows))]
fn paste_system_file_clipboard(_target_directory: String) -> Result<FilePasteResult, String> {
    Err("当前系统暂未支持文件剪贴板。".to_string())
}

#[cfg(windows)]
#[derive(Default)]
struct CopyStats {
    files: u64,
    directories: u64,
}

#[cfg(windows)]
fn copy_path(source: &Path, destination: &Path) -> Result<CopyStats, String> {
    let metadata = fs::metadata(source)
        .map_err(|err| format!("读取文件信息失败（{}）：{err}", display_path(source)))?;
    if metadata.is_dir() {
        fs::create_dir_all(destination)
            .map_err(|err| format!("创建目录失败（{}）：{err}", display_path(destination)))?;
        let mut stats = CopyStats {
            files: 0,
            directories: 1,
        };
        let entries = fs::read_dir(source)
            .map_err(|err| format!("读取目录失败（{}）：{err}", display_path(source)))?;
        for entry in entries {
            let entry = entry.map_err(|err| format!("读取目录项失败：{err}"))?;
            let child_source = entry.path();
            let child_destination = destination.join(entry.file_name());
            let child_stats = copy_path(&child_source, &child_destination)?;
            stats.files += child_stats.files;
            stats.directories += child_stats.directories;
        }
        Ok(stats)
    } else {
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)
                .map_err(|err| format!("创建目标目录失败（{}）：{err}", display_path(parent)))?;
        }
        fs::copy(source, destination).map_err(|err| {
            format!(
                "复制文件失败（{} -> {}）：{err}",
                display_path(source),
                display_path(destination)
            )
        })?;
        Ok(CopyStats {
            files: 1,
            directories: 0,
        })
    }
}

#[cfg(windows)]
fn move_path(source: &Path, destination: &Path) -> Result<CopyStats, String> {
    let stats = count_path(source)?;
    if fs::rename(source, destination).is_ok() {
        return Ok(stats);
    }
    let copied = copy_path(source, destination)?;
    let metadata = fs::metadata(source)
        .map_err(|err| format!("读取待移动文件失败（{}）：{err}", display_path(source)))?;
    if metadata.is_dir() {
        fs::remove_dir_all(source)
            .map_err(|err| format!("删除原目录失败（{}）：{err}", display_path(source)))?;
    } else {
        fs::remove_file(source)
            .map_err(|err| format!("删除原文件失败（{}）：{err}", display_path(source)))?;
    }
    Ok(copied)
}

#[cfg(windows)]
fn count_path(path: &Path) -> Result<CopyStats, String> {
    let metadata = fs::metadata(path)
        .map_err(|err| format!("读取文件信息失败（{}）：{err}", display_path(path)))?;
    if !metadata.is_dir() {
        return Ok(CopyStats {
            files: 1,
            directories: 0,
        });
    }
    let mut stats = CopyStats {
        files: 0,
        directories: 1,
    };
    for entry in fs::read_dir(path)
        .map_err(|err| format!("读取目录失败（{}）：{err}", display_path(path)))?
    {
        let child = entry.map_err(|err| format!("读取目录项失败：{err}"))?.path();
        let child_stats = count_path(&child)?;
        stats.files += child_stats.files;
        stats.directories += child_stats.directories;
    }
    Ok(stats)
}

#[cfg(windows)]
fn unique_destination(path: &Path) -> PathBuf {
    if !path.exists() {
        return path.to_path_buf();
    }
    let parent = path.parent().unwrap_or_else(|| Path::new(""));
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("copy");
    let extension = path.extension().and_then(|value| value.to_str());
    for index in 1..10_000 {
        let suffix = if index == 1 {
            " - 副本".to_string()
        } else {
            format!(" - 副本 ({index})")
        };
        let file_name = match extension {
            Some(ext) if !ext.is_empty() => format!("{stem}{suffix}.{ext}"),
            _ => format!("{stem}{suffix}"),
        };
        let candidate = parent.join(file_name);
        if !candidate.exists() {
            return candidate;
        }
    }
    path.to_path_buf()
}

#[cfg(windows)]
fn register_clipboard_format(name: &str) -> u32 {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::System::DataExchange::RegisterClipboardFormatW;

    let mut wide: Vec<u16> = OsStr::new(name).encode_wide().collect();
    wide.push(0);
    unsafe { RegisterClipboardFormatW(wide.as_ptr()) }
}

#[cfg(windows)]
fn with_open_clipboard<T>(mut operation: impl FnMut() -> Result<T, String>) -> Result<T, String> {
    use windows_sys::Win32::System::DataExchange::{CloseClipboard, OpenClipboard};

    for _ in 0..10 {
        unsafe {
            if OpenClipboard(std::ptr::null_mut()) != 0 {
                let result = operation();
                CloseClipboard();
                return result;
            }
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err("系统剪贴板正被其他程序占用，请稍后再试。".to_string())
}

#[cfg(windows)]
fn windows_service_status(
    _runtime: &AgentProcessRuntime,
    keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    let Some(probe) = probe_windows_service()? else {
        return Ok(None);
    };
    let paths = windows_service_launch_paths();
    Ok(Some(build_service_status(
        &paths,
        &probe,
        keep_agent_on_close,
        "Windows Service is installed.",
    )))
}

#[cfg(not(windows))]
fn windows_service_status(
    _runtime: &AgentProcessRuntime,
    _keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    Ok(None)
}

#[cfg(windows)]
fn start_windows_service_if_installed(
    keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    let Some(_) = probe_windows_service()? else {
        return Ok(None);
    };
    control_windows_service("start")?;
    let probe = probe_windows_service()?.ok_or_else(|| {
        format!("Windows Service {WINDOWS_SERVICE_NAME} disappeared after start command.")
    })?;
    let paths = windows_service_launch_paths();
    Ok(Some(build_service_status(
        &paths,
        &probe,
        keep_agent_on_close,
        "Windows Service start command was issued.",
    )))
}

#[cfg(not(windows))]
fn start_windows_service_if_installed(
    _keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    Ok(None)
}

#[cfg(windows)]
fn stop_windows_service_if_installed(
    _runtime: &AgentProcessRuntime,
    keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    let Some(_) = probe_windows_service()? else {
        return Ok(None);
    };
    control_windows_service("stop")?;
    let probe = probe_windows_service()?.ok_or_else(|| {
        format!("Windows Service {WINDOWS_SERVICE_NAME} disappeared after stop command.")
    })?;
    let paths = windows_service_launch_paths();
    Ok(Some(build_service_status(
        &paths,
        &probe,
        keep_agent_on_close,
        "Windows Service stop command was issued.",
    )))
}

#[cfg(not(windows))]
fn stop_windows_service_if_installed(
    _runtime: &AgentProcessRuntime,
    _keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    Ok(None)
}

#[cfg(windows)]
fn restart_windows_service_if_installed(
    keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    let Some(_) = probe_windows_service()? else {
        return Ok(None);
    };
    control_windows_service("restart")?;
    let probe = probe_windows_service()?.ok_or_else(|| {
        format!("Windows Service {WINDOWS_SERVICE_NAME} disappeared after restart command.")
    })?;
    let paths = windows_service_launch_paths();
    Ok(Some(build_service_status(
        &paths,
        &probe,
        keep_agent_on_close,
        "Windows Service restart command was issued.",
    )))
}

#[cfg(not(windows))]
fn restart_windows_service_if_installed(
    _keep_agent_on_close: bool,
) -> Result<Option<AgentProcessStatus>, String> {
    Ok(None)
}

#[cfg(windows)]
fn probe_windows_service() -> Result<Option<WindowsServiceProbe>, String> {
    let output = windows_command("sc.exe", &["query", WINDOWS_SERVICE_NAME])?;
    if !output.success && is_windows_service_missing(&output) {
        return Ok(None);
    }
    if !output.success {
        return Err(first_non_empty(
            &output.stderr,
            &output.stdout,
            "Probe Windows Service failed.",
        ));
    }
    let state = parse_sc_service_state(&output.stdout).unwrap_or_else(|| "UNKNOWN".to_string());
    Ok(Some(WindowsServiceProbe {
        running: state.eq_ignore_ascii_case("running"),
        state,
    }))
}

#[cfg(windows)]
fn control_windows_service(action: &str) -> Result<(), String> {
    match action {
        "start" => start_windows_service_command(),
        "stop" => stop_windows_service_command(),
        "restart" => {
            stop_windows_service_command()?;
            start_windows_service_command()
        }
        _ => return Err(format!("Unsupported Windows Service action: {action}")),
    }
}

#[cfg(windows)]
fn start_windows_service_command() -> Result<(), String> {
    if let Some(probe) = probe_windows_service()? {
        if probe.running {
            return Ok(());
        }
    }

    let output = windows_command("sc.exe", &["start", WINDOWS_SERVICE_NAME])?;
    match wait_for_windows_service_state("RUNNING", 20) {
        Ok(()) => Ok(()),
        Err(wait_error) if !output.success => Err(first_non_empty(
            &output.stderr,
            &output.stdout,
            &wait_error,
        )),
        Err(wait_error) => Err(wait_error),
    }
}

#[cfg(windows)]
fn stop_windows_service_command() -> Result<(), String> {
    if let Some(probe) = probe_windows_service()? {
        if probe.state.eq_ignore_ascii_case("STOPPED") {
            return Ok(());
        }
    }

    let output = windows_command("sc.exe", &["stop", WINDOWS_SERVICE_NAME])?;
    match wait_for_windows_service_state("STOPPED", 20) {
        Ok(()) => Ok(()),
        Err(wait_error) if !output.success => Err(first_non_empty(
            &output.stderr,
            &output.stdout,
            &wait_error,
        )),
        Err(wait_error) => Err(wait_error),
    }
}

#[cfg(windows)]
fn wait_for_windows_service_state(target_state: &str, max_seconds: u64) -> Result<(), String> {
    for _ in 0..max_seconds {
        let Some(probe) = probe_windows_service()? else {
            return Err(format!("Windows Service {WINDOWS_SERVICE_NAME} is not installed."));
        };
        if probe.state.eq_ignore_ascii_case(target_state) {
            return Ok(());
        }
        thread::sleep(Duration::from_secs(1));
    }
    Err(format!(
        "Windows Service {WINDOWS_SERVICE_NAME} did not reach {target_state} in time."
    ))
}

#[cfg(windows)]
fn parse_sc_service_state(text: &str) -> Option<String> {
    const STATES: [&str; 7] = [
        "STOPPED",
        "START_PENDING",
        "STOP_PENDING",
        "RUNNING",
        "CONTINUE_PENDING",
        "PAUSE_PENDING",
        "PAUSED",
    ];

    for token in text.split_whitespace() {
        let cleaned = token
            .trim_matches(|ch: char| !ch.is_ascii_alphanumeric() && ch != '_')
            .to_ascii_uppercase();
        if STATES.contains(&cleaned.as_str()) {
            return Some(cleaned);
        }
    }
    None
}

#[cfg(windows)]
fn is_windows_service_missing(output: &CommandOutput) -> bool {
    let combined = format!("{} {}", output.stdout, output.stderr).to_ascii_lowercase();
    output.exit_code == Some(1060)
        || combined.contains("1060")
        || combined.contains("does not exist")
}

#[cfg(windows)]
struct CommandOutput {
    success: bool,
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
}

#[cfg(windows)]
fn windows_command(program: &str, args: &[&str]) -> Result<CommandOutput, String> {
    let mut command = Command::new(program);
    command.args(args);
    command.creation_flags(0x08000000);
    let output = command
        .output()
        .map_err(|err| format!("Run {program} failed: {err}"))?;
    Ok(CommandOutput {
        success: output.status.success(),
        exit_code: output.status.code(),
        stdout: String::from_utf8_lossy(&output.stdout).trim().to_string(),
        stderr: String::from_utf8_lossy(&output.stderr).trim().to_string(),
    })
}

#[cfg(windows)]
fn windows_service_launch_paths() -> AgentLaunchPaths {
    let install_root = program_files_dir().join(WINDOWS_SERVICE_INSTALL_DIR);
    let config_path = install_root.join("config").join("agent-config.json");
    let bundled_java = install_root
        .join("runtime")
        .join("jre")
        .join("bin")
        .join(java_binary_name());
    let java_command = if bundled_java.exists() {
        display_path(&bundled_java)
    } else {
        "java".to_string()
    };
    let api_port = read_agent_api_port(&config_path);
    AgentLaunchPaths {
        workspace_root: install_root.clone(),
        working_dir: install_root.clone(),
        java_command,
        jar_path: install_root.join("agent").join(EMBEDDED_AGENT_JAR),
        config_path,
        api_port,
        stdout_log: install_root.join("logs").join("service.out.log"),
        stderr_log: install_root.join("logs").join("service.err.log"),
    }
}

#[cfg(windows)]
fn program_files_dir() -> PathBuf {
    std::env::var_os("ProgramFiles")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Program Files"))
}

fn start_runtime(
    runtime: &mut AgentProcessRuntime,
    paths: &AgentLaunchPaths,
    keep_agent_on_close: bool,
) -> Result<AgentProcessStatus, String> {
    if runtime.child.is_some() {
        return Ok(build_status(
            runtime,
            paths,
            keep_agent_on_close,
            "Desktop shell is already managing the local Java Agent.",
        ));
    }
    if !paths.jar_path.exists() {
        return Err(format!(
            "Agent jar not found: {}. Run the Tauri bundle preparation step again.",
            display_path(&paths.jar_path)
        ));
    }
    if !paths.config_path.exists() {
        return Err(format!(
            "Agent config not found: {}",
            display_path(&paths.config_path)
        ));
    }
    prepare_agent_port(runtime, paths)?;

    if let Some(parent) = paths.stdout_log.parent() {
        fs::create_dir_all(parent).map_err(|err| format!("Create log directory failed: {err}"))?;
    }
    let stdout = File::create(&paths.stdout_log)
        .map_err(|err| format!("Open stdout log failed: {err}"))?;
    let stderr = File::create(&paths.stderr_log)
        .map_err(|err| format!("Open stderr log failed: {err}"))?;

    let mut command = Command::new(&paths.java_command);
    command
        .arg("-jar")
        .arg(&paths.jar_path)
        .arg("--config")
        .arg(&paths.config_path)
        .current_dir(&paths.working_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));

    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    let child = command
        .spawn()
        .map_err(|err| format!("Start Agent process failed: {err}"))?;
    runtime.pid = Some(child.id());
    runtime.started_at_epoch_ms = Some(now_epoch_ms());
    runtime.exited_at_epoch_ms = None;
    runtime.exit_code = None;
    runtime.last_error = None;
    runtime.child = Some(child);

    Ok(build_status(
        runtime,
        paths,
        keep_agent_on_close,
        "Agent start command was issued. Waiting for health checks to recover.",
    ))
}

fn stop_runtime(
    runtime: &mut AgentProcessRuntime,
    paths: Option<&AgentLaunchPaths>,
    keep_agent_on_close: bool,
) -> Result<AgentProcessStatus, String> {
    if runtime.child.is_none() {
        runtime.last_error = None;
        return Ok(match paths {
            Some(paths) => build_status(
                runtime,
                paths,
                keep_agent_on_close,
                "This desktop session is not managing an Agent process.",
            ),
            None => build_runtime_error_status(
                runtime,
                keep_agent_on_close,
                "Desktop shell is available, but Agent launch resources were not found.",
            ),
        });
    }

    stop_child(runtime);

    Ok(match paths {
        Some(paths) => build_status(runtime, paths, keep_agent_on_close, "Agent has been stopped."),
        None => build_runtime_error_status(
            runtime,
            keep_agent_on_close,
            "Agent has been stopped, but launch resources are no longer available.",
        ),
    })
}

fn stop_child(runtime: &mut AgentProcessRuntime) {
    if let Some(mut child) = runtime.child.take() {
        match child.kill() {
            Ok(()) => match child.wait() {
                Ok(status) => {
                    runtime.exit_code = status.code();
                    runtime.last_error = None;
                }
                Err(err) => {
                    runtime.exit_code = None;
                    runtime.last_error = Some(format!("Wait Agent process failed: {err}"));
                }
            },
            Err(err) => {
                runtime.exit_code = None;
                runtime.last_error = Some(format!("Stop Agent process failed: {err}"));
            }
        }
        runtime.exited_at_epoch_ms = Some(now_epoch_ms());
    }
}

fn refresh_runtime(runtime: &mut AgentProcessRuntime) {
    let mut finished = None;
    if let Some(child) = runtime.child.as_mut() {
        match child.try_wait() {
            Ok(Some(status)) => {
                finished = Some((status.code(), None));
            }
            Ok(None) => {}
            Err(err) => {
                finished = Some((None, Some(format!("Probe Agent process failed: {err}"))));
            }
        }
    }
    if let Some((exit_code, error)) = finished {
        runtime.child = None;
        runtime.exit_code = exit_code;
        runtime.exited_at_epoch_ms = Some(now_epoch_ms());
        runtime.last_error = error;
    }
}

fn prepare_agent_port(
    runtime: &AgentProcessRuntime,
    paths: &AgentLaunchPaths,
) -> Result<(), String> {
    let port = paths.api_port;
    if !is_local_port_open(port) {
        return Ok(());
    }

    let pids = listening_pids(port)?;
    if pids.is_empty() {
        return Err(format!(
            "Agent API port {port} is occupied, but the owning process could not be identified."
        ));
    }

    for pid in &pids {
        if runtime.pid == Some(*pid) {
            return Ok(());
        }
        let command = process_command_line(*pid).unwrap_or_default();
        if !looks_like_agent_process(&command, paths) {
            return Err(format!(
                "Agent API port {port} is occupied by an unmanaged process (pid={pid})."
            ));
        }
    }

    for pid in &pids {
        soft_close_pid(*pid)?;
    }

    if wait_for_port_close(port, Duration::from_secs(7)) {
        Ok(())
    } else {
        Err(format!(
            "Agent API port {port} is still occupied after soft close: {:?}.",
            listening_pids(port).unwrap_or_default()
        ))
    }
}

fn is_local_port_open(port: u16) -> bool {
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    TcpStream::connect_timeout(&addr, Duration::from_millis(400)).is_ok()
}

fn wait_for_port_close(port: u16, timeout: Duration) -> bool {
    let started = SystemTime::now();
    loop {
        if !is_local_port_open(port) {
            return true;
        }
        if started.elapsed().unwrap_or_default() >= timeout {
            return false;
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn looks_like_agent_process(command: &str, paths: &AgentLaunchPaths) -> bool {
    let normalized = command.to_ascii_lowercase();
    let jar = display_path(&paths.jar_path).to_ascii_lowercase();
    let workspace = display_path(&paths.workspace_root).to_ascii_lowercase();
    normalized.contains("filebridge-agent")
        || normalized.contains("ftp-sync-agent")
        || normalized.contains(&jar)
        || (normalized.contains("java") && normalized.contains(&workspace))
}

fn read_agent_api_port(config_path: &Path) -> u16 {
    let text = match fs::read_to_string(config_path) {
        Ok(text) => text,
        Err(_) => return 18090,
    };
    serde_json::from_str::<serde_json::Value>(&text)
        .ok()
        .and_then(|value| value.pointer("/api/port").and_then(|port| port.as_u64()))
        .filter(|port| *port > 0 && *port <= u16::MAX as u64)
        .map(|port| port as u16)
        .unwrap_or(18090)
}

#[cfg(windows)]
fn listening_pids(port: u16) -> Result<Vec<u32>, String> {
    let output = windows_command("netstat.exe", &["-ano", "-p", "tcp"])?;
    if !output.success {
        return Err(first_non_empty(
            &output.stderr,
            &output.stdout,
            "Probe port owner failed.",
        ));
    }
    Ok(parse_netstat_pids(&output.stdout, port))
}

#[cfg(not(windows))]
fn listening_pids(port: u16) -> Result<Vec<u32>, String> {
    let output = Command::new("sh")
        .arg("-c")
        .arg(format!("lsof -ti tcp:{port} -sTCP:LISTEN 2>/dev/null || true"))
        .output()
        .map_err(|err| format!("Probe port owner failed: {err}"))?;
    Ok(parse_pid_lines(&String::from_utf8_lossy(&output.stdout)))
}

#[cfg(windows)]
fn process_command_line(pid: u32) -> Result<String, String> {
    let filter = format!("ProcessId={pid}");
    let output = windows_command(
        "wmic.exe",
        &[
            "process",
            "where",
            &filter,
            "get",
            "CommandLine",
            "/format:list",
        ],
    )?;
    if !output.success {
        return Err(first_non_empty(
            &output.stderr,
            &output.stdout,
            "Probe process command line failed.",
        ));
    }
    Ok(parse_wmic_list_value(&output.stdout, "CommandLine").unwrap_or_default())
}

#[cfg(windows)]
fn parse_netstat_pids(text: &str, port: u16) -> Vec<u32> {
    let mut pids = Vec::new();
    for line in text.lines() {
        if !line.to_ascii_uppercase().contains("LISTENING") {
            continue;
        }
        let columns = line.split_whitespace().collect::<Vec<_>>();
        if columns.len() < 5 {
            continue;
        }
        let local_address = columns[1];
        let pid_text = columns[columns.len() - 1];
        let Ok(pid) = pid_text.parse::<u32>() else {
            continue;
        };
        if address_uses_port(local_address, port) && !pids.contains(&pid) {
            pids.push(pid);
        }
    }
    pids
}

#[cfg(windows)]
fn address_uses_port(address: &str, port: u16) -> bool {
    address.ends_with(&format!(":{port}")) || address.ends_with(&format!("]:{port}"))
}

#[cfg(windows)]
fn parse_wmic_list_value(text: &str, target_key: &str) -> Option<String> {
    for line in text.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if key.trim().eq_ignore_ascii_case(target_key) {
            return Some(value.trim().to_string());
        }
    }
    None
}

#[cfg(not(windows))]
fn process_command_line(pid: u32) -> Result<String, String> {
    let output = Command::new("sh")
        .arg("-c")
        .arg(format!("ps -p {pid} -o command= 2>/dev/null || true"))
        .output()
        .map_err(|err| format!("Probe process command line failed: {err}"))?;
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

#[cfg(windows)]
fn soft_close_pid(pid: u32) -> Result<(), String> {
    let output = Command::new("taskkill.exe")
        .args(["/PID", &pid.to_string(), "/T"])
        .output()
        .map_err(|err| format!("Soft close process failed: {err}"))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[cfg(not(windows))]
fn soft_close_pid(pid: u32) -> Result<(), String> {
    let output = Command::new("kill")
        .args(["-TERM", &pid.to_string()])
        .output()
        .map_err(|err| format!("Soft close process failed: {err}"))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[cfg(not(windows))]
fn parse_pid_lines(text: &str) -> Vec<u32> {
    let mut pids = Vec::new();
    for part in text.split_whitespace() {
        if let Ok(pid) = part.parse::<u32>() {
            if pid > 0 && !pids.contains(&pid) {
                pids.push(pid);
            }
        }
    }
    pids
}

fn first_non_empty(first: &str, second: &str, fallback: &str) -> String {
    if !first.trim().is_empty() {
        return first.trim().to_string();
    }
    if !second.trim().is_empty() {
        return second.trim().to_string();
    }
    fallback.to_string()
}

#[cfg(windows)]
fn build_service_status(
    paths: &AgentLaunchPaths,
    probe: &WindowsServiceProbe,
    keep_agent_on_close: bool,
    message: &str,
) -> AgentProcessStatus {
    AgentProcessStatus {
        available: true,
        running: probe.running,
        managed: true,
        pid: None,
        started_at_epoch_ms: None,
        exited_at_epoch_ms: None,
        exit_code: None,
        workspace_root: display_path(&paths.workspace_root),
        java_command: paths.java_command.clone(),
        jar_path: display_path(&paths.jar_path),
        config_path: display_path(&paths.config_path),
        stdout_log: display_path(&paths.stdout_log),
        stderr_log: display_path(&paths.stderr_log),
        jar_exists: paths.jar_path.exists(),
        config_exists: paths.config_path.exists(),
        service_installed: true,
        service_name: WINDOWS_SERVICE_NAME.to_string(),
        service_state: probe.state.clone(),
        keep_agent_on_close,
        message: message.to_string(),
        last_error: None,
    }
}

fn build_status(
    runtime: &AgentProcessRuntime,
    paths: &AgentLaunchPaths,
    keep_agent_on_close: bool,
    message: &str,
) -> AgentProcessStatus {
    AgentProcessStatus {
        available: true,
        running: runtime.child.is_some(),
        managed: runtime.pid.is_some(),
        pid: runtime.pid,
        started_at_epoch_ms: runtime.started_at_epoch_ms,
        exited_at_epoch_ms: runtime.exited_at_epoch_ms,
        exit_code: runtime.exit_code,
        workspace_root: display_path(&paths.workspace_root),
        java_command: paths.java_command.clone(),
        jar_path: display_path(&paths.jar_path),
        config_path: display_path(&paths.config_path),
        stdout_log: display_path(&paths.stdout_log),
        stderr_log: display_path(&paths.stderr_log),
        jar_exists: paths.jar_path.exists(),
        config_exists: paths.config_path.exists(),
        service_installed: false,
        service_name: String::new(),
        service_state: String::new(),
        keep_agent_on_close,
        message: message.to_string(),
        last_error: runtime.last_error.clone(),
    }
}

fn build_runtime_error_status(
    runtime: &AgentProcessRuntime,
    keep_agent_on_close: bool,
    error: &str,
) -> AgentProcessStatus {
    AgentProcessStatus {
        available: true,
        running: runtime.child.is_some(),
        managed: runtime.pid.is_some(),
        pid: runtime.pid,
        started_at_epoch_ms: runtime.started_at_epoch_ms,
        exited_at_epoch_ms: runtime.exited_at_epoch_ms,
        exit_code: runtime.exit_code,
        workspace_root: String::new(),
        java_command: "java".to_string(),
        jar_path: String::new(),
        config_path: String::new(),
        stdout_log: String::new(),
        stderr_log: String::new(),
        jar_exists: false,
        config_exists: false,
        service_installed: false,
        service_name: String::new(),
        service_state: String::new(),
        keep_agent_on_close,
        message: "Desktop shell is running, but Agent launch resources are unavailable.".to_string(),
        last_error: Some(error.to_string()),
    }
}

fn status_message(runtime: &AgentProcessRuntime) -> &'static str {
    if runtime.child.is_some() {
        "Desktop shell is managing the local Java Agent."
    } else {
        "Desktop shell is ready to manage the local Java Agent."
    }
}

fn read_close_policy(state: &State<'_, AgentProcessState>) -> bool {
    state
        .keep_agent_on_close
        .lock()
        .map(|policy| *policy)
        .unwrap_or(false)
}

fn read_close_policy_from_state(state: &tauri::State<'_, AgentProcessState>) -> bool {
    state
        .keep_agent_on_close
        .lock()
        .map(|policy| *policy)
        .unwrap_or(false)
}

fn discover_launch_paths(app: &AppHandle) -> Result<AgentLaunchPaths, String> {
    if let Some(paths) = try_discover_embedded_launch_paths(app)? {
        return Ok(paths);
    }
    discover_workspace_launch_paths()
}

fn try_discover_embedded_launch_paths(app: &AppHandle) -> Result<Option<AgentLaunchPaths>, String> {
    let resource_dir = match app.path().resource_dir() {
        Ok(path) => path,
        Err(_) => return Ok(None),
    };

    let embedded_root = resource_dir.join(EMBEDDED_RUNTIME_DIR);
    if !embedded_root.exists() {
        return Ok(None);
    }

    let embedded_agent_dir = embedded_root.join(EMBEDDED_AGENT_DIR);
    let java_path = embedded_root
        .join(EMBEDDED_JRE_DIR)
        .join("bin")
        .join(java_binary_name());
    let jar_path = embedded_agent_dir.join(EMBEDDED_AGENT_JAR);
    let bundled_config_path = embedded_agent_dir.join(EMBEDDED_AGENT_CONFIG);
    let bundled_init_sql_path = embedded_agent_dir.join(EMBEDDED_INIT_SQL);

    ensure_file_exists(&java_path, "Embedded Java runtime")?;
    ensure_file_exists(&jar_path, "Embedded Agent jar")?;
    ensure_file_exists(&bundled_config_path, "Embedded Agent config")?;
    ensure_file_exists(&bundled_init_sql_path, "Embedded init SQL")?;

    let runtime_root = resolve_embedded_runtime_root(app)?;
    let runtime_logs_dir = runtime_root.join("logs");
    let runtime_desktop_logs_dir = runtime_logs_dir.join("desktop");
    let runtime_sql_dir = runtime_root.join("sql");
    let runtime_data_dir = runtime_root.join("data");
    let runtime_checkpoint_dir = runtime_root.join("checkpoint");

    fs::create_dir_all(&runtime_desktop_logs_dir)
        .map_err(|err| format!("Create embedded desktop log directory failed: {err}"))?;
    fs::create_dir_all(&runtime_sql_dir)
        .map_err(|err| format!("Create embedded SQL directory failed: {err}"))?;
    fs::create_dir_all(&runtime_data_dir)
        .map_err(|err| format!("Create embedded data directory failed: {err}"))?;
    fs::create_dir_all(&runtime_checkpoint_dir)
        .map_err(|err| format!("Create embedded checkpoint directory failed: {err}"))?;

    let runtime_config_path = runtime_root.join("agent-config.json");
    let runtime_init_sql_path = runtime_sql_dir.join("sqlite-init.sql");
    let runtime_database_path = runtime_data_dir.join("filebridge.sqlite");
    let runtime_agent_log_path = runtime_logs_dir.join("filebridge-agent.log");

    copy_if_missing(&bundled_config_path, &runtime_config_path)?;
    copy_always(&bundled_init_sql_path, &runtime_init_sql_path)?;
    migrate_embedded_agent_config(
        &runtime_config_path,
        &runtime_init_sql_path,
        &runtime_database_path,
        &runtime_agent_log_path,
        &runtime_checkpoint_dir,
    )?;
    let api_port = read_agent_api_port(&runtime_config_path);

    Ok(Some(AgentLaunchPaths {
        workspace_root: runtime_root.clone(),
        working_dir: runtime_root.clone(),
        java_command: display_path(&java_path),
        jar_path,
        config_path: runtime_config_path,
        api_port,
        stdout_log: runtime_desktop_logs_dir.join("agent.out.log"),
        stderr_log: runtime_desktop_logs_dir.join("agent.err.log"),
    }))
}

fn resolve_embedded_runtime_root(app: &AppHandle) -> Result<PathBuf, String> {
    let local_runtime_root = app
        .path()
        .app_local_data_dir()
        .map(|path| path.join(EMBEDDED_RUNTIME_ROOT))
        .or_else(|_| app.path().app_data_dir().map(|path| path.join(EMBEDDED_RUNTIME_ROOT)))
        .map_err(|_| {
            "Failed to resolve the local app data directory for the embedded Agent.".to_string()
        })?;

    if local_runtime_root.exists() {
        return Ok(local_runtime_root);
    }

    if let Ok(legacy_app_data_dir) = app.path().app_data_dir() {
        let legacy_runtime_root = legacy_app_data_dir.join(EMBEDDED_RUNTIME_ROOT);
        if legacy_runtime_root.exists() && legacy_runtime_root != local_runtime_root {
            migrate_embedded_runtime_root(&legacy_runtime_root, &local_runtime_root)?;
        }
    }

    Ok(local_runtime_root)
}

fn migrate_embedded_runtime_root(source_root: &Path, destination_root: &Path) -> Result<(), String> {
    let source_config = source_root.join("agent-config.json");
    let destination_config = destination_root.join("agent-config.json");
    copy_if_missing(&source_config, &destination_config)?;

    copy_dir_if_missing(
        &source_root.join("checkpoint"),
        &destination_root.join("checkpoint"),
        "embedded checkpoint directory",
    )?;
    copy_dir_if_missing(
        &source_root.join("logs"),
        &destination_root.join("logs"),
        "embedded log directory",
    )?;
    copy_dir_if_missing(
        &source_root.join("sql"),
        &destination_root.join("sql"),
        "embedded SQL directory",
    )?;

    Ok(())
}

fn migrate_embedded_agent_config(
    config_path: &Path,
    init_sql_path: &Path,
    database_path: &Path,
    log_file: &Path,
    checkpoint_dir: &Path,
) -> Result<(), String> {
    let text = fs::read_to_string(config_path).map_err(|err| {
        format!(
            "Read embedded Agent config failed ({}): {err}",
            display_path(config_path)
        )
    })?;
    let config = serde_json::from_str::<serde_json::Value>(&text).map_err(|err| {
        format!(
            "Parse embedded Agent config failed ({}): {err}",
            display_path(config_path)
        )
    })?;
    let root = config
        .as_object()
        .ok_or_else(|| "Embedded Agent config must be a JSON object.".to_string())?;

    let mut migrated = serde_json::Map::new();
    migrated.insert(
        "api".to_string(),
        root.get("api").cloned().unwrap_or_else(|| {
            serde_json::json!({
                "host": "127.0.0.1",
                "port": 18090,
                "token": ""
            })
        }),
    );
    migrated.insert(
        "sqlite".to_string(),
        serde_json::json!({
            "databasePath": display_path(database_path)
        }),
    );
    migrated.insert(
        "paths".to_string(),
        serde_json::json!({
            "initSqlPath": display_path(init_sql_path),
            "logFile": display_path(log_file),
            "checkpointDir": display_path(checkpoint_dir)
        }),
    );
    migrated.insert(
        "retry".to_string(),
        root.get("retry").cloned().unwrap_or_else(|| {
            serde_json::json!({
                "maxAttempts": 3,
                "backoffMillis": 500
            })
        }),
    );

    let content = serde_json::to_string_pretty(&serde_json::Value::Object(migrated))
        .map_err(|err| format!("Serialize embedded Agent config failed: {err}"))?;
    fs::write(config_path, format!("{content}\n")).map_err(|err| {
        format!(
            "Write embedded Agent config failed ({}): {err}",
            display_path(config_path)
        )
    })
}

fn discover_workspace_launch_paths() -> Result<AgentLaunchPaths, String> {
    let workspace_root = discover_workspace_root()?;
    let target_dir = workspace_root.join("package").join("target");
    let jar_path = find_agent_jar(&target_dir);
    let config_path = workspace_root
        .join("dev")
        .join("agent")
        .join("config")
        .join("agent-config.json");
    let api_port = read_agent_api_port(&config_path);
    let desktop_log_dir = workspace_root.join("package").join("logs").join("desktop");

    Ok(AgentLaunchPaths {
        workspace_root: workspace_root.clone(),
        working_dir: workspace_root,
        java_command: "java".to_string(),
        jar_path,
        config_path,
        api_port,
        stdout_log: desktop_log_dir.join("agent.out.log"),
        stderr_log: desktop_log_dir.join("agent.err.log"),
    })
}

fn discover_workspace_root() -> Result<PathBuf, String> {
    if let Ok(current_dir) = std::env::current_dir() {
        if let Some(root) = find_workspace_from(&current_dir) {
            return Ok(root);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            if let Some(root) = find_workspace_from(parent) {
                return Ok(root);
            }
        }
    }
    Err("Unable to locate the FileBridge workspace root.".to_string())
}

fn find_workspace_from(start: &Path) -> Option<PathBuf> {
    for ancestor in start.ancestors() {
        let config_dir = ancestor.join("dev").join("agent").join("config");
        let has_config_template = config_dir.join("agent-config.sample.json").exists();
        let has_local_config = config_dir.join("agent-config.json").exists();
        if ancestor.join("pom.xml").exists()
            && ancestor
                .join("dev")
                .join("agent")
                .join("src")
                .join("main")
                .join("java")
                .exists()
            && (has_local_config || has_config_template)
        {
            return Some(ancestor.to_path_buf());
        }
    }
    None
}

fn find_agent_jar(target_dir: &Path) -> PathBuf {
    let default_jar = target_dir.join(format!(
        "filebridge-agent-{DEFAULT_AGENT_VERSION}-jar-with-dependencies.jar"
    ));
    if default_jar.exists() {
        return default_jar;
    }

    let mut jars = Vec::new();
    if let Ok(entries) = fs::read_dir(target_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let is_agent_jar = path
                .file_name()
                .and_then(|name| name.to_str())
                .map(|name| name.ends_with("-jar-with-dependencies.jar"))
                .unwrap_or(false);
            if is_agent_jar {
                jars.push(path);
            }
        }
    }
    jars.sort_by_key(|path| fs::metadata(path).and_then(|meta| meta.modified()).ok());
    jars.pop().unwrap_or(default_jar)
}

fn ensure_file_exists(path: &Path, label: &str) -> Result<(), String> {
    if path.exists() {
        return Ok(());
    }
    Err(format!("{label} not found: {}", display_path(path)))
}

fn copy_if_missing(source: &Path, destination: &Path) -> Result<(), String> {
    if destination.exists() {
        return Ok(());
    }
    copy_always(source, destination)
}

fn copy_always(source: &Path, destination: &Path) -> Result<(), String> {
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent).map_err(|err| format!("Create resource directory failed: {err}"))?;
    }
    fs::copy(source, destination).map_err(|err| {
        format!(
            "Copy resource failed ({} -> {}): {err}",
            display_path(source),
            display_path(destination)
        )
    })?;
    Ok(())
}

fn copy_dir_if_missing(source: &Path, destination: &Path, label: &str) -> Result<(), String> {
    if destination.exists() || !source.exists() {
        return Ok(());
    }
    copy_dir_recursive(source, destination, label)
}

fn copy_dir_recursive(source: &Path, destination: &Path, label: &str) -> Result<(), String> {
    fs::create_dir_all(destination).map_err(|err| {
        format!(
            "Create {label} failed ({}): {err}",
            display_path(destination)
        )
    })?;

    let entries = fs::read_dir(source).map_err(|err| {
        format!(
            "Read {label} failed ({}): {err}",
            display_path(source)
        )
    })?;

    for entry in entries {
        let entry = entry.map_err(|err| format!("Read {label} entry failed: {err}"))?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        let file_type = entry
            .file_type()
            .map_err(|err| format!("Read {label} entry type failed: {err}"))?;
        if file_type.is_dir() {
            copy_dir_recursive(&source_path, &destination_path, label)?;
        } else {
            copy_always(&source_path, &destination_path)?;
        }
    }

    Ok(())
}

#[cfg(windows)]
fn java_binary_name() -> &'static str {
    "java.exe"
}

#[cfg(not(windows))]
fn java_binary_name() -> &'static str {
    "java"
}

fn display_path(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}
