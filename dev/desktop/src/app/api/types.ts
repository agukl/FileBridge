export type AgentConfig = {
  baseUrl: string;
  token: string;
};

export type StatusCodeDictionary = Record<string, Record<string, string>>;

export type HealthBody = {
  ok: boolean;
  service: string;
  startedAt: string;
  checkedAt: string;
  databaseConnected?: boolean;
  schemaReady: boolean;
  schema: string;
  tables: string[];
  error?: string;
  activeTasks: string[];
};

export type LicenseLimits = {
  maxFileSources: number;
  maxConcurrentOperations: number;
};

export type LicenseStatus = {
  required: boolean;
  valid: boolean;
  state: "ACTIVE" | "GRACE" | "EXPIRED" | "MISSING" | "INVALID" | string;
  message: string;
  deviceId: string;
  licenseId: string;
  customer: string;
  edition: string;
  issuedAt: string;
  expiresAt: string;
  graceDays: number;
  features: string[];
  limits: LicenseLimits;
  licensePath: string;
};

export type LicenseDeviceIdResult = {
  deviceId: string;
};

export type DashboardOverview = {
  checkedAt: string;
  tasks: TaskCard[];
};

export type BoardSummary = {
  totalRuns: number;
  successRuns: number;
  failedRuns: number;
  cancelledRuns: number;
  runningRuns: number;
  warningRuns: number;
  totalEvents: number;
  warningEvents: number;
  errorEvents: number;
  totalFiles: number;
  totalDirectories: number;
  totalBytes: number;
  averageDurationMs: number;
  successRate: number;
};

export type BoardTrendBucket = {
  label: string;
  startedAt: string;
  endedAt: string;
  totalRuns: number;
  successRuns: number;
  failedRuns: number;
  warningRuns: number;
  errorEvents: number;
  totalBytes: number;
};

export type BoardBreakdownItem = {
  key: string;
  label: string;
  count: number;
  share: number;
};

export type BoardAlert = {
  id: number;
  occurredAt: string;
  taskId: string;
  taskName: string;
  level: string;
  eventType: string;
  errorCategory: string;
  message: string;
  path: string;
  runId?: number | null;
  replyCode?: number | null;
};

export type BoardOverview = {
  generatedAt: string;
  rangeHours: number;
  startedAt: string;
  endedAt: string;
  truncatedRuns: boolean;
  truncatedEvents: boolean;
  summary: BoardSummary;
  trend: BoardTrendBucket[];
  operationTypes: BoardBreakdownItem[];
  eventLevels: BoardBreakdownItem[];
  errorCategories: BoardBreakdownItem[];
  eventTypes: BoardBreakdownItem[];
  taskHotspots: BoardBreakdownItem[];
  alerts: BoardAlert[];
};

export type TaskCard = {
  task: TaskProfile;
  active: boolean;
  state: string;
  health: string;
  latestDiagnostic?: DiagnosticReport | null;
  latestRun?: SyncRun | null;
  latestCacheRefresh?: SyncRun | null;
};

export type TaskProfile = {
  taskId: string;
  taskName: string;
  sourceType: "REMOTE_FTP" | "LOCAL" | "SMB";
  ftpHost: string;
  ftpPort: number;
  ftpUsername: string;
  secureMode: string;
  tlsFingerprint: string;
  tlsFingerprintHash: string;
  sourcePath: string;
  remoteDirectoryCacheEnabled: boolean;
  passwordConfigured?: boolean;
  credentialKind?: string;
  permission?: FileSourcePermission;
  cacheStats?: FileSourceCacheStats;
};

export type FileSourceCacheStats = {
  enabled: boolean;
  applicable: boolean;
  cachedPathCount: number;
  cachedFileCount: number;
  cachedDirectoryCount: number;
  cachedOtherCount: number;
  cachedTotalBytes: number;
  truncatedPathCount: number;
  oldestScannedAt: string;
  latestScannedAt: string;
  state: string;
  message: string;
};

export type TaskConfigView = TaskProfile & {
  passwordConfigured: boolean;
  credentialKind: string;
};

export type FileSourcePermission = {
  canRead: boolean;
  canWrite: boolean;
  state: string;
  checkedAt: string;
  message: string;
};

export type TaskPayload = {
  taskId: string;
  taskName: string;
  sourceType: "REMOTE_FTP" | "LOCAL" | "SMB";
  ftpHost: string;
  ftpPort: number;
  ftpUsername: string;
  passwordRef: string;
  secureMode: string;
  tlsFingerprint: string;
  tlsFingerprintHash: string;
  sourcePath: string;
  remoteDirectoryCacheEnabled: boolean;
};

export type TaskSaveResult = {
  ok: boolean;
  taskId: string;
  sourceId?: string;
  permission?: FileSourcePermission;
};

export type DraftPreflightResult = {
  ok: boolean;
  diagnostic: DiagnosticReport;
};

export type RemoteDirectoryEntry = {
  name: string;
  path: string;
  modifiedAt?: string;
};

export type RemoteDirectoryListing = {
  path: string;
  parentPath: string;
  directories: RemoteDirectoryEntry[];
  replyCode: number;
  replyText: string;
};

export type RemoteDirectoryResult = {
  ok: boolean;
  listing: RemoteDirectoryListing;
};

export type FileEntry = {
  name: string;
  path: string;
  type: "DIRECTORY" | "FILE" | "OTHER";
  size: number;
  modifiedAt: string;
};

export type FileListing = {
  path: string;
  parentPath: string;
  entries: FileEntry[];
  replyCode: number;
  replyText: string;
  cacheUsed?: boolean;
  cacheSource?: string;
  cacheMessage?: string;
  cacheScannedAt?: string;
};

