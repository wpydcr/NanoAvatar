package com.avatar.nputest;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;

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
                if (name.equals("avatar")) unpackFaces(source, asset, temporary);
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
            if (asset.equals("bundled/avatar") && name.startsWith("faces_rgb") && name.endsWith(".png")) continue;
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
                throw new IOException("Invalid bundled asset name");
            String child = asset + "/" + name;
            Path file = directory.resolve(name);
            if (source.list(child).length > 0) {
                Files.createDirectory(file);
                copy(source, child, file);
            } else {
                boolean compressed = name.endsWith(".xz");
                Path restored = compressed ? directory.resolve(name.substring(0, name.length() - 3)) : file;
                try (InputStream raw = source.open(child);
                     InputStream input = compressed ? new org.tukaani.xz.XZInputStream(raw, 65536) : raw;
                     OutputStream output = Files.newOutputStream(restored, StandardOpenOption.CREATE_NEW)) {
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
    /** Each PNG row pairs display RGB on the left and reference RGB on the right. */
    private static void unpackFaces(Source source, String asset, Path directory) throws IOException, InterruptedException {
        String[] names = source.list(asset); Arrays.sort(names);
        List<String> packed = new ArrayList<>();
        for (String name : names) if (name.startsWith("faces_rgb") && name.endsWith(".png")) packed.add(name);
        if (packed.isEmpty()) return;
        final int frames;
        try { frames = new JSONObject(new String(Files.readAllBytes(directory.resolve("avatar.json")), java.nio.charset.StandardCharsets.UTF_8)).getInt("frame_count"); }
        catch (Exception error) { throw new IOException("Invalid packed avatar frame count", error); }
        if (frames <= 0) throw new IOException("Invalid packed avatar frame count");
        Pattern chunk = Pattern.compile("faces_rgb_([0-9]{4})_([0-9]{4})\\.png");
        int next = 0;
        try (OutputStream aligned = new BufferedOutputStream(Files.newOutputStream(directory.resolve("aligned_faces_rgb.bin"), StandardOpenOption.CREATE_NEW));
             OutputStream reference = new BufferedOutputStream(Files.newOutputStream(directory.resolve("reference_faces_rgb.bin"), StandardOpenOption.CREATE_NEW))) {
            for (String name : packed) {
                interrupted();
                int count;
                if (name.equals("faces_rgb.png") && packed.size() == 1) count = frames;
                else {
                    Matcher match = chunk.matcher(name);
                    if (!match.matches()) throw new IOException("Invalid packed face filename");
                    int first = Integer.parseInt(match.group(1)), last = Integer.parseInt(match.group(2));
                    count = last - first + 1;
                    if (first != next || count <= 0 || count > 64 || last >= frames)
                        throw new IOException("Packed face ranges are not continuous");
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream input = source.open(asset + "/" + name)) { BitmapFactory.decodeStream(input, null, bounds); }
                if (bounds.outWidth != 512 || bounds.outHeight != count * 256L)
                    throw new IOException("Invalid packed face dimensions");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap;
                try (InputStream input = source.open(asset + "/" + name)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
                if (bitmap == null) throw new IOException("Invalid packed face image");
                try {
                    if (bitmap.getWidth() != 512 || bitmap.getHeight() != count * 256L)
                        throw new IOException("Decoded face dimensions changed");
                    int[] pixels = new int[512]; byte[] left = new byte[768], right = new byte[768];
                    for (int y = 0; y < bitmap.getHeight(); y++) {
                        interrupted(); bitmap.getPixels(pixels, 0, 512, 0, y, 512, 1);
                        for (int x = 0; x < 256; x++) {
                            int a = pixels[x], r = pixels[x + 256], p = x * 3;
                            left[p] = (byte)(a >> 16); left[p+1] = (byte)(a >> 8); left[p+2] = (byte)a;
                            right[p] = (byte)(r >> 16); right[p+1] = (byte)(r >> 8); right[p+2] = (byte)r;
                        }
                        aligned.write(left); reference.write(right);
                    }
                } finally { bitmap.recycle(); }
                next += count;
            }
            if (next != frames) throw new IOException("Packed avatar is missing frames");
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
