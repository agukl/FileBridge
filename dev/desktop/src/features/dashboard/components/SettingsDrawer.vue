<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import type { LicenseStatus } from "../../../app/api/types";
import type { AgentProcessStatus } from "../../../app/runtime/desktopAgent";
import { consoleThemeOptions, normalizeSettings, type ConsoleSettings } from "../../../app/settings/consoleSettings";

const props = defineProps<{
  open: boolean;
  settings: ConsoleSettings;
  processStatus: AgentProcessStatus | null;
  licenseStatus: LicenseStatus | null;
  licenseDeviceId: string;
  licenseError: string;
  licenseImporting: boolean;
}>();

const emit = defineEmits<{
  close: [];
  save: [settings: ConsoleSettings];
  reset: [];
  refreshProcess: [];
  refreshLicense: [];
  importLicense: [licenseText: string];
}>();

const localSettings = reactive<ConsoleSettings>({
  theme: "sand",
  autoRefresh: true,
  refreshIntervalSeconds: 5,
  lineLimit: 500,
  keepAgentOnClose: false
});
const licenseFileInput = ref<HTMLInputElement | null>(null);
const copyFeedback = ref("");

watch(
  () => [props.open, props.settings],
  () => {
    if (!props.open) {
      return;
    }
    Object.assign(localSettings, props.settings);
  },
  { immediate: true }
);

function save() {
  emit("save", normalizeSettings({ ...localSettings }));
}

function licenseTone(status: LicenseStatus | null) {
  if (!status) {
    return "idle";
  }
  if (status.valid && status.state === "ACTIVE") {
    return "good";
  }
  if (status.valid && status.state === "GRACE") {
    return "warn";
  }
  return "bad";
}

function licenseStateText(status: LicenseStatus | null) {
  if (!status) {
    return "未读取";
  }
  const map: Record<string, string> = {
    ACTIVE: "已激活",
    GRACE: "宽限期",
    EXPIRED: "已过期",
    MISSING: "未激活",
    INVALID: "无效"
  };
  return map[status.state] ?? status.state;
}

async function copyDeviceId() {
  const deviceId = props.licenseDeviceId || props.licenseStatus?.deviceId || "";
  if (!deviceId) {
    return;
  }
  await navigator.clipboard.writeText(deviceId);
  copyFeedback.value = "设备 ID 已复制";
  window.setTimeout(() => {
    copyFeedback.value = "";
  }, 2200);
}

function openLicenseFile() {
  licenseFileInput.value?.click();
}

async function onLicenseFileSelected(event: Event) {
  const input = event.target as HTMLInputElement | null;
  const file = input?.files?.[0];
  if (!file) {
    return;
  }
  emit("importLicense", await file.text());
  if (input) {
    input.value = "";
  }
}
</script>

<template>
  <aside v-if="open" class="settings-drawer">
    <header>
      <div>
        <span class="eyebrow">Settings</span>
        <h2>控制台设置</h2>
        <p>这里只调整桌面控制台的显示和刷新方式，不改变文件源或数据库结构。</p>
      </div>
      <button class="ghost-button" @click="$emit('close')">关闭</button>
    </header>

    <section class="form-panel">
      <div class="settings-headline">
        <div>
          <span class="eyebrow">License</span>
          <h3>授权</h3>
        </div>
        <button class="ghost-button" @click="$emit('refreshLicense')">刷新授权</button>
      </div>
      <div class="env-grid license-grid">
        <span>状态</span>
        <strong :class="`text-${licenseTone(licenseStatus)}`">{{ licenseStateText(licenseStatus) }}</strong>
        <span>设备 ID</span>
        <strong>{{ licenseDeviceId || licenseStatus?.deviceId || "暂无" }}</strong>
        <span>客户</span>
        <strong>{{ licenseStatus?.customer || "暂无" }}</strong>
        <span>版本</span>
        <strong>{{ licenseStatus?.edition || "暂无" }}</strong>
        <span>到期</span>
        <strong>{{ licenseStatus?.expiresAt || "暂无" }}</strong>
        <span>许可证</span>
        <strong>{{ licenseStatus?.licenseId || "暂无" }}</strong>
      </div>
      <div class="inline-actions">
        <button class="ghost-button" :disabled="!(licenseDeviceId || licenseStatus?.deviceId)" @click="copyDeviceId">复制设备 ID</button>
        <button class="solid-button" :disabled="licenseImporting" @click="openLicenseFile">
          {{ licenseImporting ? "导入中" : "导入许可证" }}
        </button>
        <input ref="licenseFileInput" class="hidden-input" type="file" accept=".json,application/json" @change="onLicenseFileSelected" />
      </div>
      <p v-if="copyFeedback" class="field-hint">{{ copyFeedback }}</p>
      <p v-if="licenseStatus?.message" class="field-hint">{{ licenseStatus.message }}</p>
      <p v-if="licenseError" class="form-error">{{ licenseError }}</p>
    </section>

    <section class="form-panel">
      <span class="eyebrow">Appearance</span>
      <div class="form-grid two">
        <label>
          控制台主题
          <select v-model="localSettings.theme">
            <option v-for="theme in consoleThemeOptions" :key="theme.id" :value="theme.id">
              {{ theme.label }}
            </option>
          </select>
        </label>
      </div>
      <p class="field-hint">
        {{ consoleThemeOptions.find((theme) => theme.id === localSettings.theme)?.description }}
      </p>
    </section>

    <section class="form-panel">
      <span class="eyebrow">Refresh</span>
      <div class="form-grid two">
        <label class="switch-row">
          <span>自动刷新</span>
          <input v-model="localSettings.autoRefresh" type="checkbox" />
        </label>
        <label>
          刷新间隔（秒）
          <input v-model.number="localSettings.refreshIntervalSeconds" type="number" min="3" max="120" />
        </label>
        <label>
          文件源/记录读取上限
          <input v-model.number="localSettings.lineLimit" type="number" min="20" max="500" />
        </label>
      </div>
      <p class="field-hint">结构化事件日志已改为后端分页，不再设置总读取上限。</p>
    </section>

    <section class="form-panel">
      <div class="settings-headline">
        <div>
          <span class="eyebrow">Service</span>
          <h3>后端服务</h3>
        </div>
        <button class="ghost-button" @click="$emit('refreshProcess')">重新检查</button>
      </div>
      <div class="env-grid">
        <span>运行环境</span>
        <strong>{{ processStatus?.available ? "Windows Service / 桌面壳" : "浏览器预览" }}</strong>
        <span>服务状态</span>
        <strong>{{ processStatus?.serviceInstalled ? `${processStatus.serviceName} · ${processStatus.serviceState || "未知"}` : "未安装" }}</strong>
        <span>Java 命令</span>
        <strong>{{ processStatus?.javaCommand || "java" }}</strong>
        <span>服务程序</span>
        <strong :class="processStatus?.jarExists ? 'text-good' : 'text-bad'">{{ processStatus?.jarExists ? "已发现" : "未发现" }}</strong>
        <span>服务配置</span>
        <strong :class="processStatus?.configExists ? 'text-good' : 'text-bad'">{{ processStatus?.configExists ? "已发现" : "未发现" }}</strong>
        <span>运行目录</span>
        <strong>{{ processStatus?.workspaceRoot || "暂无" }}</strong>
      </div>
    </section>

    <footer>
      <button class="ghost-button" @click="$emit('reset')">恢复默认</button>
      <button class="solid-button" @click="save">保存设置</button>
    </footer>
  </aside>
</template>
