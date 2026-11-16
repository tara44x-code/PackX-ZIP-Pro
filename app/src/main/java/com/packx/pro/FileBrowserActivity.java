package com.packx.pro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.packx.pro.compression.ArchiveTree;
import com.packx.pro.compression.SevenZip;
import com.packx.pro.ui.RingView;
import com.packx.pro.ui.Sheet;
import com.packx.pro.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole app in one screen: a file manager that can step inside archives as if they
 * were folders. Long operations are handed to {@link CompressionService}.
 */
public final class FileBrowserActivity extends Activity implements CompressionService.Listener {
    private static final int REQUEST_STORAGE = 70, REQUEST_NOTIFICATIONS = 71;
    private static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;

    /** One row: either a real file or an entry inside the open archive. */
    private static final class Item {
        String key, name;
        boolean directory, encrypted;
        long size, modified;
        int children = -1;
        int kind;
        File file;
        ArchiveTree.Node node;
    }

    private static final class Archive {
        File file;
        String password;
        SevenZip.Listing listing;
        ArchiveTree.Node root, node;
        /** True when the archive was copied into the cache from another app. */
        boolean temporary;
    }

    /** Something waiting for the user to choose a destination folder. */
    private static final class Pending {
        int type;
        List<File> sources = new ArrayList<>();
        CompressionService.Task task;
        String label;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbWorker = Executors.newFixedThreadPool(2);
    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Map<String, Item> selected = new LinkedHashMap<>();
    private final Map<String, Integer> scrollMemory = new HashMap<>();
    private final RowAdapter adapter = new RowAdapter();
    private LruCache<String, Bitmap> thumbs;
    private SharedPreferences prefs;

    private LinearLayout page, crumbs, bottomBar, stateView;
    private HorizontalScrollView crumbScroll;
    private ImageView navButton, searchButton, sortButton, moreButton, fab;
    private TextView titleView, subtitleView;
    private EditText searchField;
    private ListView listView;

    private File storageRoot, dir;
    private Archive archive;
    private Pending pending;
    private String query = "";
    private int sortMode, loadToken;
    private boolean sortDescending, showHidden, started, loading;
    private Uri incoming;
    private List<File> rootsCache;

    private Sheet progressSheet;
    private RingView progressRing;
    private TextView progressCurrent;
    private AtomicBoolean busyCancel = new AtomicBoolean();

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        prefs = getSharedPreferences("packx", MODE_PRIVATE);
        Ui.applyTheme(this, prefs.getInt("theme", Ui.THEME_AUTO));
        setTheme(Ui.dark ? R.style.Theme_PackXPro : R.style.Theme_PackXPro_Light);
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.bg);
        getWindow().setNavigationBarColor(Ui.bg);
        getWindow().getDecorView().setSystemUiVisibility(Ui.dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        sortMode = prefs.getInt("sort", SORT_NAME);
        sortDescending = prefs.getBoolean("sortDesc", false);
        showHidden = prefs.getBoolean("hidden", false);
        thumbs = new LruCache<String, Bitmap>(12 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
        };
        storageRoot = Environment.getExternalStorageDirectory();
        setContentView(buildUi());
        Intent intent = getIntent();
        if (state == null && intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) incoming = intent.getData();
        if (state != null && state.getString("dir") != null) dir = new File(state.getString("dir"));
        if (hasStorageAccess()) start(); else showPermissionState();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            incoming = intent.getData();
            if (started) openIncoming();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (dir != null) out.putString("dir", dir.getAbsolutePath());
    }

    @Override protected void onResume() {
        super.onResume();
        rootsCache = null;
        if (!started && hasStorageAccess()) start();
        else if (started && archive == null && dir != null && !loading) loadDirectory(true);
        CompressionService.setListener(this);
        if (CompressionService.running) showProgress();
    }