export type FileBrowseResult = {
  ok: boolean;
  listing: FileListing;
};

export type DirectoryCacheRefreshResult = {
  ok: boolean;
  taskId: string;
  sourceType: "REMOTE_FTP" | "LOCAL" | string;
  cachedPathCount: number;
  fileCount: number;
  directoryCount: number;
  otherCount: number;
  totalBytes: number;
  truncatedPathCount: number;
  errorCount: number;
  maxDirectoriesReached: boolean;
  startedAt: string;
  finishedAt: string;
  message: string;
};

export type DirectoryCacheRefreshResponse = {
  ok: boolean;
  result: DirectoryCacheRefreshResult;
};

export type DirectoryCopyScheduleType = "MANUAL_ONLY" | "INTERVAL" | "DAILY";

export type DirectoryCopyConflictPolicy = "SKIP" | "OVERWRITE" | "FAIL";

export type DirectoryCopyTask = {
  id: number;
  name: string;
  sourceFileSourceId: string;
  sourcePaths: string[];
  targetFileSourceId: string;
  targetDirectory: string;
  compareMode: "FAST" | string;
  mtimeToleranceSeconds: number;
  conflictPolicy: DirectoryCopyConflictPolicy;
  scheduleEnabled: boolean;
  scheduleType: DirectoryCopyScheduleType | string;
  scheduleIntervalMinutes: number;
  scheduleTimeOfDay: string;
  scheduleTimezone: string;
  nextRunAt: string;
  enabled: boolean;
  lastOperationId?: number | null;
  lastStatus: string;
  lastStartedAt: string;
  lastFinishedAt: string;
  lastMessage: string;
};

export type DirectoryCopyTaskDraft = {
  sourceFileSourceId: string;
  sourcePaths: string[];
  targetFileSourceId: string;
  targetDirectory: string;
  conflictPolicy: DirectoryCopyConflictPolicy;
};

export type DirectoryCopyTaskListResult = {
  tasks: DirectoryCopyTask[];
};

export type DirectoryCopyTaskSaveResult = {
  ok: boolean;
  task: DirectoryCopyTask;
};

export type DirectoryCopyTaskDeleteResult = {
  ok: boolean;
  taskId: number;
};

export type FileSourceDeleteResult = {
  ok: boolean;
  sourceId: string;
};

export type RemoteFileEntry = FileEntry;
export type RemoteFileListing = FileListing;
export type RemoteFileResult = FileBrowseResult;

export type FtpSyncEvent = {
  id: number;
  taskId: string;
  runId?: number | null;
  seq: number;
  occurredAt: string;
  level: string;
  phase: string;
  operationName: string;
  eventType: string;
  errorCategory: string;
  retryable: boolean;
  replyCode?: number | null;
  replyText: string;
  sourcePath: string;
  itemPath: string;
  itemType: string;
  itemSize?: number | null;
  durationMs?: number | null;
  handlingAction: string;
  healthImpact: string;
  message: string;
  detailsText: string;
};

export type EventPageResult = {
  events: FtpSyncEvent[];
  total?: number | null;
  page?: number;
  pageSize?: number;
  nextCursor?: string | null;
  hasMore?: boolean;
};

export type RunPageResult = {
  runs: SyncRun[];
  total?: number | null;
  page?: number;
  pageSize?: number;
  nextCursor?: string | null;
  hasMore?: boolean;
};

export type DiagnosticReport = {
  id: number;
  taskId: string;
  checkType: string;
  state: string;
  startedAt: string;
  finishedAt: string;
  durationMs?: number | null;
  errorCategory: string;
  handlingAction: string;
  healthImpact: string;
  message: string;
  detailsText: string;
  steps: DiagnosticStep[];
};

export type DiagnosticStep = {
  id: number;
  diagnosticId: number;
  seq: number;
  stepName: string;
  state: string;
  errorCategory: string;
  retryable: boolean;
  replyCode?: number | null;
  replyText: string;
  remotePath: string;
  localPath: string;
  durationMs?: number | null;
  handlingAction: string;
  healthImpact: string;
  message: string;
  detailsText: string;
};

export type SyncRun = {
  id: number;
  taskId: string;
  operationType: string;
  sourcePath: string;
  targetTaskId: string;
  targetPath: string;
  conflictPolicy: string;
  state: string;
  finalHealth: string;
  startedAt: string;
  finishedAt: string;
  durationMs?: number | null;
  itemCount: number;
  fileCount: number;
  directoryCount: number;
  totalBytes: number;
  warningCount: number;
  errorCount: number;
  lastErrorCategory: string;
  lastErrorMessage: string;
  summaryEventType?: string;
  summaryLevel?: string;
  summaryMessage?: string;
  summarySourcePath?: string;
  summaryTargetPath?: string;
  summaryDetailsText?: string;
  alertEventType?: string;
  alertMessage?: string;
};

export type FileCopyRequest = {
  sourceId: string;
  sourcePaths: string[];
  targetId: string;
  targetDirectory: string;
  conflictPolicy: "SKIP" | "OVERWRITE" | "FAIL";
};

export type FileCopyResult = {
  ok: boolean;
  operationId: number;
  itemCount: number;
  fileCount: number;
  directoryCount: number;
  skippedCount: number;
  totalBytes: number;
  state: string;
  message: string;
};

export type FileOperationCancelResult = {
  ok: boolean;
  operationId: number;
  message: string;
};

export type ActionResult = {
  ok?: boolean;
  started?: boolean;
  accepted?: boolean;
  runId?: number;
  message?: string;
  diagnostic?: DiagnosticReport;
};
