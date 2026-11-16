package com.packx.pro;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.packx.pro.compression.ArchiveFormat;
import com.packx.pro.compression.SevenZip;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs every long operation in the foreground so it survives leaving the app. */
public class CompressionService extends Service {
    public static final int COMPRESS = 1, EXTRACT = 2, TEST = 3, OPEN_ENTRY = 4, COPY = 5, MOVE = 6, DELETE = 7;
    private static final String ACTION_CANCEL = "com.packx.pro.CANCEL";
    private static final String CHANNEL_ID = "packx_operations";
    private static final int NOTIFICATION_ID = 2202;

    /** Everything an operation needs. Kept in memory only, so passwords never touch an Intent. */
    public static final class Task {
        public int type;
        // compress
        public File workDir;
        public List<String> names = new ArrayList<>();
        public File outputDir;
        public String baseName;
        public List<ArchiveFormat> formats = new ArrayList<>();
        public int level = SevenZip.LEVEL_NORMAL;
        public boolean hideNames, solid = true, verify = true;
        public long volumeBytes;
        // archives
        public File archive;
        public String password;
        public SevenZip.Listing listing;
        public List<String> selected;
        public int overwrite = SevenZip.OVERWRITE;
        // extract / copy / move destination
        public File target;
        // copy / move / delete
        public List<File> sources = new ArrayList<>();
    }

    public static final class Result {
        public Task task;
        public boolean success, cancelled, passwordNeeded, passwordWrong, verified;
        public String title = "", detail = "";
        public long originalBytes;
        public final List<String> labels = new ArrayList<>();
        public final List<File> outputs = new ArrayList<>();
        public final List<Long> sizes = new ArrayList<>();
        public File opened;
    }

    public interface Listener {
        void onProgress(String title, int percent, String current);
        void onFinished(Result result);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static volatile Task pending;
    private static volatile Listener listener;
    private static volatile Result undelivered;
    public static volatile boolean running;
    public static volatile String lastTitle = "";
    public static volatile String lastCurrent = "";
    public static volatile int lastPercent = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private long lastNotificationAt, lastUiAt;
    private int lastUiPercent = -2;

    /** @return false when another operation is still running. */
    public static boolean start(Context context, Task task) {
        if (running) return false;
        running = true;
        pending = task;
        CANCELLED.set(false);
        lastTitle = "جاري التحضير…";
        lastCurrent = "";
        lastPercent = -1;
        context.startForegroundService(new Intent(context, CompressionService.class));
        return true;
    }

    public static void cancel() {
        CANCELLED.set(true);
        SevenZip.cancelAll();
    }

    /** Attach the visible screen. A result that finished while detached is delivered immediately. */
    public static void setListener(Listener value) {
        listener = value;
        final Result waiting = undelivered;
        if (value != null && waiting != null) {
            undelivered = null;
            MAIN.post(new Runnable() { @Override public void run() { deliver(waiting); } });
        }
    }

    private static void deliver(Result result) {
        Listener target = listener;
        if (target != null) target.onFinished(result); else undelivered = result;
    }

    public static SevenZip engine(Context context) {
        File temp = new File(context.getCacheDir(), "engine");
        if (!temp.isDirectory()) temp.mkdirs();
        File libs = new File(context.getApplicationInfo().nativeLibraryDir);
        return new SevenZip(new File(libs, "libpackx7zz.so"), libs, temp);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "عمليات PackX Pro", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel();
            if (pending == null && !running) stopSelf();
            return START_NOT_STICKY;
        }
        final Task task = pending;
        pending = null;
        startAsForeground(lastTitle, 0, true);
        if (task == null) {
            if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            return START_NOT_STICKY;
        }
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PackXPro:Operation");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        executor.execute(new Runnable() { @Override public void run() { runTask(task); } });
        return START_NOT_STICKY;
    }

    private void runTask(Task task) {
        final Result result = new Result();
        result.task = task;
        try {
            switch (task.type) {
                case COMPRESS: compress(task, result); break;
                case EXTRACT: extract(task, result); break;
                case TEST: test(task, result); break;
                case OPEN_ENTRY: openEntry(task, result); break;
                case COPY: case MOVE: transfer(task, result); break;
                case DELETE: delete(task, result); break;
                default: throw new IOException("عملية غير معروفة");
            }
            result.success = true;
        } catch (SevenZip.PasswordException locked) {
            result.passwordNeeded = true;
            result.passwordWrong = locked.wrong;
            result.title = locked.getMessage();
        } catch (CancellationException stopped) {
            result.cancelled = true;
            result.title = "تم إلغاء العملية";
        } catch (Exception error) {
            if (CANCELLED.get()) { result.cancelled = true; result.title = "تم إلغاء العملية"; }
            else {
                result.title = "لم تكتمل العملية";
                result.detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        } finally {
            running = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (listener == null && !result.passwordNeeded) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.notify(NOTIFICATION_ID + 1, notification(result.title, 0, false, true));
            }
            MAIN.post(new Runnable() { @Override public void run() { deliver(result); } });
            stopSelf();
        }
    }

