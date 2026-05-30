import { prepareViteDevPort, runViteDev } from "./vite-dev-utils.mjs";

try {
  await prepareViteDevPort({ reuseHealthy: false });
  runViteDev();
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
