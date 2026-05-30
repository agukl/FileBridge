import { spawn } from "node:child_process";
import { cp, mkdir, readdir, rm, stat } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const desktopDir = resolve(scriptDir, "..");
const workspaceRoot = resolve(desktopDir, "..", "..");
const bundledRoot = resolve(desktopDir, "src-tauri", "resources", "embedded-runtime");
const bundledAgentDir = join(bundledRoot, "agent");
const bundledJreDir = join(bundledRoot, "jre");
const bundledServiceDir = join(bundledRoot, "service");

const mavenCommand = process.platform === "win32" ? "mvn.cmd" : "mvn";
const javaCommandName = process.platform === "win32" ? "java.exe" : "java";
const jdepsCommandName = process.platform === "win32" ? "jdeps.exe" : "jdeps";
const jlinkCommandName = process.platform === "win32" ? "jlink.exe" : "jlink";
const fallbackRuntimeModules = [
  "java.base",
  "java.logging",
  "java.management",
  "java.naming",
  "java.security.sasl",
  "java.sql",
  "java.transaction.xa",
  "java.xml",
  "jdk.crypto.ec",
  "jdk.httpserver"
];

async function pathExists(targetPath) {
  try {
    await stat(targetPath);
    return true;
  } catch {
    return false;
  }
}

function runCommand(command, args, cwd) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, {
      cwd,
      stdio: "inherit",
      shell: process.platform === "win32" && command.toLowerCase().endsWith(".cmd")
    });

    child.on("error", rejectPromise);
    child.on("exit", (code) => {
      if (code === 0) {
        resolvePromise();
        return;
      }
      rejectPromise(new Error(`${command} ${args.join(" ")} exited with code ${code ?? "unknown"}`));
    });
  });
}

async function detectJdkHome() {
  const candidates = [
    process.env.JAVA_HOME,
    process.env.JDK_HOME
  ].filter(Boolean);

  for (const candidate of candidates) {
    const javaPath = join(candidate, "bin", javaCommandName);
    const jdepsPath = join(candidate, "bin", jdepsCommandName);
    const jlinkPath = join(candidate, "bin", jlinkCommandName);
    if (await pathExists(javaPath) && await pathExists(jdepsPath) && await pathExists(jlinkPath)) {
      return candidate;
    }
  }

  const whereCommand = process.platform === "win32" ? "where" : "which";
  const output = await new Promise((resolvePromise, rejectPromise) => {
    let stdout = "";
    let stderr = "";
    const child = spawn(whereCommand, [jlinkCommandName], {
      cwd: workspaceRoot,
      stdio: ["ignore", "pipe", "pipe"],
      shell: false
    });

    child.stdout.on("data", (chunk) => {
      stdout += String(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderr += String(chunk);
    });
    child.on("error", rejectPromise);
    child.on("exit", (code) => {
      if (code === 0) {
        resolvePromise(stdout);
        return;
      }
      rejectPromise(new Error(stderr || `${whereCommand} ${jlinkCommandName} exited with code ${code ?? "unknown"}`));
    });
  });

  const jlinkBinary = String(output)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean);

  if (!jlinkBinary) {
    throw new Error("Unable to locate a full JDK with jlink. Set JAVA_HOME before building the desktop bundle.");
  }

  const javaHome = resolve(dirname(jlinkBinary), "..");
  const resolvedJava = join(javaHome, "bin", javaCommandName);
  const resolvedJdeps = join(javaHome, "bin", jdepsCommandName);
  const resolvedJlink = join(javaHome, "bin", jlinkCommandName);
  if (!(await pathExists(resolvedJava)) || !(await pathExists(resolvedJdeps)) || !(await pathExists(resolvedJlink))) {
    throw new Error(`Detected JDK home is invalid: ${javaHome}`);
  }

  return javaHome;
}

async function findAgentJar() {
  const targetDir = resolve(workspaceRoot, "package", "target");
  const entries = await readdir(targetDir, { withFileTypes: true });
  const candidates = [];

  for (const entry of entries) {
    if (!entry.isFile()) {
      continue;
    }
    if (!entry.name.endsWith("-jar-with-dependencies.jar")) {
      continue;
    }
    const fullPath = join(targetDir, entry.name);
    const metadata = await stat(fullPath);
    candidates.push({
      fullPath,
      mtimeMs: metadata.mtimeMs
    });
  }

  candidates.sort((left, right) => right.mtimeMs - left.mtimeMs);
  if (candidates.length === 0) {
    throw new Error("Agent jar was not produced. Expected a *-jar-with-dependencies.jar file under package/target/.");
  }

  return candidates[0].fullPath;
}

