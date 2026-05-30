import { computed, ref, watch, type ComputedRef, type Ref } from "vue";
import { fileOperationsApi, runsApi } from "../../../app/api/client";
import type {
  AgentConfig,
  EventPageResult,
  FtpSyncEvent,
  RunPageResult,
  SyncRun,
  TaskCard
} from "../../../app/api/types";
import type { ConsoleSettings } from "../../../app/settings/consoleSettings";

const EVENT_PAGE_SIZES = [100, 200, 500];
const OPERATION_PAGE_SIZES = [100, 200, 500];
const DEFAULT_EVENT_PAGE_SIZE = EVENT_PAGE_SIZES[0];
const DEFAULT_OPERATION_PAGE_SIZE = OPERATION_PAGE_SIZES[0];

export function useRunHistory(
  config: Ref<AgentConfig>,
  settings: Ref<ConsoleSettings>,
  fileSources: ComputedRef<TaskCard[]>
) {
  const runs = ref<SyncRun[]>([]);
  const operationTotal = ref<number | null>(null);
  const operationPage = ref(1);
  const operationPageSize = ref(DEFAULT_OPERATION_PAGE_SIZE);
  const events = ref<FtpSyncEvent[]>([]);
  const eventTotal = ref<number | null>(null);
  const eventPage = ref(1);
  const eventPageSize = ref(DEFAULT_EVENT_PAGE_SIZE);
  const runsLoading = ref(false);
  const runsError = ref("");
  const operationBusy = ref<Record<number, string>>({});
  const runStateFilter = ref("ALL");
  const runTaskFilter = ref("ALL");
  const runTextFilter = ref("");

  const runTaskOptions = computed(() => {
    const ids = new Set<string>();
    for (const source of fileSources.value) {
      ids.add(source.task.taskId);
    }
    for (const run of runs.value) {
      ids.add(run.taskId);
    }
    for (const event of events.value) {
      ids.add(event.taskId);
    }
    return Array.from(ids).sort();
  });

  const operationRuns = computed(() => runs.value.filter(isVisibleOperationRecord));
  const hasRunFilters = computed(() => (
    runStateFilter.value !== "ALL"
    || runTaskFilter.value !== "ALL"
    || Boolean(runTextFilter.value.trim())
  ));

  function clearRunFilters() {
    runStateFilter.value = "ALL";
    runTaskFilter.value = "ALL";
    runTextFilter.value = "";
  }

  async function refreshRuns() {
    runsLoading.value = true;
    runsError.value = "";
    try {
      const [runsResult, eventsResult] = await Promise.all([
        runsApi.list(config.value, runQueryOptions()),
        runsApi.recentEvents(config.value, eventQueryOptions())
      ]);
      applyRunPage(runsResult);
      applyEventPage(eventsResult);
    } catch (ex) {
      runsError.value = ex instanceof Error ? ex.message : "无法读取事件日志";
    } finally {
      runsLoading.value = false;
    }
  }

  async function refreshOperations() {
    runsLoading.value = true;
    runsError.value = "";
    try {
      applyRunPage(await runsApi.list(config.value, runQueryOptions()));
    } catch (ex) {
      runsError.value = ex instanceof Error ? ex.message : "无法读取操作记录";
    } finally {
      runsLoading.value = false;
    }
  }

  async function refreshEvents() {
    runsLoading.value = true;
    runsError.value = "";
    try {
      applyEventPage(await runsApi.recentEvents(config.value, eventQueryOptions()));
    } catch (ex) {
      runsError.value = ex instanceof Error ? ex.message : "无法读取事件日志";
    } finally {
      runsLoading.value = false;
    }
  }

  function changeOperationPage(page: number) {
    if (page === operationPage.value) {
      return;
    }
    operationPage.value = page;
    void refreshOperations();
  }

  function changeOperationPageSize(pageSize: number) {
    if (pageSize === operationPageSize.value) {
      return;
    }
    operationPageSize.value = pageSize;
    operationPage.value = 1;
    void refreshOperations();
  }

  function changeEventPage(page: number) {
    if (page === eventPage.value) {
      return;
    }
    eventPage.value = page;
    void refreshEvents();
  }

  function changeEventPageSize(pageSize: number) {
    if (pageSize === eventPageSize.value) {
      return;
    }
    eventPageSize.value = pageSize;
    eventPage.value = 1;
    void refreshEvents();
  }

  async function cancelRunOperation(operationId: number) {
    operationBusy.value = { ...operationBusy.value, [operationId]: "cancel" };
    runsError.value = "";
    try {
      const result = await fileOperationsApi.cancel(config.value, operationId);
      if (!result.ok) {
        runsError.value = result.message || "当前操作无法停止";
      }
      await refreshRuns();
    } catch (ex) {
      runsError.value = ex instanceof Error ? ex.message : "停止操作失败";
    } finally {
      const next = { ...operationBusy.value };
      delete next[operationId];
      operationBusy.value = next;
    }
  }

  watch([runStateFilter, runTaskFilter, runTextFilter], () => {
    operationPage.value = 1;
    eventPage.value = 1;
    void refreshRuns();
  });

  return {
    runs,
    operationRuns,
    operationTotal,
    operationPage,
    operationPageSize,
    events,
    eventTotal,
    eventPage,
    eventPageSize,
    runsLoading,
    runsError,
    operationBusy,
    runStateFilter,
    runTaskFilter,
    runTextFilter,
    runTaskOptions,
    hasRunFilters,
    clearRunFilters,
    refreshRuns,
    refreshOperations,
    refreshEvents,
    changeOperationPage,
    changeOperationPageSize,
    changeEventPage,
    changeEventPageSize,
    cancelRunOperation
  };

  function runQueryOptions() {
    return {
      page: operationPage.value,
      pageSize: operationPageSize.value,
      state: runStateFilter.value,
      taskId: runTaskFilter.value,
      q: runTextFilter.value,
      scope: "operations" as const,
      legacyLimit: settings.value.lineLimit
    };
  }

  function eventQueryOptions() {
    return {
      page: eventPage.value,
      pageSize: eventPageSize.value,
      state: runStateFilter.value,
      taskId: runTaskFilter.value,
      q: runTextFilter.value
    };
  }

  function applyRunPage(result: RunPageResult) {
    const nextRuns = Array.isArray(result.runs) ? result.runs : [];
    const hasPageMeta = isFiniteNumber(result.total)
      || isFiniteNumber(result.page)
      || isFiniteNumber(result.pageSize);
    const visibleRuns = hasPageMeta ? nextRuns : nextRuns.filter(isVisibleOperationRecord);
    const nextTotal = isFiniteNumber(result.total)
      ? Math.max(0, Math.trunc(Number(result.total)))
      : null;
    const fallbackPageSize = hasPageMeta
      ? operationPageSize.value
      : fallbackOperationPageSize(visibleRuns.length);
    const nextPageSize = normalizeOperationPageSize(result.pageSize, fallbackPageSize);
    const countForPage = Math.max(nextTotal ?? 0, visibleRuns.length);
    const maxPage = Math.max(1, Math.ceil(countForPage / nextPageSize));
    const fallbackPage = hasPageMeta ? operationPage.value : 1;
    const nextPage = normalizePage(result.page, fallbackPage, maxPage);

    runs.value = visibleRuns;
    operationTotal.value = nextTotal;
    operationPage.value = nextPage;
    operationPageSize.value = nextPageSize;
  }

  function applyEventPage(result: EventPageResult) {
    const nextEvents = Array.isArray(result.events) ? result.events : [];
    const hasPageMeta = isFiniteNumber(result.total)
      || isFiniteNumber(result.page)
      || isFiniteNumber(result.pageSize);
    const nextTotal = isFiniteNumber(result.total)
      ? Math.max(0, Math.trunc(Number(result.total)))
      : null;
    const fallbackPageSize = hasPageMeta
      ? eventPageSize.value
      : fallbackEventPageSize(nextEvents.length);
    const nextPageSize = normalizeEventPageSize(result.pageSize, fallbackPageSize);
    const countForPage = Math.max(nextTotal ?? 0, nextEvents.length);
    const maxPage = Math.max(1, Math.ceil(countForPage / nextPageSize));
    const fallbackPage = hasPageMeta ? eventPage.value : 1;
    const nextPage = normalizePage(result.page, fallbackPage, maxPage);

    events.value = nextEvents;
    eventTotal.value = nextTotal;
    eventPage.value = nextPage;
    eventPageSize.value = nextPageSize;
  }
}

