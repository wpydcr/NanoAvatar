package com.avatar.nputest;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** Installs ordinary APK assets once; existing user directories are never replaced. */
final class BundledAssets {
    interface Source {
        String[] list(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }
    static final class MissingBundleException extends IOException {
        MissingBundleException() { super("This build has no bundled avatar or models"); }
    }
    // ponytail: one app process owns preparation; serialize Activity recreation during a copy.
    static synchronized void ensure(Source source, File root) throws IOException, InterruptedException {
        if (root == null) throw new IOException("External app storage is unavailable");
        Files.createDirectories(root.toPath());
        for (String name : new String[]{"payload", "avatar"}) {
            interrupted();
            File target = new File(root, name);
            Path temporary = new File(root, ".bundled-" + name).toPath();
            remove(temporary);
            if (target.exists()) {
                if (!target.isDirectory()) throw new IOException("App data path is not a directory");
                continue;
            }
            String asset = "bundled/" + name;
            String required = name.equals("payload") ? "phone_config.json" : "avatar.json";
            if (!Arrays.asList(source.list(asset)).contains(required)) throw new MissingBundleException();
            try {
                Files.createDirectory(temporary);
                copy(source, asset, temporary);
                interrupted();
                // Same-parent rename publishes only the fully copied directory, without replacement.
                try { Files.move(temporary, target.toPath()); }
                catch (FileAlreadyExistsException existing) {
                    if (!target.isDirectory()) throw existing;
                }
            } finally { remove(temporary); }
        }
    }
    private static void copy(Source source, String asset, Path directory) throws IOException, InterruptedException {
        for (String name : source.list(asset)) {
            interrupted();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
                throw new IOException("Invalid bundled asset name");
            String child = asset + "/" + name;
            Path file = directory.resolve(name);
            if (source.list(child).length > 0) {
                Files.createDirectory(file);
                copy(source, child, file);
            } else {
                try (InputStream input = source.open(child); OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[128 * 1024];
                    while (true) {
                        interrupted();
                        int count = input.read(buffer);
                        if (count < 0) break;
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Asset preparation cancelled");
    }
    private static void remove(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