    @Override protected void onPause() {
        CompressionService.setListener(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (progressSheet != null) progressSheet.dismiss();
        worker.shutdownNow();
        thumbWorker.shutdownNow();
        super.onDestroy();
    }

    private void start() {
        started = true;
        showState(null);
        if (!CompressionService.running) {
            final File cache = getCacheDir();
            worker.execute(new Runnable() {
                @Override public void run() {
                    FileKit.deleteTree(new File(cache, "opened"));
                    FileKit.deleteTree(new File(cache, "engine"));
                }
            });
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        if (dir == null || !dir.isDirectory()) dir = storageRoot;
        if (incoming != null) openIncoming(); else loadDirectory(false);
    }

    // ------------------------------------------------------------------ permission

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionState() {
        showState(state(R.drawable.ic_folder, Ui.accent, "مرحباً بك في PackX Pro",
                "حتى يعرض التطبيق ملفاتك ويضغطها ويفك الأرشيفات، اسمح له بالوصول إلى التخزين. تُمنح الصلاحية مرة واحدة فقط.",
                "منح صلاحية التخزين", new Runnable() {
                    @Override public void run() { requestStorage(); }
                }));
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception missing) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Exception none) { toast("افتح إعدادات التطبيق وامنح صلاحية كل الملفات"); }
            }
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_STORAGE && hasStorageAccess() && !started) start();
    }

    // ------------------------------------------------------------------ layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Ui.bg);
        page = Ui.column(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = Ui.row(this);
        header.setPadding(dp(6), dp(8), dp(6), dp(4));
        navButton = Ui.iconButton(this, R.drawable.ic_menu, Ui.text);
        header.addView(navButton, square(44));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(dp(6), 0, dp(6), 0);
        titleView = Ui.oneLine(Ui.text(this, "PackX Pro", 19, Ui.text, true));
        subtitleView = Ui.oneLine(Ui.text(this, "", 12.5f, Ui.textDim, false));
        subtitleView.setTextDirection(View.TEXT_DIRECTION_RTL);
        titles.addView(titleView);
        titles.addView(subtitleView);
        header.addView(titles, Ui.weight());
        searchButton = Ui.iconButton(this, R.drawable.ic_search, Ui.text);
        sortButton = Ui.iconButton(this, R.drawable.ic_sort, Ui.text);
        moreButton = Ui.iconButton(this, R.drawable.ic_more, Ui.text);
        header.addView(searchButton, square(44));
        header.addView(sortButton, square(44));
        header.addView(moreButton, square(44));
        page.addView(header, Ui.fill());

        searchField = Sheet.field(this, "بحث في هذا المكان…", false);
        searchField.setVisibility(View.GONE);
        page.addView(searchField, Ui.margins(this, Ui.fill(), 14, 4, 14, 6));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim(); applyFilter(); }
        });

        crumbs = Ui.row(this);
        crumbs.setPadding(dp(12), dp(2), dp(12), dp(8));
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.addView(crumbs);
        page.addView(crumbScroll, Ui.fill());

        FrameLayout content = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setSelector(new ColorDrawable(0));
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(92));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        content.addView(listView, new FrameLayout.LayoutParams(-1, -1));
        stateView = Ui.column(this);
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(dp(32), dp(24), dp(32), dp(72));
        stateView.setVisibility(View.GONE);
        content.addView(stateView, new FrameLayout.LayoutParams(-1, -1));
        fab = Ui.icon(this, R.drawable.ic_add, Ui.onAccent);
        fab.setBackground(Ui.ripple(this, Ui.brand(this, 20), 20));
        fab.setPadding(dp(15), dp(15), dp(15), dp(15));
        fab.setElevation(dp(6));
        fab.setClickable(true);
        fab.setVisibility(View.GONE);
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(dp(20), dp(20), dp(20), dp(22));
        content.addView(fab, fabParams);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomBar = Ui.row(this);
        bottomBar.setBackgroundColor(Ui.surface);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomBar.setVisibility(View.GONE);
        page.addView(bottomBar, Ui.fill());

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                page.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        navButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!started) return;
                if (!selected.isEmpty()) clearSelection();
                else if (archive != null || !isAtRoot()) onBackPressed();
                else showPlaces();
            }
        });
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (started) toggleSearch(searchField.getVisibility() != View.VISIBLE); }
        });
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!started) return; if (selected.isEmpty()) showSort(); else selectAll(); }
        });
        moreButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (started) showMore(); else showSettings(); }
        });
        fab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCreate(); }
        });
        return root;
    }

    private int dp(float value) { return Ui.dp(this, value); }

    private LinearLayout.LayoutParams square(int size) { return new LinearLayout.LayoutParams(dp(size), dp(size)); }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    /** Builds the centred illustration used for empty folders, errors and onboarding. */
    private View state(int icon, int color, String title, String message, String action, final Runnable onAction) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView image = Ui.icon(this, icon, color);
        image.setBackground(Ui.round(this, Ui.alpha(color, 30), 28));
        image.setPadding(dp(22), dp(22), dp(22), dp(22));
        box.addView(image, square(88));
        TextView heading = Ui.text(this, title, 18, Ui.text, true);
        heading.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        heading.setPadding(0, dp(18), 0, dp(6));
        box.addView(heading, Ui.fill());
        if (message != null) {
            TextView body = Ui.text(this, message, 14, Ui.textDim, false);
            body.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            body.setLineSpacing(dp(3), 1f);
            box.addView(body, Ui.fill());
        }
        if (action != null) {
            TextView button = Ui.primaryButton(this, action);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onAction.run(); }
            });
            box.addView(button, Ui.margins(this, Ui.fill(), 0, 22, 0, 0));
        }
        return box;
    }

    private void showState(View view) {
        stateView.removeAllViews();
        if (view == null) { stateView.setVisibility(View.GONE); listView.setVisibility(View.VISIBLE); return; }
        stateView.addView(view, Ui.fill());
        stateView.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ navigation

    private List<File> storageRoots() {
        if (rootsCache != null) return rootsCache;
        List<File> roots = new ArrayList<>();
        roots.add(storageRoot);
        File[] externals = getExternalFilesDirs(null);
        if (externals != null) for (File external : externals) {
            if (external == null) continue;
            String path = external.getAbsolutePath();
            int cut = path.indexOf("/Android/");
            if (cut <= 0) continue;
            File root = new File(path.substring(0, cut));
            if (!root.equals(storageRoot) && root.canRead()) roots.add(root);
        }
        rootsCache = roots;
        return roots;
    }

    private File rootOf(File file) {
        String path = file.getAbsolutePath();
        for (File root : storageRoots())
            if (path.equals(root.getAbsolutePath()) || path.startsWith(root.getAbsolutePath() + "/")) return root;
        return file;
    }

    private boolean isAtRoot() { return dir == null || dir.equals(rootOf(dir)); }

    private void navigate(File target) {
        if (target == null) return;
        rememberScroll();
        archive = null;
        selected.clear();
        toggleSearch(false);
        dir = target;
        loadDirectory(false);
    }

    private String locationKey() {
        if (archive != null) return archive.file.getAbsolutePath() + "!" + archive.node.displayPath();
        return dir == null ? "" : dir.getAbsolutePath();
    }

    private void rememberScroll() {
        if (listView != null && (dir != null || archive != null)) scrollMemory.put(locationKey(), listView.getFirstVisiblePosition());
    }

    private void loadDirectory(final boolean keepScroll) {
        final File target = dir;
        final int token = ++loadToken;
        final boolean hidden = showHidden;
        final int keep = keepScroll ? listView.getFirstVisiblePosition() : -1;
        loading = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                final File[] files = target.listFiles();
                final List<Item> items = new ArrayList<>();
                if (files != null) for (File file : files) {
                    if (!hidden && file.getName().startsWith(".")) continue;
                    Item item = new Item();
                    item.file = file;
                    item.key = file.getAbsolutePath();
                    item.name = file.getName();
                    item.directory = file.isDirectory();
                    item.size = item.directory ? 0 : file.length();
                    item.modified = file.lastModified();
                    item.kind = FileKit.kind(item.name, item.directory);
                    if (item.directory) { String[] names = file.list(); item.children = names == null ? -1 : names.length; }
                    items.add(item);
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken || isDestroyed()) return;
                        loading = false;
                        all.clear();
                        all.addAll(items);
                        selected.keySet().retainAll(keysOf(items));
                        Integer remembered = scrollMemory.get(target.getAbsolutePath());
                        applyFilter();
                        if (files == null) showState(state(R.drawable.ic_lock, Ui.warn, "تعذر فتح المجلد",
                                "أندرويد يمنع التطبيقات من قراءة هذا المكان.", null, null));
                        listView.setSelection(keep >= 0 ? keep : remembered == null ? 0 : remembered);
                        refreshChrome();
                    }
                });
            }
        });
    }

    private static Set<String> keysOf(List<Item> items) {
        Set<String> keys = new TreeSet<>();
        for (Item item : items) keys.add(item.key);
        return keys;
    }

    private void showArchiveNode() {
        all.clear();
        for (ArchiveTree.Node node : archive.node.children()) {
            Item item = new Item();
            item.node = node;
            item.key = node.path;
            item.name = node.name;
            item.directory = node.directory;
            item.size = node.directory ? node.totalSize() : node.size;
            item.modified = node.modified;
            item.encrypted = node.encrypted;
            item.children = node.directory ? node.childCount() : -1;
            item.kind = FileKit.kind(node.name, node.directory);
            all.add(item);
        }
        Integer remembered = scrollMemory.get(locationKey());
        applyFilter();
        listView.setSelection(remembered == null ? 0 : remembered);
        refreshChrome();
    }

    private void reload() {
        if (archive != null) showArchiveNode(); else if (dir != null) loadDirectory(true);
    }

    private void applyFilter() {
        shown.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (Item item : all) if (needle.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(needle)) shown.add(item);
        final int direction = sortDescending ? -1 : 1;
        Collections.sort(shown, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                int result = 0;
                if (sortMode == SORT_DATE) result = Long.compare(a.modified, b.modified);
                else if (sortMode == SORT_SIZE) result = Long.compare(a.size, b.size);
                else if (sortMode == SORT_TYPE) result = FileKit.extension(a.name).compareTo(FileKit.extension(b.name));
                if (result == 0) result = a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
                return result * direction;
            }
        });
        adapter.notifyDataSetChanged();
        if (!shown.isEmpty()) showState(null);
        else if (!needle.isEmpty()) showState(state(R.drawable.ic_search, Ui.textDim, "لا توجد نتائج", "لا يوجد عنصر يطابق «" + query + "» هنا.", null, null));
        else showState(state(R.drawable.ic_folder, Ui.textDim, archive != null ? "لا توجد عناصر" : "المجلد فارغ",
                archive != null ? null : "اضغط + لإنشاء مجلد جديد.", null, null));
    }

    private void toggleSearch(boolean show) {
        if (searchField == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            searchField.setVisibility(View.VISIBLE);
            searchField.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        } else if (searchField.getVisibility() == View.VISIBLE) {
            searchField.setVisibility(View.GONE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
            if (searchField.length() > 0) searchField.setText("");
        }
    }

    @Override public void onBackPressed() {
        if (!selected.isEmpty()) { clearSelection(); return; }
        if (searchField.getVisibility() == View.VISIBLE) { toggleSearch(false); return; }
        if (archive != null) {
            rememberScroll();
            if (archive.node.parent != null) { archive.node = archive.node.parent; showArchiveNode(); }
            else closeArchive();
            return;
        }
        if (pending != null && isAtRoot()) { pending = null; refreshChrome(); return; }
        if (dir != null && !isAtRoot() && dir.getParentFile() != null) { navigate(dir.getParentFile()); return; }
        super.onBackPressed();
    }

    private void closeArchive() {
        Archive closing = archive;
        archive = null;
        selected.clear();
        if (closing != null && closing.temporary) FileKit.deleteTree(closing.file.getParentFile());
        if (dir == null) dir = storageRoot;
        loadDirectory(false);
    }

    // ------------------------------------------------------------------ header, crumbs, bottom bar

    private void refreshChrome() {
        boolean selecting = !selected.isEmpty();
        boolean inArchive = archive != null;
        Ui.setIcon(navButton, selecting ? R.drawable.ic_close : inArchive || !isAtRoot() ? R.drawable.ic_back : R.drawable.ic_menu, Ui.text);
        Ui.setIcon(sortButton, selecting ? R.drawable.ic_select_all : R.drawable.ic_sort, Ui.text);
        searchButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        moreButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (selecting) {
            long bytes = 0;
            for (Item item : selected.values()) bytes += item.size;
            titleView.setText(selected.size() + " محدد");
            subtitleView.setText(bytes > 0 ? FileKit.size(bytes) : "اضغط على العناصر لتحديدها");
        } else if (inArchive) {
            titleView.setText(archive.file.getName());
            SevenZip.Listing listing = archive.listing;
            subtitleView.setText(listing.fileCount() + " ملف • " + FileKit.size(listing.totalSize())
                    + (listing.encrypted || archive.password != null ? " • محمي" : ""));
        } else {
            boolean root = isAtRoot();
            titleView.setText(root ? (dir != null && dir.equals(storageRoot) ? "PackX Pro" : "بطاقة الذاكرة") : dir.getName());
            subtitleView.setText(root ? freeSpace(dir, true) : all.size() + " عنصر");
        }
        buildCrumbs();
        buildBottomBar();
    }

    private String freeSpace(File root, boolean brief) {
        try {
            StatFs stat = new StatFs(root.getAbsolutePath());
            String free = "المتاح " + FileKit.size(stat.getAvailableBytes());
            return brief ? free : free + " من " + FileKit.size(stat.getTotalBytes());
        } catch (Exception e) {
            return "";
        }
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        if (dir == null && archive == null) return;
        if (archive != null) {
            final Archive current = archive;
            addCrumb(current.file.getName(), R.drawable.ic_archive, current.node == current.root, new Runnable() {
                @Override public void run() { rememberScroll(); current.node = current.root; showArchiveNode(); }
            });
            List<ArchiveTree.Node> chain = new ArrayList<>();
            for (ArchiveTree.Node node = current.node; node != null && node.parent != null; node = node.parent) chain.add(0, node);
            for (final ArchiveTree.Node node : chain)
                addCrumb(node.name, 0, node == current.node, new Runnable() {
                    @Override public void run() { rememberScroll(); current.node = node; showArchiveNode(); }
                });
        } else {
            File root = rootOf(dir);
            List<File> chain = new ArrayList<>();
            for (File file = dir; file != null; file = file.getParentFile()) {
                chain.add(0, file);
                if (file.equals(root)) break;
            }
            for (final File file : chain) {
                boolean isRoot = file.equals(root);
                addCrumb(isRoot ? (root.equals(storageRoot) ? "الذاكرة الداخلية" : "بطاقة الذاكرة") : file.getName(),
                        isRoot ? (root.equals(storageRoot) ? R.drawable.ic_phone : R.drawable.ic_sd) : 0, file.equals(dir),
                        new Runnable() { @Override public void run() { if (!file.equals(dir)) navigate(file); } });
            }
        }
        crumbScroll.post(new Runnable() {
            @Override public void run() { crumbScroll.fullScroll(View.FOCUS_LEFT); }
        });
    }

    private void addCrumb(String label, int icon, boolean current, final Runnable onClick) {
        if (crumbs.getChildCount() > 0) {
            ImageView arrow = Ui.icon(this, R.drawable.ic_chevron, Ui.textDim);
            crumbs.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        }
        LinearLayout chip = Ui.row(this);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setMinimumHeight(dp(32));
        chip.setBackground(Ui.ripple(this, Ui.round(this, current ? Ui.alpha(Ui.accent, Ui.dark ? 50 : 28) : Ui.surface2, 16), 16));
        if (icon != 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(16), dp(16));
            params.setMarginEnd(dp(6));
            chip.addView(Ui.icon(this, icon, current ? Ui.accent : Ui.textDim), params);
        }
        TextView text = Ui.text(this, label, 13, current ? (Ui.dark ? Ui.text : Ui.accent) : Ui.textDim, current);
        text.setSingleLine(true);
        text.setMaxWidth(dp(190));
        text.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        chip.addView(text);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        crumbs.addView(chip, params);
    }

    private void buildBottomBar() {
        bottomBar.removeAllViews();
        boolean visible = true;
        if (!selected.isEmpty()) {
            if (archive != null) {
                addBarPrimary("استخراج المحدد (" + selected.size() + ")", new Runnable() {
                    @Override public void run() { extractFromArchive(new ArrayList<>(selected.keySet())); }
                });
            } else {
                addBarAction(R.drawable.ic_compress, "ضغط", Ui.accent, new Runnable() {
                    @Override public void run() { compress(selectedFiles()); }
                });
                addBarAction(R.drawable.ic_copy, "نسخ", Ui.text, new Runnable() {
                    @Override public void run() { beginTransfer(CompressionService.COPY, selectedFiles()); }
                });
                addBarAction(R.drawable.ic_move, "نقل", Ui.text, new Runnable() {
                    @Override public void run() { beginTransfer(CompressionService.MOVE, selectedFiles()); }
                });
                addBarAction(R.drawable.ic_delete, "حذف", Ui.danger, new Runnable() {
                    @Override public void run() { confirmDelete(selectedFiles()); }
                });
                addBarAction(R.drawable.ic_share, "مشاركة", Ui.text, new Runnable() {
                    @Override public void run() { share(selectedFiles()); }
                });
            }
        } else if (pending != null && archive == null) {
            TextView cancel = Ui.secondaryButton(this, "إلغاء", Ui.text);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pending = null; refreshChrome(); }
            });
            bottomBar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
            addBarPrimary(pending.label, new Runnable() {
                @Override public void run() { finishPending(); }
            });
        } else if (archive != null) {
            TextView check = Ui.secondaryButton(this, "فحص", Ui.text);
            check.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { testArchive(archive.file, archive.password); }
            });
            bottomBar.addView(check, new LinearLayout.LayoutParams(0, -2, 1f));
            addBarPrimary("استخراج الكل", new Runnable() {
                @Override public void run() { extractFromArchive(null); }
            });
        } else visible = false;
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        fab.setVisibility(visible || archive != null || !started ? View.GONE : View.VISIBLE);
    }

    private void addBarPrimary(String label, final Runnable onClick) {
        TextView button = Ui.primaryButton(this, label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1.6f);
        if (bottomBar.getChildCount() > 0) params.setMarginStart(dp(10));
        bottomBar.addView(button, params);
    }

    private void addBarAction(int icon, String label, int color, final Runnable onClick) {
        LinearLayout button = Ui.column(this);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, dp(6), 0, dp(4));
        button.setBackground(Ui.touch(this, 14));
        button.addView(Ui.icon(this, icon, color), square(24));
        TextView text = Ui.text(this, label, 11.5f, color, false);
        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        text.setPadding(0, dp(3), 0, 0);
        button.addView(text, Ui.fill());
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        bottomBar.addView(button, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    // ------------------------------------------------------------------ list rows

    private static final class Holder {
        LinearLayout row;
        FrameLayout tile;
        ImageView icon, thumb, trailing;
        TextView name, meta;
        Item item;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            final Holder holder;
            if (convert == null) {
                holder = new Holder();
                FrameLayout wrap = new FrameLayout(FileBrowserActivity.this);
                wrap.setPadding(dp(10), dp(2), dp(10), dp(2));
                holder.row = Ui.row(FileBrowserActivity.this);
                holder.row.setPaddingRelative(dp(10), dp(8), dp(4), dp(8));
                holder.tile = new FrameLayout(FileBrowserActivity.this);
                holder.icon = new ImageView(FileBrowserActivity.this);
                holder.icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                holder.tile.addView(holder.icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
                holder.thumb = new ImageView(FileBrowserActivity.this);
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.thumb.setBackground(Ui.round(FileBrowserActivity.this, Ui.surface2, 13));
                holder.thumb.setClipToOutline(true);
                holder.tile.addView(holder.thumb, new FrameLayout.LayoutParams(-1, -1));
                holder.row.addView(holder.tile, square(46));
                LinearLayout texts = Ui.column(FileBrowserActivity.this);
                texts.setPaddingRelative(dp(13), 0, dp(6), 0);
                holder.name = Ui.oneLine(Ui.text(FileBrowserActivity.this, "", 15.5f, Ui.text, false));
                holder.meta = Ui.oneLine(Ui.text(FileBrowserActivity.this, "", 12.5f, Ui.textDim, false));
                holder.meta.setPadding(0, dp(2), 0, 0);
                holder.meta.setTextDirection(View.TEXT_DIRECTION_RTL);
                texts.addView(holder.name);
                texts.addView(holder.meta);
                holder.row.addView(texts, Ui.weight());
                holder.trailing = new ImageView(FileBrowserActivity.this);
                holder.trailing.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                holder.trailing.setPadding(dp(10), dp(10), dp(10), dp(10));
                holder.row.addView(holder.trailing, square(44));
                wrap.addView(holder.row, new FrameLayout.LayoutParams(-1, -2));
                wrap.setTag(holder);
                convert = wrap;
                holder.row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onItemClick(holder.item); }
                });
                holder.row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { toggleSelection(holder.item); return true; }
                });
                holder.trailing.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (selected.isEmpty()) showItemActions(holder.item); else toggleSelection(holder.item);
                    }
                });
            } else holder = (Holder) convert.getTag();

            Item item = shown.get(position);
            holder.item = item;
            boolean isSelected = selected.containsKey(item.key);
            int color = FileKit.color(item.kind);
            holder.row.setBackground(Ui.ripple(FileBrowserActivity.this,
                    Ui.round(FileBrowserActivity.this, isSelected ? Ui.alpha(Ui.accent, Ui.dark ? 46 : 26) : 0, 16), 16));
            holder.tile.setBackground(Ui.round(FileBrowserActivity.this, Ui.alpha(color, Ui.dark ? 38 : 26), 13));
            Ui.setIcon(holder.icon, FileKit.icon(item.kind), color);
            holder.name.setText(item.name);
            holder.name.setAlpha(item.name.startsWith(".") ? 0.6f : 1f);
            StringBuilder meta = new StringBuilder();
            if (item.directory) {
                if (item.children >= 0) meta.append(item.children).append(" عنصر");
                else meta.append("مجلد");
                if (item.node != null && item.size > 0) meta.append("  •  ").append(FileKit.size(item.size));
            } else meta.append(FileKit.size(item.size));
            String date = FileKit.date(item.modified);
            if (!date.isEmpty()) meta.append("  •  ").append(date);
            if (item.encrypted) meta.append("  •  محمي");
            holder.meta.setText(meta);
            if (!selected.isEmpty()) {
                Ui.setIcon(holder.trailing, R.drawable.ic_check, isSelected ? Ui.onAccent : 0);
                holder.trailing.setBackground(new android.graphics.drawable.InsetDrawable(isSelected
                        ? Ui.round(FileBrowserActivity.this, Ui.accent, 12)
                        : Ui.outlined(FileBrowserActivity.this, 0, Ui.textDim, 12), dp(10)));
                holder.trailing.setPadding(dp(14), dp(14), dp(14), dp(14));
            } else {
                Ui.setIcon(holder.trailing, R.drawable.ic_more, Ui.textDim);
                holder.trailing.setBackground(Ui.touch(FileBrowserActivity.this, 22));
                holder.trailing.setPadding(dp(11), dp(11), dp(11), dp(11));
            }
            holder.thumb.setImageDrawable(null);
            holder.thumb.setVisibility(View.GONE);
            holder.thumb.setTag(null);
            if (item.file != null && !item.directory && FileKit.hasThumbnail(item.name)) loadThumb(item, holder.thumb);
            return convert;
        }
    }

    private void loadThumb(final Item item, final ImageView target) {
        final String key = item.key + ":" + item.modified;
        Bitmap cached = thumbs.get(key);
        if (cached != null) { target.setImageBitmap(cached); target.setVisibility(View.VISIBLE); return; }
        target.setTag(key);
        final int edge = dp(46);
        thumbWorker.execute(new Runnable() {
            @Override public void run() {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(item.file.getAbsolutePath(), bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
                    int sample = 1;
                    while (bounds.outWidth / (sample * 2) >= edge && bounds.outHeight / (sample * 2) >= edge) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bitmap = BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
                    if (bitmap == null) return;
                    thumbs.put(key, bitmap);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (key.equals(target.getTag())) { target.setImageBitmap(bitmap); target.setVisibility(View.VISIBLE); }
                        }
                    });
                } catch (Throwable ignored) { /* a thumbnail is optional */ }
            }
        });
    }

    // ------------------------------------------------------------------ selection

    private void toggleSelection(Item item) {
        if (item == null || pending != null) return;
        if (selected.remove(item.key) == null) selected.put(item.key, item);
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private void selectAll() {
        boolean everything = selected.size() == shown.size();
        selected.clear();
        if (!everything) for (Item item : shown) selected.put(item.key, item);
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private List<File> selectedFiles() {
        List<File> files = new ArrayList<>();
        for (Item item : selected.values()) if (item.file != null) files.add(item.file);
        return files;
    }

    private static List<File> one(File file) {
        List<File> list = new ArrayList<>();
        list.add(file);
        return list;
    }

    // ------------------------------------------------------------------ opening things

    private void onItemClick(Item item) {
        if (item == null) return;
        if (!selected.isEmpty()) { toggleSelection(item); return; }
        if (item.node != null) {
            if (item.directory) { rememberScroll(); archive.node = item.node; showArchiveNode(); }
            else openEntry(item.node);
        } else if (item.directory) navigate(item.file);
        else if (item.kind == FileKit.ARCHIVE) openArchive(item.file, null, false);
        else openFile(item.file);
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileKit.mime(file.getName()));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Exception e) {
            toast("لا يوجد تطبيق يفتح هذا النوع");
        }
    }

    private void openArchive(final File file, final String password, final boolean temporary) {
        final Sheet busy = busySheet("جاري قراءة الأرشيف…", file.getName());
        final AtomicBoolean cancel = busyCancel;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final SevenZip.Listing listing = CompressionService.engine(FileBrowserActivity.this).list(file, password, cancel);
                    final ArchiveTree.Node root = ArchiveTree.build(listing);
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy.dismiss();
                            if (isDestroyed()) return;
                            rememberScroll();
                            Archive opened = new Archive();
                            opened.file = file;
                            opened.password = password;
                            opened.listing = listing;
                            opened.root = root;
                            opened.node = root;
                            opened.temporary = temporary;
                            archive = opened;
                            selected.clear();
                            toggleSearch(false);
                            showArchiveNode();
                        }
                    });
                } catch (final SevenZip.PasswordException locked) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy.dismiss();
                            if (isDestroyed()) return;
                            Sheets.password(FileBrowserActivity.this, file.getName(), locked.wrong, new Sheet.TextCallback() {
                                @Override public String onText(String value) {
                                    if (value.isEmpty()) return "أدخل كلمة المرور.";
                                    openArchive(file, value, temporary);
                                    return null;
                                }
                            });
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy.dismiss();
                            if (isDestroyed() || cancel.get()) return;
                            Sheet.message(FileBrowserActivity.this, "تعذر فتح الأرشيف", String.valueOf(error.getMessage()));
                            if (archive == null && all.isEmpty() && dir != null) loadDirectory(false);
                        }
                    });
                }
            }
        });
    }

    /** Small non-dismissable sheet with a spinner and a cancel button. */
    private Sheet busySheet(String title, String detail) {
        busyCancel = new AtomicBoolean();
        final AtomicBoolean cancel = busyCancel;
        Sheet sheet = new Sheet(this, title, detail).cancelable(false);
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(14);
        params.bottomMargin = dp(6);
        sheet.body.addView(spinner, params);
        sheet.button("إلغاء", false, Ui.danger, true, new Runnable() {
            @Override public void run() { cancel.set(true); SevenZip.cancelAll(); }
        });
        return sheet.show();
    }

    private void openEntry(final ArchiveTree.Node node) {
        final Archive current = archive;
        if (current.listing.encrypted && current.password == null) {
            Sheets.password(this, current.file.getName(), false, new Sheet.TextCallback() {
                @Override public String onText(String value) {
                    if (value.isEmpty()) return "أدخل كلمة المرور.";
                    current.password = value;
                    openEntry(node);
                    return null;
                }
            });
            return;
        }
        CompressionService.Task task = new CompressionService.Task();
        task.type = CompressionService.OPEN_ENTRY;
        task.archive = current.file;
        task.listing = current.listing;
        task.password = current.password;
        task.selected = new ArrayList<>();
        task.selected.add(node.path);
        launch(task);
    }

    /** Handles "open with PackX" from other apps. */
    private void openIncoming() {
        final Uri uri = incoming;
        incoming = null;
        if (uri == null) return;
        if (dir == null) dir = storageRoot;
        File direct = directFile(uri);
        if (direct != null && direct.isFile() && direct.canRead()) {
            dir = direct.getParentFile();
            loadDirectory(false);
            openArchive(direct, null, false);
            return;
        }
        loadDirectory(false);
        final Sheet busy = busySheet("جاري استلام الملف…", null);
        final AtomicBoolean cancel = busyCancel;
        worker.execute(new Runnable() {
            @Override public void run() {
                File folder = new File(new File(getCacheDir(), "opened"), "in" + System.nanoTime());
                File copy = null;
                try {
                    String name = displayName(uri);
                    if (!folder.mkdirs()) throw new java.io.IOException("تعذر تجهيز مساحة مؤقتة");
                    copy = new File(folder, name.replace('/', '_'));
                    byte[] buffer = new byte[256 * 1024];
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
                        if (in == null) throw new java.io.IOException("تعذر قراءة الملف");
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (cancel.get()) throw new java.io.IOException("تم الإلغاء");
                            out.write(buffer, 0, read);
                        }
                    }
                    final File ready = copy;
                    main.post(new Runnable() {
                        @Override public void run() { busy.dismiss(); if (!isDestroyed()) openArchive(ready, null, true); }
                    });
                } catch (final Exception error) {
                    if (copy != null) copy.delete();
                    main.post(new Runnable() {
                        @Override public void run() {
                            busy.dismiss();
                            if (!isDestroyed() && !cancel.get()) Sheet.message(FileBrowserActivity.this, "تعذر فتح الملف", String.valueOf(error.getMessage()));
                        }
                    });
                }
            }
        });
    }

    /** Maps a shared Uri to a real path when it points at shared storage. */
    private File directFile(Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath());
            if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && DocumentsContract.isDocumentUri(this, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                int colon = id.indexOf(':');
                if (colon > 0) {
                    String volume = id.substring(0, colon), path = id.substring(colon + 1);
                    return "primary".equalsIgnoreCase(volume) ? new File(storageRoot, path) : new File("/storage/" + volume, path);
                }
            }
            try (Cursor cursor = getContentResolver().query(uri, new String[]{"_data"}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String path = cursor.getString(0);
                    if (path != null && path.startsWith("/")) return new File(path);
                }
            }
        } catch (Exception ignored) { /* fall back to copying */ }
        return null;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (Exception ignored) { /* use the path instead */ }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "archive" : last;
    }

    // ------------------------------------------------------------------ archive actions

    private File homeOf(File archiveFile, boolean temporary) {
        if (!temporary && archiveFile.getParentFile() != null) return archiveFile.getParentFile();
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private void extractFromArchive(List<String> paths) {
        Archive current = archive;
        extract(current.file, current.listing, current.password, paths, current.temporary);
    }

    private void extract(final File file, final SevenZip.Listing listing, final String password, final List<String> paths,
                         final boolean temporary) {
        final File home = homeOf(file, temporary);
        final String folderName = FileKit.stripArchiveExtension(file.getName());
        String here = home.equals(storageRoot) ? "الذاكرة الداخلية" : home.getName();
        Sheets.extract(this, file.getName(), folderName, here, paths == null ? 0 : paths.size(), new Sheets.ExtractCallback() {
            @Override public void onExtract(int destination, int overwrite) {
                CompressionService.Task task = new CompressionService.Task();
                task.type = CompressionService.EXTRACT;
                task.archive = file;
                task.listing = listing;
                task.password = password;
                task.selected = paths;
                task.overwrite = overwrite;
                if (destination == 2) {
                    Pending waiting = new Pending();
                    waiting.type = CompressionService.EXTRACT;
                    waiting.task = task;
                    waiting.label = "استخراج هنا";
                    pending = waiting;
                    if (archive != null) { archive = null; selected.clear(); }
                    navigate(home);
                    return;
                }
                task.target = destination == 0 ? new File(home, folderName) : home;
                launch(task);
            }
        });
    }

    private void testArchive(File file, String password) {
        CompressionService.Task task = new CompressionService.Task();
        task.type = CompressionService.TEST;
        task.archive = file;
        task.password = password;
        launch(task);
    }

    private void showArchiveInfo() {
        Archive current = archive;
        SevenZip.Listing listing = current.listing;
        Sheet sheet = new Sheet(this, current.file.getName(), "معلومات الأرشيف");
        long packed = 0;
        for (File part : SevenZip.outputsOf(current.file)) packed += part.length();
        if (packed == 0) packed = current.file.length();
        long total = listing.totalSize();
        Sheets.detail(this, sheet.body, "النوع", listing.type.toUpperCase(Locale.ROOT) + (listing.method.isEmpty() ? "" : "  •  " + listing.method));
        Sheets.detail(this, sheet.body, "المحتوى", listing.fileCount() + " ملف");
        Sheets.detail(this, sheet.body, "الحجم الأصلي", FileKit.size(total));
        Sheets.detail(this, sheet.body, "حجم الأرشيف", FileKit.size(packed)
                + (total > 0 && packed < total ? String.format(Locale.US, "  •  توفير %.1f%%", (1 - packed / (double) total) * 100) : ""));
        Sheets.detail(this, sheet.body, "الحماية", listing.encrypted || current.password != null ? "مشفّر بكلمة مرور" : "بدون كلمة مرور");
        if (listing.solid) Sheets.detail(this, sheet.body, "البنية", "متماسك Solid");
        if (listing.volumes > 1) Sheets.detail(this, sheet.body, "الأجزاء", listing.volumes + " أجزاء");
        Sheets.detail(this, sheet.body, "المسار", current.file.getAbsolutePath());
        sheet.button("إغلاق", false, Ui.text, true, null);
        sheet.show();
    }

    // ------------------------------------------------------------------ file actions

    private void compress(List<File> sources) {
        if (sources.isEmpty()) return;
        Sheets.compress(this, sources, new Sheets.TaskCallback() {
            @Override public void onTask(CompressionService.Task task) { clearSelection(); launch(task); }
        });
    }

    private void beginTransfer(int type, List<File> sources) {
        if (sources.isEmpty()) return;
        Pending waiting = new Pending();
        waiting.type = type;
        waiting.sources = sources;
        waiting.label = (type == CompressionService.MOVE ? "نقل إلى هنا" : "لصق هنا") + " (" + sources.size() + ")";
        pending = waiting;
        clearSelection();
        toast("افتح المجلد المطلوب ثم اضغط «" + (type == CompressionService.MOVE ? "نقل إلى هنا" : "لصق هنا") + "»");
    }

    private void finishPending() {
        Pending waiting = pending;
        if (waiting == null || dir == null) return;
        pending = null;
        CompressionService.Task task;
        if (waiting.type == CompressionService.EXTRACT) task = waiting.task;
        else {
            task = new CompressionService.Task();
            task.type = waiting.type;
            task.sources = waiting.sources;
        }
        task.target = dir;
        refreshChrome();
        launch(task);
    }

    private void confirmDelete(final List<File> sources) {
        if (sources.isEmpty()) return;
        String what = sources.size() == 1 ? "«" + sources.get(0).getName() + "»" : sources.size() + " عنصر";
        Sheet.confirm(this, "حذف نهائي", "سيُحذف " + what + " نهائياً ولا يمكن استرجاعه.", "حذف", true, new Runnable() {
            @Override public void run() {
                CompressionService.Task task = new CompressionService.Task();
                task.type = CompressionService.DELETE;
                task.sources = sources;
                clearSelection();
                launch(task);
            }
        });
    }

    private void rename(final File file) {
        Sheet.input(this, "إعادة تسمية", null, "الاسم الجديد", file.getName(), false, "حفظ", new Sheet.TextCallback() {
            @Override public String onText(String value) {
                String name = value.trim();
                if (name.isEmpty() || name.contains("/")) return "اسم غير صالح.";
                if (name.equals(file.getName())) return null;
                File target = new File(file.getParentFile(), name);
                if (target.exists()) return "يوجد عنصر بهذا الاسم.";
                if (!file.renameTo(target)) return "تعذرت إعادة التسمية.";
                reload();
                return null;
            }
        });
    }

    private void share(List<File> sources) {
        ArrayList<Uri> uris = new ArrayList<>();
        try {
            for (File file : sources) if (file.isFile()) uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", file));
        } catch (Exception e) {
            toast("تعذرت مشاركة هذا الملف");
            return;
        }
        if (uris.isEmpty()) { toast("المشاركة للملفات فقط. اضغط المجلد أولاً ثم شاركه."); return; }
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("*/*");
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "مشاركة"));
    }

    private void showCreate() {
        if (dir == null) return;
        Sheet.input(this, "مجلد جديد", null, "اسم المجلد", null, false, "إنشاء", new Sheet.TextCallback() {
            @Override public String onText(String value) {
                String name = value.trim();
                if (name.isEmpty() || name.contains("/")) return "اسم غير صالح.";
                File folder = new File(dir, name);
                if (folder.exists()) return "يوجد عنصر بهذا الاسم.";
                if (!folder.mkdir()) return "تعذر إنشاء المجلد هنا.";
                reload();
                return null;
            }
        });
    }

    private void showItemActions(final Item item) {
        if (item == null) return;
        Sheet sheet = new Sheet(this, item.name, item.directory ? "مجلد" : FileKit.size(item.size));
        if (item.node != null) {
            final List<String> path = new ArrayList<>();
            path.add(item.node.path);
            if (!item.directory) sheet.action(R.drawable.ic_open, Ui.accent, "فتح", null, new Runnable() {
                @Override public void run() { openEntry(item.node); }
            });
            sheet.action(R.drawable.ic_extract, Ui.accent2, "استخراج…", null, new Runnable() {
                @Override public void run() { extractFromArchive(path); }
            });
            sheet.action(R.drawable.ic_select_all, Ui.textDim, "تحديد", null, new Runnable() {
                @Override public void run() { toggleSelection(item); }
            });
            sheet.show();
            return;
        }
        final File file = item.file;
        boolean isArchive = item.kind == FileKit.ARCHIVE;
        if (isArchive) {
            sheet.action(R.drawable.ic_archive, Ui.violet, "تصفّح الأرشيف", null, new Runnable() {
                @Override public void run() { openArchive(file, null, false); }
            });
            sheet.action(R.drawable.ic_extract, Ui.accent2, "استخراج…", "اختر المكان وطريقة التعامل مع الملفات الموجودة", new Runnable() {
                @Override public void run() { extract(file, null, null, null, false); }
            });
            sheet.action(R.drawable.ic_shield, Ui.success, "فحص سلامة الأرشيف", null, new Runnable() {
                @Override public void run() { testArchive(file, null); }
            });
        } else if (!item.directory) {
            sheet.action(R.drawable.ic_open, Ui.accent, "فتح", null, new Runnable() {
                @Override public void run() { openFile(file); }
            });
            sheet.action(R.drawable.ic_archive, Ui.violet, "فتح كأرشيف", "للملفات مثل APK و DOCX و EXE", new Runnable() {
                @Override public void run() { openArchive(file, null, false); }
            });
        }
        sheet.action(R.drawable.ic_compress, Ui.accent, "ضغط…", "7Z • ZIP • TAR وغيرها", new Runnable() {
            @Override public void run() { compress(one(file)); }
        });
        sheet.action(R.drawable.ic_copy, Ui.text, "نسخ", null, new Runnable() {
            @Override public void run() { beginTransfer(CompressionService.COPY, one(file)); }
        });
        sheet.action(R.drawable.ic_move, Ui.text, "نقل", null, new Runnable() {
            @Override public void run() { beginTransfer(CompressionService.MOVE, one(file)); }
        });
        sheet.action(R.drawable.ic_edit, Ui.text, "إعادة تسمية", null, new Runnable() {
            @Override public void run() { rename(file); }
        });
        if (!item.directory) sheet.action(R.drawable.ic_share, Ui.text, "مشاركة", null, new Runnable() {
            @Override public void run() { share(one(file)); }
        });
        if (item.directory) {
            final boolean favorite = favorites().contains(file.getAbsolutePath());
            sheet.action(R.drawable.ic_star, Ui.warn, favorite ? "إزالة من المفضلة" : "إضافة إلى المفضلة", null, new Runnable() {
                @Override public void run() { setFavorite(file, !favorite); }
            });
        }
        sheet.action(R.drawable.ic_info, Ui.text, "معلومات", null, new Runnable() {
            @Override public void run() { showInfo(file); }
        });
        sheet.action(R.drawable.ic_delete, Ui.danger, "حذف", null, new Runnable() {
            @Override public void run() { confirmDelete(one(file)); }
        });
        sheet.show();
    }

    private void showInfo(final File file) {
        final Sheet sheet = new Sheet(this, file.getName(), file.isDirectory() ? "مجلد" : FileKit.mime(file.getName()));
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView size = Sheets.detail(this, sheet.body, "الحجم", file.isDirectory() ? "جاري الحساب…" : FileKit.size(file.length()));
        Sheets.detail(this, sheet.body, "آخر تعديل", FileKit.date(file.lastModified()));
        Sheets.detail(this, sheet.body, "المسار", file.getAbsolutePath());
        if (file.isDirectory()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final long[] totals = FileKit.measure(file, cancel);
                        main.post(new Runnable() {
                            @Override public void run() { size.setText(FileKit.size(totals[0]) + "  •  " + totals[1] + " ملف"); }
                        });
                    } catch (Exception stopped) { /* sheet closed */ }
                }
            }).start();
        } else {
            final TextView hash = Sheets.detail(this, sheet.body, "بصمة SHA-256", "اضغط «حساب البصمة»");
            sheet.button("حساب البصمة", false, Ui.accent, false, new Runnable() {
                @Override public void run() {
                    hash.setText("جاري الحساب…");
                    new Thread(new Runnable() {
                        @Override public void run() {
                            String value;
                            try { value = FileKit.hash(file, "SHA-256", cancel); }
                            catch (Exception error) { value = "تعذر الحساب"; }
                            final String text = value;
                            main.post(new Runnable() { @Override public void run() { hash.setText(text); } });
                        }
                    }).start();
                }
            });
        }
        sheet.button("إغلاق", false, Ui.text, true, null);
        sheet.dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) { cancel.set(true); }
        });
        sheet.show();
    }

    // ------------------------------------------------------------------ menus

    private void showSort() {
        Sheet sheet = new Sheet(this, "ترتيب حسب", null);
        String[] names = {"الاسم", "تاريخ التعديل", "الحجم", "النوع"};
        int[] icons = {R.drawable.ic_sort, R.drawable.ic_sort, R.drawable.ic_sort, R.drawable.ic_sort};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            boolean current = sortMode == i;
            sheet.action(current ? R.drawable.ic_check : icons[i], current ? Ui.accent : Ui.textDim, names[i],
                    current ? (sortDescending ? "تنازلي • اضغط للعكس" : "تصاعدي • اضغط للعكس") : null, new Runnable() {
                        @Override public void run() {
                            sortDescending = sortMode == mode ? !sortDescending : mode == SORT_DATE || mode == SORT_SIZE;
                            sortMode = mode;
                            prefs.edit().putInt("sort", sortMode).putBoolean("sortDesc", sortDescending).apply();
                            applyFilter();
                        }
                    });
        }
        sheet.show();
    }

    private void showMore() {
        Sheet sheet = new Sheet(this, archive != null ? archive.file.getName() : "خيارات", null);
        if (archive != null) {
            sheet.action(R.drawable.ic_info, Ui.accent, "معلومات الأرشيف", null, new Runnable() {
                @Override public void run() { showArchiveInfo(); }
            });
            sheet.action(R.drawable.ic_shield, Ui.success, "فحص سلامة الأرشيف", null, new Runnable() {
                @Override public void run() { testArchive(archive.file, archive.password); }
            });
            sheet.action(R.drawable.ic_select_all, Ui.text, "تحديد الكل", null, new Runnable() {
                @Override public void run() { selectAll(); }
            });
            sheet.action(R.drawable.ic_close, Ui.text, "إغلاق الأرشيف", null, new Runnable() {
                @Override public void run() { closeArchive(); }
            });
            sheet.show();
            return;
        }
        sheet.action(R.drawable.ic_folder_add, Ui.accent, "مجلد جديد", null, new Runnable() {
            @Override public void run() { showCreate(); }
        });
        if (!all.isEmpty()) {
            sheet.action(R.drawable.ic_select_all, Ui.text, "تحديد الكل", null, new Runnable() {
                @Override public void run() { selectAll(); }
            });
            sheet.action(R.drawable.ic_compress, Ui.violet, "ضغط كل محتويات هذا المجلد", null, new Runnable() {
                @Override public void run() {
                    List<File> files = new ArrayList<>();
                    for (Item item : all) if (item.file != null) files.add(item.file);
                    compress(files);
                }
            });
        }
        sheet.action(R.drawable.ic_eye, Ui.text, showHidden ? "إخفاء الملفات المخفية" : "إظهار الملفات المخفية", null, new Runnable() {
            @Override public void run() {
                showHidden = !showHidden;
                prefs.edit().putBoolean("hidden", showHidden).apply();
                loadDirectory(false);
            }
        });
        sheet.action(R.drawable.ic_phone, Ui.text, "الأماكن والمفضلة", null, new Runnable() {
            @Override public void run() { showPlaces(); }
        });
        sheet.action(R.drawable.ic_tune, Ui.text, "المظهر وحول التطبيق", null, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        sheet.show();
    }

    private void showPlaces() {
        Sheet sheet = new Sheet(this, "الأماكن", null);
        for (final File root : storageRoots()) {
            boolean internal = root.equals(storageRoot);
            sheet.action(internal ? R.drawable.ic_phone : R.drawable.ic_sd, Ui.accent, internal ? "الذاكرة الداخلية" : "بطاقة الذاكرة",
                    freeSpace(root, false), new Runnable() { @Override public void run() { navigate(root); } });
        }
        String[] names = {"التنزيلات", "الكاميرا", "الصور", "المستندات", "الموسيقى", "الفيديو"};
        String[] folders = {Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES};
        int[] icons = {R.drawable.ic_download, R.drawable.ic_image, R.drawable.ic_image, R.drawable.ic_doc, R.drawable.ic_audio, R.drawable.ic_video};
        for (int i = 0; i < names.length; i++) {
            final File place = Environment.getExternalStoragePublicDirectory(folders[i]);
            if (!place.isDirectory()) continue;
            sheet.action(icons[i], Ui.textDim, names[i], null, new Runnable() {
                @Override public void run() { navigate(place); }
            });
        }
        Set<String> saved = favorites();
        if (!saved.isEmpty()) sheet.body.addView(Ui.sectionLabel(this, "المفضلة"));
        for (final String path : saved) {
            final File favorite = new File(path);
            sheet.action(R.drawable.ic_star, Ui.warn, favorite.getName(), path, new Runnable() {
                @Override public void run() {
                    if (favorite.isDirectory()) navigate(favorite);
                    else { setFavorite(favorite, false); toast("المجلد لم يعد موجوداً، أزيل من المفضلة"); }
                }
            });
        }
        sheet.show();
    }

    private Set<String> favorites() {
        return new TreeSet<>(prefs.getStringSet("favorites", new LinkedHashSet<String>()));
    }

    private void setFavorite(File folder, boolean add) {
        Set<String> saved = favorites();
        if (add) saved.add(folder.getAbsolutePath()); else saved.remove(folder.getAbsolutePath());
        prefs.edit().putStringSet("favorites", saved).apply();
        toast(add ? "أضيف إلى المفضلة" : "أزيل من المفضلة");
    }

    private void showSettings() {
        Sheet sheet = new Sheet(this, "PackX Pro", "الإصدار " + versionName());
        sheet.body.addView(Ui.sectionLabel(this, "المظهر"));
        String[] names = {"حسب النظام", "داكن", "فاتح"};
        int current = prefs.getInt("theme", Ui.THEME_AUTO);
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            TextView chip = Ui.chip(this, names[i], i == current);
            LinearLayout.LayoutParams params = Ui.weight();
            if (i > 0) params.setMarginStart(dp(8));
            row.addView(chip, params);
            final Sheet owner = sheet;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt("theme", mode).apply();
                    owner.dismiss();
                    recreate();
                }
            });
        }
        sheet.body.addView(row, Ui.fill());
        sheet.body.addView(Ui.sectionLabel(this, "حول"));
        TextView about = Ui.text(this, "مدير ملفات وأرشيفات عربي.\n"
                + "يفتح: 7Z • ZIP • RAR • TAR • GZ • BZ2 • XZ • ZST • ISO • CAB • ARJ • LZH • WIM • DEB • RPM وغيرها.\n"
                + "ينشئ: 7Z • ZIP • TAR • TAR.GZ • TAR.BZ2 • TAR.XZ مع تشفير AES-256 وتقسيم إلى أجزاء.\n\n"
                + "محرك الضغط: 7-Zip 26.03 © Igor Pavlov (رخصة LGPL).\n"
                + "المطور: مهند سات لنك", 13.5f, Ui.textDim, false);
        about.setLineSpacing(dp(3), 1f);
        sheet.body.addView(about, Ui.fill());
        sheet.button("إغلاق", false, Ui.text, true, null);
        sheet.show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ running operations

    private void launch(CompressionService.Task task) {
        if (!CompressionService.engine(this).isAvailable()
                && task.type != CompressionService.COPY && task.type != CompressionService.MOVE && task.type != CompressionService.DELETE) {
            Sheet.message(this, "محرك الضغط غير متاح", "لم يُعثر على محرك 7-Zip لهذا الجهاز. أعد تثبيت التطبيق.");
            return;
        }
        if (!CompressionService.start(this, task)) { toast("توجد عملية جارية. انتظر حتى تنتهي."); return; }
        showProgress();
    }

    private void showProgress() {
        if (progressSheet != null && progressSheet.dialog.isShowing()) return;
        Sheet sheet = new Sheet(this, CompressionService.lastTitle, null).cancelable(false);
        progressRing = new RingView(this);
        progressRing.setProgress(CompressionService.lastPercent);
        LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(dp(168), dp(168));
        ringParams.gravity = Gravity.CENTER_HORIZONTAL;
        ringParams.topMargin = dp(8);
        sheet.body.addView(progressRing, ringParams);
        progressCurrent = Ui.oneLine(Ui.text(this, CompressionService.lastCurrent, 13, Ui.textDim, false));
        progressCurrent.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        progressCurrent.setPadding(0, dp(12), 0, 0);
        sheet.body.addView(progressCurrent, Ui.fill());
        TextView note = Ui.text(this, "يمكنك مغادرة التطبيق، ستستمر العملية في الخلفية.", 12.5f, Ui.textDim, false);
        note.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        note.setPadding(0, dp(6), 0, 0);
        sheet.body.addView(note, Ui.fill());
        sheet.button("إخفاء", false, Ui.text, true, null);
        sheet.button("إلغاء العملية", false, Ui.danger, false, new Runnable() {
            @Override public void run() { CompressionService.cancel(); progressSheet.title("جاري الإلغاء…"); }
        });
        progressSheet = sheet;
        sheet.show();
    }

    @Override public void onProgress(String title, int percent, String current) {
        if (progressSheet == null || !progressSheet.dialog.isShowing()) return;
        progressSheet.title(title);
        progressRing.setProgress(percent);
        if (current != null && !current.isEmpty()) progressCurrent.setText(current);
    }

    @Override public void onFinished(final CompressionService.Result result) {
        if (progressSheet != null) { progressSheet.dismiss(); progressSheet = null; }
        if (isDestroyed()) return;
        final CompressionService.Task task = result.task;
        if (result.passwordNeeded) {
            Sheets.password(this, task.archive.getName(), result.passwordWrong, new Sheet.TextCallback() {
                @Override public String onText(String value) {
                    if (value.isEmpty()) return "أدخل كلمة المرور.";
                    task.password = value;
                    if (archive != null && archive.file.equals(task.archive)) archive.password = value;
                    launch(task);
                    return null;
                }
            });
            return;
        }
        if (result.cancelled) { toast("تم إلغاء العملية"); reloadFiles(); return; }
        if (!result.success) { Sheet.message(this, result.title, result.detail); reloadFiles(); return; }
        switch (task.type) {
            case CompressionService.COMPRESS:
                reloadFiles();
                Sheets.compressResult(this, result);
                break;
            case CompressionService.EXTRACT: {
                reloadFiles();
                Sheet sheet = new Sheet(this, "اكتمل فك الضغط", null);
                sheet.body.addView(Sheets.badge(this, R.drawable.ic_check, Ui.success, "استُخرجت الملفات بنجاح"), Ui.fill());
                Sheets.detail(this, sheet.body, "المكان", task.target.getAbsolutePath());
                sheet.button("تم", false, Ui.text, true, null);
                sheet.button("فتح المجلد", true, 0, true, new Runnable() {
                    @Override public void run() { navigate(task.target); }
                });
                sheet.show();
                break;
            }
            case CompressionService.TEST: {
                Sheet sheet = new Sheet(this, "الأرشيف سليم", task.archive.getName());
                sheet.body.addView(Sheets.badge(this, R.drawable.ic_shield, Ui.success, "اجتازت كل الملفات فحص السلامة"), Ui.fill());
                sheet.button("تم", true, 0, true, null);
                sheet.show();
                break;
            }
            case CompressionService.OPEN_ENTRY:
                if (result.opened != null) openFile(result.opened);
                break;
            default:
                toast(result.title);
                reloadFiles();
        }
    }

    /** Refreshes the folder listing after an operation without leaving an open archive. */
    private void reloadFiles() {
        if (archive == null && dir != null) loadDirectory(true);
    }
}