async function copyFile(sourcePath, destinationPath) {
  await mkdir(dirname(destinationPath), { recursive: true });
  await cp(sourcePath, destinationPath, { force: true });
}

async function listDependencyModules(jdkHome, targetPath) {
  const jdepsPath = join(jdkHome, "bin", jdepsCommandName);
  const output = await new Promise((resolvePromise, rejectPromise) => {
    let stdout = "";
    let stderr = "";
    const child = spawn(jdepsPath, ["--multi-release", "17", "--ignore-missing-deps", "-s", targetPath], {
      cwd: workspaceRoot,
      stdio: ["ignore", "pipe", "pipe"],
      shell: false
    });

    child.stdout.on("data", (chunk) => {
      stdout += String(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderr += String(chunk);
    });
    child.on("error", rejectPromise);
    child.on("exit", (code) => {
      if (code === 0) {
        resolvePromise(stdout);
        return;
      }
      rejectPromise(new Error(stderr || `jdeps failed for ${targetPath} with code ${code ?? "unknown"}`));
    });
  });

  return String(output)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.includes("->"))
    .map((line) => line.split("->")[1]?.trim())
    .filter((moduleName) => moduleName && /^[A-Za-z0-9_.]+$/.test(moduleName));
}

async function detectRuntimeModules(jdkHome) {
  const modules = new Set(fallbackRuntimeModules);
  const classModules = await listDependencyModules(jdkHome, resolve(workspaceRoot, "package", "target", "classes"));
  for (const moduleName of classModules) {
    modules.add(moduleName);
  }

  return Array.from(modules).sort();
}

async function buildSlimRuntime(jdkHome, modules) {
  const jlinkPath = join(jdkHome, "bin", jlinkCommandName);
  const modulePath = join(jdkHome, "jmods");

  await runCommand(jlinkPath, [
    "--module-path",
    modulePath,
    "--add-modules",
    modules.join(","),
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress=2",
    "--output",
    bundledJreDir
  ], workspaceRoot);
}

async function main() {
  console.log("Building Java Agent package for the Tauri bundle...");
  await runCommand(mavenCommand, ["-q", "-DskipTests", "package"], workspaceRoot);

  const javaHome = await detectJdkHome();
  const agentJar = await findAgentJar();
  const configPath = resolve(workspaceRoot, "dev", "agent", "config", "agent-config.sample.json");
  const initSqlPath = resolve(workspaceRoot, "dev", "agent", "sql", "sqlite-init.sql");
  const licensePath = resolve(workspaceRoot, "dev", "agent", "config", "license", "license.json");
  const bundleLicense = process.env.FILEBRIDGE_BUNDLE_LICENSE === "1" || process.env.FTP_SYNC_BUNDLE_LICENSE === "1";
  const runtimeModules = await detectRuntimeModules(javaHome);

  console.log(`Building jlink runtime from ${javaHome}`);
  console.log(`Using Java modules: ${runtimeModules.join(", ")}`);
  await rm(bundledRoot, { recursive: true, force: true });
  await mkdir(bundledAgentDir, { recursive: true });
  await mkdir(bundledServiceDir, { recursive: true });

  await buildSlimRuntime(javaHome, runtimeModules);
  await copyFile(agentJar, join(bundledAgentDir, "filebridge-agent.jar"));
  await copyFile(configPath, join(bundledAgentDir, "agent-config.json"));
  await copyFile(initSqlPath, join(bundledAgentDir, "sql", "sqlite-init.sql"));
  if (bundleLicense && await pathExists(licensePath)) {
    await copyFile(licensePath, join(bundledAgentDir, "license", "license.json"));
  }

  const nssmPath = resolve(workspaceRoot, "tools", "bin", "nssm.exe");
  if (!(await pathExists(nssmPath))) {
    throw new Error("NSSM was not found at tools/bin/nssm.exe. Add it before building the Windows installer.");
  }
  await copyFile(nssmPath, join(bundledServiceDir, "nssm.exe"));

  for (const scriptName of [
    "install-service.cmd",
    "uninstall-service.cmd",
    "start-service.cmd",
    "stop-service.cmd",
    "service-status.cmd"
  ]) {
    await copyFile(resolve(workspaceRoot, "tools", "scripts", scriptName), join(bundledServiceDir, scriptName));
  }

  console.log("Embedded runtime staged for Tauri packaging.");
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
