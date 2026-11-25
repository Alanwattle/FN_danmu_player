package dev.ushio.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BASE = 0xff0c0e15, PANEL = 0xff171a25, STROKE = 0xff2b2f3f;
    static final int TEXT = 0xfff7f5ff, MUTED = 0xffa9aabd, ACCENT = 0xffc4b5ff, INK = 0xff241a42;
    static final int WHITE = 0xffffffff, GREEN = 0xffa1e7cd;

    final Context context;
    Ui(Context context) { this.context = context; }

    int dp(float number) { return Math.round(number * context.getResources().getDisplayMetrics().density); }

    TextView text(String value, float size, int color, boolean bold) {
        TextView result = new TextView(context);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setIncludeFontPadding(false);
        result.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return result;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    void space(LinearLayout layout, int height) {
        layout.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }

    void gap(LinearLayout layout, int width) {
        layout.addView(new View(context), new LinearLayout.LayoutParams(dp(width), 1));
    }

    void stretch(LinearLayout row) { row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1)); }

    GradientDrawable filled(int color, float radius, int outline) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (outline != 0) drawable.setStroke(dp(1), outline);
        return drawable;
    }

    GradientDrawable gradient(int... colors) { return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors); }

    void click(View view, int bg, float radius, int stroke, Runnable action) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x32c4b5ff),
                filled(bg, radius, stroke), filled(WHITE, radius, 0)));
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
    }

    LinearLayout button(String label, String glyph, boolean primary, Runnable action) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(47));
        pad(button, 15, 5, 15, 5);
        int color = primary ? INK : TEXT;
        if (!glyph.isEmpty()) {
            button.addView(icon(glyph, color), new LinearLayout.LayoutParams(dp(20), dp(20)));
            gap(button, 8);
        }
        TextView name = text(label, 14, color, true);
        name.setGravity(Gravity.CENTER);
        button.addView(name);
        button.setContentDescription(label);
        click(button, primary ? ACCENT : PANEL, 13, primary ? 0 : STROKE, action);
        return button;
    }

    View iconButton(String glyph, String description, int color, int background, Runnable action) {
        FrameLayout control = new FrameLayout(context);
        control.addView(icon(glyph, color), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        control.setContentDescription(description);
        click(control, background, 100, 0, action);
        return control;
    }

    View icon(String name, int color) { return new Glyph(context, name, color); }

    private static final class Glyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private final String type;

        Glyph(Context context, String type, int color) {
            super(context);
            this.type = type;
            paint.setColor(color);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float unit = Math.min(getWidth(), getHeight()) / 24f;
            canvas.save();
            canvas.translate((getWidth() - 24 * unit) / 2, (getHeight() - 24 * unit) / 2);
            canvas.scale(unit, unit);
            paint.setStyle(Paint.Style.STROKE);
            switch (type) {
                case "play":
                    paint.setStyle(Paint.Style.FILL);
                    path(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
                    break;
                case "pause":
                    canvas.drawLine(9, 5, 9, 19, paint);
                    canvas.drawLine(15, 5, 15, 19, paint);
                    break;
                case "back": path(canvas, 14, 5, 7, 12, 14, 19); break;
                case "right": path(canvas, 9, 5, 16, 12, 9, 19); break;
                case "home":
                    path(canvas, 3, 10, 12, 3, 21, 10, 21, 21, 15, 21, 15, 15, 9, 15, 9, 21, 3, 21, 3, 10);
                    break;
                case "search":
                    canvas.drawCircle(10, 10, 6, paint);
                    canvas.drawLine(15, 15, 21, 21, paint);
                    break;
                case "grid":
                    for (int x = 3; x <= 13; x += 10) for (int y = 3; y <= 13; y += 10) {
                        canvas.drawRoundRect(x, y, x + 8, y + 8, 1.4f, 1.4f, paint);
                    }
                    break;
                case "plug":
                    canvas.drawRoundRect(3, 4, 21, 10, 2, 2, paint);
                    canvas.drawRoundRect(3, 14, 21, 20, 2, 2, paint);
                    canvas.drawPoint(7, 7, paint);
                    canvas.drawPoint(7, 17, paint);
                    break;
                case "chat":
                    path(canvas, 4, 4, 20, 4, 20, 16, 10, 16, 5, 20, 5, 16, 4, 16, 4, 4);
                    canvas.drawLine(8, 8, 16, 8, paint);
                    canvas.drawLine(8, 12, 13, 12, paint);
                    break;
                case "lock":
                case "unlock":
                    canvas.drawRoundRect(5, 10, 19, 21, 2, 2, paint);
                    canvas.drawArc(8, 3, 16, 13, type.equals("lock") ? 180 : 210, 180, false, paint);
                    canvas.drawLine(12, 14, 12, 17, paint);
                    break;
                case "full":
                    path(canvas, 3, 9, 3, 3, 9, 3);
                    path(canvas, 15, 3, 21, 3, 21, 9);
                    path(canvas, 21, 15, 21, 21, 15, 21);
                    path(canvas, 9, 21, 3, 21, 3, 15);
                    break;
                case "pip":
                    canvas.drawRoundRect(3, 5, 21, 19, 1, 1, paint);
                    canvas.drawRect(11, 12, 17, 16, paint);
                    break;
                case "next":
                    path(canvas, 5, 5, 15, 12, 5, 19, 5, 5);
                    canvas.drawLine(19, 5, 19, 19, paint);
                    break;
                case "rewind":
                case "forward":
                    if (type.equals("forward")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                    canvas.drawArc(4, 4, 20, 20, 215, 285, false, paint);
                    path(canvas, 4, 4, 4, 10, 10, 10);
                    break;
                case "clock":
                    canvas.drawCircle(12, 12, 8.5f, paint);
                    path(canvas, 12, 7, 12, 12, 16, 14);
                    break;
                case "list":
                    for (int y : new int[]{6, 12, 18}) {
                        canvas.drawPoint(4, y, paint);
                        canvas.drawLine(8, y, 20, y, paint);
                    }
                    break;
                case "close":
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(6, 18, 18, 6, paint);
                    break;
                case "check": path(canvas, 5, 12, 10, 17, 20, 7); break;
                case "more":
                    canvas.drawPoint(5, 12, paint);
                    canvas.drawPoint(12, 12, paint);
                    canvas.drawPoint(19, 12, paint);
                    break;
                default: canvas.drawCircle(12, 12, 7, paint);
            }
            canvas.restore();
        }

        private void path(Canvas canvas, float... points) {
            outline.rewind();
            outline.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) outline.lineTo(points[i], points[i + 1]);
            canvas.drawPath(outline, paint);
        }
    }
}
