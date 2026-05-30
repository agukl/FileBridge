<script setup lang="ts">
import { computed, ref } from "vue";
import type {
  BoardAlert,
  BoardBreakdownItem,
  BoardOverview,
  BoardTrendBucket,
  StatusCodeDictionary
} from "../../../app/api/types";
import { displayTime, formatBytes, formatDuration, formatNumber, stateTone } from "../../../shared/formatters";

type BoardFilters = {
  state?: string;
  taskId?: string;
  q?: string;
};

type DonutTarget = "operations" | "logs" | null;

type DonutSlice = {
  key: string;
  label: string;
  count: number;
  share: number;
  color: string;
  target: DonutTarget;
  filters: BoardFilters;
};

type DonutSegment = {
  key: string;
  label: string;
  count: number;
  share: number;
  color: string;
  dasharray: string;
  dashoffset: string;
  tooltip: string;
};

type ActiveDonutTooltip = {
  chartKey: string;
  color: string;
  label: string;
  countText: string;
  shareText: string;
  style: Record<string, string>;
};

const DONUT_COLORS = [
  "rgb(var(--nav-rgb) / 0.94)",
  "rgb(var(--green-rgb) / 0.94)",
  "rgb(var(--amber-rgb) / 0.94)",
  "rgb(var(--blue-rgb) / 0.86)",
  "rgb(var(--red-rgb) / 0.9)",
  "rgb(var(--muted-rgb) / 0.78)"
];
const OTHER_SLICE_COLOR = "rgb(var(--shadow-rgb) / 0.18)";
const DONUT_RADIUS = 42;
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

const props = defineProps<{
  board: BoardOverview | null;
  loading: boolean;
  error: string;
  rangeHours: number;
  rangeOptions: number[];
  statusCodes: StatusCodeDictionary;
}>();

const emit = defineEmits<{
  "update:rangeHours": [value: number];
  refresh: [];
  openOperations: [filters: BoardFilters];
  openLogs: [filters: BoardFilters];
}>();

const summary = computed(() => props.board?.summary ?? null);
const trend = computed(() => props.board?.trend ?? []);
const maxTrendRuns = computed(() => Math.max(1, ...trend.value.map((item) => item.totalRuns)));
const maxTrendErrors = computed(() => Math.max(1, ...trend.value.map((item) => item.errorEvents)));
const activeDonutTooltip = ref<ActiveDonutTooltip | null>(null);

const kpiCards = computed(() => {
  const value = summary.value;
  if (!value) {
    return [];
  }
  return [
    { key: "total", label: "操作总数", value: formatNumber(value.totalRuns), detail: `${formatNumber(value.totalEvents)} 条日志`, tone: "good", target: "operations" as const, filters: {} },
    { key: "rate", label: "成功率", value: `${Math.round((value.successRate || 0) * 100)}%`, detail: `成功 ${formatNumber(value.successRuns)} 次`, tone: "good", target: "operations" as const, filters: { state: "SUCCESS" } },
    { key: "failed", label: "失败操作", value: formatNumber(value.failedRuns), detail: `取消 ${formatNumber(value.cancelledRuns)} 次`, tone: value.failedRuns ? "bad" : "idle", target: "operations" as const, filters: { state: "FAILED" } },
    { key: "running", label: "运行中", value: formatNumber(value.runningRuns), detail: `告警操作 ${formatNumber(value.warningRuns)} 次`, tone: value.runningRuns ? "run" : "idle", target: "operations" as const, filters: { state: "RUNNING" } },
    { key: "events", label: "异常日志", value: formatNumber(value.errorEvents), detail: `警告 ${formatNumber(value.warningEvents)} 条`, tone: value.errorEvents ? "bad" : "warn", target: "logs" as const, filters: { q: "ERROR" } },
    { key: "bytes", label: "传输体积", value: formatBytes(value.totalBytes), detail: `平均耗时 ${formatDuration(value.averageDurationMs)}`, tone: "run", target: "operations" as const, filters: { q: "FILE_COPY" } },
    { key: "files", label: "处理文件", value: formatNumber(value.totalFiles), detail: `目录 ${formatNumber(value.totalDirectories)} 个`, tone: "good", target: "operations" as const, filters: { q: "FILE_COPY" } }
  ];
});

