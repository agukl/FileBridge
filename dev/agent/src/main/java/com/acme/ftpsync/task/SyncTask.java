package com.acme.ftpsync.task;

import com.fasterxml.jackson.annotation.JsonAlias;

public record SyncTask(
        String taskId,
        String taskName,
        String sourceType,
        String ftpHost,
        int ftpPort,
        String ftpUsername,
        String passwordRef,
        String secureMode,
        String tlsFingerprint,
        String tlsFingerprintHash,
        @JsonAlias({"remotePath", "localPath"})
        String sourcePath,
        boolean remoteDirectoryCacheEnabled,
        FileSourcePermission permission
) {
    public String remotePath() {
        String type = sourceType == null ? "" : sourceType;
        return "LOCAL".equalsIgnoreCase(type) || "SMB".equalsIgnoreCase(type) ? "" : sourcePath;
    }

    public String localPath() {
        String type = sourceType == null ? "" : sourceType;
        return "LOCAL".equalsIgnoreCase(type) || "SMB".equalsIgnoreCase(type) ? sourcePath : "";
    }
}
