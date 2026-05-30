import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

const generatedWatchIgnores = [
  "**/src-tauri/**",
  "**/src-tauri/target/**",
  "**/src-tauri/resources/embedded-runtime/**",
  "**/dist/**",
  "**/.tmp/**",
  "**/logs/**",
  "**/*.log"
];

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 1420,
    strictPort: true,
    watch: {
      ignored: generatedWatchIgnores
    }
  },
  clearScreen: false
});
