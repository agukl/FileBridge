<script setup lang="ts">
import { computed } from "vue";
import type { AgentStatus } from "../../../app/runtime/agentStatus";

const props = defineProps<{
  agentStatus: AgentStatus;
  processBusy: string;
  processError: string;
}>();

const emit = defineEmits<{
  runPrimaryAction: [action: "start" | "stop" | "restart"];
  openSettings: [];
}>();

const actionLabel = computed(() => {
  if (props.processBusy === "start") {
    return "启动中";
  }
  if (props.processBusy === "restart") {
    return "重启中";
  }
  if (props.processBusy === "stop") {
    return "停止中";
  }
  return props.agentStatus.actionLabel;
});

const actionTitle = computed(() => {
  if (props.processError) {
    return props.processError;
  }
  return props.agentStatus.detail;
});

const canRunPrimaryAction = computed(() => (
  Boolean(props.agentStatus.actionKind)
  && props.agentStatus.actionEnabled
  && !props.processBusy
));

function runPrimaryAction() {
  if (!props.agentStatus.actionKind || !canRunPrimaryAction.value) {
    return;
  }
  emit("runPrimaryAction", props.agentStatus.actionKind);
}
</script>

<template>
  <section class="agent-strip" aria-label="本机 Agent 状态">
    <div class="agent-status-group">
      <div class="agent-status-item agent-status-summary">
        <h2 :title="processError || agentStatus.error || agentStatus.detail">
          <span class="status-dot" :class="`tone-${agentStatus.tone}`"></span>
          {{ agentStatus.title }}
        </h2>
        <p>{{ processError || agentStatus.detail }}</p>
      </div>
    </div>

    <div class="control-buttons" role="group" aria-label="服务操作">
      <button class="action-button" type="button" title="设置" aria-label="设置" @click="$emit('openSettings')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" />
          <path d="M19.4 13.5c.1-.5.1-1 .1-1.5s0-1-.1-1.5l2-1.5-2-3.5-2.4 1a8.1 8.1 0 0 0-2.6-1.5L14 2h-4l-.4 2.5A8.1 8.1 0 0 0 7 6L4.6 5 2.6 8.5l2 1.5c-.1.5-.1 1-.1 1.5s0 1 .1 1.5l-2 1.5 2 3.5 2.4-1a8.1 8.1 0 0 0 2.6 1.5L10 22h4l.4-2.5A8.1 8.1 0 0 0 17 18l2.4 1 2-3.5-2-1.5Z" />
        </svg>
        <span>设置</span>
      </button>
      <button
        v-if="agentStatus.actionKind"
        class="action-button action-button-primary"
        :class="{ spinning: processBusy === agentStatus.actionKind }"
        type="button"
        :title="actionTitle"
        :aria-label="actionLabel"
        :disabled="!canRunPrimaryAction"
        @click="runPrimaryAction"
      >
        <svg v-if="agentStatus.actionKind === 'start'" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 12h13" />
          <path d="M13 5l7 7-7 7" />
        </svg>
        <svg v-else-if="agentStatus.actionKind === 'stop'" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M7 7h10v10H7Z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path d="M3 12a9 9 0 0 1 15.5-6.36" />
          <path d="M18 3v4h-4" />
          <path d="M21 12a9 9 0 0 1-15.5 6.36" />
          <path d="M6 21v-4h4" />
        </svg>
        <span>{{ actionLabel }}</span>
      </button>
    </div>
  </section>
</template>
