package dev.ushio.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DanmuView extends View {
    private static final long ROLL_TIME = 7600;
    private static final long FIXED_TIME = 4100;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<Active> visible = new ArrayList<>();
    private List<DanmuClient.Comment> comments = Collections.emptyList();
    private final float scaled;
    private int cursor;
    private long position = -10000;
    private boolean drawing = true;
    private int alpha = 210;
    private int area = 50;
    private int size = 19;
    private long offset;
    private final long[] rollLanes = new long[22];
    private final long[] topLanes = new long[22];
    private final long[] bottomLanes = new long[22];

    private static final class Active {
        final DanmuClient.Comment comment;
        final int lane;
        final float width;
        Active(DanmuClient.Comment comment, int lane, float width) {
            this.comment = comment;
            this.lane = lane;
            this.width = width;
        }
    }

    DanmuView(Context context) {
        super(context);
        scaled = getResources().getDisplayMetrics().scaledDensity;
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void settings(SharedPreferences prefs) {
        drawing = prefs.getBoolean("comments.enabled", true);
        alpha = prefs.getInt("comments.opacity", 82) * 255 / 100;
        area = prefs.getInt("comments.area", 50);
        size = prefs.getInt("comments.size", 19);
        reset(prefs.getInt("comments.offset", 0) * 1000L);
    }

    void load(List<DanmuClient.Comment> input) {
        comments = input;
        reset(offset);
    }

    private void reset(long newOffset) {
        position = -10000;
        offset = newOffset;
        cursor = 0;
        visible.clear();
        invalidate();
    }

    void seek() { reset(offset); }

    void frame(long playbackPosition) {
        if (!drawing || comments.isEmpty() || getWidth() == 0) return;
        long target = playbackPosition - offset;
        if (target == position) return;
        if (target < position || target - position > 1000) {
            visible.clear();
            java.util.Arrays.fill(rollLanes, Long.MIN_VALUE);
            java.util.Arrays.fill(topLanes, Long.MIN_VALUE);
            java.util.Arrays.fill(bottomLanes, Long.MIN_VALUE);
            int start = 0, end = comments.size();
            while (start < end) {
                int middle = (start + end) >>> 1;
                if (comments.get(middle).time < target - ROLL_TIME * 3) start = middle + 1;
                else end = middle;
            }
            cursor = start;
        }
        position = target;
        paint.setTextSize(size * scaled);
        float line = paint.getTextSize() * 1.42f;
        int rows = Math.max(1, Math.min(22, (int) (getHeight() * area / 100f / line) - 1));
        for (int i = visible.size() - 1; i >= 0; i--) {
            Active active = visible.get(i);
            if (target - active.comment.time >= duration(active.comment.mode, active.width)) visible.remove(i);
        }
        while (cursor < comments.size() && comments.get(cursor).time <= target) {
            DanmuClient.Comment comment = comments.get(cursor++);
            float width = paint.measureText(comment.text);
            if (visible.size() >= rows * 4 || target - comment.time >= duration(comment.mode, width)) continue;
            long[] lanes = comment.mode == 1 ? rollLanes : comment.mode == 4 ? bottomLanes : topLanes;
            int count = comment.mode == 1 ? rows : Math.min(3, rows);
            for (int lane = 0; lane < count; lane++) {
                if (comment.time < lanes[lane]) continue;
                visible.add(new Active(comment, lane, width));
                // Every scrolling comment has the same pixel velocity at a given screen width.
                lanes[lane] = comment.time + (comment.mode == 1
                        ? Math.max(1050L, (long) (ROLL_TIME * (width + 28) / getWidth())) : FIXED_TIME);
                break;
            }
        }
        invalidate();
    }

    private long duration(int mode, float width) {
        return mode == 1 ? (long) (ROLL_TIME * (1 + width / getWidth())) : FIXED_TIME;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { reset(offset); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!drawing) return;
        float line = paint.getTextSize() * 1.42f;
        for (Active active : visible) {
            DanmuClient.Comment comment = active.comment;
            float x = comment.mode == 1
                    ? getWidth() - (float) getWidth() * (position - comment.time) / ROLL_TIME
                    : (getWidth() - active.width) / 2;
            float y = comment.mode == 4
                    ? getHeight() - (active.lane + 2.6f) * line
                    : (active.lane + 1.55f) * line;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(2f, scaled * 2));
            paint.setColor(0x0010131b | (Math.min(alpha, 190) << 24));
            canvas.drawText(comment.text, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((alpha << 24) | (comment.color & 0xffffff));
            canvas.drawText(comment.text, x, y, paint);
        }
    }
}
