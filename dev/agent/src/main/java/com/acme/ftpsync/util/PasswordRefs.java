package com.acme.ftpsync.util;

public final class PasswordRefs {
    private PasswordRefs() {
    }

    public static String resolve(String passwordRef) {
        if (passwordRef == null) {
            return "";
        }
        String value = passwordRef.trim();
        if (value.startsWith("env:")) {
            String envValue = System.getenv(value.substring("env:".length()));
            return envValue == null ? "" : envValue;
        }
        if (value.startsWith("plain:")) {
            return value.substring("plain:".length());
        }
        return value;
    }
}
