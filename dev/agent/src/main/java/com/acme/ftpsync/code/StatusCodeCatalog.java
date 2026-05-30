package com.acme.ftpsync.code;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StatusCodeCatalog {
    private StatusCodeCatalog() {
    }

    public static Map<String, Map<String, String>> all() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("runStates", map(
                "RUNNING", "\u8fdb\u884c\u4e2d",
                "SUCCESS", "\u6210\u529f",
                "FAILED", "\u5931\u8d25",
                "CANCELLED", "\u5df2\u53d6\u6d88",
                "NEVER_RUN", "\u6682\u65e0\u8bb0\u5f55",
                "UNKNOWN", "\u672a\u77e5"
        ));
        groups.put("triggerTypes", map(
                "FILE_BROWSE", "\u6d4f\u89c8\u6587\u4ef6",
                "REMOTE_BROWSE", "\u6d4f\u89c8\u6587\u4ef6",
                "FILE_COPY", "\u590d\u5236\u6587\u4ef6",
                "DIRECTORY_COPY_TASK", "\u76ee\u5f55\u590d\u5236",
                "CONNECTION_TEST", "\u6d4b\u8bd5\u8fde\u63a5",
                "DIRECTORY_CACHE_REFRESH", "\u5237\u65b0\u76ee\u5f55\u7f13\u5b58",
                "FILE_OPERATION", "\u6587\u4ef6\u64cd\u4f5c",
                "MANUAL", "\u624b\u52a8\u6267\u884c",
                "RECOVERY", "\u542f\u52a8\u6062\u590d"
        ));
        groups.put("eventLevels", map(
                "INFO", "\u4fe1\u606f",
                "WARN", "\u8b66\u544a",
                "WARNING", "\u8b66\u544a",
                "ERROR", "\u9519\u8bef"
        ));
        groups.put("phases", map(
                "BROWSE", "\u6d4f\u89c8",
                "COPY", "\u590d\u5236",
                "CHECK", "\u68c0\u67e5",
                "CACHE", "\u7f13\u5b58",
                "CONNECT", "\u8fde\u63a5",
                "LOGIN", "\u767b\u5f55",
                "LIST", "\u8bfb\u53d6\u76ee\u5f55",
                "RECOVERY", "\u6062\u590d",
                "DONE", "\u5b8c\u6210",
                "FAILED", "\u5931\u8d25",
                "CANCELLED", "\u53d6\u6d88",
                "UNKNOWN", "\u672a\u77e5"
        ));
        groups.put("operations", map(
                "LIST", "\u8bfb\u53d6\u6587\u4ef6\u5217\u8868",
                "COPY", "\u590d\u5236\u6587\u4ef6",
                "MKDIR", "\u51c6\u5907\u76ee\u5f55",
                "DIRECTORY_COPY", "\u76ee\u5f55\u590d\u5236",
                "CONNECTION_TEST", "\u6d4b\u8bd5\u8fde\u63a5",
                "DIRECTORY_CACHE_REFRESH", "\u5237\u65b0\u76ee\u5f55\u7f13\u5b58",
                "CONNECT", "\u8fde\u63a5 FTP",
                "LOGIN", "\u767b\u5f55\u8ba4\u8bc1",
                "TLS", "TLS \u4fdd\u62a4",
                "AGENT", "Agent",
                "UNKNOWN", "\u672a\u77e5\u64cd\u4f5c"
        ));
        groups.put("eventTypes", map(
                "FILES_LISTED", "\u6587\u4ef6\u5df2\u8bfb\u53d6",
                "REMOTE_FILES_LISTED", "\u6587\u4ef6\u5df2\u8bfb\u53d6",
                "FILE_COPY_STARTED", "\u5f00\u59cb\u590d\u5236\u6587\u4ef6",
                "FILE_COPIED", "\u6587\u4ef6\u5df2\u590d\u5236",
                "DIRECTORY_CREATED", "\u76ee\u5f55\u5df2\u51c6\u5907",
                "FILE_SKIPPED", "\u6587\u4ef6\u5df2\u8df3\u8fc7",
                "FILE_COPY_FINISHED", "\u6587\u4ef6\u590d\u5236\u5b8c\u6210",
                "FILE_COPY_FAILED", "\u6587\u4ef6\u590d\u5236\u5931\u8d25",
                "FILE_COPY_CANCELLED", "\u6587\u4ef6\u590d\u5236\u5df2\u53d6\u6d88",
                "FILE_COPY_ITEM_FAILED", "\u6587\u4ef6\u590d\u5236\u5355\u9879\u5931\u8d25",
                "DIRECTORY_COPY_TASK_STARTED", "\u5f00\u59cb\u76ee\u5f55\u590d\u5236",
                "DIRECTORY_COPY_TASK_FINISHED", "\u76ee\u5f55\u590d\u5236\u5b8c\u6210",
                "DIRECTORY_COPY_TASK_FAILED", "\u76ee\u5f55\u590d\u5236\u5931\u8d25",
                "DIRECTORY_COPY_TASK_CANCELLED", "\u76ee\u5f55\u590d\u5236\u5df2\u53d6\u6d88",
                "DIRECTORY_COMPARE_STARTED", "\u5f00\u59cb\u76ee\u5f55\u6bd4\u5bf9",
                "DIRECTORY_COMPARE_FINISHED", "\u76ee\u5f55\u6bd4\u5bf9\u5b8c\u6210",
                "TYPE_CONFLICT", "\u6587\u4ef6\u7c7b\u578b\u51b2\u7a81",
                "DIRECTORY_CACHE_REFRESHED", "\u76ee\u5f55\u7f13\u5b58\u5df2\u5237\u65b0",
                "SUCCESS", "\u68c0\u67e5\u901a\u8fc7",
                "FAILED", "\u68c0\u67e5\u5931\u8d25",
                "FILE_OPERATION_FAILED", "\u6587\u4ef6\u64cd\u4f5c\u5931\u8d25",
                "RUN_ABANDONED", "\u64cd\u4f5c\u4e2d\u65ad\u6062\u590d",
                "FTP_CONNECT_STARTED", "\u5f00\u59cb\u8fde\u63a5 FTP",
                "FTP_CONNECT_SUCCESS", "FTP \u8fde\u63a5\u6210\u529f",
                "FTP_CONNECT_FAILED", "FTP \u8fde\u63a5\u5931\u8d25",
                "FTP_SESSION_PREPARE", "\u51c6\u5907 FTP \u4f1a\u8bdd",
                "FTP_SESSION_PREPARE_FAILED", "FTP \u4f1a\u8bdd\u51c6\u5907\u5931\u8d25",
                "FTP_LOGIN_SUCCESS", "FTP \u767b\u5f55\u6210\u529f",
                "FTP_LOGIN_FAILED", "FTP \u767b\u5f55\u5931\u8d25",
                "FTP_TLS_PROTECTION_ENABLED", "TLS \u4fdd\u62a4\u5df2\u542f\u7528",
                "FTP_TLS_PROTECTION_FAILED", "TLS \u4fdd\u62a4\u542f\u7528\u5931\u8d25",
                "FTP_LIST_SUCCESS", "\u76ee\u5f55\u8bfb\u53d6\u6210\u529f",
                "FTP_LIST_FAILED", "\u76ee\u5f55\u8bfb\u53d6\u5931\u8d25",
                "FTP_LIST_WARNING", "\u76ee\u5f55\u8bfb\u53d6\u8b66\u544a"
        ));
        groups.put("errorCategories", map(
                "CONNECTION_ERROR", "\u8fde\u63a5\u9519\u8bef",
                "AUTH_ERROR", "\u8ba4\u8bc1\u9519\u8bef",
                "TLS_ERROR", "TLS \u8bc1\u4e66\u6216\u63e1\u624b\u9519\u8bef",
                "DATA_CHANNEL_ERROR", "\u6570\u636e\u901a\u9053\u9519\u8bef",
                "REMOTE_PATH_ERROR", "\u8fdc\u7a0b\u8def\u5f84\u6216\u6743\u9650\u9519\u8bef",
                "REMOTE_TEMPORARY_ERROR", "\u8fdc\u7a0b\u4e34\u65f6\u9519\u8bef",
                "REMOTE_CAPACITY_ERROR", "\u8fdc\u7a0b\u5bb9\u91cf\u9519\u8bef",
                "LOCAL_IO_ERROR", "\u672c\u5730 IO \u9519\u8bef",
                "LOCAL_CAPACITY_ERROR", "\u672c\u5730\u5bb9\u91cf\u9519\u8bef",
                "FILE_OPERATION_ERROR", "\u6587\u4ef6\u64cd\u4f5c\u9519\u8bef",
                "AGENT_INTERRUPTED", "Agent \u4e2d\u65ad",
                "USER_CANCELLED", "\u7528\u6237\u53d6\u6d88",
                "UNKNOWN_ERROR", "\u672a\u77e5\u9519\u8bef"
        ));
        groups.put("handlingActions", map(
                "RETRY_THEN_ALERT", "\u5148\u91cd\u8bd5\uff0c\u4ecd\u5931\u8d25\u5c31\u68c0\u67e5",
                "RETRY_WITH_BACKOFF", "\u7b49\u5f85\u540e\u91cd\u8bd5",
                "FAIL_FAST", "\u5148\u4fee\u6b63\u914d\u7f6e\uff0c\u518d\u91cd\u65b0\u6267\u884c",
                "FAIL_AND_ALERT", "\u7acb\u5373\u5904\u7406\u540e\u91cd\u8bd5",
                "FAIL_OR_RETRY_BY_CAUSE", "\u6309\u5177\u4f53\u539f\u56e0\u5904\u7406",
                "MARK_FAILED_AND_REVIEW", "\u6807\u8bb0\u5931\u8d25\u5e76\u590d\u67e5",
                "CAPTURE_CONTEXT_AND_ALERT", "\u4fdd\u7559\u4e0a\u4e0b\u6587\u5e76\u6392\u67e5"
        ));
        groups.put("healthImpacts", map(
                "HEALTHY", "\u5065\u5eb7",
                "RUNNING", "\u8fdb\u884c\u4e2d",
                "COMPLETED_WITH_WARNINGS", "\u5b8c\u6210\u4f46\u6709\u8b66\u544a",
                "COMPLETED_WITH_ERRORS", "\u5b8c\u6210\u4f46\u6709\u9519\u8bef",
                "TEMPORARY_DEGRADED", "\u4e34\u65f6\u5f02\u5e38",
                "NEEDS_NETWORK_CHECK", "\u9700\u8981\u68c0\u67e5\u7f51\u7edc",
                "NEEDS_CONFIG_FIX", "\u9700\u8981\u4fee\u6b63\u914d\u7f6e",
                "NEEDS_TLS_CONFIRMATION", "\u9700\u8981\u786e\u8ba4 TLS",
                "NEEDS_REMOTE_PATH_CHECK", "\u9700\u8981\u68c0\u67e5\u8fdc\u7a0b\u8def\u5f84",
                "NEEDS_LOCAL_PERMISSION_CHECK", "\u9700\u8981\u68c0\u67e5\u672c\u5730\u6743\u9650",
                "NEEDS_SERVER_MAINTENANCE", "\u9700\u8981\u670d\u52a1\u7aef\u7ef4\u62a4",
                "NEEDS_AGENT_RECOVERY_REVIEW", "\u9700\u8981\u590d\u67e5 Agent \u6062\u590d",
                "NEEDS_INVESTIGATION", "\u9700\u8981\u4eba\u5de5\u6392\u67e5",
                "USER_CANCELLED", "\u7528\u6237\u53d6\u6d88"
        ));
        groups.put("ftpReplyCodes", map(
                "150", "\u6587\u4ef6\u72b6\u6001\u6b63\u5e38\uff0c\u51c6\u5907\u6253\u5f00\u6570\u636e\u8fde\u63a5",
                "200", "\u547d\u4ee4\u6210\u529f",
                "211", "\u7cfb\u7edf\u72b6\u6001\u6216\u5e2e\u52a9\u54cd\u5e94",
                "220", "\u670d\u52a1\u5c31\u7eea",
                "221", "\u670d\u52a1\u5173\u95ed\u63a7\u5236\u8fde\u63a5",
                "226", "\u6587\u4ef6\u64cd\u4f5c\u5b8c\u6210",
                "227", "\u8fdb\u5165\u88ab\u52a8\u6a21\u5f0f",
                "230", "\u767b\u5f55\u6210\u529f",
                "250", "\u6587\u4ef6\u64cd\u4f5c\u5b8c\u6210",
                "257", "\u8fd4\u56de\u5f53\u524d\u8def\u5f84",
                "331", "\u7528\u6237\u540d\u6709\u6548\uff0c\u9700\u8981\u5bc6\u7801",
                "421", "\u670d\u52a1\u4e0d\u53ef\u7528\uff0c\u7a0d\u540e\u91cd\u8bd5",
                "425", "\u65e0\u6cd5\u6253\u5f00\u6570\u636e\u8fde\u63a5",
                "450", "\u6587\u4ef6\u4e0d\u53ef\u7528\uff0c\u4e34\u65f6\u5931\u8d25",
                "500", "\u547d\u4ee4\u8bed\u6cd5\u9519\u8bef",
                "530", "\u672a\u767b\u5f55\u6216\u8ba4\u8bc1\u5931\u8d25",
                "550", "\u6587\u4ef6\u4e0d\u53ef\u7528\u3001\u65e0\u6743\u9650\u6216\u8def\u5f84\u4e0d\u5b58\u5728"
        ));
        return groups;
    }

    private static Map<String, String> map(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            result.put(entries[i], entries[i + 1]);
        }
        return result;
    }
}
