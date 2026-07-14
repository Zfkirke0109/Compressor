package io.github.zfkirke0109.mtenglishoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BeautifulOverlayView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final ArrayList<OverlayItem> items = new ArrayList<>();

    private Bitmap screenshot;
    private String status;
    private float density;

    BeautifulOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        borderPaint.setStyle(Paint.Style.STROKE);
    }

    void setFrame(Bitmap bitmap, List<OverlayItem> newItems) {
        screenshot = bitmap;
        items.clear();
        if (newItems != null) items.addAll(newItems);
        animate().cancel();
        setAlpha(0.86f);
        animate().alpha(1f).setDuration(140L).start();
        invalidate();
    }

    void setItems(List<OverlayItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        animate().cancel();
        setAlpha(0.9f);
        animate().alpha(1f).setDuration(110L).start();
        invalidate();
    }

    void setStatus(String value) {
        status = value;
        invalidate();
    }

    List<OverlayItem> snapshotItems() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (OverlayItem item : items) {
            drawReplacement(canvas, item);
        }
        if (status != null && !status.trim().isEmpty()) {
            drawStatus(canvas, status);
        }
    }

    private void drawReplacement(Canvas canvas, OverlayItem item) {
        Rect clipped = new Rect(item.bounds);
        clipped.intersect(0, 0, getWidth(), getHeight());
        if (clipped.width() < dp(12) || clipped.height() < dp(10)) return;

        int sampled = sampleBackground(clipped);
        float luminance = luminance(sampled);
        int panelColor = tunePanel(sampled, luminance);
        int foreground = luminance(panelColor) > 0.55f ? Color.rgb(18, 20, 24) : Color.WHITE;
        int border = luminance(panelColor) > 0.55f
                ? Color.argb(70, 0, 0, 0)
                : Color.argb(72, 255, 255, 255);

        float radius = Math.min(dp(11), Math.max(dp(4), clipped.height() * 0.24f));
        RectF panel = new RectF(
                clipped.left - dp(2),
                clipped.top - dp(1),
                clipped.right + dp(2),
                clipped.bottom + dp(1));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(panelColor);
        fillPaint.setShadowLayer(dp(7), 0f, dp(2), Color.argb(85, 0, 0, 0));
        canvas.drawRoundRect(panel, radius, radius, fillPaint);
        fillPaint.clearShadowLayer();

        borderPaint.setStrokeWidth(Math.max(1f, density * 0.55f));
        borderPaint.setColor(border);
        canvas.drawRoundRect(panel, radius, radius, borderPaint);

        int horizontalPadding = dp(6);
        int verticalPadding = dp(2);
        int availableWidth = Math.max(dp(20), clipped.width() - horizontalPadding * 2);
        int availableHeight = Math.max(dp(10), clipped.height() - verticalPadding * 2);
        float size = fitText(item.english, availableWidth, availableHeight);

        textPaint.setTextSize(size);
        textPaint.setColor(foreground);
        textPaint.setShadowLayer(luminance(panelColor) > 0.55f ? 0f : dp(1), 0f, dp(1), Color.argb(90, 0, 0, 0));

        StaticLayout layout = StaticLayout.Builder
                .obtain(item.english, 0, item.english.length(), textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.96f)
                .setMaxLines(Math.max(1, Math.min(5, availableHeight / Math.max(1, Math.round(size * 1.05f)))))
                .build();

        float x = clipped.left + horizontalPadding;
        float y = clipped.top + Math.max(verticalPadding, (clipped.height() - layout.getHeight()) / 2f);
        canvas.save();
        canvas.clipRect(clipped);
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
        textPaint.clearShadowLayer();
    }

    private void drawStatus(Canvas canvas, String value) {
        textPaint.setTextSize(dp(11));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float width = textPaint.measureText(value) + dp(22);
        float height = dp(32);
        RectF pill = new RectF(getWidth() - width - dp(12), dp(14), getWidth() - dp(12), dp(14) + height);
        fillPaint.setColor(Color.argb(220, 24, 27, 32));
        fillPaint.setShadowLayer(dp(8), 0, dp(2), Color.argb(90, 0, 0, 0));
        canvas.drawRoundRect(pill, height / 2f, height / 2f, fillPaint);
        fillPaint.clearShadowLayer();
        textPaint.setColor(Color.WHITE);
        canvas.drawText(value, pill.left + dp(11), pill.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
    }

    private float fitText(String text, int width, int height) {
        float low = dp(8);
        float high = dp(18);
        float best = low;
        for (int i = 0; i < 8; i++) {
            float mid = (low + high) / 2f;
            textPaint.setTextSize(mid);
            StaticLayout layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), textPaint, width)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 0.96f)
                    .build();
            if (layout.getHeight() <= height && layout.getLineCount() <= 5) {
                best = mid;
                low = mid;
            } else {
                high = mid;
            }
        }
        return best;
    }

    private int sampleBackground(Rect bounds) {
        if (screenshot == null || screenshot.isRecycled()) return Color.rgb(28, 31, 36);
        int width = screenshot.getWidth();
        int height = screenshot.getHeight();
        long red = 0, green = 0, blue = 0;
        int count = 0;
        int[] xs = {bounds.left, bounds.centerX(), bounds.right - 1};
        int[] ys = {bounds.top, bounds.centerY(), bounds.bottom - 1};
        for (int x : xs) {
            for (int y : ys) {
                int px = Math.max(0, Math.min(width - 1, x));
                int py = Math.max(0, Math.min(height - 1, y));
                int color = screenshot.getPixel(px, py);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }
        if (count == 0) return Color.rgb(28, 31, 36);
        return Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    private int tunePanel(int sampled, float luminance) {
        int r = Color.red(sampled);
        int g = Color.green(sampled);
        int b = Color.blue(sampled);
        if (luminance > 0.62f) {
            r = Math.max(225, r);
            g = Math.max(225, g);
            b = Math.max(225, b);
        } else {
            r = Math.min(52, Math.max(18, r));
            g = Math.min(56, Math.max(20, g));
            b = Math.min(62, Math.max(24, b));
        }
        return Color.argb(248, r, g, b);
    }

    private float luminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
