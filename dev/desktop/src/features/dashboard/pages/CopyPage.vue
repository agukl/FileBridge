<script setup lang="ts">
import type {
  DirectoryCopyTaskDraft,
  FileCopyRequest,
  FileListing,
  SyncRun,
  TaskCard
} from "../../../app/api/types";
import FileCopyPanel from "../components/FileCopyPanel.vue";

type PaneSide = "left" | "right";

defineProps<{
  sources: TaskCard[];
  leftSourceId: string;
  rightSourceId: string;
  leftListing: FileListing | null;
  rightListing: FileListing | null;
  leftLoading: boolean;
  rightLoading: boolean;
  leftError: string;
  rightError: string;
  copying: boolean;
  copyMessage: string;
  activeRun: SyncRun | null;
}>();

defineEmits<{
  selectPaneSource: [side: PaneSide, sourceId: string];
  openPanePath: [side: PaneSide, path?: string];
  copyFiles: [payload: FileCopyRequest];
  cancelCopy: [operationId: number];
  saveDirectoryTask: [draft: DirectoryCopyTaskDraft];
}>();
</script>

<template>
  <section class="page-panel copy-page">
    <FileCopyPanel
      :sources="sources"
      :left-source-id="leftSourceId"
      :right-source-id="rightSourceId"
      :left-listing="leftListing"
      :right-listing="rightListing"
      :left-loading="leftLoading"
      :right-loading="rightLoading"
      :left-error="leftError"
      :right-error="rightError"
      :copying="copying"
      :copy-message="copyMessage"
      :active-run="activeRun"
      @select-pane-source="(side, sourceId) => $emit('selectPaneSource', side, sourceId)"
      @open-pane-path="(side, path) => $emit('openPanePath', side, path)"
      @copy-files="$emit('copyFiles', $event)"
      @cancel-copy="$emit('cancelCopy', $event)"
      @save-directory-task="$emit('saveDirectoryTask', $event)"
    />
  </section>
</template>