const operationTypeSlices = computed(() => createSlices(
  props.board?.operationTypes ?? [],
  summary.value?.totalRuns ?? 0,
  "operations",
  (item) => codeText("triggerTypes", item.key, item.label),
  (item) => ({ q: item.key })
));

const eventLevelSlices = computed(() => createSlices(
  props.board?.eventLevels ?? [],
  summary.value?.totalEvents ?? 0,
  "logs",
  (item) => codeText("eventLevels", item.key, item.label),
  (item) => ({ q: item.key })
));

const errorCategorySlices = computed(() => createSlices(
  props.board?.errorCategories ?? [],
  summary.value?.totalEvents ?? 0,
  "logs",
  (item) => codeText("errorCategories", item.key, item.label),
  (item) => ({ q: item.key })
));

const eventTypeSlices = computed(() => createSlices(
  props.board?.eventTypes ?? [],
  summary.value?.totalEvents ?? 0,
  "logs",
  (item) => codeText("eventTypes", item.key, item.label),
  (item) => ({ q: item.key })
));

const taskHotspotSlices = computed(() => createSlices(
  props.board?.taskHotspots ?? [],
  summary.value?.totalRuns ?? 0,
  "operations",
  (item) => item.label,
  (item) => ({ taskId: item.key })
));

function codeText(group: string, value: string | number | null | undefined, fallback?: string): string {
  if (value === null || value === undefined || value === "") {
    return fallback ?? "-";
  }
  const code = String(value).trim();
  const normalized = code.toUpperCase();
  return props.statusCodes[group]?.[code] ?? props.statusCodes[group]?.[normalized] ?? fallback ?? code;
}

function updateRange(event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (!target) {
    return;
  }
  const nextValue = Number.parseInt(target.value, 10);
  if (!Number.isFinite(nextValue) || nextValue <= 0) {
    return;
  }
  emit("update:rangeHours", nextValue);
}

function trendRunStyle(item: BoardTrendBucket) {
  const ratio = Math.max(0.12, item.totalRuns / maxTrendRuns.value);
  return { height: `${Math.round(ratio * 100)}%` };
}

function trendErrorStyle(item: BoardTrendBucket) {
  if (!item.errorEvents) {
    return { height: "0%" };
  }
  const ratio = Math.max(0.1, item.errorEvents / maxTrendErrors.value);
  return { height: `${Math.round(ratio * 100)}%` };
}

function trendKey(item: BoardTrendBucket) {
  return `${item.startedAt}-${item.endedAt}`;
}

function formatShare(value: number) {
  return `${Math.round((value || 0) * 100)}%`;
}

