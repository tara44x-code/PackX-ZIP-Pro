package com.packx.pro;

import android.webkit.MimeTypeMap;

import com.packx.pro.ui.Ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** File helpers: type detection, formatting and tree operations. */
public final class FileKit {
    public static final int FOLDER = 0, ARCHIVE = 1, IMAGE = 2, VIDEO = 3, AUDIO = 4, DOC = 5, APP = 6, OTHER = 7;

    public interface ByteProgress { void onBytes(long done, String name); }

    private static final Set<String> ARCHIVES = set("7z", "zip", "rar", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz",
            "txz", "zst", "tzst", "z", "taz", "lzma", "cab", "iso", "arj", "lzh", "lha", "cpio", "rpm", "deb", "wim",
            "jar", "xpi", "zipx", "001", "r00", "img", "dmg", "squashfs", "chm", "msi", "vhd", "vhdx", "vmdk", "udf");
    private static final Set<String> IMAGES = set("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "ico", "tif", "tiff");
    private static final Set<String> VIDEOS = set("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "ts", "m4v", "mpg", "mpeg");
    private static final Set<String> AUDIOS = set("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma", "amr", "mid");
    private static final Set<String> DOCS = set("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "csv",
            "json", "xml", "html", "htm", "md", "log", "rtf", "epub", "srt", "ini", "conf", "java", "kt", "py", "js", "css", "c", "cpp", "h", "sh");
    private static final Set<String> APPS = set("apk", "apks", "xapk", "apkm", "aab", "exe", "dll", "so", "dex", "bin");
    private static final Set<String> THUMBNAILS = set("jpg", "jpeg", "png", "webp", "gif", "bmp");

    private FileKit() { }

    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static int kind(String name, boolean directory) {
        if (directory) return FOLDER;
        String ext = extension(name);
        if (ARCHIVES.contains(ext)) return ARCHIVE;
        if (IMAGES.contains(ext)) return IMAGE;
        if (VIDEOS.contains(ext)) return VIDEO;
        if (AUDIOS.contains(ext)) return AUDIO;
        if (DOCS.contains(ext)) return DOC;
        if (APPS.contains(ext)) return APP;
        return OTHER;
    }

    public static boolean isArchive(String name) { return ARCHIVES.contains(extension(name)); }

    public static boolean hasThumbnail(String name) { return THUMBNAILS.contains(extension(name)); }

    public static int icon(int kind) {
        switch (kind) {
            case FOLDER: return R.drawable.ic_folder;
            case ARCHIVE: return R.drawable.ic_archive;
            case IMAGE: return R.drawable.ic_image;
            case VIDEO: return R.drawable.ic_video;
            case AUDIO: return R.drawable.ic_audio;
            case DOC: return R.drawable.ic_doc;
            case APP: return R.drawable.ic_app;
            default: return R.drawable.ic_file;
        }
    }

    public static int color(int kind) {
        switch (kind) {
            case FOLDER: return Ui.warn;
            case ARCHIVE: return Ui.violet;
            case IMAGE: return Ui.success;
            case VIDEO: return Ui.danger;
            case AUDIO: return Ui.dark ? 0xFFFB923C : 0xFFEA580C;
            case DOC: return Ui.accent;
            case APP: return Ui.accent2;
            default: return Ui.textDim;
        }
    }

    public static String mime(String name) {
        String ext = extension(name);
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type == null ? "*/*" : type;
    }

    /** "report.tar.gz" → "report". */
    public static String stripArchiveExtension(String name) {
        String stripped = name.replaceFirst("(?i)\\.(7z|zip|rar|tar|iso|cab)\\.\\d{3}$", "");
        stripped = stripped.replaceFirst("(?i)\\.part\\d+\\.rar$", "");
        stripped = stripped.replaceFirst("(?i)\\.tar\\.[a-z0-9]{1,4}$", "");
        if (stripped.equals(name)) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) stripped = name.substring(0, dot);
        }
        return stripped.isEmpty() ? name : stripped;
    }

    /** Human-readable size, wrapped so "12 MB" keeps its order inside Arabic sentences. */
    public static String size(long bytes) { return "\u202A" + plainSize(bytes) + "\u202C"; }

    private static String plainSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do { value /= 1024.0; unit++; } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    public static String date(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(new Date(millis));
    }

    /** A name that does not exist yet in the folder: "a.zip", "a (2).zip", … */
    public static File unique(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        String base = name, ext = "";
        java.util.regex.Matcher tar = java.util.regex.Pattern.compile("(?i)^(.+?)(\\.tar\\.[a-z0-9]{1,4})$").matcher(name);
        if (tar.matches()) { base = tar.group(1); ext = tar.group(2); }
        else {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && !new File(directory, name).isDirectory()) { base = name.substring(0, dot); ext = name.substring(dot); }
        }
        for (int i = 2; ; i++) {
            candidate = new File(directory, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    /** @return {bytes, files} below the given file or folder. */
    public static long[] measure(File file, AtomicBoolean cancelled) {
        long[] totals = new long[2];
        measure(file, totals, cancelled);
        return totals;
    }

    private static void measure(File file, long[] totals, AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children == null) {
            if (file.isFile()) { totals[0] += file.length(); totals[1]++; }
            return;
        }
        for (File child : children) measure(child, totals, cancelled);
    }

    public static void copyTree(File source, File target, AtomicBoolean cancelled, long[] done, ByteProgress progress) throws IOException {
        if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("تعذر إنشاء المجلد " + target.getName());
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(target, child.getName()), cancelled, done, progress);
            return;
        }
        byte[] buffer = new byte[256 * 1024];
        boolean complete = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
                out.write(buffer, 0, read);
                done[0] += read;
                if (progress != null) progress.onBytes(done[0], source.getName());
            }
            complete = true;
        } finally {
            if (!complete) target.delete();
        }
        target.setLastModified(source.lastModified());
    }

    /** @return true when everything was removed. */
    public static boolean deleteTree(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        boolean ok = true;
        if (children != null) for (File child : children) ok &= deleteTree(child);
        return file.delete() && ok;
    }

    public static boolean isInside(File parent, File child) {
        try {
            String root = parent.getCanonicalPath();
            String path = child.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (IOException e) {
            return true;
        }
    }

    public static String hash(File file, String algorithm, AtomicBoolean cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}
