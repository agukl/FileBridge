package com.acme.ftpsync.license;

import com.acme.ftpsync.config.AgentConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class DeviceIdService {
    private static final String PRODUCT_SALT = "ftp-sync-agent-license-device-v1";

    public DeviceIdService(AgentConfig config) {
    }

    public String deviceId() {
        try {
            List<String> signals = new ArrayList<>();
            String machineGuid = windowsMachineGuid();
            if (!blank(machineGuid)) {
                signals.add("machineGuid=" + machineGuid);
            } else {
                signals.add("computer=" + env("COMPUTERNAME", env("HOSTNAME", "")));
                signals.add("os=" + System.getProperty("os.name", ""));
                signals.add("arch=" + System.getProperty("os.arch", ""));
            }
            String canonical = String.join("|", signals);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((PRODUCT_SALT + "|" + canonical).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash).toUpperCase(Locale.ROOT).substring(0, 24);
            return "FSA1-" + hex.substring(0, 4)
                    + "-" + hex.substring(4, 8)
                    + "-" + hex.substring(8, 12)
                    + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20)
                    + "-" + hex.substring(20, 24);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate device id: " + ex.getMessage(), ex);
        }
    }

    private static String windowsMachineGuid() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return "";
        }
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "reg",
                    "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                    "/v",
                    "MachineGuid"
            ).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("MachineGuid")) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\s+");
                    return parts.length == 0 ? "" : parts[parts.length - 1];
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
        return "";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
