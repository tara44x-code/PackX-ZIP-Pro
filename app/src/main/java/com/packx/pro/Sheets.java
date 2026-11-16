package com.packx.pro;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.packx.pro.compression.ArchiveFormat;
import com.packx.pro.compression.SevenZip;
import com.packx.pro.ui.RingView;
import com.packx.pro.ui.Sheet;
import com.packx.pro.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The larger bottom sheets: compression options, extraction options, results and details. */
final class Sheets {
    interface TaskCallback { void onTask(CompressionService.Task task); }

    interface ExtractCallback {
        /** @param destination 0 = new folder named after the archive, 1 = next to the archive, 2 = let the user pick. */
        void onExtract(int destination, int overwrite);
    }

    private static final String[] LEVEL_NAMES = {"تخزين", "سريع", "متوازن", "قوي", "أقصى"};
    private static final String[] LEVEL_HINTS = {
            "بدون ضغط: تجميع فوري للملفات المضغوطة أصلاً مثل الفيديو والصور.",
            "سريع جداً مع ضغط خفيف.",
            "توازن جيد بين الحجم والوقت.",
            "حجم أصغر بوقت معقول. مناسب لأغلب الملفات.",
            "أصغر حجم ممكن. أبطأ ويستهلك ذاكرة أكثر."};
    private static final long MB = 1024L * 1024;
    private static final long[] VOLUME_SIZES = {0, 25 * MB, 100 * MB, 700 * MB, 2000 * MB, 4000 * MB};
    private static final String[] VOLUME_NAMES = {"بدون", "25 MB", "100 MB", "700 MB", "2 GB", "4 GB"};

    private Sheets() { }

    // ------------------------------------------------------------------ compress

