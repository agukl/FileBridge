package com.acme.ftpsync.files;

import com.acme.ftpsync.diagnostic.FtpPreflightService;
import com.acme.ftpsync.task.FileSourcePermission;
import com.acme.ftpsync.task.SyncTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileSourcePermissionService {
    private final FtpPreflightService ftpPreflightService;

    public FileSourcePermissionService(FtpPreflightService ftpPreflightService) {
        this.ftpPreflightService = ftpPreflightService;
    }

    public FileSourcePermission check(SyncTask source) {
        try {
            if (isFileSystemSource(source)) {
                return checkFileSystem(source);
            }
            return ftpPreflightService.checkRemotePermissions(source);
        } catch (Exception ex) {
            return FileSourcePermission.failed(safeMessage(ex));
        }
    }

    private FileSourcePermission checkFileSystem(SyncTask source) throws IOException {
        String rawPath = source.sourcePath();
        if (rawPath == null || rawPath.isBlank()) {
            return FileSourcePermission.failed("Local file source path is empty.");
        }

        try (SmbCredentialSession ignored = SmbCredentialSession.open(source)) {
            Path root;
            try {
                root = LocalPathSupport.toAbsolutePath(rawPath);
            } catch (RuntimeException ex) {
                return FileSourcePermission.failed("Invalid local path: " + safeMessage(ex));
            }

            List<String> messages = new ArrayList<>();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return FileSourcePermission.failed(LocalPathSupport.missingPathMessage(rawPath, root));
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return FileSourcePermission.failed(LocalPathSupport.notDirectoryMessage(rawPath, root));
            }

            boolean canRead = Files.isReadable(root);
            if (canRead) {
                canRead = canList(root, messages);
            } else {
                messages.add("read=failed: local directory is not readable");
            }
            boolean canWrite = Files.isWritable(root);
            if (canWrite) {
                canWrite = canCreateTempFile(root, messages);
            } else {
                messages.add("write=failed: local directory is not writable");
            }
            if (canRead) {
                messages.add("read=ok");
            }
            if (canWrite) {
                messages.add("write=ok");
            }
            return FileSourcePermission.from(canRead, canWrite, String.join("; ", messages));
        }
    }

    private static boolean canList(Path root, List<String> messages) {
        try (var stream = Files.list(root)) {
            stream.limit(1).count();
            return true;
        } catch (IOException | SecurityException ex) {
            messages.add("read=failed: " + safeMessage(ex));
            return false;
        }
    }

    private static boolean canCreateTempFile(Path root, List<String> messages) {
        Path probe = null;
        try {
            probe = Files.createTempFile(root, ".fm-permission-", ".tmp");
            return true;
        } catch (IOException | SecurityException ex) {
            messages.add("write=failed: " + safeMessage(ex));
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException | SecurityException ex) {
                    messages.add("cleanup=failed: " + safeMessage(ex));
                }
            }
        }
    }

    private static boolean isFileSystemSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "REMOTE_FTP" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "LOCAL".equals(sourceType) || "SMB".equals(sourceType);
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }
}
