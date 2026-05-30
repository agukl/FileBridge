<script setup lang="ts">
import type { FileSourceCacheStats, SyncRun, TaskCard } from "../../../app/api/types";
import { displayTime, formatBytes, formatNumber } from "../../../shared/formatters";

defineProps<{
  card: TaskCard;
  busyAction?: string;
}>();

defineEmits<{
  edit: [taskId: string];
  refreshCache: [taskId: string];
  delete: [taskId: string];
}>();

function isLocalSource(card: TaskCard) {
  return ["LOCAL", "SMB"].includes((card.task.sourceType ?? "").toUpperCase());
}

function sourceEyebrow(card: TaskCard) {
  if ((card.task.sourceType ?? "").toUpperCase() === "SMB") {
    return `SMB · ${card.task.ftpHost || card.task.sourcePath}`;
  }
  return isLocalSource(card) ? "本机目录" : `${card.task.ftpHost || "FTP"}:${card.task.ftpPort || 21}`;
}

function stats(card: TaskCard): FileSourceCacheStats {
  const local = isLocalSource(card);
  return card.task.cacheStats ?? {
    enabled: local,
    applicable: true,
    cachedPathCount: 0,
    cachedFileCount: 0,
    cachedDirectoryCount: 0,
    cachedOtherCount: 0,
    cachedTotalBytes: 0,
    truncatedPathCount: 0,
    oldestScannedAt: "",
    latestScannedAt: "",
    state: local ? "EMPTY" : "UNKNOWN",
    message: local ? "本地统计缓存会在浏览本地目录时自动建立。" : ""
  };
}

function latestCacheRefresh(card: TaskCard): SyncRun | null {
  return card.latestCacheRefresh ?? null;
}

function hasIncompleteCache(card: TaskCard, cache: FileSourceCacheStats) {
  const state = (cache.state || "").toUpperCase();
  if (state === "PARTIAL") {
    return true;
  }
  const refresh = latestCacheRefresh(card);
  if (!refresh) {
    return false;
  }
  if ((refresh.operationType || "").toUpperCase() !== "DIRECTORY_CACHE_REFRESH") {
    return false;
  }
  const health = (refresh.finalHealth || "").toUpperCase();
  return refresh.errorCount > 0 || refresh.warningCount > 0 || health === "COMPLETED_WITH_WARNINGS";
}

function incompleteCacheNote(card: TaskCard, cache: FileSourceCacheStats) {
  const state = (cache.state || "").toUpperCase();
  if (state === "PARTIAL" && cache.message) {
    return cache.message;
  }
  const refresh = latestCacheRefresh(card);
  if (!refresh || !hasIncompleteCache(card, cache)) {
    return "";
  }
  if (refresh.errorCount > 0) {
    return `本次目录缓存仅成功索引 ${formatNumber(cache.cachedPathCount)} 个路径，${formatNumber(refresh.errorCount)} 个目录读取失败，当前统计不是完整结果。`;
  }
  return "本次目录缓存未完整结束，当前统计可能不是完整结果。";
}

function cacheTone(card: TaskCard, cache: FileSourceCacheStats) {
  if (hasIncompleteCache(card, cache)) {
    return "warn";
  }
  const state = (cache.state || "").toUpperCase();
  if (state === "READY") {
    return "good";
  }
  if (state === "FAILED") {
    return "bad";
  }
  if (state === "DISABLED") {
    return "warn";
  }
  if (isLocalSource(card) || state === "EMPTY") {
    return "idle";
  }
  return "warn";
}

function cacheBadge(card: TaskCard, cache: FileSourceCacheStats) {
  if (hasIncompleteCache(card, cache)) {
    return "部分缓存";
  }
  const state = (cache.state || "").toUpperCase();
  if (isLocalSource(card)) {
    if (state === "READY") {
      return "本地缓存";
    }
    if (state === "FAILED") {
      return "异常";
    }
    return "待缓存";
  }
  const map: Record<string, string> = {
    READY: "有缓存",
    EMPTY: "无缓存",
    DISABLED: "统计失效",
    NOT_APPLICABLE: "不适用",
    FAILED: "异常",
    UNKNOWN: "未知"
  };
  return map[state] ?? "未知";
}

