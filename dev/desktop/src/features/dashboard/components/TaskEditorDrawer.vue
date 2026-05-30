<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ftpBrowserApi } from "../../../app/api/client";
import type {
  AgentConfig,
  RemoteDirectoryEntry,
  TaskPayload,
  TaskProfile
} from "../../../app/api/types";
import RemoteDirectoryTree from "./RemoteDirectoryTree.vue";

type RemoteDirectoryNode = {
  name: string;
  path: string;
  children: RemoteDirectoryNode[];
  loaded: boolean;
  expanded: boolean;
  loading: boolean;
};

const props = defineProps<{
  open: boolean;
  config: AgentConfig;
  task: TaskProfile | null;
  initialSourceType: "REMOTE_FTP" | "LOCAL" | "SMB";
  saving: boolean;
  error: string;
}>();

const emit = defineEmits<{
  close: [];
  submit: [payload: TaskPayload, runPreflight: boolean];
}>();

const form = reactive<TaskPayload>({
  taskId: "",
  taskName: "",
  sourceType: "REMOTE_FTP",
  ftpHost: "",
  ftpPort: 21,
  ftpUsername: "",
  passwordRef: "",
  secureMode: "NONE",
  tlsFingerprint: "",
  tlsFingerprintHash: "SHA256",
  sourcePath: "/",
  remoteDirectoryCacheEnabled: false
});
const remoteBrowseLoading = ref(false);
const remoteBrowseError = ref("");
const directoryRoot = ref<RemoteDirectoryNode | null>(null);

const editing = computed(() => Boolean(props.task?.taskId));
const isLocalSource = computed(() => form.sourceType === "LOCAL");
const isSmbSource = computed(() => form.sourceType === "SMB");
const isRemoteSource = computed(() => form.sourceType === "REMOTE_FTP");
const needsPassword = computed(() => isRemoteSource.value || isSmbSource.value);
const usesFtps = computed(() => isRemoteSource.value && form.secureMode !== "NONE");
const hasStoredPassword = computed(() => Boolean(props.task?.passwordConfigured));
const passwordPlaceholder = computed(() => (
  hasStoredPassword.value ? "********" : isSmbSource.value ? "env:SMB_PASSWORD 或 plain:密码" : "env:FTP_PASSWORD 或 plain:密码"
));
const invalidReason = computed(() => basicInvalidReason());
const remoteBrowseInvalidReason = computed(() => {
  if (!isRemoteSource.value) {
    return "";
  }
  const remoteFields = remoteInvalidReason(false);
  if (remoteFields) {
    return remoteFields;
  }
  if (!hasStoredPassword.value && !form.passwordRef.trim()) {
    return "需要填写密码引用后才能浏览远端";
  }
  return "";
});

watch(
  () => [props.open, props.task],
  () => {
    if (!props.open) {
      return;
    }
    const source = props.task;
    form.taskId = source?.taskId ?? generateTaskId();
    form.sourceType = source?.sourceType ?? props.initialSourceType ?? "REMOTE_FTP";
    form.taskName = source?.taskName ?? "";
    form.ftpHost = source?.ftpHost ?? "";
    form.ftpPort = source?.ftpPort ?? 21;
    form.ftpUsername = source?.ftpUsername ?? "";
    form.passwordRef = "";
    form.secureMode = source?.secureMode ?? "NONE";
    form.tlsFingerprint = source?.tlsFingerprint ?? "";
    form.tlsFingerprintHash = source?.tlsFingerprintHash ?? "SHA256";
    form.sourcePath = source?.sourcePath ?? (form.sourceType === "REMOTE_FTP" ? "/" : form.sourceType === "SMB" ? "\\\\server\\share" : "");
    form.remoteDirectoryCacheEnabled = Boolean(source?.remoteDirectoryCacheEnabled);
    resetDraftState();
  },
  { immediate: true }
);

watch(
  () => [
    form.sourceType,
    form.ftpHost,
    form.ftpPort,
    form.ftpUsername,
    form.passwordRef,
    form.secureMode,
    form.tlsFingerprint,
    form.tlsFingerprintHash,
    form.sourcePath,
    form.remoteDirectoryCacheEnabled
  ],
  () => resetDraftState()
);

function basicInvalidReason() {
  if (!form.taskId.trim()) {
    return "文件源 ID 生成失败，请重新打开新增文件源";
  }
  if (isLocalSource.value) {
    if (!form.sourcePath.trim()) {
      return "需要选择或填写本机路径";
    }
    return "";
  }
  if (isSmbSource.value) {
    return smbInvalidReason(true);
  }
  return remoteInvalidReason(true);
}

function smbInvalidReason(requirePassword: boolean) {
  if (!form.sourcePath.trim()) {
    return "SMB 共享路径必填";
  }
  if (!isUncPath(form.sourcePath)) {
    return "SMB 共享路径必须是 \\\\server\\share 格式";
  }
  if (!form.ftpUsername.trim()) {
    return "SMB 用户名必填";
  }
  if (!editing.value && !form.passwordRef.trim()) {
    return "新建 SMB 文件源需要密码引用";
  }
  if (requirePassword && !hasStoredPassword.value && !form.passwordRef.trim()) {
    return "当前 SMB 文件源需要密码引用";
  }
  return "";
}

