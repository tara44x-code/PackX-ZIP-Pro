package com.packx.pro.compression;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin, dependency-free driver for the bundled 7-Zip console engine.
 *
 * Everything PackX does with archives goes through here: listing, testing, extracting
 * (7z, zip, rar, iso, cab, tar.* …) and creating 7z / zip / tar / tar.gz / tar.bz2 / tar.xz.
 * The class has no Android imports so it can be exercised on a desktop JVM.
 */
public final class SevenZip {
    public static final int LEVEL_STORE = 0, LEVEL_FAST = 1, LEVEL_NORMAL = 2, LEVEL_MAX = 3, LEVEL_ULTRA = 4;
    public static final int OVERWRITE = 0, SKIP_EXISTING = 1, RENAME_NEW = 2;

    public interface Progress {
        /** @param percent 0..100, or -1 when unknown. @param current file being processed, may be null. */
        void update(int percent, String current);
    }

    public static final class Entry {
        public String path = "";
        public long size, packedSize, modified;
        public boolean directory, encrypted;
        public String method = "";
    }

    public static final class Listing {
        public final List<Entry> entries = new ArrayList<>();
        public String type = "";
        public String method = "";
        public boolean solid, encrypted, nestedTar;
        public int volumes = 1;
        /** Size of the tar stream inside a compressed tar, or -1. */
        public long nestedSize = -1;

        public int fileCount() { int n = 0; for (Entry e : entries) if (!e.directory) n++; return n; }
        public long totalSize() { long n = 0; for (Entry e : entries) if (!e.directory) n += e.size; return n; }
    }

    public static final class CompressSpec {
        /** Directory the names are relative to. */
        public File workDir;
        /** Files and folders (direct children of workDir) to add. */
        public List<String> names = new ArrayList<>();
        public File output;
        public ArchiveFormat format = ArchiveFormat.SEVEN_Z;
        public int level = LEVEL_NORMAL;
        public String password;
        public boolean hideNames;
        public boolean solid = true;
        /** 0 = single file. */
        public long volumeBytes;
        /** Device RAM and cores, used to pick a safe dictionary and thread count. */
        public long ramBytes;
        public int cores = 2;
        /** Total input size if known; drives progress for tar.* pipelines. */
        public long totalBytes = -1;
    }

    public static class EngineException extends IOException {
        public EngineException(String message) { super(message); }
    }

    public static final class PasswordException extends EngineException {
        public final boolean wrong;
        public PasswordException(boolean wrong) {
            super(wrong ? "كلمة المرور غير صحيحة" : "الأرشيف محمي بكلمة مرور");
            this.wrong = wrong;
        }
    }

    private static final Pattern PERCENT =
            Pattern.compile("^(\\d{1,3})%(?:\\s+\\d+)?(?:\\s+[+\\-=.UTDRE]\\s+(.*))?$");
    private static final Pattern SCAN = Pattern.compile("^\\d+[KMG]?\\s+Scan.*$");
    private static final Pattern TAR_NAME =
            Pattern.compile("(?i).*\\.(tar\\.[a-z0-9]+|tgz|tbz|tbz2|txz|tzst|taz|tpz)$");
    private static final Set<String> STREAM_TYPES = new HashSet<>();
    private static final Set<Process> ACTIVE = Collections.synchronizedSet(new HashSet<Process>());

    static {
        Collections.addAll(STREAM_TYPES, "gzip", "bzip2", "xz", "zstd", "z", "lzma", "lzma86");
    }

    private final File binary;
    private final File libraryDir;
    private final File tempDir;

    public SevenZip(File binary, File libraryDir, File tempDir) {
        this.binary = binary;
        this.libraryDir = libraryDir;
        this.tempDir = tempDir;
    }

    public boolean isAvailable() { return binary.isFile() && binary.canExecute(); }

    /** Stops every running engine process (used by the cancel button). */
    public static void cancelAll() {
        List<Process> copy;
        synchronized (ACTIVE) { copy = new ArrayList<>(ACTIVE); }
        for (Process p : copy) p.destroy();
    }

