package com.acme.ftpsync.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DateTimes {
    private static final DateTimeFormatter DATABASE_DATETIME_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DATABASE_DATETIME_FLEX = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private DateTimes() {
    }

    public static String nowDatabase() {
        return LocalDateTime.now().format(DATABASE_DATETIME_MS);
    }

    public static String formatDatabase(LocalDateTime value) {
        return value == null ? "" : value.format(DATABASE_DATETIME_MS);
    }

    public static LocalDateTime parseDatabase(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim().replace('T', ' '), DATABASE_DATETIME_FLEX);
    }
}

