import { createApp } from "vue";
import App from "./App.vue";
import { applyConsoleTheme, loadSettings } from "./app/settings/consoleSettings";
import "./styles.css";

applyConsoleTheme(loadSettings().theme);
disableDefaultContextMenu();
disableBrowserNavigation();

createApp(App).mount("#app");

function disableDefaultContextMenu() {
  window.addEventListener("contextmenu", (event) => {
    event.preventDefault();
  }, { capture: true });
}

function disableBrowserNavigation() {
  window.addEventListener("keydown", (event) => {
    if (!shouldBlockNavigationKey(event)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
  }, { capture: true });

  const blockNavigationButton = (event: MouseEvent) => {
    if (event.button !== 3 && event.button !== 4) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
  };

  window.addEventListener("mousedown", blockNavigationButton, { capture: true });
  window.addEventListener("mouseup", blockNavigationButton, { capture: true });
  window.addEventListener("auxclick", blockNavigationButton, { capture: true });
}

function shouldBlockNavigationKey(event: KeyboardEvent) {
  if (event.key === "BrowserBack" || event.key === "BrowserForward") {
    return true;
  }
  return (
    event.altKey
    && !event.ctrlKey
    && !event.metaKey
    && (event.key === "ArrowLeft" || event.key === "ArrowRight")
  );
}
