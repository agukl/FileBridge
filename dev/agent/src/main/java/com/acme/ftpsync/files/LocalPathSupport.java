package com.acme.ftpsync.files;

import java.nio.file.Path;
import java.util.Locale;

final class LocalPathSupport {
    private LocalPathSupport() {
    }

    static String normalizeInputPath(String path) {
        String value = stripOuterQuotes(path == null ? "" : path.trim());
        if (value.matches("^/[A-Za-z]:[/\\\\].*")) {
            return value.substring(1);
        }
        if (hasMultiSeparatorPrefix(value) && !isExtendedWindowsPath(value)) {
            String withoutPrefix = value.substring(separatorPrefixLength(value)).replace('/', '\\');
            return "\\\\" + withoutPrefix;
        }
        return value;
    }

    static Path toAbsolutePath(String path) {
        return Path.of(normalizeInputPath(path)).toAbsolutePath().normalize();
    }

    static Path resolveWithinRoot(Path root, String path) {
        String normalized = normalizeInputPath(path);
        if (normalized == null || normalized.isBlank()) {
            return root;
        }
        Path requested = Path.of(normalized);
        return (requested.isAbsolute() ? requested : root.resolve(requested))
                .toAbsolutePath()
                .normalize();
    }

    static boolean isNetworkShare(String rawPath, Path normalizedPath) {
        String normalized = normalizeInputPath(rawPath);
        if (normalized.startsWith("\\\\")) {
            return true;
        }
        Path root = normalizedPath == null ? null : normalizedPath.getRoot();
        return root != null && root.toString().startsWith("\\\\");
    }

    static String missingPathMessage(String rawPath, Path normalizedPath) {
        if (isNetworkShare(rawPath, normalizedPath)) {
            return "Network share is unreachable or the file source credentials have no permission: "
                    + normalizedPath
                    + ". Use an SMB file source with a UNC path like \\\\server\\share and valid share credentials.";
        }
        return "Local path does not exist: " + normalizedPath;
    }

    static String notDirectoryMessage(String rawPath, Path normalizedPath) {
        if (isNetworkShare(rawPath, normalizedPath)) {
            return "Network share path is not a directory or cannot be listed by the file source credentials: "
                    + normalizedPath;
        }
        return "Local path is not a directory: " + normalizedPath;
    }

    private static String stripOuterQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean hasMultiSeparatorPrefix(String value) {
        return separatorPrefixLength(value) >= 2;
    }

    private static int separatorPrefixLength(String value) {
        int count = 0;
        while (count < value.length()) {
            char ch = value.charAt(count);
            if (ch != '\\' && ch != '/') {
                break;
            }
            count++;
        }
        return count;
    }

    private static boolean isExtendedWindowsPath(String value) {
        String normalized = value.replace('/', '\\').toLowerCase(Locale.ROOT);
        return normalized.startsWith("\\\\?\\") || normalized.startsWith("\\\\.\\");
    }
}
