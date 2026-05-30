<script setup lang="ts">
defineProps<{
  mode: "operations" | "logs";
  stateFilter: string;
  taskFilter: string;
  textFilter: string;
  taskOptions: string[];
  hasFilters: boolean;
  loading: boolean;
}>();

const emit = defineEmits<{
  "update:stateFilter": [value: string];
  "update:taskFilter": [value: string];
  "update:textFilter": [value: string];
  clear: [];
  refresh: [];
}>();

function updateState(event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (target) {
    emit("update:stateFilter", target.value);
  }
}

function updateTask(event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (target) {
    emit("update:taskFilter", target.value);
  }
}

function updateText(event: Event) {
  const target = event.target as HTMLInputElement | null;
  if (target) {
    emit("update:textFilter", target.value.trim());
  }
}
</script>

<template>
  <div class="page-action-row">
    <div class="run-filter-bar" :aria-label="mode === 'operations' ? '操作记录筛选' : '事件日志筛选'">
      <select class="filter-select" :value="stateFilter" aria-label="执行状态" @change="updateState">
        <option value="ALL">{{ mode === "operations" ? "全部结果" : "全部状态" }}</option>
        <option value="SUCCESS">成功</option>
        <option value="FAILED">失败</option>
        <option value="RUNNING">执行中</option>
        <option value="CANCELLED">已取消</option>
      </select>
      <select class="filter-select" :value="taskFilter" aria-label="文件源" @change="updateTask">
        <option value="ALL">全部文件源</option>
        <option v-for="taskId in taskOptions" :key="taskId" :value="taskId">{{ taskId }}</option>
      </select>
      <input
        class="filter-input"
        type="search"
        :value="textFilter"
        :placeholder="mode === 'operations' ? '搜索文件源 / 路径 / 结果' : '搜索 run / 事件 / 错误'"
        :aria-label="mode === 'operations' ? '搜索操作记录' : '搜索事件日志'"
        @input="updateText"
      />
      <button v-if="hasFilters" class="ghost-button compact-button" @click="emit('clear')">清空</button>
    </div>
    <button class="ghost-button" :disabled="loading" @click="emit('refresh')">
      {{ loading ? "刷新中" : mode === "operations" ? "刷新操作" : "刷新日志" }}
    </button>
  </div>
</template>
