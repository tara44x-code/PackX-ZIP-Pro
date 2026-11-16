package com.packx.pro.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

/** Circular progress indicator with the percentage in the middle. */
public final class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float shown;          // animated value, 0..100
    private int target = -1;      // -1 = indeterminate
    private float spin;
    private String centerText;
    private String captionText = "";
    private int arcColor;
    private boolean finished;

    public RingView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setTextAlign(Paint.Align.CENTER);
    }

    /** @param percent 0..100, or -1 while the amount of work is unknown. */
    public void setProgress(int percent) {
        target = percent;
        finished = false;
        arcColor = 0;
        centerText = null;
        invalidate();
    }

    public void setCaption(String value) { captionText = value == null ? "" : value; invalidate(); }

    /** Freezes the ring as a full circle in the given colour with a symbol in the middle. */
    public void finish(int color, String symbol) {
        finished = true;
        arcColor = color;
        centerText = symbol;
        target = 100;
        shown = 100;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Ui.dp(getContext(), 168);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        float strokeWidth = Ui.dp(getContext(), 11);
        float radius = Math.min(width, height) / 2f - strokeWidth;
        float cx = width / 2f, cy = height / 2f;
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        track.setStrokeWidth(strokeWidth);
        track.setColor(Ui.surface2);
        canvas.drawCircle(cx, cy, radius, track);

        arc.setStrokeWidth(strokeWidth);
        if (arcColor != 0) {
            arc.setShader(null);
            arc.setColor(arcColor);
        } else {
            arc.setShader(new SweepGradient(cx, cy,
                    new int[]{Ui.accent2, Ui.accent, Ui.violet, Ui.accent2}, null));
        }

        boolean animating = false;
        if (target < 0 && !finished) {
            spin = (spin + 5f) % 360f;
            canvas.drawArc(bounds, spin, 80f, false, arc);
            animating = true;
        } else {
            if (Math.abs(target - shown) > 0.4f) { shown += (target - shown) * 0.18f; animating = true; }
            else shown = target;
            canvas.drawArc(bounds, -90f, 360f * shown / 100f, false, arc);
        }

        label.setColor(finished ? arcColor : Ui.text);
        label.setTextSize(radius * (finished ? 0.8f : 0.52f));
        String center = centerText != null ? centerText : target < 0 ? "…" : Math.round(shown) + "%";
        float baseline = cy - (label.descent() + label.ascent()) / 2f - (captionText.isEmpty() || finished ? 0 : radius * 0.12f);
        canvas.drawText(center, cx, baseline, label);
        if (!captionText.isEmpty() && !finished) {
            caption.setColor(Ui.textDim);
            caption.setTextSize(radius * 0.17f);
            canvas.drawText(captionText, cx, cy + radius * 0.42f, caption);
        }
        if (animating) postInvalidateOnAnimation();
    }
}