function isVisibleOperationRecord(run: SyncRun) {
  const operationType = (run.operationType || "").toUpperCase();
  return !["CONNECTION_TEST", "FILE_BROWSE", "REMOTE_BROWSE"].includes(operationType);
}

function isFiniteNumber(value: unknown) {
  return Number.isFinite(Number(value));
}

function normalizePage(value: unknown, fallback: number, maxPage: number) {
  const numeric = Number(value);
  const page = Number.isFinite(numeric) && numeric > 0 ? Math.trunc(numeric) : fallback;
  return Math.min(Math.max(1, page), maxPage);
}

function normalizeEventPageSize(value: unknown, fallback: number) {
  const numeric = Number(value);
  const pageSize = Number.isFinite(numeric) && numeric > 0 ? Math.trunc(numeric) : fallback;
  return EVENT_PAGE_SIZES.includes(pageSize)
    ? pageSize
    : fallbackEventPageSize(pageSize);
}

function normalizeOperationPageSize(value: unknown, fallback: number) {
  const numeric = Number(value);
  const pageSize = Number.isFinite(numeric) && numeric > 0 ? Math.trunc(numeric) : fallback;
  return OPERATION_PAGE_SIZES.includes(pageSize)
    ? pageSize
    : fallbackOperationPageSize(pageSize);
}

function fallbackOperationPageSize(runCount: number) {
  if (runCount <= 0) {
    return DEFAULT_OPERATION_PAGE_SIZE;
  }
  return OPERATION_PAGE_SIZES.find((size) => size >= runCount)
    ?? OPERATION_PAGE_SIZES[OPERATION_PAGE_SIZES.length - 1];
}

function fallbackEventPageSize(eventCount: number) {
  if (eventCount <= 0) {
    return DEFAULT_EVENT_PAGE_SIZE;
  }
  return EVENT_PAGE_SIZES.find((size) => size >= eventCount)
    ?? EVENT_PAGE_SIZES[EVENT_PAGE_SIZES.length - 1];
}
