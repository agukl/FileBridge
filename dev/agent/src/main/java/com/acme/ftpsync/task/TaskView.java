package com.acme.ftpsync.task;

import java.util.Locale;

public record TaskView(
        String taskId,
        String taskName,
        String sourceType,
        String ftpHost,
        int ftpPort,
        String ftpUsername,
        boolean passwordConfigured,
        String credentialKind,
        String secureMode,
        String tlsFingerprint,
        String tlsFingerprintHash,
        String sourcePath,
        boolean remoteDirectoryCacheEnabled,
        FileSourcePermission permission
) {
    public static TaskView from(SyncTask task) {
        String passwordRef = task.passwordRef();
        return new TaskView(
                task.taskId(),
                task.taskName(),
                task.sourceType(),
                task.ftpHost(),
                task.ftpPort(),
                task.ftpUsername(),
                passwordRef != null && !passwordRef.isBlank(),
                passwordKind(passwordRef),
                task.secureMode(),
                task.tlsFingerprint(),
                task.tlsFingerprintHash(),
                task.sourcePath(),
                task.remoteDirectoryCacheEnabled(),
                task.permission() == null ? FileSourcePermission.unknown() : task.permission()
        );
    }

    private static String passwordKind(String passwordRef) {
        if (passwordRef == null || passwordRef.isBlank()) {
            return "NONE";
        }
        String value = passwordRef.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("env:")) {
            return "ENV";
        }
        if (value.startsWith("plain:")) {
            return "INLINE";
        }
        return "REFERENCE";
    }
}