    // ------------------------------------------------------------------ listing

    public Listing list(File archive, String password, AtomicBoolean cancelled) throws IOException {
        Listing outer = listPlain(archive, password, cancelled);
        if (isCompressedTar(outer, archive.getName())) {
            long inner = outer.entries.isEmpty() ? -1 : outer.entries.get(0).size;
            Listing nested = listNested(archive, cancelled);
            nested.nestedTar = true;
            nested.nestedSize = inner > 0 ? inner : -1;
            nested.type = "tar." + outer.type;
            nested.method = outer.method;
            return nested;
        }
        String fallback = stripLastExtension(archive.getName());
        for (Entry e : outer.entries) {
            if (e.path.isEmpty()) e.path = fallback;
            if (e.encrypted) outer.encrypted = true;
        }
        return outer;
    }

    private Listing listPlain(File archive, String password, AtomicBoolean cancelled) throws IOException {
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "l", "-slt", "-sccUTF-8", "-bso1", "-bse1", "-bsp0");
        addPassword(args, password);
        args.add(archive.getAbsolutePath());
        Console console = new Console(null, new ListParser());
        int exit = run(args, null, cancelled, console);
        if (exit >= 2 || console.needPassword || (console.wrongPassword && console.parser.listing.entries.isEmpty()))
            throw failure("تعذر فتح الأرشيف", exit, console);
        return console.parser.listing;
    }

    private Listing listNested(File archive, AtomicBoolean cancelled) throws IOException {
        List<String> first = new ArrayList<>();
        Collections.addAll(first, "x", "-so", "-bsp0", "-bso0", "-bse2", archive.getAbsolutePath());
        List<String> second = new ArrayList<>();
        Collections.addAll(second, "l", "-slt", "-si", "-ttar", "-sccUTF-8", "-bso1", "-bse1", "-bsp0");
        Console console = new Console(null, new ListParser());
        pipeline(first, null, second, null, -1, cancelled, null, console, "تعذر قراءة محتويات الأرشيف");
        return console.parser.listing;
    }

    private static boolean isCompressedTar(Listing outer, String archiveName) {
        if (!STREAM_TYPES.contains(outer.type.toLowerCase(Locale.ROOT)) || outer.entries.size() != 1) return false;
        String inner = outer.entries.get(0).path.toLowerCase(Locale.ROOT);
        return inner.endsWith(".tar") || TAR_NAME.matcher(archiveName).matches();
    }

    private static boolean mayBeCompressedTar(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(gz|tgz|bz2|tbz|tbz2|xz|txz|zst|tzst|z|taz|tpz|lzma)$");
    }

    // ------------------------------------------------------------------ extract / test

    /**
     * @param listing  result of {@link #list}, or null to let the engine work it out.
     * @param selected entry paths (files or folders) to extract, or null for everything.
     */
    public void extract(File archive, Listing listing, File target, Collection<String> selected, String password,
                        int overwrite, AtomicBoolean cancelled, Progress progress) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) throw new EngineException("تعذر إنشاء مجلد الاستخراج");
        boolean nested = listing != null ? listing.nestedTar
                : mayBeCompressedTar(archive.getName()) && isCompressedTar(listPlain(archive, password, cancelled), archive.getName());
        long nestedSize = listing != null ? listing.nestedSize : -1;
        File listFile = null;
        try {
            List<String> out = new ArrayList<>();
            out.add("x");
            if (nested) Collections.addAll(out, "-si", "-ttar");
            else out.add(archive.getAbsolutePath());
            out.add("-o" + target.getAbsolutePath());
            out.add("-y");
            out.add(overwrite == SKIP_EXISTING ? "-aos" : overwrite == RENAME_NEW ? "-aou" : "-aoa");
            Collections.addAll(out, "-sccUTF-8", "-bso1", "-bse1", nested ? "-bsp0" : "-bsp1");
            if (!nested) addPassword(out, password);
            if (selected != null && !selected.isEmpty()) {
                listFile = writeList(selected);
                Collections.addAll(out, "-spd", "-scsUTF-8", "@" + listFile.getAbsolutePath());
            }
            if (nested) {
                List<String> first = new ArrayList<>();
                Collections.addAll(first, "x", "-so", "-bsp0", "-bso0", "-bse2", archive.getAbsolutePath());
                pipeline(first, null, out, null, nestedSize, cancelled, progress, new Console(null, null), "لم يكتمل فك الضغط");
            } else {
                Console console = new Console(progress, null);
                int exit = run(out, null, cancelled, console);
                // Storage that refuses permissions, timestamps or links is not a failed extraction.
                boolean onlyCosmetic = console.cosmetic > 0 && console.serious == 0;
                if ((exit >= 2 && !onlyCosmetic) || console.needPassword || console.wrongPassword)
                    throw failure("لم يكتمل فك الضغط", exit, console);
            }
        } finally {
            if (listFile != null) listFile.delete();
        }
    }

    /** Verifies every stream in the archive. Throws when anything is damaged. */
    public void test(File archive, String password, AtomicBoolean cancelled, Progress progress) throws IOException {
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "t", archive.getAbsolutePath(), "-sccUTF-8", "-bso1", "-bse1", "-bsp1");
        addPassword(args, password);
        Console console = new Console(progress, null);
        int exit = run(args, null, cancelled, console);
        if (exit != 0 || console.needPassword || console.wrongPassword)
            throw failure("الأرشيف لم يجتز فحص السلامة", exit, console);
    }

    // ------------------------------------------------------------------ compress

    /** Creates the archive and returns the files written (several when split into volumes). */
    public List<File> compress(CompressSpec spec, AtomicBoolean cancelled, Progress progress) throws IOException {
        if (spec.names.isEmpty()) throw new EngineException("لا توجد ملفات للضغط");
        if (spec.output.exists() || new File(spec.output.getPath() + ".001").exists())
            throw new EngineException("يوجد ملف بنفس اسم الأرشيف");
        File parent = spec.output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new EngineException("تعذر إنشاء مجلد الحفظ");
        ArchiveFormat format = spec.format;
        if (format == ArchiveFormat.ZIP && spec.password != null && !isAscii(spec.password))
            throw new EngineException("كلمة مرور ZIP يجب أن تكون بأحرف إنجليزية وأرقام ورموز فقط. استخدم 7Z لكلمة مرور عربية.");
        boolean volumes = spec.volumeBytes > 0 && format.supportsVolumes;
        File listFile = writeList(spec.names);
        boolean ok = false;
        try {
            List<String> pack = new ArrayList<>();
            pack.add("a");
            if (format.wrapper != null) Collections.addAll(pack, "packx", "-ttar", "-so", "-bsp0", "-bso0", "-bse2");
            else {
                pack.add("-t" + format.type);
                pack.add(spec.output.getAbsolutePath());
                methodArgs(pack, format.type, spec);
                if (format.supportsPassword && spec.password != null && !spec.password.isEmpty()) {
                    pack.add("-p" + spec.password);
                    if (format == ArchiveFormat.ZIP) pack.add("-mem=AES256");
                    else pack.add("-mhe=" + (spec.hideNames ? "on" : "off"));
                }
                if (volumes) pack.add("-v" + spec.volumeBytes + "b");
                Collections.addAll(pack, "-sccUTF-8", "-bso1", "-bse1", "-bsp1");
            }
            Collections.addAll(pack, "-spd", "-scsUTF-8", "@" + listFile.getAbsolutePath());

            if (format.wrapper != null) {
                List<String> wrap = new ArrayList<>();
                String tarName = stripLastExtension(spec.output.getName());
                Collections.addAll(wrap, "a", "-t" + format.wrapper, "-si" + tarName, spec.output.getAbsolutePath());
                methodArgs(wrap, format.wrapper, spec);
                Collections.addAll(wrap, "-sccUTF-8", "-bso1", "-bse1", "-bsp0");
                pipeline(pack, spec.workDir, wrap, null, spec.totalBytes, cancelled, progress,
                        new Console(null, null), "لم يكتمل الضغط");
            } else {
                Console console = new Console(progress, null);
                int exit = run(pack, spec.workDir, cancelled, console);
                if (exit >= 2) throw failure("لم يكتمل الضغط", exit, console);
            }
            List<File> written = outputsOf(spec.output);
            if (written.isEmpty()) throw new EngineException("لم يُنشأ الأرشيف");
            ok = true;
            return written;
        } finally {
            listFile.delete();
            if (!ok) for (File f : outputsOf(spec.output)) f.delete();
        }
    }

    /** The archive itself, or its numbered volumes. */
    public static List<File> outputsOf(File output) {
        List<File> files = new ArrayList<>();
        if (output.isFile()) files.add(output);
        for (int i = 1; i < 100000; i++) {
            File part = new File(output.getPath() + String.format(Locale.ROOT, ".%03d", i));
            if (!part.isFile()) break;
            files.add(part);
        }
        return files;
    }

    private static void methodArgs(List<String> args, String type, CompressSpec spec) {
        int level = Math.max(LEVEL_STORE, Math.min(LEVEL_ULTRA, spec.level));
        int cores = Math.max(1, spec.cores);
        int ultraDict = ultraDictionaryMb(spec.ramBytes);
        if ("7z".equals(type) || "xz".equals(type)) {
            if (level == LEVEL_STORE && "7z".equals(type)) { args.add("-mx=0"); return; }
            int mx = level <= LEVEL_FAST ? 1 : level == LEVEL_NORMAL ? 5 : level == LEVEL_MAX ? 7 : 9;
            int dict = level <= LEVEL_FAST ? 1 : level == LEVEL_NORMAL ? 16 : level == LEVEL_MAX ? Math.min(32, ultraDict) : ultraDict;
            args.add("-mx=" + mx);
            if (level >= LEVEL_MAX) args.add("-md=" + dict + "m");
            if (level == LEVEL_ULTRA) args.add("-mfb=273");
            if ("7z".equals(type)) {
                args.add("-ms=" + (spec.solid ? "on" : "off"));
                if (level >= LEVEL_MAX && spec.solid) args.add("-mqs=on");
            }
            args.add("-mmt=" + safeThreads(dict, spec.ramBytes, cores));
        } else if ("zip".equals(type) || "gzip".equals(type)) {
            int mx = level == LEVEL_STORE ? ("zip".equals(type) ? 0 : 1) : level == LEVEL_FAST ? 1
                    : level == LEVEL_NORMAL ? 5 : level == LEVEL_MAX ? 7 : 9;
            args.add("-mx=" + mx);
            if (level == LEVEL_ULTRA) { args.add("-mfb=258"); args.add("-mpass=15"); }
            if ("zip".equals(type)) { args.add("-mcu=on"); args.add("-mmt=" + cores); }
        } else if ("bzip2".equals(type)) {
            args.add("-mx=" + (level <= LEVEL_FAST ? 1 : level == LEVEL_NORMAL ? 5 : level == LEVEL_MAX ? 7 : 9));
            args.add("-mmt=" + cores);
        }
    }

    static int ultraDictionaryMb(long ramBytes) {
        long gb = 1024L * 1024 * 1024;
        if (ramBytes >= 7 * gb) return 128;
        if (ramBytes >= 7 * gb / 2) return 64;
        if (ramBytes <= 0 || ramBytes >= 5 * gb / 2) return 32;
        return 16;
    }

    /** LZMA2 needs roughly 11x the dictionary per thread pair plus block buffers; stay under ~40% of RAM. */
    static int safeThreads(int dictMb, long ramBytes, int cores) {
        if (ramBytes <= 0) return Math.min(cores, 2);
        long perPair = dictMb * 16L * 1024 * 1024;
        long pairs = Math.max(1, (long) (ramBytes * 0.4) / perPair);
        return (int) Math.max(1, Math.min(cores, pairs * 2));
    }

    // ------------------------------------------------------------------ process plumbing

    private ProcessBuilder builder(List<String> args, File dir) {
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (dir != null) builder.directory(dir);
        if (libraryDir != null) {
            // A process started this way does not inherit the app's linker namespace, so point
            // the dynamic linker at the bundled libc++_shared.so explicitly.
            String old = builder.environment().get("LD_LIBRARY_PATH");
            String lib = libraryDir.getAbsolutePath();
            builder.environment().put("LD_LIBRARY_PATH", old == null || old.isEmpty() ? lib : lib + ":" + old);
        }
        builder.environment().put("LC_ALL", "C.UTF-8");
        return builder;
    }

    private int run(List<String> args, File dir, AtomicBoolean cancelled, Console console) throws IOException {
        Process process = builder(args, dir).redirectErrorStream(true).start();
        ACTIVE.add(process);
        console.owner = process;
        Thread watchdog = watch(cancelled, process);
        try {
            process.getOutputStream().close();
            console.consume(process.getInputStream());
            int exit = waitFor(process);
            if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
            return exit;
        } finally {
            ACTIVE.remove(process);
            process.destroy();
            watchdog.interrupt();
        }
    }

    /** Runs {@code first | second}; the console reads the second process. */
    private void pipeline(List<String> firstArgs, File firstDir, List<String> secondArgs, File secondDir,
                          final long expectedBytes, AtomicBoolean cancelled, final Progress progress,
                          Console console, String action) throws IOException {
        File firstLog = File.createTempFile("packx", ".log", tempDir);
        Process first = null, second = null;
        Thread watchdog = null;
        try {
            first = builder(firstArgs, firstDir).redirectError(firstLog).start();
            ACTIVE.add(first);
            first.getOutputStream().close();
            second = builder(secondArgs, secondDir).redirectErrorStream(true).start();
            ACTIVE.add(second);
            watchdog = watch(cancelled, first, second);
            final Process source = first, sink = second;
            final AtomicLong moved = new AtomicLong();
            Thread pump = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buffer = new byte[256 * 1024];
                    int last = -2;
                    try (InputStream in = source.getInputStream(); OutputStream out = sink.getOutputStream()) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            long total = moved.addAndGet(read);
                            if (progress != null) {
                                int percent = expectedBytes > 0 ? (int) Math.min(99, total * 100 / expectedBytes) : -1;
                                if (percent != last || percent < 0) { last = percent; progress.update(percent, null); }
                            }
                        }
                    } catch (IOException broken) {
                        source.destroy();
                    }
                }
            }, "packx-pipe");
            pump.start();
            console.owner = second;
            console.consume(second.getInputStream());
            int secondExit = waitFor(second);
            join(pump);
            int firstExit = waitFor(first);
            if (cancelled != null && cancelled.get()) throw new CancellationException("تم إلغاء العملية");
            if (firstExit >= 2 || secondExit >= 2) {
                for (String line : readTail(firstLog)) console.note(line);
                throw failure(action, Math.max(firstExit, secondExit), console);
            }
            if (progress != null) progress.update(100, null);
        } finally {
            if (watchdog != null) watchdog.interrupt();
            if (first != null) { ACTIVE.remove(first); first.destroy(); }
            if (second != null) { ACTIVE.remove(second); second.destroy(); }
            firstLog.delete();
        }
    }

    private static Thread watch(final AtomicBoolean cancelled, final Process... processes) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        Thread.sleep(250);
                        if (cancelled != null && cancelled.get()) {
                            for (Process p : processes) p.destroy();
                            Thread.sleep(2000);
                            for (Process p : processes) p.destroyForcibly();
                            return;
                        }
                    }
                } catch (InterruptedException finished) { /* operation ended */ }
            }
        }, "packx-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static int waitFor(Process process) throws IOException {
        try {
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) { /* keep waiting; the watchdog handles cancel */ }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new CancellationException("تم إلغاء العملية");
        }
    }

    private static void join(Thread thread) {
        try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private File writeList(Collection<String> names) throws IOException {
        File file = File.createTempFile("packx", ".lst", tempDir);
        StringBuilder text = new StringBuilder();
        for (String name : names) text.append(name.replace('\n', ' ').replace('\r', ' ')).append('\n');
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static List<String> readTail(File log) {
        List<String> lines = new ArrayList<>();
        try {
            for (String line : new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).split("[\\r\\n\\x08]+")) {
                String clean = line.trim();
                if (!clean.isEmpty() && !PERCENT.matcher(clean).matches()) lines.add(clean);
            }
        } catch (IOException ignored) { /* diagnostics only */ }
        return lines.size() > 8 ? lines.subList(lines.size() - 8, lines.size()) : lines;
    }

    public static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c < 0x20 || c > 0x7e) return false; }
        return true;
    }

    private static void addPassword(List<String> args, String password) {
        if (password != null && !password.isEmpty()) args.add("-p" + password);
    }

    private static String stripLastExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tgz") || lower.endsWith(".tbz") || lower.endsWith(".tbz2") || lower.endsWith(".txz")
                || lower.endsWith(".tzst") || lower.endsWith(".taz") || lower.endsWith(".tpz"))
            return name.substring(0, name.lastIndexOf('.')) + ".tar";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static EngineException failure(String action, int exit, Console console) {
        if (console.needPassword) return new PasswordException(false);
        if (console.wrongPassword) return new PasswordException(true);
        String all = console.errorText();
        String lower = all.toLowerCase(Locale.ROOT);
        String reason;
        if (exit == 8 || lower.contains("allocate") || lower.contains("not enough memory"))
            reason = "الذاكرة غير كافية لهذا المستوى. جرّب مستوى ضغط أقل.";
        else if (lower.contains("no space left") || lower.contains("errno=28"))
            reason = "لا توجد مساحة تخزين كافية.";
        else if (lower.contains("permission denied") || lower.contains("errno=13") || lower.contains("operation not permitted"))
            reason = "لا توجد صلاحية للوصول إلى هذا المكان.";
        else if (lower.contains("cannot open the file as") || lower.contains("is not archive") || lower.contains("can't open as archive"))
            reason = "الملف ليس أرشيفاً مدعوماً أو أنه تالف.";
        else if (lower.contains("unsupported method") || lower.contains("unsupported compression"))
            reason = "طريقة الضغط داخل هذا الأرشيف غير مدعومة.";
        else if (lower.contains("crc failed") || lower.contains("data error") || lower.contains("headers error") || lower.contains("unexpected end"))
            reason = "الأرشيف تالف أو غير مكتمل.";
        else if (lower.contains("missing volume") || lower.contains("unavailable start of archive"))
            reason = "أحد أجزاء الأرشيف مفقود. ضع كل الأجزاء في نفس المجلد.";
        else reason = "رمز المحرك " + exit;
        return new EngineException(action + ": " + reason + (all.isEmpty() ? "" : "\n\n" + all));
    }

    /** Splits engine output into tokens and routes progress, errors and listing lines. */
    private static final class Console {
        final Progress progress;
        final ListParser parser;
        final ArrayDeque<String> errors = new ArrayDeque<>();
        final ArrayDeque<String> recent = new ArrayDeque<>();
        volatile boolean needPassword, wrongPassword;
        int serious, cosmetic;
        private boolean openError;
        Process owner;
        private int lastPercent = -2;
        private String lastName;

        Console(Progress progress, ListParser parser) { this.progress = progress; this.parser = parser; }

        void consume(InputStream stream) throws IOException {
            ByteArrayOutputStream token = new ByteArrayOutputStream(256);
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    for (int i = 0; i < read; i++) {
                        byte b = buffer[i];
                        if (b == '\n' || b == '\r' || b == '\b') {
                            if (token.size() > 0 || b == '\n') accept(new String(token.toByteArray(), StandardCharsets.UTF_8), b == '\n');
                            token.reset();
                        } else token.write(b);
                    }
                }
            } catch (IOException closed) {
                // The stream ends abruptly when the process is destroyed (cancel / password prompt).
            }
            if (token.size() > 0) accept(new String(token.toByteArray(), StandardCharsets.UTF_8), true);
        }

        private void accept(String raw, boolean lineEnd) {
            String text = raw.trim();
            if (text.isEmpty()) { if (lineEnd && parser != null) parser.blank(); return; }
            Matcher percent = PERCENT.matcher(text);
            if (percent.matches()) {
                int value = Math.min(100, Integer.parseInt(percent.group(1)));
                String name = percent.group(2);
                if (name != null) lastName = name;
                if (progress != null && (value != lastPercent || name != null)) { lastPercent = value; progress.update(value, lastName); }
                return;
            }
            if (SCAN.matcher(text).matches()) return;
            if (text.contains("Enter password")) {
                needPassword = true;
                if (owner != null) owner.destroy();
                return;
            }
            boolean property = parser != null && parser.inEntries && (text.contains(" = ") || text.endsWith(" ="));
            if (parser != null) parser.line(raw);
            if (property) return;
            if (text.contains("Wrong password")) wrongPassword = true;
            note(text);
        }

        void note(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (openError || text.startsWith("ERROR:")) {
                openError = text.equals("ERROR:");
                if (!openError) {
                    if (lower.contains("cannot set file attribute") || lower.contains("cannot set time")
                            || lower.contains("symbolic link") || lower.contains("hard link")) cosmetic++;
                    else serious++;
                }
            }
            if (lower.contains("error") || lower.contains("warning") || lower.contains("errno") || lower.contains("cannot")
                    || lower.contains("can't") || lower.contains("unsupported") || lower.contains("no space")
                    || lower.contains("denied") || lower.contains("missing") || lower.contains("unavailable")
                    || lower.contains("unexpected")) {
                if (errors.size() < 10) errors.add(text);
            }
            if (recent.size() >= 6) recent.removeFirst();
            recent.add(text);
        }

        String errorText() {
            StringBuilder builder = new StringBuilder();
            for (String line : errors.isEmpty() ? recent : errors) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }
            return builder.length() > 900 ? builder.substring(0, 900) : builder.toString();
        }
    }

    /** Parses the output of {@code 7zz l -slt}. */
    private static final class ListParser {
        final Listing listing = new Listing();
        boolean inEntries;
        private Entry current;
        private final SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

        void blank() { current = null; }

        void line(String raw) {
            String key, value;
            int split = raw.indexOf(" = ");
            if (split > 0) { key = raw.substring(0, split).trim(); value = raw.substring(split + 3); }
            else if (raw.trim().endsWith(" =")) { String t = raw.trim(); key = t.substring(0, t.length() - 2).trim(); value = ""; }
            else {
                if (!inEntries && raw.startsWith("----------")) inEntries = true;
                return;
            }
            if (!inEntries) {
                if (key.equals("Type") && !value.equalsIgnoreCase("Split")) listing.type = value.trim();
                else if (key.equals("Solid")) listing.solid = value.trim().equals("+");
                else if (key.equals("Method")) listing.method = value.trim();
                else if (key.equals("Volumes")) listing.volumes = (int) Math.max(1, number(value));
                return;
            }
            if (current == null || key.equals("Path") && !current.path.isEmpty()) {
                current = new Entry();
                listing.entries.add(current);
            }
            switch (key) {
                case "Path": current.path = value; break;
                case "Size": current.size = number(value); break;
                case "Packed Size": current.packedSize = number(value); break;
                case "Folder": if (value.trim().equals("+")) current.directory = true; break;
                case "Attributes": if (value.trim().startsWith("D")) current.directory = true; break;
                case "Encrypted": current.encrypted = value.trim().equals("+"); break;
                case "Method": current.method = value.trim(); break;
                case "Modified":
                    if (value.length() >= 19) {
                        try { Date date = dates.parse(value.substring(0, 19)); if (date != null) current.modified = date.getTime(); }
                        catch (Exception ignored) { /* unknown date format */ }
                    }
                    break;
                default: break;
            }
        }

        private static long number(String value) {
            try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return 0; }
        }
    }
}
