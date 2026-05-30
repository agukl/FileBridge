import { ref, type Ref } from "vue";

export function useDashboardNavigation<TPage extends string>(
  pageKeys: readonly TPage[],
  legacyPageAliases: Record<string, TPage>,
  defaultPage: TPage,
  onPageActivated: (page: TPage) => void
) {
  const activePage = ref(loadInitialPage()) as Ref<TPage>;

  function setPage(page: TPage) {
    activePage.value = page;
    persistActivePage(page);
    onPageActivated(page);
  }

  function clearLegacyHashFromLocation() {
    if (!resolveLegacyPageHash()) {
      return;
    }
    const cleanUrl = `${window.location.pathname}${window.location.search}`;
    window.history.replaceState(window.history.state, document.title, cleanUrl);
  }

  return {
    activePage,
    setPage,
    clearLegacyHashFromLocation
  };

  function isPageKey(value: string | null): value is TPage {
    return value !== null && pageKeys.includes(value as TPage);
  }

  function resolveLegacyPageHash(): TPage | null {
    const hash = window.location.hash.replace(/^#\/?/, "");
    if (!hash) {
      return null;
    }
    const normalized = legacyPageAliases[hash] ?? hash;
    return isPageKey(normalized) ? normalized : null;
  }

  function loadInitialPage(): TPage {
    const legacyPage = resolveLegacyPageHash();
    if (legacyPage) {
      return legacyPage;
    }
    try {
      const savedPage = window.sessionStorage.getItem("filebridge.active-page")
        ?? window.sessionStorage.getItem("ftp-sync-agent.active-page");
      return isPageKey(savedPage) ? savedPage : defaultPage;
    } catch {
      return defaultPage;
    }
  }

  function persistActivePage(page: TPage) {
    try {
      window.sessionStorage.setItem("filebridge.active-page", page);
      window.sessionStorage.removeItem("ftp-sync-agent.active-page");
    } catch {
      // Session storage may be unavailable in restricted preview environments.
    }
  }
}
