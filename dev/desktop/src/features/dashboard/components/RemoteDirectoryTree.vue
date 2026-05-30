<script setup lang="ts">
export type RemoteDirectoryNode = {
  name: string;
  path: string;
  children: RemoteDirectoryNode[];
  loaded: boolean;
  expanded: boolean;
  loading: boolean;
};

defineProps<{
  nodes: RemoteDirectoryNode[];
  selectedPath: string;
}>();

defineEmits<{
  select: [path: string];
  expand: [node: RemoteDirectoryNode];
}>();
</script>

<template>
  <ul class="remote-tree-list">
    <li v-for="node in nodes" :key="node.path">
      <div class="remote-tree-row" :class="{ selected: selectedPath === node.path }">
        <button type="button" class="tree-expand-button" @click="$emit('expand', node)">
          {{ node.loading ? "..." : node.expanded ? "v" : ">" }}
        </button>
        <button type="button" class="tree-path-button" @click="$emit('select', node.path)">
          {{ node.name || node.path }}
        </button>
      </div>
      <RemoteDirectoryTree
        v-if="node.expanded && node.children.length"
        :nodes="node.children"
        :selected-path="selectedPath"
        @select="$emit('select', $event)"
        @expand="$emit('expand', $event)"
      />
    </li>
  </ul>
</template>
