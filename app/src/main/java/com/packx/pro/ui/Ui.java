package com.packx.pro.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours, measurements and small view factories shared by every PackX screen. */
public final class Ui {
    public static final int THEME_AUTO = 0, THEME_DARK = 1, THEME_LIGHT = 2;

    public static boolean dark = true;
    public static int bg, surface, surface2, stroke, text, textDim, accent, accent2, violet, success, danger, warn, onAccent;

    private Ui() { }

    public static void applyTheme(Context context, int mode) {
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = mode == THEME_DARK || (mode == THEME_AUTO && night);
        if (dark) {
            bg = 0xFF070B14; surface = 0xFF0F1626; surface2 = 0xFF18233A; stroke = 0xFF24324F;
            text = 0xFFF2F6FF; textDim = 0xFF8C9DBD; accent = 0xFF5B8CFF; accent2 = 0xFF22D3EE;
            violet = 0xFFA78BFA; success = 0xFF34D399; danger = 0xFFF87171; warn = 0xFFFBBF24;
        } else {
            bg = 0xFFF3F5FA; surface = 0xFFFFFFFF; surface2 = 0xFFE9EEF7; stroke = 0xFFD6DEEC;
            text = 0xFF0E1626; textDim = 0xFF5D6C88; accent = 0xFF2F5BEA; accent2 = 0xFF0891B2;
            violet = 0xFF7C3AED; success = 0xFF059669; danger = 0xFFDC2626; warn = 0xFFD97706;
        }
        onAccent = 0xFFFFFFFF;
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable round(Context context, int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(Context context, int fill, int strokeColor, float radiusDp) {
        GradientDrawable drawable = round(context, fill, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        return drawable;
    }

    /** The blue-to-cyan brand gradient used for primary actions. */
    public static GradientDrawable brand(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{dark ? 0xFF6D5BFF : 0xFF4F46E5, dark ? 0xFF2F8BFF : 0xFF2563EB, dark ? 0xFF1FC8E8 : 0xFF0EA5C6});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static Drawable ripple(Context context, Drawable content, float radiusDp) {
        GradientDrawable mask = round(context, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(alpha(dark ? Color.WHITE : Color.BLACK, 40)), content, mask);
    }

    /** Transparent, touch-highlighting background. */
    public static Drawable touch(Context context, float radiusDp) { return ripple(context, null, radiusDp); }

    public static TextView text(Context context, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    public static TextView oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return view;
    }

    public static ImageView icon(Context context, int resource, int tint) {
        ImageView view = new ImageView(context);
        setIcon(view, resource, tint);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return view;
    }

    public static void setIcon(ImageView view, int resource, int tint) {
        Drawable drawable = view.getContext().getDrawable(resource);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(tint);
        }
        view.setImageDrawable(drawable);
    }

    /** Round icon button for toolbars. */
    public static ImageView iconButton(Context context, int resource, int tint) {
        ImageView view = icon(context, resource, tint);
        int pad = dp(context, 10);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(touch(context, 22));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(-1, -2); }

    public static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }

    public static LinearLayout.LayoutParams margins(Context context, LinearLayout.LayoutParams params,
                                                    float start, float top, float end, float bottom) {
        params.setMarginStart(dp(context, start));
        params.topMargin = dp(context, top);
        params.setMarginEnd(dp(context, end));
        params.bottomMargin = dp(context, bottom);
        return params;
    }

    /** Large gradient call-to-action. */
    public static TextView primaryButton(Context context, CharSequence label) {
        TextView button = text(context, label, 15.5f, onAccent, true);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setBackground(ripple(context, brand(context, 16), 16));
        button.setMinHeight(dp(context, 52));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static TextView secondaryButton(Context context, CharSequence label, int color) {
        TextView button = text(context, label, 15f, color, true);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setBackground(ripple(context, outlined(context, surface2, stroke, 16), 16));
        button.setMinHeight(dp(context, 52));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    /** Pill that can be switched on and off; call {@link #setChip} to change its state. */
    public static TextView chip(Context context, CharSequence label, boolean selected) {
        TextView chip = text(context, label, 13.5f, text, true);
        chip.setGravity(Gravity.CENTER);
        chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        chip.setSingleLine(true);
        chip.setMinHeight(dp(context, 40));
        chip.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        setChip(chip, selected);
        return chip;
    }

    public static void setChip(TextView chip, boolean selected) {
        Context context = chip.getContext();
        chip.setSelected(selected);
        chip.setTextColor(selected ? (dark ? 0xFFFFFFFF : accent) : textDim);
        Drawable content = selected
                ? outlined(context, alpha(accent, dark ? 60 : 30), accent, 20)
                : outlined(context, surface2, stroke, 20);
        chip.setBackground(ripple(context, content, 20));
    }

    public static TextView sectionLabel(Context context, CharSequence value) {
        TextView label = text(context, value, 12.5f, textDim, true);
        label.setPadding(0, dp(context, 18), 0, dp(context, 8));
        return label;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(stroke);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1))));
        return line;
    }
}
