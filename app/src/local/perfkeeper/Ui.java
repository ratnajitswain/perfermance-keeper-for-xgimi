package local.perfkeeper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small design system for a 10-foot (TV) UI: colors, type, cards, chips, segmented controls, fan + bars. */
final class Ui {
    // ----- palette (dark, high contrast for a projected image) -----
    static final int BG = 0xFF0E1117, SURFACE = 0xFF171B24, SURFACE_2 = 0xFF1F2430, OUTLINE = 0xFF2B3240,
            TEXT = 0xFFF1F4F9, TEXT_2 = 0xFFA7B0C0, TEXT_3 = 0xFF6F7A8C,
            ACCENT = 0xFF4F8CFF, ACCENT_2 = 0xFF7AA7FF,
            GREEN = 0xFF34D399, AMBER = 0xFFFBBF24, BLUE = 0xFF60A5FA, TEAL = 0xFF2DD4BF, RED = 0xFFF87171, GREY = 0xFF94A3B8;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView overline(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, TEXT_3, MEDIUM);
        t.setLetterSpacing(0.12f);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(SURFACE, dp(c, 16), dp(c, 1), OUTLINE));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static View gap(Context c, int dpW, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, dpW), dp(c, dpH)));
        return v;
    }

    /** TV focus feedback: gentle zoom. */
    static void zoomOnFocus(View v, float scale) {
        v.setOnFocusChangeListener((view, has) ->
                view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(140).start());
    }

    // ---------- buttons ----------
    static TextView button(Context c, String label, boolean primary, Runnable onClick) {
        TextView b = text(c, label, 15, TEXT, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        Runnable paint = () -> {
            boolean f = b.isFocused();
            int fill = primary ? (f ? ACCENT_2 : ACCENT) : (f ? 0xFF2C3446 : SURFACE_2);
            b.setBackground(rounded(fill, dp(c, 12), dp(c, f ? 2 : 1), f ? TEXT : (primary ? ACCENT : OUTLINE)));
        };
        paint.run();
        b.setOnFocusChangeListener((v, has) -> {
            paint.run();
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    // ---------- segmented control ----------
    interface OnPick { void pick(String value); }

    static final class Segmented extends LinearLayout {
        final String[] values;
        final TextView[] chips;
        String selected;
        private final OnPick onPick;

        Segmented(Context c, String[] labels, String[] values, OnPick onPick) {
            super(c);
            this.values = values;
            this.onPick = onPick;
            setOrientation(HORIZONTAL);
            setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            setBackground(rounded(SURFACE_2, dp(c, 14), dp(c, 1), OUTLINE));
            setClipChildren(false);
            setClipToPadding(false);
            chips = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView t = text(c, labels[i], 15, TEXT_2, MEDIUM);
                t.setGravity(Gravity.CENTER);
                t.setFocusable(true);
                t.setClickable(true);
                t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
                t.setOnFocusChangeListener((v, has) -> {
                    restyle();
                    v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(140).start();
                });
                t.setOnClickListener(v -> { select(values[idx]); onPick.pick(values[idx]); });
                LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = dp(c, 4);
                addView(t, lp);
                chips[i] = t;
            }
            restyle();
        }

        void select(String value) { selected = value; restyle(); }

        void restyle() {
            Context c = getContext();
            for (int i = 0; i < chips.length; i++) {
                TextView t = chips[i];
                boolean sel = values[i].equals(selected), foc = t.isFocused();
                int fill = sel ? ACCENT : (foc ? 0xFF2C3446 : 0x00000000);
                t.setBackground(rounded(fill, dp(c, 10), foc ? dp(c, 2) : 0, TEXT));
                t.setTextColor(sel || foc ? TEXT : TEXT_2);
            }
        }
    }

    // ---------- spinning fan icon (speed follows the real fans) ----------
    static final class FanView extends View {
        private final Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), hub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float angle, speed;       // speed: revolutions per second
        private long lastFrame;
        private boolean animating;

        FanView(Context c, int color) {
            super(c);
            setColor(color);
            ring.setStyle(Paint.Style.STROKE);
            hub.setColor(BG);
        }

        void setColor(int color) { blade.setColor(color); ring.setColor(color); invalidate(); }

        /** fanPercent 0..100 → spin speed. */
        void setSpeed(int fanPercent) { speed = Math.max(0, fanPercent) / 100f * 1.6f; }

        void setAnimating(boolean on) {
            animating = on;
            lastFrame = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f * 0.78f;
            long now = SystemClock.uptimeMillis();
            angle = (angle + speed * 360f * (now - lastFrame) / 1000f) % 360f;
            lastFrame = now;
            ring.setStrokeWidth(R * 0.10f);
            cv.drawCircle(cx, cy, R * 1.18f, ring);
            cv.save();
            cv.rotate(angle, cx, cy);
            for (int i = 0; i < 4; i++) {
                cv.save();
                cv.rotate(i * 90 + 20, cx, cy);
                path.reset();
                path.moveTo(cx, cy);
                path.cubicTo(cx + R * 0.15f, cy - R * 0.55f, cx + R * 0.75f, cy - R * 0.95f, cx + R * 0.95f, cy - R * 0.35f);
                path.cubicTo(cx + R * 0.75f, cy - R * 0.15f, cx + R * 0.35f, cy - R * 0.05f, cx, cy);
                cv.drawPath(path, blade);
                cv.restore();
            }
            cv.restore();
            cv.drawCircle(cx, cy, R * 0.17f, hub);
            if (animating && speed > 0) postInvalidateDelayed(33);     // ~30 fps, light on the CPU
        }
    }

    // ---------- horizontal bar ----------
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value;            // 0..1
        private int c1 = ACCENT, c2 = ACCENT_2;

        Bar(Context c) { super(c); track.setColor(SURFACE_2); }

        void set(float v, int from, int to) { value = Math.max(0, Math.min(1, v)); c1 = from; c2 = to; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, track);
            if (value <= 0) return;
            r.set(0, 0, Math.max(h, w * value), h);
            fill.setShader(new LinearGradient(0, 0, r.right, 0, c1, c2, Shader.TileMode.CLAMP));
            cv.drawRoundRect(r, rad, rad, fill);
        }
    }

    static GradientDrawable dot(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(dp(c, 10), dp(c, 10));
        return g;
    }
}
