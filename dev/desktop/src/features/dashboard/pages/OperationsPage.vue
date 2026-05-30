<script setup lang="ts">
import type { StatusCodeDictionary, SyncRun, TaskCard } from "../../../app/api/types";
import RunFilterBar from "../components/RunFilterBar.vue";
import SyncRecordPanel from "../components/SyncRecordPanel.vue";

defineProps<{
  runs: SyncRun[];
  total: number | null;
  page: number;
  pageSize: number;
  sources: TaskCard[];
  statusCodes: StatusCodeDictionary;
  loading: boolean;
  error: string;
  operationBusy: Record<number, string>;
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
  cancelRun: [operationId: number];
}>();
</script>

<template>
  <section class="page-panel operations-page">
    <RunFilterBar
      mode="operations"
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

    <SyncRecordPanel
      :runs="runs"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :lines="sources"
      :status-codes="statusCodes"
      :loading="loading"
      :error="error"
      :operation-busy="operationBusy"
      @page-change="$emit('pageChange', $event)"
      @page-size-change="$emit('pageSizeChange', $event)"
      @cancel-run="$emit('cancelRun', $event)"
    />
  </section>
</template>