function remoteInvalidReason(requirePassword: boolean) {
  if (!form.ftpHost.trim()) {
    return "FTP Host 必填";
  }
  if (form.ftpPort <= 0 || form.ftpPort > 65535) {
    return "端口必须在 1-65535 之间";
  }
  if (!form.ftpUsername.trim()) {
    return "FTP 用户名必填";
  }
  if (!editing.value && !form.passwordRef.trim()) {
    return "新建文件源需要密码引用";
  }
  if (requirePassword && !hasStoredPassword.value && !form.passwordRef.trim()) {
    return "当前文件源需要密码引用";
  }
  if (!form.sourcePath.trim()) {
    return "远端路径必填";
  }
  if (usesFtps.value && !normalizedFingerprint(form.tlsFingerprint)) {
    return "FTPS 需要填写证书指纹";
  }
  if (usesFtps.value && !["SHA1", "SHA256"].includes(form.tlsFingerprintHash)) {
    return "证书指纹算法必须是 SHA1 或 SHA256";
  }
  return "";
}

function generateTaskId() {
  return `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 4)}`;
}

function payload(): TaskPayload {
  if (!form.taskId.trim()) {
    form.taskId = generateTaskId();
  }
  const sourceType = form.sourceType === "LOCAL" ? "LOCAL" : form.sourceType === "SMB" ? "SMB" : "REMOTE_FTP";
  return {
    ...form,
    sourceType,
    ftpHost: sourceType === "LOCAL" ? "" : sourceType === "SMB" ? smbHost(form.sourcePath) : form.ftpHost,
    ftpPort: sourceType === "REMOTE_FTP" ? form.ftpPort : sourceType === "SMB" ? 445 : 0,
    ftpUsername: sourceType === "LOCAL" ? "" : form.ftpUsername,
    passwordRef: sourceType === "LOCAL" ? "" : form.passwordRef,
    secureMode: sourceType === "REMOTE_FTP" ? form.secureMode : "NONE",
    tlsFingerprint: sourceType === "REMOTE_FTP" ? normalizedFingerprint(form.tlsFingerprint) : "",
    tlsFingerprintHash: sourceType === "REMOTE_FTP" ? form.tlsFingerprintHash || "SHA256" : "SHA256",
    sourcePath: form.sourcePath,
    remoteDirectoryCacheEnabled: sourceType === "REMOTE_FTP" && form.remoteDirectoryCacheEnabled
  };
}

