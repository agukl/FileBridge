package com.acme.ftpsync.util;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern INLINE_PASSWORD =
            Pattern.compile("(?i)\\bplain:([^\\s,;\\]})]+)");
    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile("(?i)\\b(password|passwordRef|pwd|token|secret|authorization)\\s*[:=]\\s*([^\\s,;\\]})]+)");
    private static final Pattern JSON_SECRET =
            Pattern.compile("(?i)(\"(?:password|passwordRef|pwd|token|secret|authorization)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");

    private SecretRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String redacted = INLINE_PASSWORD.matcher(value).replaceAll("plain:***");
        redacted = JSON_SECRET.matcher(redacted).replaceAll("$1***$3");
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1=***");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer ***");
        return redacted;
    }
}
