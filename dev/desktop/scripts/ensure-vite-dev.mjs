import { prepareViteDevPort, runViteDev } from "./vite-dev-utils.mjs";

try {
  const result = await prepareViteDevPort({ reuseHealthy: true });
  if (result === "reused") {
    process.exit(0);
  }
  runViteDev();
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