function isUncPath(value: string) {
  return value.trim().replace(/\//g, "\\").startsWith("\\\\");
}

function smbHost(value: string) {
  const normalized = value.trim().replace(/\//g, "\\").replace(/^\\\\+/, "");
  const [host] = normalized.split("\\");
  return host || form.ftpHost;
}

function normalizedFingerprint(value: string) {
  return (value ?? "").replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
}

function resetDraftState() {
  remoteBrowseError.value = "";
  directoryRoot.value = null;
}

async function loadRemoteDirectories(path = form.sourcePath, target?: RemoteDirectoryNode) {
  if (remoteBrowseInvalidReason.value) {
    remoteBrowseError.value = remoteBrowseInvalidReason.value;
    return;
  }
  remoteBrowseLoading.value = true;
  remoteBrowseError.value = "";
  if (target) {
    target.loading = true;
  }
  try {
    const result = await ftpBrowserApi.listDirectories(props.config, payload(), path || "/");
    const children = result.listing.directories.map(toNode);
    if (target) {
      target.children = children;
      target.loaded = true;
      target.expanded = true;
    } else {
      directoryRoot.value = {
        name: result.listing.path,
        path: result.listing.path,
        children,
        loaded: true,
        expanded: true,
        loading: false
      };
      form.sourcePath = result.listing.path;
    }
  } catch (ex) {
    remoteBrowseError.value = ex instanceof Error ? ex.message : "加载远端目录失败";
  } finally {
    if (target) {
      target.loading = false;
    }
    remoteBrowseLoading.value = false;
  }
}

function toNode(entry: RemoteDirectoryEntry): RemoteDirectoryNode {
  return {
    name: entry.name,
    path: entry.path,
    children: [],
    loaded: false,
    expanded: false,
    loading: false
  };
}

function selectRemotePath(path: string) {
  form.sourcePath = path;
}

function expandRemoteDirectory(node: RemoteDirectoryNode) {
  if (node.loading) {
    return;
  }
  if (node.loaded) {
    node.expanded = !node.expanded;
    return;
  }
  void loadRemoteDirectories(node.path, node);
}

function submit(runPreflight: boolean) {
  if (invalidReason.value) {
    return;
  }
  emit("submit", payload(), isRemoteSource.value && runPreflight);
}
</script>

<template>
  <aside v-if="open" class="task-editor-drawer">
    <header>
      <div>
        <span class="eyebrow">File Source</span>
        <h2>{{ editing ? "编辑文件源" : "新增文件源" }}</h2>
      </div>
      <button class="ghost-button" @click="$emit('close')">关闭</button>
    </header>

    <section class="source-editor-form">
      <section class="editor-section">
        <div class="editor-section-head">
          <strong>{{ isLocalSource ? "本机目录" : isSmbSource ? "SMB 共享" : "连接信息" }}</strong>
        </div>
        <div class="form-grid two">
          <label>
            文件源名称
            <input v-model.trim="form.taskName" :placeholder="isLocalSource ? '本地资料目录' : isSmbSource ? '共享资料目录' : '日报 FTP 文件源'" />
          </label>
          <label v-if="isLocalSource">
            路径
            <input v-model.trim="form.sourcePath" placeholder="D:\\Files\\Reports" />
          </label>
          <label v-if="isSmbSource">
            共享路径
            <input v-model.trim="form.sourcePath" placeholder="\\\\Desktop-7euojmh\\d" />
          </label>
          <label v-if="isSmbSource">
            用户名
            <input v-model.trim="form.ftpUsername" placeholder="Desktop-7euojmh\\admin" />
          </label>
          <label v-if="isRemoteSource">
            FTP Host
            <input v-model.trim="form.ftpHost" placeholder="192.168.1.10" />
          </label>
          <label v-if="isRemoteSource">
            端口
            <input v-model.number="form.ftpPort" type="number" min="1" max="65535" />
          </label>
          <label v-if="isRemoteSource">
            用户名
            <input v-model.trim="form.ftpUsername" placeholder="ftp_user" />
          </label>
        </div>
      </section>

      <section v-if="needsPassword" class="editor-section">
        <div class="editor-section-head">
          <strong>凭据和安全</strong>
        </div>
        <div class="form-grid two">
          <label>
            密码引用
            <input v-model="form.passwordRef" type="password" :placeholder="passwordPlaceholder" />
          </label>
          <label v-if="isRemoteSource">
            FTP/FTPS 模式
            <select v-model="form.secureMode">
              <option value="NONE">普通 FTP（不使用 SSL/TLS）</option>
              <option value="FTPS_EXPLICIT">显式 FTPS（AUTH TLS）</option>
              <option value="FTPS_IMPLICIT">隐式 FTPS（SSL/TLS）</option>
            </select>
          </label>
          <label v-if="usesFtps">
            证书指纹
            <input v-model.trim="form.tlsFingerprint" placeholder="SHA256 指纹，如 74F3..." />
          </label>
          <label v-if="usesFtps">
            指纹算法
            <select v-model="form.tlsFingerprintHash">
              <option value="SHA256">SHA256</option>
              <option value="SHA1">SHA1</option>
            </select>
          </label>
        </div>
      </section>

      <section class="editor-section">
        <div class="editor-section-head">
          <strong>{{ isRemoteSource ? "远端根目录" : "说明" }}</strong>
        </div>
        <div v-if="isRemoteSource" class="form-grid two">
          <label class="field-with-action">
            <span>路径</span>
            <div class="input-action-row">
              <input v-model.trim="form.sourcePath" placeholder="/remote/path" />
              <button
                type="button"
                class="ghost-button compact-button"
                :disabled="remoteBrowseLoading || Boolean(remoteBrowseInvalidReason)"
                @click="loadRemoteDirectories()"
              >
                {{ remoteBrowseLoading ? "加载中" : "浏览远端" }}
              </button>
            </div>
          </label>
          <label class="switch-row">
            <span>启用远端目录缓存</span>
            <input v-model="form.remoteDirectoryCacheEnabled" type="checkbox" />
          </label>
        </div>

        <p v-else class="form-warning">
          {{ isSmbSource ? "SMB 文件源会使用这里配置的共享路径、用户名和密码引用访问远程目录；远端本地账号建议填写 主机名\\用户名。env: 密码需要配置成系统环境变量，普通部署可用 plain:密码。" : "支持本机盘符路径。访问 SMB 共享请新建 SMB 文件源。" }}
        </p>

        <div v-if="isRemoteSource" class="inline-actions">
          <small v-if="remoteBrowseError" class="text-bad">{{ remoteBrowseError }}</small>
        </div>

        <div v-if="isRemoteSource && directoryRoot" class="remote-tree-panel">
          <div class="remote-tree-title">
            <strong>远端目录</strong>
            <span>{{ form.sourcePath }}</span>
          </div>
          <RemoteDirectoryTree
            :nodes="[directoryRoot]"
            :selected-path="form.sourcePath"
            @select="selectRemotePath"
            @expand="expandRemoteDirectory"
          />
        </div>
      </section>
    </section>

    <p v-if="error || invalidReason" class="form-error">
      {{ error || invalidReason }}
    </p>

    <footer>
      <button class="ghost-button" @click="$emit('close')">取消</button>
      <button class="solid-button" :disabled="saving || Boolean(invalidReason)" @click="submit(false)">
        {{ saving ? "保存中" : "保存文件源" }}
      </button>
    </footer>
  </aside>
</template>