function donutSegments(slices: DonutSlice[]): DonutSegment[] {
  if (!slices.length) {
    return [];
  }

  let start = 0;
  return slices.map((slice) => {
    const length = Math.max(0, Math.min(DONUT_CIRCUMFERENCE, slice.share * DONUT_CIRCUMFERENCE));
    const segment = {
      key: slice.key,
      label: slice.label,
      count: slice.count,
      share: slice.share,
      color: slice.color,
      dasharray: `${length} ${Math.max(0, DONUT_CIRCUMFERENCE - length)}`,
      dashoffset: `${-start}`,
      tooltip: `${slice.label} · ${formatNumber(slice.count)} · ${formatShare(slice.share)}`
    };
    start += length;
    return segment;
  });
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function updateDonutTooltip(chartKey: string, wrapper: HTMLElement, pointX: number, pointY: number, segment: DonutSegment) {
  const left = clamp(pointX, 96, Math.max(96, wrapper.clientWidth - 96));
  const top = clamp(pointY, 72, Math.max(72, wrapper.clientHeight - 12));
  activeDonutTooltip.value = {
    chartKey,
    color: segment.color,
    label: segment.label,
    countText: formatNumber(segment.count),
    shareText: formatShare(segment.share),
    style: {
      left: `${left}px`,
      top: `${top}px`
    }
  };
}

function showDonutTooltip(chartKey: string, event: PointerEvent, segment: DonutSegment) {
  const target = event.currentTarget as SVGCircleElement | null;
  const wrapper = target?.closest(".board-donut-wrap") as HTMLElement | null;
  if (!target || !wrapper) {
    return;
  }
  const rect = wrapper.getBoundingClientRect();
  updateDonutTooltip(chartKey, wrapper, event.clientX - rect.left, event.clientY - rect.top, segment);
}

function focusDonutTooltip(chartKey: string, event: FocusEvent, segment: DonutSegment) {
  const target = event.currentTarget as SVGCircleElement | null;
  const wrapper = target?.closest(".board-donut-wrap") as HTMLElement | null;
  if (!target || !wrapper) {
    return;
  }
  const wrapperRect = wrapper.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  updateDonutTooltip(
    chartKey,
    wrapper,
    targetRect.left - wrapperRect.left + targetRect.width / 2,
    targetRect.top - wrapperRect.top + targetRect.height / 2,
    segment
  );
}

function hideDonutTooltip(chartKey: string) {
  if (activeDonutTooltip.value?.chartKey === chartKey) {
    activeDonutTooltip.value = null;
  }
}

function eventTone(level: string) {
  return stateTone(level);
}

function openOperations(filters: BoardFilters = {}) {
  emit("openOperations", filters);
}

function openLogs(filters: BoardFilters = {}) {
  emit("openLogs", filters);
}

function openAlert(alert: BoardAlert) {
  openLogs({
    taskId: alert.taskId,
    q: alert.runId ? String(alert.runId) : alert.eventType
  });
}

function createSlices(
  items: BoardBreakdownItem[],
  total: number,
  target: Exclude<DonutTarget, null>,
  labelOf: (item: BoardBreakdownItem) => string,
  filtersOf: (item: BoardBreakdownItem) => BoardFilters
): DonutSlice[] {
  if (!items.length || total <= 0) {
    return [];
  }

  const slices: DonutSlice[] = items
    .filter((item) => item.count > 0)
    .map((item, index) => ({
      key: item.key,
      label: labelOf(item),
      count: item.count,
      share: Math.max(0, item.count / total),
      color: DONUT_COLORS[index % DONUT_COLORS.length],
      target,
      filters: filtersOf(item)
    }));

  const counted = slices.reduce((sum, item) => sum + item.count, 0);
  const remaining = Math.max(0, total - counted);
  if (remaining > 0) {
    slices.push({
      key: "__other__",
      label: "其他",
      count: remaining,
      share: remaining / total,
      color: OTHER_SLICE_COLOR,
      target: null,
      filters: {}
    });
  }

  return slices;
}
</script>

<template>
  <section class="page-panel board-page">
    <header class="board-hero-card">
      <div class="board-hero-copy">
        <h2>运行看板</h2>
        <div class="board-meta-strip">
          <span>最近 {{ rangeHours }} 小时</span>
          <span v-if="board">更新 {{ displayTime(board.generatedAt) }}</span>
          <span v-if="board?.truncatedRuns || board?.truncatedEvents" class="pill tone-warn">样本已截断</span>
        </div>
      </div>

      <div class="board-hero-actions">
        <select
          class="filter-select board-range-select"
          :value="rangeHours"
          :disabled="loading"
          aria-label="时间范围"
          @change="updateRange"
        >
          <option v-for="hours in rangeOptions" :key="hours" :value="hours">最近 {{ hours }} 小时</option>
        </select>
        <button class="ghost-button" type="button" :disabled="loading" @click="$emit('refresh')">
          {{ loading ? "刷新中" : "刷新看板" }}
        </button>
      </div>
    </header>

    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-else-if="loading && !board" class="loading-note">正在加载看板...</p>

    <template v-if="board && summary">
      <section class="board-kpi-grid">
        <button
          v-for="card in kpiCards"
          :key="card.key"
          class="board-kpi-card board-kpi-button"
          type="button"
          @click="card.target === 'logs' ? openLogs(card.filters) : openOperations(card.filters)"
        >
          <span class="board-kpi-label">{{ card.label }}</span>
          <strong class="board-kpi-value">{{ card.value }}</strong>
          <span class="board-kpi-detail">
            <span class="status-dot" :class="`tone-${card.tone}`" />
            {{ card.detail }}
          </span>
        </button>
      </section>

      <section class="board-grid board-grid-primary">
        <article class="board-card board-trend-card">
          <header class="board-card-head">
            <div><h3>运行趋势</h3></div>
          </header>

          <div class="board-trend-columns">
            <article
              v-for="item in trend"
              :key="trendKey(item)"
              class="board-trend-column"
              tabindex="0"
            >
              <div class="board-trend-bars">
                <span class="board-trend-bar run-bar" :style="trendRunStyle(item)" />
                <span class="board-trend-bar error-bar" :style="trendErrorStyle(item)" />
              </div>
              <strong>{{ item.label }}</strong>
              <div class="board-trend-tooltip">
                <strong>{{ item.label }}</strong>
                <span>操作 {{ formatNumber(item.totalRuns) }}</span>
                <span>错误 {{ formatNumber(item.errorEvents) }}</span>
                <span>传输 {{ formatBytes(item.totalBytes) }}</span>
              </div>
            </article>
          </div>
        </article>
      </section>

      <section class="board-grid board-grid-secondary">
        <article class="board-card">
          <header class="board-card-head">
            <div><h3>运行结构</h3></div>
          </header>

          <div class="board-donut-grid">
            <section class="board-donut-panel">
              <div class="board-stack-head">
                <strong>操作类型</strong>
                <span>{{ formatNumber(summary.totalRuns) }} 次</span>
              </div>
              <div class="board-donut-stage">
                <div class="board-donut-wrap">
                  <div v-if="operationTypeSlices.length" class="board-donut-chart">
                    <svg class="board-donut-svg" viewBox="0 0 120 120">
                      <circle class="board-donut-track" cx="60" cy="60" :r="DONUT_RADIUS" />
                      <circle
                        v-for="segment in donutSegments(operationTypeSlices)"
                        :key="`ops-segment-${segment.key}`"
                        class="board-donut-segment"
                        cx="60"
                        cy="60"
                        :r="DONUT_RADIUS"
                        :stroke="segment.color"
                        :stroke-dasharray="segment.dasharray"
                        :stroke-dashoffset="segment.dashoffset"
                        :aria-label="segment.tooltip"
                        tabindex="0"
                        @pointerenter="showDonutTooltip('ops', $event, segment)"
                        @pointermove="showDonutTooltip('ops', $event, segment)"
                        @pointerleave="hideDonutTooltip('ops')"
                        @focus="focusDonutTooltip('ops', $event, segment)"
                        @blur="hideDonutTooltip('ops')"
                      />
                    </svg>
                    <div class="board-donut-hole">
                      <strong>{{ formatNumber(summary.totalRuns) }}</strong>
                      <span>次操作</span>
                    </div>
                  </div>
                  <div v-else class="board-donut-empty">暂无数据</div>
                  <div v-if="activeDonutTooltip?.chartKey === 'ops'" class="board-donut-tooltip" :style="activeDonutTooltip.style">
                    <div class="board-donut-tooltip-head">
                      <span class="board-donut-tooltip-dot" :style="{ background: activeDonutTooltip.color }" />
                      <strong>{{ activeDonutTooltip.label }}</strong>
                    </div>
                    <span class="board-donut-tooltip-meta">{{ activeDonutTooltip.countText }} · {{ activeDonutTooltip.shareText }}</span>
                  </div>
                </div>
              </div>
            </section>

            <section class="board-donut-panel">
              <div class="board-stack-head">
                <strong>日志级别</strong>
                <span>{{ formatNumber(summary.totalEvents) }} 条</span>
              </div>
              <div class="board-donut-stage">
                <div class="board-donut-wrap">
                  <div v-if="eventLevelSlices.length" class="board-donut-chart">
                    <svg class="board-donut-svg" viewBox="0 0 120 120">
                      <circle class="board-donut-track" cx="60" cy="60" :r="DONUT_RADIUS" />
                      <circle
                        v-for="segment in donutSegments(eventLevelSlices)"
                        :key="`levels-segment-${segment.key}`"
                        class="board-donut-segment"
                        cx="60"
                        cy="60"
                        :r="DONUT_RADIUS"
                        :stroke="segment.color"
                        :stroke-dasharray="segment.dasharray"
                        :stroke-dashoffset="segment.dashoffset"
                        :aria-label="segment.tooltip"
                        tabindex="0"
                        @pointerenter="showDonutTooltip('levels', $event, segment)"
                        @pointermove="showDonutTooltip('levels', $event, segment)"
                        @pointerleave="hideDonutTooltip('levels')"
                        @focus="focusDonutTooltip('levels', $event, segment)"
                        @blur="hideDonutTooltip('levels')"
                      />
                    </svg>
                    <div class="board-donut-hole">
                      <strong>{{ formatNumber(summary.totalEvents) }}</strong>
                      <span>条日志</span>
                    </div>
                  </div>
                  <div v-else class="board-donut-empty">暂无数据</div>
                  <div v-if="activeDonutTooltip?.chartKey === 'levels'" class="board-donut-tooltip" :style="activeDonutTooltip.style">
                    <div class="board-donut-tooltip-head">
                      <span class="board-donut-tooltip-dot" :style="{ background: activeDonutTooltip.color }" />
                      <strong>{{ activeDonutTooltip.label }}</strong>
                    </div>
                    <span class="board-donut-tooltip-meta">{{ activeDonutTooltip.countText }} · {{ activeDonutTooltip.shareText }}</span>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </article>

        <article class="board-card">
          <header class="board-card-head">
            <div><h3>异常热点</h3></div>
          </header>

          <div class="board-donut-grid">
            <section class="board-donut-panel">
              <div class="board-stack-head">
                <strong>错误类别</strong>
                <span>Top {{ props.board?.errorCategories.length ?? 0 }}</span>
              </div>
              <div class="board-donut-stage">
                <div class="board-donut-wrap">
                  <div v-if="errorCategorySlices.length" class="board-donut-chart">
                    <svg class="board-donut-svg" viewBox="0 0 120 120">
                      <circle class="board-donut-track" cx="60" cy="60" :r="DONUT_RADIUS" />
                      <circle
                        v-for="segment in donutSegments(errorCategorySlices)"
                        :key="`errors-segment-${segment.key}`"
                        class="board-donut-segment"
                        cx="60"
                        cy="60"
                        :r="DONUT_RADIUS"
                        :stroke="segment.color"
                        :stroke-dasharray="segment.dasharray"
                        :stroke-dashoffset="segment.dashoffset"
                        :aria-label="segment.tooltip"
                        tabindex="0"
                        @pointerenter="showDonutTooltip('errors', $event, segment)"
                        @pointermove="showDonutTooltip('errors', $event, segment)"
                        @pointerleave="hideDonutTooltip('errors')"
                        @focus="focusDonutTooltip('errors', $event, segment)"
                        @blur="hideDonutTooltip('errors')"
                      />
                    </svg>
                    <div class="board-donut-hole">
                      <strong>{{ formatNumber(errorCategorySlices.reduce((sum, item) => sum + item.count, 0)) }}</strong>
                      <span>条异常</span>
                    </div>
                  </div>
                  <div v-else class="board-donut-empty">暂无错误类别</div>
                  <div v-if="activeDonutTooltip?.chartKey === 'errors'" class="board-donut-tooltip" :style="activeDonutTooltip.style">
                    <div class="board-donut-tooltip-head">
                      <span class="board-donut-tooltip-dot" :style="{ background: activeDonutTooltip.color }" />
                      <strong>{{ activeDonutTooltip.label }}</strong>
                    </div>
                    <span class="board-donut-tooltip-meta">{{ activeDonutTooltip.countText }} · {{ activeDonutTooltip.shareText }}</span>
                  </div>
                </div>
              </div>
            </section>

            <section class="board-donut-panel">
              <div class="board-stack-head">
                <strong>事件类型</strong>
                <span>Top {{ props.board?.eventTypes.length ?? 0 }}</span>
              </div>
              <div class="board-donut-stage">
                <div class="board-donut-wrap">
                  <div v-if="eventTypeSlices.length" class="board-donut-chart">
                    <svg class="board-donut-svg" viewBox="0 0 120 120">
                      <circle class="board-donut-track" cx="60" cy="60" :r="DONUT_RADIUS" />
                      <circle
                        v-for="segment in donutSegments(eventTypeSlices)"
                        :key="`types-segment-${segment.key}`"
                        class="board-donut-segment"
                        cx="60"
                        cy="60"
                        :r="DONUT_RADIUS"
                        :stroke="segment.color"
                        :stroke-dasharray="segment.dasharray"
                        :stroke-dashoffset="segment.dashoffset"
                        :aria-label="segment.tooltip"
                        tabindex="0"
                        @pointerenter="showDonutTooltip('types', $event, segment)"
                        @pointermove="showDonutTooltip('types', $event, segment)"
                        @pointerleave="hideDonutTooltip('types')"
                        @focus="focusDonutTooltip('types', $event, segment)"
                        @blur="hideDonutTooltip('types')"
                      />
                    </svg>
                    <div class="board-donut-hole">
                      <strong>{{ formatNumber(summary.totalEvents) }}</strong>
                      <span>条日志</span>
                    </div>
                  </div>
                  <div v-else class="board-donut-empty">暂无事件类型</div>
                  <div v-if="activeDonutTooltip?.chartKey === 'types'" class="board-donut-tooltip" :style="activeDonutTooltip.style">
                    <div class="board-donut-tooltip-head">
                      <span class="board-donut-tooltip-dot" :style="{ background: activeDonutTooltip.color }" />
                      <strong>{{ activeDonutTooltip.label }}</strong>
                    </div>
                    <span class="board-donut-tooltip-meta">{{ activeDonutTooltip.countText }} · {{ activeDonutTooltip.shareText }}</span>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </article>

        <article class="board-card">
          <header class="board-card-head">
            <div><h3>活跃文件源</h3></div>
          </header>

          <section class="board-donut-panel board-donut-panel-single">
            <div class="board-stack-head">
              <strong>文件源活跃度</strong>
              <span>{{ formatNumber(summary.totalRuns) }} 次操作</span>
            </div>
            <div class="board-donut-stage board-donut-stage-single">
              <div class="board-donut-wrap board-donut-wrap-large">
                <div v-if="taskHotspotSlices.length" class="board-donut-chart board-donut-chart-large">
                  <svg class="board-donut-svg" viewBox="0 0 120 120">
                    <circle class="board-donut-track" cx="60" cy="60" :r="DONUT_RADIUS" />
                    <circle
                      v-for="segment in donutSegments(taskHotspotSlices)"
                      :key="`tasks-segment-${segment.key}`"
                      class="board-donut-segment"
                      cx="60"
                      cy="60"
                      :r="DONUT_RADIUS"
                      :stroke="segment.color"
                      :stroke-dasharray="segment.dasharray"
                      :stroke-dashoffset="segment.dashoffset"
                      :aria-label="segment.tooltip"
                      tabindex="0"
                      @pointerenter="showDonutTooltip('tasks', $event, segment)"
                      @pointermove="showDonutTooltip('tasks', $event, segment)"
                      @pointerleave="hideDonutTooltip('tasks')"
                      @focus="focusDonutTooltip('tasks', $event, segment)"
                      @blur="hideDonutTooltip('tasks')"
                    />
                  </svg>
                  <div class="board-donut-hole">
                    <strong>{{ formatNumber(summary.totalRuns) }}</strong>
                    <span>次操作</span>
                  </div>
                </div>
                <div v-else class="board-donut-empty">暂无活跃文件源</div>
                <div v-if="activeDonutTooltip?.chartKey === 'tasks'" class="board-donut-tooltip" :style="activeDonutTooltip.style">
                  <div class="board-donut-tooltip-head">
                    <span class="board-donut-tooltip-dot" :style="{ background: activeDonutTooltip.color }" />
                    <strong>{{ activeDonutTooltip.label }}</strong>
                  </div>
                  <span class="board-donut-tooltip-meta">{{ activeDonutTooltip.countText }} · {{ activeDonutTooltip.shareText }}</span>
                </div>
              </div>
            </div>
          </section>
        </article>
      </section>

      <article class="board-card board-alert-card">
        <header class="board-card-head">
          <div><h3>最近异常</h3></div>
        </header>

        <div v-if="board.alerts.length" class="board-alert-list">
          <button
            v-for="alert in board.alerts"
            :key="alert.id"
            class="board-alert-row board-alert-button"
            type="button"
            @click="openAlert(alert)"
          >
            <div class="board-alert-main">
              <span class="pill" :class="`tone-${eventTone(alert.level)}`">
                {{ codeText("eventLevels", alert.level, alert.level) }}
              </span>
              <strong>{{ codeText("eventTypes", alert.eventType, alert.eventType) }}</strong>
              <span>{{ alert.taskName }}</span>
              <span v-if="alert.errorCategory">{{ codeText("errorCategories", alert.errorCategory, alert.errorCategory) }}</span>
            </div>
            <p>{{ alert.message }}</p>
            <div class="board-alert-meta">
              <span>{{ displayTime(alert.occurredAt) }}</span>
              <span class="mono-cell" :title="alert.path">{{ alert.path }}</span>
              <span v-if="alert.runId">#{{ alert.runId }}</span>
              <span v-if="alert.replyCode">FTP {{ alert.replyCode }}</span>
            </div>
          </button>
        </div>

        <p v-else class="empty-text">当前窗口内没有告警或错误日志。</p>
      </article>
    </template>
  </section>
</template>




