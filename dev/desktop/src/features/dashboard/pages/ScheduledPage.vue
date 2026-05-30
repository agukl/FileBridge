<script setup lang="ts">
import type { DirectoryCopyTask, TaskCard } from "../../../app/api/types";
import DirectoryTaskPanel from "../components/DirectoryTaskPanel.vue";

defineProps<{
  tasks: DirectoryCopyTask[];
  sources: TaskCard[];
  loading: boolean;
  error: string;
  message: string;
  busy: Record<number, string>;
}>();

defineEmits<{
  refresh: [];
  runTask: [taskId: number];
  cancelTask: [task: DirectoryCopyTask];
  toggleTask: [task: DirectoryCopyTask, enabled: boolean];
  editTask: [task: DirectoryCopyTask];
  deleteTask: [task: DirectoryCopyTask];
}>();
</script>

<template>
  <section class="page-panel scheduled-page">
    <DirectoryTaskPanel
      :tasks="tasks"
      :sources="sources"
      :loading="loading"
      :error="error"
      :message="message"
      :busy="busy"
      @refresh="$emit('refresh')"
      @run-task="$emit('runTask', $event)"
      @cancel-task="$emit('cancelTask', $event)"
      @toggle-task="(task, enabled) => $emit('toggleTask', task, enabled)"
      @edit-task="$emit('editTask', $event)"
      @delete-task="$emit('deleteTask', $event)"
    />
  </section>
</template>
