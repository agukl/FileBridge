package com.acme.ftpsync.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LicenseAdminTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LicenseAdminTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return;
        }
        switch (args[0]) {
            case "generate-keypair" -> generateKeyPair();
            case "sign" -> sign(Arrays.copyOfRange(args, 1, args.length));
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        Map<String, String> output = new LinkedHashMap<>();
        output.put("keyId", LicenseKeys.DEFAULT_KEY_ID);
        output.put("publicKeySpkiBase64", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        output.put("privateKeyPkcs8Base64", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }

    private static void sign(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String privateKey = required(options, "private-key");
        String deviceId = required(options, "device-id");
        String licenseId = required(options, "license-id");
        String customer = required(options, "customer");
        String expiresAt = required(options, "expires-at");
        String keyId = options.getOrDefault("key-id", LicenseKeys.DEFAULT_KEY_ID);
        String edition = options.getOrDefault("edition", "professional");
        String issuedAt = options.getOrDefault("issued-at", Instant.now().toString());
        int graceDays = intOption(options, "grace-days", 7);
        List<String> features = List.of(options.getOrDefault(
                "features",
                "FILE_SOURCE_MANAGE,REMOTE_FTP,FILE_COPY,DIRECTORY_CACHE"
        ).split(","));
        LicenseLimit limits = new LicenseLimit(
                intOption(options, "max-file-sources", 20),
                intOption(options, "max-concurrent-operations", 2)
        );
        LicensePayload payload = new LicensePayload(
                1,
                LicenseService.PRODUCT_ID,
                licenseId,
                customer,
                edition,
                deviceId,
                issuedAt,
                expiresAt,
                features.stream().map(String::trim).filter(item -> !item.isBlank()).toList(),
                limits,
                graceDays
        );
        byte[] payloadJson = MAPPER.writeValueAsBytes(payload);
        String payloadText = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson);
        String signature = signPayload(payloadText, loadPrivateKey(privateKey));
        LicenseEnvelope envelope = new LicenseEnvelope(keyId, payloadText, signature);
        String licenseText = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        String output = options.get("out");
        if (output == null || output.isBlank()) {
            System.out.println(licenseText);
        } else {
            Files.writeString(Path.of(output), licenseText + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    private static PrivateKey loadPrivateKey(String value) throws Exception {
        String raw = Files.exists(Path.of(value))
                ? Files.readString(Path.of(value), StandardCharsets.UTF_8).trim()
                : value.trim();
        byte[] keyBytes = Base64.getDecoder().decode(raw);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static String signPayload(String payload, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("Expected option name, got: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            result.put(key.substring(2), args[++i]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value.trim();
    }

    private static int intOption(Map<String, String> options, String key, int fallback) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private static void printHelp() {
        System.out.println("""
                Usage:
                  generate-keypair
                  sign --private-key <pkcs8-base64-or-file> --device-id <id> --license-id <id> --customer <name> --expires-at <iso-instant>

                Optional sign arguments:
                  --key-id prod-2026-01
                  --edition professional
                  --issued-at 2026-05-25T00:00:00Z
                  --features FILE_SOURCE_MANAGE,REMOTE_FTP,FILE_COPY,DIRECTORY_CACHE
                  --max-file-sources 20
                  --max-concurrent-operations 2
                  --grace-days 7
                  --out license.json
                """);
    }
}
