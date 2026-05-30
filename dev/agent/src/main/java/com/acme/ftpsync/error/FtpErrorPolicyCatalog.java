package com.acme.ftpsync.error;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FtpErrorPolicyCatalog {
    private static final Map<String, FtpErrorPolicy> FALLBACKS = new LinkedHashMap<>();

    static {
        add("CONNECTION_ERROR", "ERROR", true, "RETRY_THEN_ALERT", "NEEDS_NETWORK_CHECK", "Connection Error",
                "Network, DNS, port, firewall, or FTP service connectivity failure.");
        add("AUTH_ERROR", "ERROR", false, "FAIL_FAST", "NEEDS_CONFIG_FIX", "Authentication Error",
                "Username, password, account status, or login permission failure.");
        add("TLS_ERROR", "ERROR", false, "FAIL_FAST", "NEEDS_TLS_CONFIRMATION", "TLS Error",
                "FTPS mode, certificate, fingerprint, or TLS handshake failure.");
        add("DATA_CHANNEL_ERROR", "ERROR", true, "RETRY_THEN_ALERT", "NEEDS_NETWORK_CHECK", "Data Channel Error",
                "Passive port, NAT, firewall, or transfer data connection failure.");
        add("REMOTE_PATH_ERROR", "WARN", false, "SKIP_OR_FAIL_BY_PHASE", "NEEDS_REMOTE_PATH_CHECK", "Remote Path Error",
                "Remote path missing, permission denied, encoding issue, or unsupported path format.");
        add("REMOTE_TEMPORARY_ERROR", "WARN", true, "RETRY_WITH_BACKOFF", "TEMPORARY_DEGRADED", "Remote Temporary Error",
                "Temporary FTP server or file availability issue.");
        add("REMOTE_CAPACITY_ERROR", "ERROR", false, "FAIL_AND_ALERT", "NEEDS_SERVER_MAINTENANCE", "Remote Capacity Error",
                "Remote server storage, quota, or resource exhaustion.");
        add("LOCAL_IO_ERROR", "ERROR", false, "FAIL_OR_RETRY_BY_CAUSE", "NEEDS_LOCAL_PERMISSION_CHECK", "Local IO Error",
                "Local permission, path length, lock, or filesystem write/delete failure.");
        add("LOCAL_CAPACITY_ERROR", "ERROR", false, "FAIL_AND_ALERT", "NEEDS_DISK_CLEANUP", "Local Capacity Error",
                "Local disk space or quota exhaustion.");
        add("AGENT_INTERRUPTED", "ERROR", false, "MARK_FAILED_AND_REVIEW", "NEEDS_AGENT_RECOVERY_REVIEW",
                "Agent Interrupted", "The Agent process stopped while a file operation was still RUNNING.");
        add("USER_CANCELLED", "INFO", false, "STOP_AND_KEEP_STATE", "USER_CANCELLED", "User Cancelled",
                "The file operation was cancelled by the local user.");
        add("UNKNOWN_ERROR", "ERROR", false, "CAPTURE_CONTEXT_AND_ALERT", "NEEDS_INVESTIGATION", "Unknown Error",
                "Unclassified failure.");
    }

    private FtpErrorPolicyCatalog() {
    }

    public static Map<String, FtpErrorPolicy> allFallbacks() {
        return FALLBACKS;
    }

    public static FtpErrorPolicy fallback(String category) {
        if (category == null || category.isBlank()) {
            return FALLBACKS.get("UNKNOWN_ERROR");
        }
        return FALLBACKS.getOrDefault(category.trim().toUpperCase(Locale.ROOT), FALLBACKS.get("UNKNOWN_ERROR"));
    }

    public static String classify(Integer replyCode, String replyText, String message, String eventType) {
        if (replyCode != null) {
            return classifyReplyCode(replyCode);
        }
        String text = ((replyText == null ? "" : replyText) + " "
                + (message == null ? "" : message) + " "
                + (eventType == null ? "" : eventType)).toUpperCase(Locale.ROOT);
        if (containsAny(text, "530", "LOGIN FAILED", "USER CANNOT LOG IN", "PASSWORD")) {
            return "AUTH_ERROR";
        }
        if (containsAny(text, "534", "REQUIRES SSL", "SSLHANDSHAKE", "CERTIFICATE", "FINGERPRINT")) {
            return "TLS_ERROR";
        }
        if (containsAny(text, "425", "426", "DATA CONNECTION", "TRANSFER ABORTED")) {
            return "DATA_CHANNEL_ERROR";
        }
        if (containsAny(text, "550", "553", "FILE UNAVAILABLE", "FILE NAME NOT ALLOWED", "PATH")) {
            return "REMOTE_PATH_ERROR";
        }
        if (containsAny(text, "421", "450", "451", "SERVICE NOT AVAILABLE", "TEMPORARY")) {
            return "REMOTE_TEMPORARY_ERROR";
        }
        if (containsAny(text, "452", "INSUFFICIENT STORAGE")) {
            return "REMOTE_CAPACITY_ERROR";
        }
        if (containsAny(text, "NO SPACE", "DISK FULL")) {
            return "LOCAL_CAPACITY_ERROR";
        }
        if (containsAny(text, "ACCESSDENIED", "FILE SYSTEM", "FILESystemException".toUpperCase(Locale.ROOT))) {
            return "LOCAL_IO_ERROR";
        }
        if (containsAny(text, "CHECKPOINT")) {
            return "CHECKPOINT_ERROR";
        }
        if (containsAny(text, "AGENT INTERRUPTED", "RUN_ABANDONED")) {
            return "AGENT_INTERRUPTED";
        }
        if (containsAny(text, "CONNECTION REFUSED", "CONNECTION TIMED OUT", "UNKNOWNHOST", "CONNECT FAILED")) {
            return "CONNECTION_ERROR";
        }
        return "UNKNOWN_ERROR";
    }

    private static String classifyReplyCode(int replyCode) {
        return switch (replyCode) {
            case 421, 450, 451 -> "REMOTE_TEMPORARY_ERROR";
            case 425, 426 -> "DATA_CHANNEL_ERROR";
            case 452 -> "REMOTE_CAPACITY_ERROR";
            case 500, 501, 502, 550, 553 -> "REMOTE_PATH_ERROR";
            case 530 -> "AUTH_ERROR";
            case 534 -> "TLS_ERROR";
            default -> replyCode >= 400 ? "UNKNOWN_ERROR" : "";
        };
    }

    private static void add(String category, String severity, boolean retryable, String handlingAction,
                            String healthImpact, String displayName, String description) {
        FALLBACKS.put(category, new FtpErrorPolicy(category, severity, retryable, handlingAction,
                healthImpact, displayName, description));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