    // ------------------------------------------------------------------ operations

    private void compress(Task task, Result result) throws IOException {
        progress("جاري حساب الحجم…", -1, null, true);
        long total = 0;
        for (String name : task.names) total += FileKit.measure(new File(task.workDir, name), CANCELLED)[0];
        result.originalBytes = total;
        SevenZip engine = engine(this);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(memory);
        final int count = task.formats.size();
        List<String> scan = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ArchiveFormat format = task.formats.get(i);
            SevenZip.CompressSpec spec = new SevenZip.CompressSpec();
            spec.workDir = task.workDir;
            spec.names = task.names;
            spec.format = format;
            spec.level = task.level;
            spec.password = format.supportsPassword ? task.password : null;
            spec.hideNames = task.hideNames;
            spec.solid = task.solid;
            spec.volumeBytes = task.volumeBytes;
            spec.ramBytes = memory.totalMem;
            spec.cores = Runtime.getRuntime().availableProcessors();
            spec.totalBytes = total;
            spec.output = freeOutput(task.outputDir, task.baseName, format.extension);
            final String stage = count > 1 ? "ضغط " + format.label + "  (" + (i + 1) + "/" + count + ")" : "جاري الضغط • " + format.label;
            final int index = i;
            final float share = task.verify ? 0.9f : 1f;
            progress(stage, overall(index, count, 0), null, true);
            List<File> written = engine.compress(spec, CANCELLED, new SevenZip.Progress() {
                @Override public void update(int percent, String current) {
                    progress(stage, percent < 0 ? -1 : overall(index, count, percent * share), current, false);
                }
            });
            if (task.verify) {
                final String check = "فحص سلامة " + format.label;
                progress(check, overall(index, count, 90), null, true);
                engine.test(written.get(0), spec.password, CANCELLED, new SevenZip.Progress() {
                    @Override public void update(int percent, String current) {
                        progress(check, overall(index, count, 90 + Math.max(0, percent) * 0.1f), current, false);
                    }
                });
                result.verified = true;
            }
            long size = 0;
            for (File part : written) { size += part.length(); scan.add(part.getAbsolutePath()); }
            result.labels.add(written.size() > 1 ? format.label + " • " + written.size() + " أجزاء" : format.label);
            result.outputs.add(written.get(0));
            result.sizes.add(size);
        }
        MediaScannerConnection.scanFile(this, scan.toArray(new String[0]), null, null);
        result.title = "اكتمل الضغط";
    }

    private static int overall(int index, int count, float stagePercent) {
        return Math.min(100, Math.round((index * 100f + stagePercent) / count));
    }

    /** First "name.ext", "name (2).ext"… that collides with neither a file nor split volumes. */
    private static File freeOutput(File directory, String base, String extension) {
        for (int i = 1; ; i++) {
            File candidate = new File(directory, base + (i == 1 ? "" : " (" + i + ")") + extension);
            if (!candidate.exists() && !new File(candidate.getPath() + ".001").exists()) return candidate;
        }
    }

    private void extract(Task task, Result result) throws IOException {
        final String stage = "جاري فك الضغط";
        progress(stage, -1, null, true);
        engine(this).extract(task.archive, task.listing, task.target, task.selected, task.password, task.overwrite, CANCELLED,
                new SevenZip.Progress() {
                    @Override public void update(int percent, String current) { progress(stage, percent, current, false); }
                });
        scanTree(task.target);
        result.title = "اكتمل فك الضغط";
        result.detail = task.target.getAbsolutePath();
    }

    private void test(Task task, Result result) throws IOException {
        final String stage = "جاري فحص الأرشيف";
        progress(stage, -1, null, true);
        engine(this).test(task.archive, task.password, CANCELLED, new SevenZip.Progress() {
            @Override public void update(int percent, String current) { progress(stage, percent, current, false); }
        });
        result.verified = true;
        result.title = "الأرشيف سليم";
        result.detail = "اجتازت كل الملفات فحص السلامة.";
    }

    private void openEntry(Task task, Result result) throws IOException {
        final String stage = "جاري تجهيز الملف";
        progress(stage, -1, null, true);
        File folder = new File(new File(getCacheDir(), "opened"), Long.toString(System.nanoTime()));
        engine(this).extract(task.archive, task.listing, folder, task.selected, task.password, SevenZip.OVERWRITE, CANCELLED,
                new SevenZip.Progress() {
                    @Override public void update(int percent, String current) { progress(stage, percent, current, false); }
                });
        File file = new File(folder, task.selected.get(0).replace('\\', '/'));
        if (!file.isFile()) throw new IOException("تعذر استخراج الملف للعرض");
        result.opened = file;
        result.title = "جاهز";
    }

    private void transfer(Task task, Result result) throws IOException {
        final boolean move = task.type == MOVE;
        final String stage = move ? "جاري النقل" : "جاري النسخ";
        progress(stage, -1, null, true);
        for (File source : task.sources)
            if (source.isDirectory() && FileKit.isInside(source, task.target))
                throw new IOException("لا يمكن وضع المجلد «" + source.getName() + "» داخل نفسه");
        long sum = 0;
        for (File source : task.sources) sum += FileKit.measure(source, CANCELLED)[0];
        final long total = sum;
        long[] done = {0};
        for (File source : task.sources) {
            if (move && task.target.equals(source.getParentFile())) continue;
            File destination = FileKit.unique(task.target, source.getName());
            if (move && source.renameTo(destination)) { done[0] += FileKit.measure(destination, CANCELLED)[0]; continue; }
            FileKit.copyTree(source, destination, CANCELLED, done, new FileKit.ByteProgress() {
                @Override public void onBytes(long bytes, String name) {
                    progress(stage, total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1, name, false);
                }
            });
            if (move && !FileKit.deleteTree(source)) throw new IOException("تم النسخ لكن تعذر حذف الأصل: " + source.getName());
        }
        scanTree(task.target);
        result.title = move ? "اكتمل النقل" : "اكتمل النسخ";
        result.detail = task.sources.size() + " عنصر • " + FileKit.size(total);
    }

    private void delete(Task task, Result result) throws IOException {
        int failed = 0, index = 0;
        for (File source : task.sources) {
            if (CANCELLED.get()) throw new CancellationException("تم إلغاء العملية");
            progress("جاري الحذف", task.sources.size() > 1 ? index * 100 / task.sources.size() : -1, source.getName(), true);
            if (!FileKit.deleteTree(source)) failed++;
            index++;
        }
        if (failed > 0) throw new IOException("تعذر حذف " + failed + " عنصر. قد لا يملك التطبيق صلاحية على هذا المكان.");
        result.title = "تم الحذف";
        result.detail = task.sources.size() + " عنصر";
    }

    /** Tells the media library about new files so galleries and PCs see them right away. */
    private void scanTree(File root) {
        List<String> paths = new ArrayList<>();
        collect(root, paths, 0);
        if (!paths.isEmpty()) MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null, null);
    }

    private static void collect(File file, List<String> out, int depth) {
        if (out.size() >= 400 || depth > 6) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children == null) { if (file.isFile()) out.add(file.getAbsolutePath()); return; }
        for (File child : children) collect(child, out, depth + 1);
    }

    // ------------------------------------------------------------------ progress + notification

    private void progress(final String title, final int percent, final String current, boolean force) {
        long now = System.currentTimeMillis();
        lastTitle = title;
        lastPercent = percent;
        if (current != null) lastCurrent = current;
        if (force || percent != lastUiPercent || now - lastUiAt > 120) {
            lastUiAt = now;
            lastUiPercent = percent;
            final String name = lastCurrent;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    Listener target = listener;
                    if (target != null && running) target.onProgress(title, percent, name);
                }
            });
        }
        if (force || now - lastNotificationAt > 700) {
            lastNotificationAt = now;
            startAsForeground(percent >= 0 ? title + " • " + percent + "%" : title, Math.max(0, percent), percent < 0);
        }
    }

    private void startAsForeground(String text, int progress, boolean indeterminate) {
        Notification value = notification(text, progress, indeterminate, false);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, value);
    }

    private Notification notification(String text, int progress, boolean indeterminate, boolean finished) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, FileBrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PackX Pro")
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setAutoCancel(finished);
        if (!finished) {
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CompressionService.class)
                    .setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setProgress(100, progress, indeterminate);
            builder.addAction(new Notification.Action.Builder(null, "إلغاء", stop).build());
        }
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        executor.shutdown();
        super.onDestroy();
    }
}
