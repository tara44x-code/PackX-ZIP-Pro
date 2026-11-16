package com.packx.pro.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** PackX bottom sheet: a rounded panel that slides up, replacing stock alert dialogs. */
public final class Sheet {
    public interface TextCallback {
        /** @return an error to show under the field, or null to accept and close. */
        String onText(String value);
    }

    public final Dialog dialog;
    public final LinearLayout body;
    public final LinearLayout footer;
    private final Context context;
    private final TextView titleView;
    private final TextView subtitleView;

    public Sheet(Context context, CharSequence title, CharSequence subtitle) {
        this.context = context;
        dialog = new Dialog(context, Ui.dark ? android.R.style.Theme_Material_Dialog : android.R.style.Theme_Material_Light_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout panel = Ui.column(context);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.surface);
        float r = Ui.dp(context, 26);
        background.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(background);
        final int side = Ui.dp(context, 20);
        panel.setPadding(side, Ui.dp(context, 10), side, Ui.dp(context, 18));

        View handle = new View(context);
        handle.setBackground(Ui.round(context, Ui.stroke, 3));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Ui.dp(context, 42), Ui.dp(context, 5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = Ui.dp(context, 12);
        panel.addView(handle, handleParams);

        titleView = Ui.text(context, title == null ? "" : title, 19, Ui.text, true);
        Ui.oneLine(titleView);
        titleView.setVisibility(title == null ? View.GONE : View.VISIBLE);
        panel.addView(titleView, Ui.fill());
        subtitleView = Ui.text(context, subtitle == null ? "" : subtitle, 13, Ui.textDim, false);
        subtitleView.setPadding(0, Ui.dp(context, 3), 0, 0);
        subtitleView.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        panel.addView(subtitleView, Ui.fill());

        body = Ui.column(context);
        body.setPadding(0, Ui.dp(context, 8), 0, 0);
        ScrollView scroll = new LimitedScroll(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));

        footer = Ui.row(context);
        footer.setPadding(0, Ui.dp(context, 14), 0, 0);
        footer.setVisibility(View.GONE);
        panel.addView(footer, Ui.fill());

        panel.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                view.setPadding(side, Ui.dp(view.getContext(), 10), side,
                        Ui.dp(view.getContext(), 18) + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.6f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
    }

    public Sheet title(CharSequence title) { titleView.setText(title); titleView.setVisibility(View.VISIBLE); return this; }

    public Sheet subtitle(CharSequence subtitle) {
        subtitleView.setText(subtitle == null ? "" : subtitle);
        subtitleView.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        return this;
    }

    public Sheet add(View view) { body.addView(view, Ui.fill()); return this; }

    /** A tappable row with an icon; the sheet closes before the action runs. */
    public View action(int icon, int tint, CharSequence label, CharSequence hint, final Runnable onClick) {
        LinearLayout row = Ui.row(context);
        row.setMinimumHeight(Ui.dp(context, 54));
        row.setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
        row.setBackground(Ui.touch(context, 14));
        ImageView image = Ui.icon(context, icon, tint);
        int pad = Ui.dp(context, 9);
        image.setPadding(pad, pad, pad, pad);
        image.setBackground(Ui.round(context, Ui.alpha(tint, 34), 12));
        row.addView(image, new LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 40)));
        LinearLayout texts = Ui.column(context);
        texts.setPaddingRelative(Ui.dp(context, 14), 0, 0, 0);
        texts.addView(Ui.oneLine(Ui.text(context, label, 15.5f, Ui.text, false)));
        if (hint != null) texts.addView(Ui.oneLine(Ui.text(context, hint, 12.5f, Ui.textDim, false)));
        row.addView(texts, Ui.weight());
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); if (onClick != null) onClick.run(); }
        });
        body.addView(row, Ui.fill());
        return row;
    }

    /** Adds a button to the pinned footer. Buttons share the width equally. */
    public TextView button(CharSequence label, boolean primary, int color, final boolean dismiss, final Runnable onClick) {
        TextView button = primary ? Ui.primaryButton(context, label) : Ui.secondaryButton(context, label, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, primary ? 1.4f : 1f);
        if (footer.getChildCount() > 0) params.setMarginStart(Ui.dp(context, 10));
        footer.addView(button, params);
        footer.setVisibility(View.VISIBLE);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (dismiss) dialog.dismiss(); if (onClick != null) onClick.run(); }
        });
        return button;
    }

    public Sheet cancelable(boolean value) {
        dialog.setCancelable(value);
        dialog.setCanceledOnTouchOutside(value);
        return this;
    }

    public Sheet show() { dialog.show(); return this; }

    public void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }

    // ------------------------------------------------------------------ ready-made sheets

    public static Sheet message(Context context, CharSequence title, CharSequence message) {
        Sheet sheet = new Sheet(context, title, null);
        TextView text = Ui.text(context, message, 14.5f, Ui.textDim, false);
        text.setLineSpacing(Ui.dp(context, 3), 1f);
        text.setTextIsSelectable(true);
        sheet.add(text);
        sheet.button("حسناً", false, Ui.text, true, null);
        return sheet.show();
    }

    public static Sheet confirm(Context context, CharSequence title, CharSequence message, CharSequence positive,
                                boolean destructive, Runnable onConfirm) {
        Sheet sheet = new Sheet(context, title, null);
        TextView text = Ui.text(context, message, 14.5f, Ui.textDim, false);
        text.setLineSpacing(Ui.dp(context, 3), 1f);
        sheet.add(text);
        sheet.button("إلغاء", false, Ui.text, true, null);
        if (destructive) sheet.button(positive, false, Ui.danger, true, onConfirm);
        else sheet.button(positive, true, 0, true, onConfirm);
        return sheet.show();
    }

    public static Sheet input(Context context, CharSequence title, CharSequence message, String hint, String initial,
                              boolean password, CharSequence positive, final TextCallback callback) {
        final Sheet sheet = new Sheet(context, title, message);
        final EditText field = field(context, hint, password);
        if (initial != null) {
            field.setText(initial);
            int dot = initial.lastIndexOf('.');
            field.setSelection(0, dot > 0 ? dot : initial.length());
        }
        sheet.body.addView(field, Ui.margins(context, Ui.fill(), 0, 8, 0, 0));
        final TextView error = Ui.text(context, "", 13, Ui.danger, false);
        error.setPadding(0, Ui.dp(context, 8), 0, 0);
        error.setVisibility(View.GONE);
        sheet.add(error);
        sheet.button("إلغاء", false, Ui.text, true, null);
        sheet.button(positive, true, 0, false, new Runnable() {
            @Override public void run() {
                String problem = callback.onText(field.getText().toString());
                if (problem == null) sheet.dismiss();
                else { error.setText(problem); error.setVisibility(View.VISIBLE); }
            }
        });
        Window window = sheet.dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        field.requestFocus();
        return sheet.show();
    }

    public static EditText field(Context context, String hint, boolean password) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(15.5f);
        field.setTextColor(Ui.text);
        field.setHintTextColor(Ui.alpha(Ui.textDim, 170));
        field.setBackground(Ui.outlined(context, Ui.surface2, Ui.stroke, 14));
        field.setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0);
        field.setMinHeight(Ui.dp(context, 50));
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        field.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }

    /** A labelled on/off row. */
    public static Switch toggle(Context context, LinearLayout parent, int icon, CharSequence label, CharSequence hint, boolean on) {
        LinearLayout row = Ui.row(context);
        row.setMinimumHeight(Ui.dp(context, 52));
        row.addView(Ui.icon(context, icon, Ui.textDim), new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)));
        LinearLayout texts = Ui.column(context);
        texts.setPaddingRelative(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0);
        texts.addView(Ui.text(context, label, 15, Ui.text, false));
        if (hint != null) texts.addView(Ui.text(context, hint, 12, Ui.textDim, false));
        row.addView(texts, Ui.weight());
        final Switch toggle = new Switch(context);
        toggle.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{Ui.accent, Ui.dark ? 0xFF9AA8C2 : 0xFFF5F7FB}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{Ui.alpha(Ui.accent, 120), Ui.dark ? 0xFF33415E : 0xFFB8C2D6}));
        row.addView(toggle);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle.toggle(); }
        });
        parent.addView(row, Ui.fill());
        return toggle;
    }

    /** Scroll view that never grows beyond ~70% of the screen so the footer stays visible. */
    private static final class LimitedScroll extends ScrollView {
        LimitedScroll(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = (int) (getResources().getDisplayMetrics().heightPixels * 0.68f);
            int size = View.MeasureSpec.getSize(heightSpec);
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) limit = Math.min(limit, size);
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
        }
    }
}
