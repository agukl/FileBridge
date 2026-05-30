import { execFile, spawn } from "node:child_process";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

export const devHost = "127.0.0.1";
export const devPort = 1420;
export const devUrl = `http://${devHost}:${devPort}/`;

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const workspaceRoot = path.resolve(scriptDir, "..");
const viteBin = path.join(workspaceRoot, "node_modules", "vite", "bin", "vite.js");

export async function prepareViteDevPort({ reuseHealthy = false } = {}) {
  const ready = await isDevServerReady();
  if (ready && reuseHealthy) {
    console.log(`Vite dev server already available at ${devUrl}; reusing it.`);
    return "reused";
  }

  const pids = await listeningPids(devPort);
  if (pids.length === 0) {
    return "free";
  }

  const details = await Promise.all(pids.map(async (pid) => ({
    pid,
    info: await processInfo(pid)
  })));
  const unmanaged = details.filter((detail) => !isManagedViteProcess(detail.info));
  if (unmanaged.length > 0) {
    const summary = unmanaged.map(describeProcess).join("; ");
    throw new Error(`Port ${devPort} is occupied by an unmanaged process: ${summary}`);
  }

  const mode = ready ? "healthy old" : "unresponsive";
  console.log(`Port ${devPort} is occupied by a ${mode} Vite process; closing it before startup.`);
  for (const detail of details) {
    await softCloseProcess(detail.pid);
  }

  if (!(await waitForPortFree(devPort, 7000))) {
    const remaining = await listeningPids(devPort);
    throw new Error(
      `Port ${devPort} is still occupied after soft close: ${remaining.join(", ") || "unknown"}`
    );
  }
  return "cleaned";
}

export function runViteDev() {
  console.log(`Starting Vite dev server at ${devUrl}.`);
  const child = spawn(process.execPath, [viteBin, "--host", devHost], {
    cwd: workspaceRoot,
    stdio: "inherit",
    shell: false
  });

  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.once(signal, () => {
      child.kill(signal);
    });
  }

  child.on("exit", (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code ?? 0);
  });
}

export async function isDevServerReady() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 1500);
  try {
    const response = await fetch(devUrl, { method: "GET", signal: controller.signal });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

async function listeningPids(port) {
  const output = process.platform === "win32"
    ? await runCommand("netstat.exe", ["-ano", "-p", "tcp"], { tolerateFailure: true })
    : await runCommand("sh", ["-c", `lsof -ti tcp:${port} -sTCP:LISTEN 2>/dev/null || true`]);
  if (process.platform === "win32") {
    return parseNetstatPids(output, port);
  }
  return [...new Set(output.split(/\s+/)
    .map((value) => Number.parseInt(value, 10))
    .filter((pid) => Number.isInteger(pid) && pid > 0))];
}

async function processInfo(pid) {
  if (process.platform === "win32") {
    const output = await runCommand("wmic.exe", [
      "process",
      "where",
      `ProcessId=${pid}`,
      "get",
      "ProcessId,CommandLine,ExecutablePath",
      "/format:list"
    ], { tolerateFailure: true });
    if (!output.trim()) {
      return { pid };
    }
    const parsed = parseWmicList(output);
    return {
      pid,
      commandLine: parsed.CommandLine ?? "",
      executablePath: parsed.ExecutablePath ?? ""
    };
  }

  const output = await runCommand("sh", ["-c", `ps -p ${pid} -o command= 2>/dev/null || true`]);
  return { pid, commandLine: output.trim() };
}

function isManagedViteProcess(info) {
  const command = `${info.commandLine ?? ""} ${info.executablePath ?? ""}`.toLowerCase();
  const root = workspaceRoot.toLowerCase();
  return command.includes(root)
    && (command.includes("vite") || command.includes("npm") || command.includes("node_modules"));
}

async function softCloseProcess(pid) {
  try {
    process.kill(pid, "SIGTERM");
    return;
  } catch {
    // The process may have exited between port probing and shutdown.
  }
  if (process.platform === "win32") {
    await runCommand("taskkill.exe", ["/PID", String(pid), "/T"], { tolerateFailure: true });
  }
}

async function waitForPortFree(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await listeningPids(port)).length === 0) {
      return true;
    }
    await delay(250);
  }
  return (await listeningPids(port)).length === 0;
}

function describeProcess(detail) {
  const command = detail.info.commandLine || detail.info.executablePath || "unknown command";
  return `pid=${detail.pid}, ${command}`;
}

function parseNetstatPids(output, port) {
  const pids = new Set();
  for (const line of output.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!/\bLISTENING\b/i.test(trimmed)) {
      continue;
    }
    const columns = trimmed.split(/\s+/);
    if (columns.length < 5) {
      continue;
    }
    const localAddress = columns[1] ?? "";
    const pid = Number.parseInt(columns[columns.length - 1], 10);
    if (!Number.isInteger(pid) || pid <= 0) {
      continue;
    }
    if (addressUsesPort(localAddress, port)) {
      pids.add(pid);
    }
  }
  return [...pids];
}

function addressUsesPort(address, port) {
  const normalized = address.trim();
  if (normalized.endsWith(`:${port}`)) {
    return true;
  }
  return normalized.endsWith(`]:${port}`);
}

function parseWmicList(output) {
  const result = {};
  for (const line of output.split(/\r?\n/)) {
    const index = line.indexOf("=");
    if (index <= 0) {
      continue;
    }
    const key = line.slice(0, index).trim();
    const value = line.slice(index + 1).trim();
    if (key) {
      result[key] = value;
    }
  }
  return result;
}

function runCommand(file, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(file, args, { timeout: 5000, windowsHide: true }, (error, stdout, stderr) => {
      if (error && !options.tolerateFailure) {
        reject(new Error((stderr || error.message).trim()));
        return;
      }
      resolve(String(stdout ?? "").trim());
    });
  });
}
