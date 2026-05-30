const SETTINGS_KEY = "filebridge.settings";
const LEGACY_SETTINGS_KEYS = ["ftp-remote-file-manager.settings", "ftp-sync-console.settings"];

export type ConsoleThemeId = "sand" | "harbor" | "copper" | "blueprint" | "graphite" | "mintgrid" | "indigo" | "blackboard" | "neon" | "terminal" | "titanium" | "datastream";

export const consoleThemeOptions: Array<{
  id: ConsoleThemeId;
  label: string;
  description: string;
}> = [
  {
    id: "sand",
    label: "暖沙晨雾",
    description: "保留当前暖米色、墨绿和玻璃感，是最接近现在产品气质的默认主题。"
  },
  {
    id: "harbor",
    label: "海港冷雾",
    description: "把底色拉向蓝灰和海盐色，状态色更清爽，适合更偏专业控制台的观感。"
  },
  {
    id: "copper",
    label: "铜釉工坊",
    description: "在米白底上叠加铜色和烟熏棕，整体更有工单台和手工台账的味道。"
  },
  {
    id: "blueprint",
    label: "蓝图白板",
    description: "白底浅蓝网格，主色只保留蓝色和少量青绿，适合偏文档与流程图的清爽界面。"
  },
  {
    id: "graphite",
    label: "石墨线框",
    description: "中性灰白底配石墨强调色，减少彩色干扰，适合长时间查看记录和文件列表。"
  },
  {
    id: "mintgrid",
    label: "薄荷网格",
    description: "浅薄荷底色配青绿色强调，整体更轻、更干净，保留状态色但降低饱和度。"
  },
  {
    id: "indigo",
    label: "靛蓝文档",
    description: "近白底配靛蓝强调色，卡片和边框更像文档模板，视觉层次克制。"
  },
  {
    id: "blackboard",
    label: "黑图白板",
    description: "白板底色配黑色图线和浅灰网格，去掉装饰过渡，适合更克制的文档式工作台。"
  },
  {
    id: "neon",
    label: "霓虹算力",
    description: "深色底配青蓝荧光和低亮度卡片，适合偏监控中心和实时数据屏的科技感。"
  },
  {
    id: "terminal",
    label: "矩阵终端",
    description: "黑绿终端配色，状态信息更像命令台输出，适合运维和调试场景。"
  },
  {
    id: "titanium",
    label: "钛灰实验室",
    description: "冷白钛灰底配电蓝强调，像实验室设备界面，明亮但更有技术感。"
  },
  {
    id: "datastream",
    label: "数据流青",
    description: "浅青底配墨色文字和湖蓝强调，适合文件、日志和传输状态的高密度界面。"
  }
];

export type ConsoleSettings = {
  theme: ConsoleThemeId;
  autoRefresh: boolean;
  refreshIntervalSeconds: number;
  lineLimit: number;
  keepAgentOnClose: boolean;
};

export const defaultSettings: ConsoleSettings = {
  theme: "blackboard",
  autoRefresh: true,
  refreshIntervalSeconds: 5,
  lineLimit: 500,
  keepAgentOnClose: false
};

export function loadSettings(): ConsoleSettings {
  const raw = window.localStorage.getItem(SETTINGS_KEY)
    ?? LEGACY_SETTINGS_KEYS.map((key) => window.localStorage.getItem(key)).find(Boolean);
  if (!raw) {
    return defaultSettings;
  }
  try {
    return normalizeSettings({ ...defaultSettings, ...JSON.parse(raw) });
  } catch {
    return defaultSettings;
  }
}

export function saveSettings(settings: ConsoleSettings) {
  window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(normalizeSettings(settings)));
  for (const legacyKey of LEGACY_SETTINGS_KEYS) {
    window.localStorage.removeItem(legacyKey);
  }
}

export function normalizeSettings(settings: Partial<ConsoleSettings>): ConsoleSettings {
  return {
    theme: normalizeTheme(settings.theme),
    autoRefresh: settings.autoRefresh !== false,
    refreshIntervalSeconds: clamp(settings.refreshIntervalSeconds, 3, 120),
    lineLimit: clamp(settings.lineLimit, 20, 500),
    keepAgentOnClose: Boolean(settings.keepAgentOnClose)
  };
}

export function applyConsoleTheme(theme: ConsoleThemeId) {
  document.documentElement.dataset.consoleTheme = normalizeTheme(theme);
}

function normalizeTheme(theme: ConsoleThemeId | undefined): ConsoleThemeId {
  return consoleThemeOptions.some((option) => option.id === theme) ? theme as ConsoleThemeId : defaultSettings.theme;
}

function clamp(value: number | undefined, min: number, max: number): number {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return min;
  }
  return Math.max(min, Math.min(Math.round(number), max));
}
