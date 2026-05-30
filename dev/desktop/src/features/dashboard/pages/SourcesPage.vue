<script setup lang="ts">
import type { TaskCard } from "../../../app/api/types";
import TaskCardView from "../components/TaskCard.vue";

defineProps<{
  sources: TaskCard[];
  busy: Record<string, string>;
}>();

defineEmits<{
  createLocal: [];
  createRemote: [];
  createSmb: [];
  edit: [taskId: string];
  delete: [sourceId: string];
  refreshCache: [sourceId: string];
}>();
</script>

<template>
  <section class="page-panel file-sources-page">
    <div v-if="sources.length" class="page-action-row">
      <button class="ghost-button" @click="$emit('createLocal')">新增本地文件源</button>
      <button class="ghost-button" @click="$emit('createSmb')">新增 SMB 文件源</button>
      <button class="solid-button" @click="$emit('createRemote')">新增远程文件源</button>
    </div>

    <section class="task-grid source-grid">
      <TaskCardView
        v-for="card in sources"
        :key="card.task.taskId"
        :card="card"
        :busy-action="busy[card.task.taskId]"
        @edit="$emit('edit', $event)"
        @delete="$emit('delete', $event)"
        @refresh-cache="$emit('refreshCache', $event)"
      />
    </section>

    <section v-if="!sources.length" class="empty-board">
      <span class="eyebrow">No File Sources</span>
      <h2>还没有文件源</h2>
      <p>先创建一个文件源。远程 FTP 与本机目录都会进入统一文件浏览页。</p>
      <button class="ghost-button" @click="$emit('createLocal')">创建本地文件源</button>
      <button class="ghost-button" @click="$emit('createSmb')">创建 SMB 文件源</button>
      <button class="solid-button" @click="$emit('createRemote')">创建远程文件源</button>
    </section>
  </section>
</template>
