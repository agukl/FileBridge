package com.acme.ftpsync.task;

import com.acme.ftpsync.util.DateTimes;

public record FileSourcePermission(
        boolean canRead,
        boolean canWrite,
        String state,
        String checkedAt,
        String message
) {
    public static FileSourcePermission ready(String message) {
        return new FileSourcePermission(true, true, "READY", DateTimes.nowDatabase(), value(message, "Read/write available."));
    }

    public static FileSourcePermission from(boolean canRead, boolean canWrite, String message) {
        String state;
        if (canRead && canWrite) {
            state = "READY";
        } else if (canRead || canWrite) {
            state = "LIMITED";
        } else {
            state = "FAILED";
        }
        return new FileSourcePermission(canRead, canWrite, state, DateTimes.nowDatabase(), value(message, ""));
    }

    public static FileSourcePermission failed(String message) {
        return new FileSourcePermission(false, false, "FAILED", DateTimes.nowDatabase(), value(message, "Permission check failed."));
    }

    public static FileSourcePermission unknown() {
        return new FileSourcePermission(false, false, "UNKNOWN", "", "");
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

