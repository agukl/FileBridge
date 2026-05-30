package com.acme.ftpsync.license;

import com.acme.ftpsync.config.AgentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class LicenseService {
    public static final String PRODUCT_ID = "filebridge";
    private static final boolean LICENSE_REQUIRED = true;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceIdService deviceIdService;
    private final Path licensePath;

    public LicenseService(AgentConfig config) {
        this.deviceIdService = new DeviceIdService(config);
        Path configParent = config.sourceFile() == null || config.sourceFile().getParent() == null
                ? Path.of("config").toAbsolutePath().normalize()
                : config.sourceFile().getParent().toAbsolutePath().normalize();
        this.licensePath = configParent.resolve("license").resolve("license.json").normalize();
    }

    public String deviceId() {
        return deviceIdService.deviceId();
    }

    public LicenseStatus status() {
        String deviceId = deviceId();
        if (!Files.exists(licensePath)) {
            return emptyStatus(LicenseState.MISSING, "尚未导入许可证。", deviceId);
        }
        try {
            return verify(Files.readString(licensePath, StandardCharsets.UTF_8), deviceId);
        } catch (Exception ex) {
            return emptyStatus(LicenseState.INVALID, "许可证无效：" + ex.getMessage(), deviceId);
        }
    }

    public LicenseStatus importLicense(String licenseText) throws Exception {
        String normalized = normalizeLicenseText(licenseText);
        LicenseStatus verified = verify(normalized, deviceId());
        if (!verified.valid()) {
            throw new LicenseException(verified.message());
        }
        if (licensePath.getParent() != null) {
            Files.createDirectories(licensePath.getParent());
        }
        Files.writeString(licensePath, normalized + System.lineSeparator(), StandardCharsets.UTF_8);
        return verified;
    }

    public void requireFeature(LicenseFeature feature) {
        LicenseStatus current = status();
        if (!LICENSE_REQUIRED) {
            return;
        }
        if (!current.valid()) {
            throw new LicenseException(current.message());
        }
        if (!current.features().contains(feature.name())) {
            throw new LicenseException("当前许可证未包含功能：" + feature.name());
        }
    }

    public void requireFileSourceLimit(int nextCount) {
        LicenseStatus current = status();
        if (!LICENSE_REQUIRED || !current.valid()) {
            if (LICENSE_REQUIRED) {
                throw new LicenseException(current.message());
            }
            return;
        }
        int maxFileSources = current.limits().maxFileSources();
        if (maxFileSources > 0 && nextCount > maxFileSources) {
            throw new LicenseException("当前许可证最多允许 " + maxFileSources + " 个文件源。");
        }
    }

    private LicenseStatus verify(String licenseText, String deviceId) throws Exception {
        LicenseEnvelope envelope = mapper.readValue(normalizeLicenseText(licenseText), LicenseEnvelope.class);
        if (blank(envelope.keyId()) || blank(envelope.payload()) || blank(envelope.signature())) {
            return emptyStatus(LicenseState.INVALID, "许可证字段不完整。", deviceId);
        }
        String publicKeyBase64 = LicenseKeys.ED25519_PUBLIC_KEYS.get(envelope.keyId().trim());
        if (blank(publicKeyBase64)) {
            return emptyStatus(LicenseState.INVALID, "未知许可证公钥：" + envelope.keyId(), deviceId);
        }
        if (!verifySignature(envelope.payload(), envelope.signature(), publicKeyBase64)) {
            return emptyStatus(LicenseState.INVALID, "许可证签名校验失败。", deviceId);
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(envelope.payload());
        LicensePayload payload = mapper.readValue(payloadBytes, LicensePayload.class);
        return statusFromPayload(payload, deviceId);
    }

    private LicenseStatus statusFromPayload(LicensePayload payload, String deviceId) {
        if (payload.schema() != 1) {
            return emptyStatus(LicenseState.INVALID, "不支持的许可证版本。", deviceId);
        }
        if (!PRODUCT_ID.equals(payload.product())) {
            return emptyStatus(LicenseState.INVALID, "许可证产品不匹配。", deviceId);
        }
        if (!deviceId.equals(payload.deviceId())) {
            return emptyStatus(LicenseState.INVALID, "许可证设备 ID 不匹配。", deviceId);
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(payload.expiresAt());
        } catch (Exception ex) {
            return emptyStatus(LicenseState.INVALID, "许可证到期时间格式无效。", deviceId);
        }

        Instant now = Instant.now();
        int graceDays = Math.max(0, payload.graceDays());
        Instant graceUntil = expiresAt.plus(graceDays, ChronoUnit.DAYS);
        LicenseState state;
        boolean valid;
        String message;
        if (!now.isAfter(expiresAt)) {
            state = LicenseState.ACTIVE;
            valid = true;
            message = "许可证有效。";
        } else if (!now.isAfter(graceUntil)) {
            state = LicenseState.GRACE;
            valid = true;
            message = "许可证已到期，当前处于宽限期。";
        } else {
            state = LicenseState.EXPIRED;
            valid = false;
            message = "许可证已过期。";
        }

        return new LicenseStatus(
                LICENSE_REQUIRED,
                valid,
                state,
                message,
                deviceId,
                value(payload.licenseId()),
                value(payload.customer()),
                value(payload.edition()),
                value(payload.issuedAt()),
                value(payload.expiresAt()),
                graceDays,
                normalizeFeatures(payload.features()),
                payload.limits() == null ? LicenseLimit.empty() : payload.limits(),
                licensePath.toString()
        );
    }

    private LicenseStatus emptyStatus(LicenseState state, String message, String deviceId) {
        return new LicenseStatus(
                LICENSE_REQUIRED,
                false,
                state,
                message,
                deviceId,
                "",
                "",
                "",
                "",
                "",
                0,
                List.of(),
                LicenseLimit.empty(),
                licensePath.toString()
        );
    }

    private static boolean verifySignature(String payload, String signature, String publicKeyBase64) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getUrlDecoder().decode(signature));
    }

    private static List<String> normalizeFeatures(List<String> features) {
        Set<String> normalized = new TreeSet<>();
        for (String feature : features == null ? List.<String>of() : features) {
            if (!blank(feature)) {
                normalized.add(feature.trim().toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(normalized);
    }

    private static String normalizeLicenseText(String value) {
        if (value == null || value.isBlank()) {
            throw new LicenseException("许可证内容为空。");
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
