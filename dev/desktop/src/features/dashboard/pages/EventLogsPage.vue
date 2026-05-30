<script setup lang="ts">
import type { FtpSyncEvent, StatusCodeDictionary, SyncRun } from "../../../app/api/types";
import RunFilterBar from "../components/RunFilterBar.vue";
import RunTimelinePanel from "../components/RunTimelinePanel.vue";

defineProps<{
  runs: SyncRun[];
  events: FtpSyncEvent[];
  total: number | null;
  page: number;
  pageSize: number;
  statusCodes: StatusCodeDictionary;
  loading: boolean;
  error: string;
  stateFilter: string;
  taskFilter: string;
  textFilter: string;
  taskOptions: string[];
  hasFilters: boolean;
}>();

defineEmits<{
  "update:stateFilter": [value: string];
  "update:taskFilter": [value: string];
  "update:textFilter": [value: string];
  clearFilters: [];
  refresh: [];
  pageChange: [page: number];
  pageSizeChange: [pageSize: number];
}>();
</script>

<template>
  <section class="page-panel runs-page">
    <RunFilterBar
      mode="logs"
      :state-filter="stateFilter"
      :task-filter="taskFilter"
      :text-filter="textFilter"
      :task-options="taskOptions"
      :has-filters="hasFilters"
      :loading="loading"
      @update:state-filter="$emit('update:stateFilter', $event)"
      @update:task-filter="$emit('update:taskFilter', $event)"
      @update:text-filter="$emit('update:textFilter', $event)"
      @clear="$emit('clearFilters')"
      @refresh="$emit('refresh')"
    />

    <RunTimelinePanel
      :runs="runs"
      :events="events"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :status-codes="statusCodes"
      :loading="loading"
      :error="error"
      @page-change="$emit('pageChange', $event)"
      @page-size-change="$emit('pageSizeChange', $event)"
    />
  </section>
</template>