function cacheStatusText(card: TaskCard, cache: FileSourceCacheStats) {
  if (hasIncompleteCache(card, cache)) {
    return "部分完成";
  }
  const state = (cache.state || "").toUpperCase();
  if (isLocalSource(card)) {
    return state === "READY" ? "已建立" : "自动建立";
  }
  if (!cache.enabled || state === "DISABLED") {
    return "统计失效";
  }
  return "已开启";
}

function cacheStatusDetail(card: TaskCard, cache: FileSourceCacheStats) {
  if (hasIncompleteCache(card, cache)) {
    return incompleteCacheNote(card, cache);
  }
  const state = (cache.state || "").toUpperCase();
  if (isLocalSource(card)) {
    return cache.message || "仅用于文件源卡片统计，浏览和复制仍实时读盘。";
  }
  if (!cache.enabled || state === "DISABLED") {
    return cache.message || "远程缓存关闭，卡片统计不可用，浏览和复制实时读取远端。";
  }
  return cache.message || "远程目录缓存已开启。";
}

function cachedPathText(cache: FileSourceCacheStats) {
  return `${formatNumber(cache.cachedPathCount)} 个`;
}

function cachedEntryText(cache: FileSourceCacheStats) {
  const total = cache.cachedFileCount + cache.cachedDirectoryCount + cache.cachedOtherCount;
  return `${formatNumber(total)} 项`;
}

function cachedEntryDetail(cache: FileSourceCacheStats) {
  return `文件 ${formatNumber(cache.cachedFileCount)} · 目录 ${formatNumber(cache.cachedDirectoryCount)}`;
}

function cacheUpdatedText(cache: FileSourceCacheStats) {
  return cache.latestScannedAt ? displayTime(cache.latestScannedAt) : "-";
}

function cacheUpdatedDetail(cache: FileSourceCacheStats) {
  if (!cache.latestScannedAt) {
    return "暂无更新时间";
  }
  return cache.oldestScannedAt ? `最早 ${displayTime(cache.oldestScannedAt)}` : "最近缓存时间";
}

function canRefreshCache(card: TaskCard) {
  return isLocalSource(card) || card.task.remoteDirectoryCacheEnabled;
}

function refreshCacheTitle(card: TaskCard) {
  return canRefreshCache(card) ? "重新扫描目录并刷新缓存统计" : "远程目录缓存未开启";
}
</script>

<template>
  <article class="task-card file-source-card">
    <header class="line-card-header">
      <div>
        <span class="eyebrow">{{ sourceEyebrow(card) }}</span>
        <h3>{{ card.task.taskName || card.task.taskId }}</h3>
      </div>
      <span class="pill" :class="`tone-${cacheTone(card, stats(card))}`">
        {{ cacheBadge(card, stats(card)) }}
      </span>
    </header>

    <div class="line-run-summary source-cache-summary">
      <div>
        <span>缓存</span>
        <strong>{{ cacheStatusText(card, stats(card)) }}</strong>
      </div>
      <div>
        <span>缓存路径</span>
        <strong>{{ cachedPathText(stats(card)) }}</strong>
      </div>
      <div>
        <span>缓存条目</span>
        <strong>{{ cachedEntryText(stats(card)) }}</strong>
        <small>{{ cachedEntryDetail(stats(card)) }}</small>
      </div>
      <div>
        <span>缓存时间</span>
        <strong>{{ formatBytes(stats(card).cachedTotalBytes) }}</strong>
        <small :title="cacheUpdatedDetail(stats(card))">{{ cacheUpdatedText(stats(card)) }}</small>
      </div>
    </div>

    <p v-if="hasIncompleteCache(card, stats(card))" class="cache-status-note">
      {{ incompleteCacheNote(card, stats(card)) }}
    </p>

    <footer>
      <button class="ghost-button" @click="$emit('edit', card.task.taskId)">编辑文件源</button>
      <button
        class="ghost-button danger-button"
        :disabled="!!busyAction"
        @click="$emit('delete', card.task.taskId)"
      >
        {{ busyAction === "delete" ? "删除中" : "删除" }}
      </button>
      <button
        class="ghost-button"
        :disabled="!!busyAction || !canRefreshCache(card)"
        :title="refreshCacheTitle(card)"
        @click="$emit('refreshCache', card.task.taskId)"
      >
        {{ busyAction === "refresh-cache" ? "刷新中" : "刷新目录缓存" }}
      </button>
    </footer>
  </article>
</template>
