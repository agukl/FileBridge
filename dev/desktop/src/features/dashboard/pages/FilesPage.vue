<script setup lang="ts">
import type { FileListing, FileMoveRequest, TaskCard } from "../../../app/api/types";
import FileBrowserPanel from "../components/FileBrowserPanel.vue";

defineProps<{
  sources: TaskCard[];
  selectedSourceId: string;
  listing: FileListing | null;
  loading: boolean;
  error: string;
}>();

defineEmits<{
  selectSource: [sourceId: string];
  openPath: [path?: string];
  parent: [];
  refresh: [];
  moveFiles: [payload: FileMoveRequest];
}>();
</script>

<template>
  <section class="page-panel files-page">
    <FileBrowserPanel
      :sources="sources"
      :selected-source-id="selectedSourceId"
      :listing="listing"
      :loading="loading"
      :error="error"
      @select-source="$emit('selectSource', $event)"
      @open-path="$emit('openPath', $event)"
      @parent="$emit('parent')"
      @refresh="$emit('refresh')"
      @move-files="$emit('moveFiles', $event)"
    />
  </section>
</template>
