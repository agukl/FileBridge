-- SQLite initialization script for the FileBridge Agent.
-- Safe to run repeatedly. Mutable data is stored in a single local database file.

CREATE TABLE IF NOT EXISTS file_source (
  task_id TEXT NOT NULL PRIMARY KEY,
  task_name TEXT NOT NULL,
  source_type TEXT NOT NULL DEFAULT 'REMOTE_FTP',
  ftp_host TEXT NOT NULL,
  ftp_port INTEGER NOT NULL DEFAULT 21,
  ftp_username TEXT NOT NULL,
  password_ref TEXT DEFAULT NULL,
  secure_mode TEXT NOT NULL DEFAULT 'NONE',
  tls_fingerprint TEXT DEFAULT NULL,
  tls_fingerprint_hash TEXT NOT NULL DEFAULT 'SHA256',
  source_path TEXT NOT NULL,
  can_read INTEGER NOT NULL DEFAULT 0,
  can_write INTEGER NOT NULL DEFAULT 0,
  permission_state TEXT NOT NULL DEFAULT 'UNKNOWN',
  permission_checked_at TEXT DEFAULT NULL,
  permission_message TEXT DEFAULT NULL,
  remote_directory_cache_enabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_file_source_type ON file_source(source_type);
CREATE INDEX IF NOT EXISTS idx_file_source_updated ON file_source(updated_at);

CREATE TABLE IF NOT EXISTS file_source_directory_cache (
  task_id TEXT NOT NULL,
  path_hash TEXT NOT NULL,
  source_signature TEXT NOT NULL,
  path_text TEXT NOT NULL,
  parent_path TEXT DEFAULT NULL,
  directory_modified_at TEXT DEFAULT NULL,
  directories_json TEXT NOT NULL,
  entries_json TEXT DEFAULT NULL,
  reply_code INTEGER NOT NULL DEFAULT 0,
  reply_text TEXT DEFAULT NULL,
  limit_used INTEGER NOT NULL DEFAULT 0,
  truncated INTEGER NOT NULL DEFAULT 0,
  scanned_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  PRIMARY KEY (task_id, path_hash)
);

CREATE INDEX IF NOT EXISTS idx_directory_cache_task_scanned
  ON file_source_directory_cache(task_id, scanned_at DESC);
CREATE INDEX IF NOT EXISTS idx_directory_cache_source_signature
  ON file_source_directory_cache(source_signature);

CREATE TABLE IF NOT EXISTS directory_copy_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  source_file_source_id TEXT NOT NULL,
  source_paths_json TEXT NOT NULL,
  target_file_source_id TEXT NOT NULL,
  target_directory TEXT NOT NULL,
  compare_mode TEXT NOT NULL DEFAULT 'FAST',
  mtime_tolerance_seconds INTEGER NOT NULL DEFAULT 5,
  conflict_policy TEXT NOT NULL DEFAULT 'SKIP',
  schedule_enabled INTEGER NOT NULL DEFAULT 0,
  schedule_type TEXT NOT NULL DEFAULT 'MANUAL_ONLY',
  schedule_interval_minutes INTEGER NOT NULL DEFAULT 0,
  schedule_time_of_day TEXT DEFAULT NULL,
  schedule_timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  next_run_at TEXT DEFAULT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  last_operation_id INTEGER DEFAULT NULL,
  last_status TEXT DEFAULT NULL,
  last_started_at TEXT DEFAULT NULL,
  last_finished_at TEXT DEFAULT NULL,
  last_message TEXT DEFAULT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_directory_copy_enabled_next_run
  ON directory_copy_task(enabled, schedule_enabled, next_run_at);
CREATE INDEX IF NOT EXISTS idx_directory_copy_source ON directory_copy_task(source_file_source_id);
CREATE INDEX IF NOT EXISTS idx_directory_copy_target ON directory_copy_task(target_file_source_id);
CREATE INDEX IF NOT EXISTS idx_directory_copy_last_operation ON directory_copy_task(last_operation_id);
CREATE INDEX IF NOT EXISTS idx_directory_copy_updated ON directory_copy_task(updated_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS file_operation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  operation_type TEXT NOT NULL,
  state TEXT NOT NULL,
  final_health TEXT DEFAULT NULL,
  source_path TEXT DEFAULT NULL,
  target_task_id TEXT DEFAULT NULL,
  target_path TEXT DEFAULT NULL,
  conflict_policy TEXT DEFAULT NULL,
  started_at TEXT NOT NULL,
  finished_at TEXT DEFAULT NULL,
  duration_ms INTEGER DEFAULT NULL,
  item_count INTEGER NOT NULL DEFAULT 0,
  file_count INTEGER NOT NULL DEFAULT 0,
  directory_count INTEGER NOT NULL DEFAULT 0,
  total_bytes INTEGER NOT NULL DEFAULT 0,
  warning_count INTEGER NOT NULL DEFAULT 0,
  error_count INTEGER NOT NULL DEFAULT 0,
  last_error_category TEXT DEFAULT NULL,
  last_error_message TEXT DEFAULT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_operation_task_started ON file_operation(task_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_type_started ON file_operation(operation_type, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_state_started ON file_operation(state, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_health_started ON file_operation(final_health, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_started_id ON file_operation(started_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_operation_state_started_id ON file_operation(state, started_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_operation_task_started_id ON file_operation(task_id, started_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_operation_type_started_id ON file_operation(operation_type, started_at DESC, id DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS file_operation_fts USING fts5(
  task_id,
  operation_type,
  source_path,
  target_task_id,
  target_path,
  conflict_policy,
  state,
  final_health,
  last_error_category,
  last_error_message,
  content='file_operation',
  content_rowid='id',
  tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS file_operation_ai AFTER INSERT ON file_operation BEGIN
  INSERT INTO file_operation_fts(rowid, task_id, operation_type, source_path, target_task_id, target_path,
    conflict_policy, state, final_health, last_error_category, last_error_message)
  VALUES (new.id, new.task_id, new.operation_type, new.source_path, new.target_task_id, new.target_path,
    new.conflict_policy, new.state, new.final_health, new.last_error_category, new.last_error_message);
END;

CREATE TRIGGER IF NOT EXISTS file_operation_ad AFTER DELETE ON file_operation BEGIN
  INSERT INTO file_operation_fts(file_operation_fts, rowid, task_id, operation_type, source_path, target_task_id,
    target_path, conflict_policy, state, final_health, last_error_category, last_error_message)
  VALUES ('delete', old.id, old.task_id, old.operation_type, old.source_path, old.target_task_id,
    old.target_path, old.conflict_policy, old.state, old.final_health, old.last_error_category, old.last_error_message);
END;

CREATE TRIGGER IF NOT EXISTS file_operation_au AFTER UPDATE ON file_operation BEGIN
  INSERT INTO file_operation_fts(file_operation_fts, rowid, task_id, operation_type, source_path, target_task_id,
    target_path, conflict_policy, state, final_health, last_error_category, last_error_message)
  VALUES ('delete', old.id, old.task_id, old.operation_type, old.source_path, old.target_task_id,
    old.target_path, old.conflict_policy, old.state, old.final_health, old.last_error_category, old.last_error_message);
  INSERT INTO file_operation_fts(rowid, task_id, operation_type, source_path, target_task_id, target_path,
    conflict_policy, state, final_health, last_error_category, last_error_message)
  VALUES (new.id, new.task_id, new.operation_type, new.source_path, new.target_task_id, new.target_path,
    new.conflict_policy, new.state, new.final_health, new.last_error_category, new.last_error_message);
END;

CREATE TABLE IF NOT EXISTS file_operation_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  run_id INTEGER DEFAULT NULL,
  seq INTEGER NOT NULL,
  occurred_at TEXT NOT NULL,
  level TEXT NOT NULL,
  phase TEXT NOT NULL,
  operation_name TEXT NOT NULL,
  event_type TEXT NOT NULL,
  error_category TEXT DEFAULT NULL,
  retryable INTEGER NOT NULL DEFAULT 0,
  reply_code INTEGER DEFAULT NULL,
  reply_text TEXT DEFAULT NULL,
  source_path TEXT,
  item_path TEXT,
  item_type TEXT DEFAULT NULL,
  item_size INTEGER DEFAULT NULL,
  duration_ms INTEGER DEFAULT NULL,
  handling_action TEXT DEFAULT NULL,
  health_impact TEXT DEFAULT NULL,
  message TEXT DEFAULT NULL,
  details_text TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  UNIQUE (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_log_task_time ON file_operation_log(task_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_run_time ON file_operation_log(run_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_occurred_id ON file_operation_log(occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_log_task_event_time ON file_operation_log(task_id, event_type, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_task_error_time ON file_operation_log(task_id, error_category, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_level_time ON file_operation_log(level, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_health_time ON file_operation_log(health_impact, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_log_task_occurred_id ON file_operation_log(task_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_log_run_occurred_id ON file_operation_log(run_id, occurred_at DESC, id DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS file_operation_log_fts USING fts5(
  task_id,
  level,
  phase,
  operation_name,
  event_type,
  error_category,
  handling_action,
  health_impact,
  message,
  details_text,
  reply_text,
  source_path,
  item_path,
  item_type,
  content='file_operation_log',
  content_rowid='id',
  tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS file_operation_log_ai AFTER INSERT ON file_operation_log BEGIN
  INSERT INTO file_operation_log_fts(rowid, task_id, level, phase, operation_name, event_type, error_category,
    handling_action, health_impact, message, details_text, reply_text, source_path, item_path, item_type)
  VALUES (new.id, new.task_id, new.level, new.phase, new.operation_name, new.event_type, new.error_category,
    new.handling_action, new.health_impact, new.message, new.details_text, new.reply_text,
    new.source_path, new.item_path, new.item_type);
END;

CREATE TRIGGER IF NOT EXISTS file_operation_log_ad AFTER DELETE ON file_operation_log BEGIN
  INSERT INTO file_operation_log_fts(file_operation_log_fts, rowid, task_id, level, phase, operation_name,
    event_type, error_category, handling_action, health_impact, message, details_text, reply_text,
    source_path, item_path, item_type)
  VALUES ('delete', old.id, old.task_id, old.level, old.phase, old.operation_name, old.event_type,
    old.error_category, old.handling_action, old.health_impact, old.message, old.details_text,
    old.reply_text, old.source_path, old.item_path, old.item_type);
END;

CREATE TRIGGER IF NOT EXISTS file_operation_log_au AFTER UPDATE ON file_operation_log BEGIN
  INSERT INTO file_operation_log_fts(file_operation_log_fts, rowid, task_id, level, phase, operation_name,
    event_type, error_category, handling_action, health_impact, message, details_text, reply_text,
    source_path, item_path, item_type)
  VALUES ('delete', old.id, old.task_id, old.level, old.phase, old.operation_name, old.event_type,
    old.error_category, old.handling_action, old.health_impact, old.message, old.details_text,
    old.reply_text, old.source_path, old.item_path, old.item_type);
  INSERT INTO file_operation_log_fts(rowid, task_id, level, phase, operation_name, event_type, error_category,
    handling_action, health_impact, message, details_text, reply_text, source_path, item_path, item_type)
  VALUES (new.id, new.task_id, new.level, new.phase, new.operation_name, new.event_type, new.error_category,
    new.handling_action, new.health_impact, new.message, new.details_text, new.reply_text,
    new.source_path, new.item_path, new.item_type);
END;

CREATE TABLE IF NOT EXISTS file_source_diagnostic (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id TEXT NOT NULL,
  check_type TEXT NOT NULL,
  state TEXT NOT NULL,
  started_at TEXT NOT NULL,
  finished_at TEXT DEFAULT NULL,
  duration_ms INTEGER DEFAULT NULL,
  error_category TEXT DEFAULT NULL,
  handling_action TEXT DEFAULT NULL,
  health_impact TEXT DEFAULT NULL,
  message TEXT DEFAULT NULL,
  details_text TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_diagnostic_task_started ON file_source_diagnostic(task_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_diagnostic_state_started ON file_source_diagnostic(state, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_diagnostic_category_started ON file_source_diagnostic(error_category, started_at DESC);

CREATE TABLE IF NOT EXISTS file_source_diagnostic_step (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  diagnostic_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  step_name TEXT NOT NULL,
  state TEXT NOT NULL,
  error_category TEXT DEFAULT NULL,
  retryable INTEGER NOT NULL DEFAULT 0,
  reply_code INTEGER DEFAULT NULL,
  reply_text TEXT DEFAULT NULL,
  remote_path TEXT,
  local_path TEXT,
  duration_ms INTEGER DEFAULT NULL,
  handling_action TEXT DEFAULT NULL,
  health_impact TEXT DEFAULT NULL,
  message TEXT DEFAULT NULL,
  details_text TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')),
  UNIQUE (diagnostic_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_diagnostic_step_diagnostic ON file_source_diagnostic_step(diagnostic_id);
CREATE INDEX IF NOT EXISTS idx_diagnostic_step_state ON file_source_diagnostic_step(step_name, state);
CREATE INDEX IF NOT EXISTS idx_diagnostic_step_error_category ON file_source_diagnostic_step(error_category);