    static void compress(final Context context, final List<File> sources, final TaskCallback callback) {
        final File parent = sources.get(0).getParentFile();
        long files = 0;
        for (File source : sources) if (source.isFile()) files++;
        String what = sources.size() == 1 ? sources.get(0).getName()
                : sources.size() + " عنصر" + (files == sources.size() ? "" : " (ملفات ومجلدات)");
        final Sheet sheet = new Sheet(context, "إنشاء أرشيف", what);
        LinearLayout body = sheet.body;

        String suggested;
        if (sources.size() == 1) {
            File only = sources.get(0);
            String name = only.getName();
            int dot = name.lastIndexOf('.');
            suggested = only.isFile() && dot > 0 ? name.substring(0, dot) : name;
        } else suggested = parent == null || parent.getName().isEmpty() || parent.getName().equals("0") ? "أرشيف" : parent.getName();
        body.addView(Ui.sectionLabel(context, "اسم الأرشيف"));
        final EditText name = Sheet.field(context, "الاسم", false);
        name.setText(suggested);
        body.addView(name, Ui.fill());

        // Formats: any combination can be produced in one go.
        body.addView(Ui.sectionLabel(context, "الصيغة • يمكنك اختيار أكثر من واحدة"));
        final ArchiveFormat[] formats = ArchiveFormat.values();
        final Set<ArchiveFormat> chosen = new LinkedHashSet<>();
        chosen.add(ArchiveFormat.SEVEN_Z);
        final TextView formatHint = Ui.text(context, "", 12.5f, Ui.textDim, false);
        final TextView[] formatChips = new TextView[formats.length];
        LinearLayout formatRow = null;
        for (int i = 0; i < formats.length; i++) {
            if (i % 3 == 0) {
                formatRow = Ui.row(context);
                body.addView(formatRow, Ui.margins(context, Ui.fill(), 0, i == 0 ? 0 : 8, 0, 0));
            }
            final ArchiveFormat format = formats[i];
            final TextView chip = Ui.chip(context, format.label, chosen.contains(format));
            formatChips[i] = chip;
            LinearLayout.LayoutParams params = Ui.weight();
            if (i % 3 != 0) params.setMarginStart(Ui.dp(context, 8));
            formatRow.addView(chip, params);
        }
        formatHint.setPadding(0, Ui.dp(context, 8), 0, 0);
        body.addView(formatHint, Ui.fill());

        // Level.
        body.addView(Ui.sectionLabel(context, "قوة الضغط"));
        final int[] level = {SevenZip.LEVEL_MAX};
        final TextView[] levelChips = new TextView[LEVEL_NAMES.length];
        final TextView levelHint = Ui.text(context, LEVEL_HINTS[level[0]], 12.5f, Ui.textDim, false);
        LinearLayout levelRow = Ui.row(context);
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(context, LEVEL_NAMES[i], i == level[0]);
            chip.setTextSize(12.5f);
            chip.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0);
            levelChips[i] = chip;
            LinearLayout.LayoutParams params = Ui.weight();
            if (i > 0) params.setMarginStart(Ui.dp(context, 6));
            levelRow.addView(chip, params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    level[0] = index;
                    for (int k = 0; k < levelChips.length; k++) Ui.setChip(levelChips[k], k == index);
                    levelHint.setText(LEVEL_HINTS[index]);
                }
            });
        }
        body.addView(levelRow, Ui.fill());
        levelHint.setPadding(0, Ui.dp(context, 8), 0, 0);
        body.addView(levelHint, Ui.fill());

        // Options.
        body.addView(Ui.sectionLabel(context, "خيارات"));
        final Switch solid = Sheet.toggle(context, body, R.drawable.ic_bolt, "أرشيف متماسك Solid",
                "حجم أصغر عند وجود ملفات متشابهة • 7Z", true);
        final Switch verify = Sheet.toggle(context, body, R.drawable.ic_shield, "فحص السلامة بعد الضغط",
                "يتأكد أن الأرشيف يُفتح وكل ملف فيه سليم", true);
        final Switch protect = Sheet.toggle(context, body, R.drawable.ic_lock, "حماية بكلمة مرور",
                "تشفير AES-256 • مع 7Z و ZIP", false);
        final LinearLayout secret = Ui.column(context);
        secret.setVisibility(View.GONE);
        LinearLayout passwordRow = Ui.row(context);
        final EditText password = Sheet.field(context, "كلمة المرور", true);
        passwordRow.addView(password, Ui.weight());
        final ImageView reveal = Ui.iconButton(context, R.drawable.ic_eye, Ui.textDim);
        passwordRow.addView(reveal, new LinearLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 46)));
        secret.addView(passwordRow, Ui.margins(context, Ui.fill(), 0, 4, 0, 0));
        final TextView strength = Ui.text(context, "", 12.5f, Ui.textDim, false);
        strength.setPadding(0, Ui.dp(context, 6), 0, 0);
        secret.addView(strength, Ui.fill());
        final Switch hideNames = Sheet.toggle(context, secret, R.drawable.ic_eye, "إخفاء أسماء الملفات",
                "لا يظهر أي اسم قبل إدخال كلمة المرور • 7Z", true);
        body.addView(secret, Ui.fill());
        protect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                secret.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });
        reveal.setOnClickListener(new View.OnClickListener() {
            boolean visible;
            @Override public void onClick(View v) {
                visible = !visible;
                int position = password.getSelectionEnd();
                password.setInputType(InputType.TYPE_CLASS_TEXT | (visible
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                password.setSelection(Math.max(0, Math.min(position, password.length())));
                Ui.setIcon(reveal, R.drawable.ic_eye, visible ? Ui.accent : Ui.textDim);
            }
        });
        password.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                int score = passwordScore(s.toString());
                String[] words = {"ضعيفة جداً", "ضعيفة", "متوسطة", "جيدة", "قوية", "قوية جداً"};
                int[] colors = {Ui.danger, Ui.danger, Ui.warn, Ui.warn, Ui.success, Ui.success};
                strength.setText(s.length() == 0 ? "" : "قوة كلمة المرور: " + words[score]);
                strength.setTextColor(colors[score]);
            }
        });

        // Volumes.
        body.addView(Ui.sectionLabel(context, "تقسيم إلى أجزاء • 7Z و ZIP و TAR"));
        final int[] volume = {0};
        final TextView[] volumeChips = new TextView[VOLUME_NAMES.length];
        LinearLayout volumeRow = Ui.row(context);
        for (int i = 0; i < VOLUME_NAMES.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(context, VOLUME_NAMES[i], i == 0);
            volumeChips[i] = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) params.setMarginStart(Ui.dp(context, 8));
            volumeRow.addView(chip, params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    volume[0] = index;
                    for (int k = 0; k < volumeChips.length; k++) Ui.setChip(volumeChips[k], k == index);
                }
            });
        }
        HorizontalScrollView volumeScroll = new HorizontalScrollView(context);
        volumeScroll.setHorizontalScrollBarEnabled(false);
        volumeScroll.addView(volumeRow);
        body.addView(volumeScroll, Ui.fill());

        final TextView error = Ui.text(context, "", 13, Ui.danger, false);
        error.setPadding(0, Ui.dp(context, 12), 0, 0);
        error.setVisibility(View.GONE);
        body.addView(error, Ui.fill());

        final Runnable describeFormats = new Runnable() {
            @Override public void run() {
                StringBuilder text = new StringBuilder();
                for (ArchiveFormat format : chosen) {
                    if (text.length() > 0) text.append('\n');
                    text.append(format.label).append(": ").append(format.hint);
                }
                if (chosen.size() > 1) text.append("\nسيُنشأ أرشيف لكل صيغة وتُعرض مقارنة الأحجام في النهاية.");
                formatHint.setText(text.length() == 0 ? "اختر صيغة واحدة على الأقل." : text.toString());
            }
        };
        describeFormats.run();
        for (int i = 0; i < formats.length; i++) {
            final ArchiveFormat format = formats[i];
            final TextView chip = formatChips[i];
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!chosen.remove(format)) chosen.add(format);
                    Ui.setChip(chip, chosen.contains(format));
                    describeFormats.run();
                }
            });
        }

        sheet.button("إلغاء", false, Ui.text, true, null);
        sheet.button("بدء الضغط", true, 0, false, new Runnable() {
            @Override public void run() {
                String base = name.getText().toString().trim();
                String secretText = protect.isChecked() ? password.getText().toString() : null;
                String problem = null;
                if (base.isEmpty()) problem = "اكتب اسم الأرشيف.";
                else if (base.contains("/") || base.contains("\\") || base.equals(".") || base.equals("..")) problem = "الاسم يحتوي رموزاً غير مسموحة.";
                else if (chosen.isEmpty()) problem = "اختر صيغة واحدة على الأقل.";
                else if (secretText != null && secretText.length() < 4) problem = "كلمة المرور قصيرة. استخدم 4 أحرف على الأقل.";
                else if (secretText != null) {
                    for (ArchiveFormat format : chosen) {
                        if (!format.supportsPassword) { problem = "كلمة المرور متاحة مع 7Z و ZIP فقط. أزل " + format.label + " أو ألغِ الحماية."; break; }
                        if (format == ArchiveFormat.ZIP && !SevenZip.isAscii(secretText)) { problem = "كلمة مرور ZIP يجب أن تكون بأحرف إنجليزية وأرقام ورموز. استخدم 7Z لكلمة مرور عربية."; break; }
                    }
                }
                if (problem != null) { error.setText(problem); error.setVisibility(View.VISIBLE); return; }
                CompressionService.Task task = new CompressionService.Task();
                task.type = CompressionService.COMPRESS;
                task.workDir = parent;
                for (File source : sources) task.names.add(source.getName());
                task.outputDir = parent;
                task.baseName = base;
                task.formats = new ArrayList<>(chosen);
                task.level = level[0];
                task.password = secretText;
                task.hideNames = hideNames.isChecked();
                task.solid = solid.isChecked();
                task.verify = verify.isChecked();
                task.volumeBytes = VOLUME_SIZES[volume[0]];
                sheet.dismiss();
                callback.onTask(task);
            }
        });
        sheet.show();
    }

    private static int passwordScore(String value) {
        int score = 0;
        if (value.length() >= 8) score++;
        if (value.length() >= 12) score++;
        if (value.matches(".*[a-z].*") && value.matches(".*[A-Z].*")) score++;
        if (value.matches(".*[0-9].*")) score++;
        if (value.matches(".*[^A-Za-z0-9].*")) score++;
        return Math.min(5, value.length() < 6 ? Math.min(score, 1) : score);
    }

    // ------------------------------------------------------------------ extract

    static void extract(Context context, String archiveName, String folderName, String hereName, int entryCount,
                        final ExtractCallback callback) {
        final Sheet sheet = new Sheet(context, "استخراج", entryCount > 0 ? archiveName + " • " + entryCount + " عنصر محدد" : archiveName);
        LinearLayout body = sheet.body;
        body.addView(Ui.sectionLabel(context, "المكان"));
        final int[] destination = {0};
        String[] titles = {"مجلد جديد", "هنا مباشرة", "اختيار مجلد آخر…"};
        String[] hints = {folderName + "/", "داخل " + hereName, "تصفّح ثم اضغط «استخراج هنا»"};
        int[] icons = {R.drawable.ic_folder_add, R.drawable.ic_download, R.drawable.ic_folder};
        final LinearLayout[] rows = new LinearLayout[titles.length];
        final ImageView[] marks = new ImageView[titles.length];
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout row = Ui.row(context);
            row.setMinimumHeight(Ui.dp(context, 58));
            row.setPaddingRelative(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8));
            row.addView(Ui.icon(context, icons[i], Ui.accent), new LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)));
            LinearLayout texts = Ui.column(context);
            texts.setPaddingRelative(Ui.dp(context, 14), 0, Ui.dp(context, 8), 0);
            texts.addView(Ui.text(context, titles[i], 15, Ui.text, true));
            texts.addView(Ui.oneLine(Ui.text(context, hints[i], 12.5f, Ui.textDim, false)));
            row.addView(texts, Ui.weight());
            marks[i] = Ui.icon(context, R.drawable.ic_check, Ui.accent);
            row.addView(marks[i], new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)));
            rows[i] = row;
            body.addView(row, Ui.margins(context, Ui.fill(), 0, i == 0 ? 0 : 8, 0, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { destination[0] = index; markChoice(rows, marks, index); }
            });
        }
        markChoice(rows, marks, 0);

        body.addView(Ui.sectionLabel(context, "إذا كان الملف موجوداً"));
        final int[] overwrite = {SevenZip.OVERWRITE};
        String[] modes = {"استبدال", "تخطّي", "إبقاء الاثنين"};
        final TextView[] chips = new TextView[modes.length];
        LinearLayout modeRow = Ui.row(context);
        for (int i = 0; i < modes.length; i++) {
            final int index = i;
            chips[i] = Ui.chip(context, modes[i], i == 0);
            LinearLayout.LayoutParams params = Ui.weight();
            if (i > 0) params.setMarginStart(Ui.dp(context, 8));
            modeRow.addView(chips[i], params);
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    overwrite[0] = index;
                    for (int k = 0; k < chips.length; k++) Ui.setChip(chips[k], k == index);
                }
            });
        }
        body.addView(modeRow, Ui.fill());
        sheet.button("إلغاء", false, Ui.text, true, null);
        sheet.button("استخراج", true, 0, true, new Runnable() {
            @Override public void run() { callback.onExtract(destination[0], overwrite[0]); }
        });
        sheet.show();
    }

    private static void markChoice(LinearLayout[] rows, ImageView[] marks, int selected) {
        for (int i = 0; i < rows.length; i++) {
            Context context = rows[i].getContext();
            boolean on = i == selected;
            rows[i].setBackground(Ui.ripple(context, on
                    ? Ui.outlined(context, Ui.alpha(Ui.accent, Ui.dark ? 44 : 24), Ui.accent, 16)
                    : Ui.outlined(context, Ui.surface2, Ui.stroke, 16), 16));
            marks[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ------------------------------------------------------------------ password

    static void password(Context context, String archiveName, boolean wrong, Sheet.TextCallback callback) {
        Sheet.input(context, wrong ? "كلمة المرور غير صحيحة" : "أرشيف محمي",
                wrong ? "حاول مرة أخرى: " + archiveName : archiveName + " يحتاج كلمة مرور.",
                "كلمة المرور", null, true, "متابعة", callback);
    }

    // ------------------------------------------------------------------ results

    static void compressResult(Context context, CompressionService.Result result) {
        Sheet sheet = new Sheet(context, "اكتمل الضغط", null);
        LinearLayout body = sheet.body;
        long best = Long.MAX_VALUE;
        for (long size : result.sizes) best = Math.min(best, size);
        RingView ring = new RingView(context);
        ring.finish(Ui.success, "✓");
        LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(Ui.dp(context, 112), Ui.dp(context, 112));
        ringParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(ring, ringParams);
        TextView headline = Ui.text(context, FileKit.size(result.originalBytes) + "  →  " + FileKit.size(best), 20, Ui.text, true);
        headline.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        headline.setTextDirection(View.TEXT_DIRECTION_LTR);
        headline.setPadding(0, Ui.dp(context, 10), 0, 0);
        body.addView(headline, Ui.fill());
        TextView saving = Ui.text(context, savingText(result.originalBytes, best), 14, Ui.success, true);
        saving.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        saving.setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 12));
        body.addView(saving, Ui.fill());

        for (int i = 0; i < result.outputs.size(); i++) {
            long size = result.sizes.get(i);
            LinearLayout row = Ui.row(context);
            row.setPadding(Ui.dp(context, 14), Ui.dp(context, 11), Ui.dp(context, 14), Ui.dp(context, 11));
            boolean winner = result.outputs.size() > 1 && size == best;
            row.setBackground(Ui.outlined(context, winner ? Ui.alpha(Ui.success, 30) : Ui.surface2, winner ? Ui.success : Ui.stroke, 14));
            LinearLayout texts = Ui.column(context);
            texts.addView(Ui.oneLine(Ui.text(context, result.outputs.get(i).getName().replaceFirst("\\.001$", ""), 14.5f, Ui.text, true)));
            texts.addView(Ui.text(context, result.labels.get(i) + (winner ? "  •  الأصغر" : ""), 12.5f, winner ? Ui.success : Ui.textDim, false));
            row.addView(texts, Ui.weight());
            TextView sizeText = Ui.text(context, FileKit.size(size), 14.5f, Ui.text, true);
            sizeText.setPaddingRelative(Ui.dp(context, 10), 0, 0, 0);
            row.addView(sizeText);
            body.addView(row, Ui.margins(context, Ui.fill(), 0, 8, 0, 0));
        }
        if (result.verified) body.addView(badge(context, R.drawable.ic_shield, Ui.success, "تم فحص الأرشيف: كل الملفات سليمة"),
                Ui.margins(context, Ui.fill(), 0, 14, 0, 0));
        if (result.task.password != null) body.addView(badge(context, R.drawable.ic_lock, Ui.accent, "محمي بتشفير AES-256"),
                Ui.margins(context, Ui.fill(), 0, 8, 0, 0));
        sheet.button("تم", true, 0, true, null);
        sheet.show();
    }

    private static String savingText(long original, long packed) {
        if (original <= 0) return "";
        double saved = (1.0 - packed / (double) original) * 100.0;
        if (saved < 0.5) return "الملفات مضغوطة أصلاً، لذلك لم يصغر الحجم";
        return String.format(Locale.US, "تم توفير %.1f%% من الحجم", saved);
    }

    static View badge(Context context, int icon, int color, CharSequence label) {
        LinearLayout row = Ui.row(context);
        row.addView(Ui.icon(context, icon, color), new LinearLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20)));
        TextView text = Ui.text(context, label, 13.5f, color, false);
        text.setPaddingRelative(Ui.dp(context, 10), 0, 0, 0);
        row.addView(text, Ui.weight());
        return row;
    }

    // ------------------------------------------------------------------ details

    /** Adds a "label: value" line; tapping copies the value. */
    static TextView detail(final Context context, LinearLayout parent, final String label, final String value) {
        LinearLayout row = Ui.column(context);
        row.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8));
        row.addView(Ui.text(context, label, 12, Ui.textDim, false));
        final TextView text = Ui.text(context, value, 14.5f, Ui.text, false);
        text.setPadding(0, Ui.dp(context, 2), 0, 0);
        row.addView(text);
        row.setBackground(Ui.touch(context, 10));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text.getText()));
                Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show();
            }
        });
        parent.addView(row, Ui.fill());
        return text;
    }
}
