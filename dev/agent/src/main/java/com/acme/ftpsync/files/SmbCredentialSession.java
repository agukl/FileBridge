package com.acme.ftpsync.files;

import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.util.PasswordRefs;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Mpr;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.Winnetwk;
import com.sun.jna.platform.win32.Winnetwk.NETRESOURCE;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

final class SmbCredentialSession implements AutoCloseable {
    private static final int ERROR_SESSION_CREDENTIAL_CONFLICT = 1219;
    private static final int ERROR_NO_SUCH_LOGON_SESSION = 1312;
    private static final int ERROR_LOGON_FAILURE = 1326;
    private static final int RESOURCETYPE_DISK = 1;

    private final String shareRoot;
    private final boolean connected;

    private SmbCredentialSession(String shareRoot, boolean connected) {
        this.shareRoot = shareRoot;
        this.connected = connected;
    }

    static SmbCredentialSession open(SyncTask source) throws IOException {
        if (!isSmbSource(source)) {
            return new SmbCredentialSession("", false);
        }
        if (!Platform.isWindows()) {
            throw new IOException("SMB file sources are only supported on Windows.");
        }
        String shareRoot = shareRoot(source.sourcePath());
        String username = value(source.ftpUsername(), "");
        String password = PasswordRefs.resolve(source.passwordRef());
        if (!username.isBlank() && password.isBlank()) {
            throw new IOException("SMB password is empty for " + shareRoot
                    + ". Check the password reference. If it uses env:, define the variable for the Windows service "
                    + "environment, or use plain:password for this file source.");
        }
        int rc = connect(shareRoot, username, password);
        if (rc == WinError.NO_ERROR) {
            return new SmbCredentialSession(shareRoot, true);
        }
        if (rc == ERROR_SESSION_CREDENTIAL_CONFLICT) {
            throw new IOException("SMB connection already exists for " + shareRoot
                    + " with different credentials. Disconnect the existing Windows SMB session and retry.");
        }
        String qualifiedUsername = hostQualifiedUsername(shareRoot, username);
        if (!qualifiedUsername.equals(username)) {
            int retryRc = connect(shareRoot, qualifiedUsername, password);
            if (retryRc == WinError.NO_ERROR) {
                return new SmbCredentialSession(shareRoot, true);
            }
            throw connectionFailed(shareRoot, username, rc, qualifiedUsername, retryRc, source.passwordRef());
        }
        throw connectionFailed(shareRoot, username, rc, "", -1, source.passwordRef());
    }

    @Override
    public void close() {
        if (connected && !shareRoot.isBlank()) {
            Mpr.INSTANCE.WNetCancelConnection2(shareRoot, 0, true);
        }
    }

    static boolean isSmbSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "SMB".equals(sourceType);
    }

    static String shareRoot(String rawPath) {
        String normalized = LocalPathSupport.normalizeInputPath(rawPath);
        if (!normalized.startsWith("\\\\")) {
            throw new IllegalArgumentException("SMB path must be a UNC path like \\\\server\\share.");
        }
        String withoutPrefix = normalized.substring(2);
        int first = withoutPrefix.indexOf('\\');
        if (first <= 0) {
            throw new IllegalArgumentException("SMB path must include a server and share name: " + normalized);
        }
        int second = withoutPrefix.indexOf('\\', first + 1);
        String serverAndShare = second < 0 ? withoutPrefix : withoutPrefix.substring(0, second);
        return "\\\\" + serverAndShare;
    }

    static String hostFromUnc(String rawPath) {
        String shareRoot = shareRoot(rawPath);
        String withoutPrefix = shareRoot.substring(2);
        int index = withoutPrefix.indexOf('\\');
        return index < 0 ? withoutPrefix : withoutPrefix.substring(0, index);
    }

    static Path rootPath(SyncTask source) {
        return LocalPathSupport.toAbsolutePath(source.sourcePath());
    }

    private static int connect(String shareRoot, String username, String password) {
        NETRESOURCE resource = new NETRESOURCE();
        resource.dwType = RESOURCETYPE_DISK;
        resource.lpRemoteName = shareRoot;
        return Mpr.INSTANCE.WNetAddConnection3(
                null,
                resource,
                password.isBlank() ? null : password,
                username.isBlank() ? null : username,
                0
        );
    }

    private static IOException connectionFailed(String shareRoot,
                                                String username,
                                                int rc,
                                                String retryUsername,
                                                int retryRc,
                                                String passwordRef) {
        int finalRc = retryRc > 0 ? retryRc : rc;
        StringBuilder message = new StringBuilder("SMB connection failed for ")
                .append(shareRoot)
                .append(": ")
                .append(new Win32Exception(finalRc).getMessage());
        if (retryRc > 0 && !retryUsername.isBlank()) {
            message.append(" Tried usernames: ")
                    .append(username)
                    .append(", ")
                    .append(retryUsername)
                    .append(".");
        }
        if (isCredentialError(rc) || isCredentialError(retryRc)) {
            message.append(" Check the SMB username and password. For a Windows local account on the remote machine, ")
                    .append("use ")
                    .append(hostFromUnc(shareRoot))
                    .append("\\")
                    .append(lastUsernameSegment(username))
                    .append(". If the password uses env:, make sure it is a machine-level/service environment variable; ")
                    .append("current reference: ")
                    .append(passwordRefDescription(passwordRef))
                    .append(".");
        }
        return new IOException(message.toString());
    }

    private static boolean isCredentialError(int rc) {
        return rc == ERROR_NO_SUCH_LOGON_SESSION || rc == ERROR_LOGON_FAILURE;
    }

    private static String hostQualifiedUsername(String shareRoot, String username) {
        String value = value(username, "");
        if (value.isBlank() || value.contains("@")) {
            return value;
        }
        String host = hostFromUnc(shareRoot);
        if (value.startsWith(".\\") || value.startsWith("./")) {
            return host + "\\" + value.substring(2);
        }
        if (value.contains("\\") || value.contains("/")) {
            return value;
        }
        return host + "\\" + value;
    }

    private static String lastUsernameSegment(String username) {
        String value = value(username, "");
        int slash = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String passwordRefDescription(String passwordRef) {
        String value = value(passwordRef, "");
        if (value.isBlank()) {
            return "<empty>";
        }
        int separator = value.indexOf(':');
        return separator > 0 ? value.substring(0, separator + 1) + "***" : "***";
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
