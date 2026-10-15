package com.sghmire.flow;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;

/**
 * Alternative looks for an app tile, rendered once into a bitmap the same shape as a TV banner,
 * so every place that shows the tile (dock, Recents, folder, open/close animation) stays consistent.
 */
public final class TileStyles {
    public static final int BANNER = 0;
    public static final int ICON_AND_NAME = 1;
    public static final int ICON_ON_DARK = 2;
    public static final int NAME_ONLY = 3;
    /** Brand logo from Simple Icons on the brand's color. */
    public static final int LOGO = 4;
    /** A picture the user supplied by link. */
    public static final int IMAGE = 5;
    public static final String[] NAMES = {"Original", "Icon and name", "Icon on dark", "Name only", "Logo", "Picture"};

    private TileStyles() {}

    /** Renders {@code style}; returns {@code banner} for {@link #BANNER} (or when there is none, falls back). */
    public static Drawable render(Resources res, int style, Drawable banner, Drawable icon, String label) {
        if (style == BANNER && banner != null) return banner;
        if (style == BANNER) style = ICON_AND_NAME;
        float d = res.getDisplayMetrics().density;
        int w = Math.round(244 * d), h = Math.round(144 * d); // 2x a dock tile, sharp when it grows
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int brand = brandColor(icon, banner);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (style == ICON_ON_DARK) {
            p.setShader(new LinearGradient(0, 0, w, h, 0xFF3A3C42, 0xFF1E2024, Shader.TileMode.CLAMP));
        } else {
            p.setShader(new LinearGradient(0, 0, w, h, lighten(brand, 0.12f), darken(brand, 0.18f), Shader.TileMode.CLAMP));
        }
        c.drawRect(0, 0, w, h, p);

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        int iconSize = Math.round(h * (style == ICON_ON_DARK ? 0.52f : 0.42f));
        switch (style) {
            case ICON_AND_NAME: {
                tp.setTextSize(h * 0.17f);
                String text = TextUtils.ellipsize(label, tp, w * 0.52f, TextUtils.TruncateAt.END).toString();
                float textW = tp.measureText(text);
                float gap = h * 0.08f;
                float total = iconSize + gap + textW;
                float x = (w - total) / 2f;
                drawIcon(c, icon, Math.round(x), (h - iconSize) / 2, iconSize);
                c.drawText(text, x + iconSize + gap, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp);
                break;
            }
            case ICON_ON_DARK:
                drawIcon(c, icon, (w - iconSize) / 2, (h - iconSize) / 2, iconSize);
                break;
            case NAME_ONLY: {
                tp.setTextSize(h * 0.22f);
                tp.setTextAlign(Paint.Align.CENTER);
                String text = TextUtils.ellipsize(label, tp, w * 0.86f, TextUtils.TruncateAt.END).toString();
                c.drawText(text, w / 2f, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp);
                break;
            }
        }
        return new BitmapDrawable(res, b);
    }

    /** Brand logo centered on its brand color. */
    public static Drawable renderLogo(Resources res, Logos.Logo logo) {
        float d = res.getDisplayMetrics().density;
        int w = Math.round(244 * d), h = Math.round(144 * d);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int brand = logo.color;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, lighten(brand, 0.10f), darken(brand, 0.15f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // Bright brand colors (yellow, white) get a dark logo; everything else a white one.
        p.setColor(luminance(brand) > 0.6f ? 0xFF111214 : Color.WHITE);
        android.graphics.Path path = SvgPath.parse(logo.path);
        android.graphics.RectF bounds = new android.graphics.RectF();
        path.computeBounds(bounds, true);
        float target = h * 0.46f;
        float scale = Math.min(target / Math.max(1e-3f, bounds.height()), (w * 0.7f) / Math.max(1e-3f, bounds.width()));
        c.translate(w / 2f, h / 2f);
        c.scale(scale, scale);
        c.translate(-bounds.centerX(), -bounds.centerY());
        c.drawPath(path, p);
        return new BitmapDrawable(res, b);
    }

    /** A user picture cropped to the tile's shape. */
    public static Drawable renderImage(Resources res, Bitmap src) {
        float d = res.getDisplayMetrics().density;
        int w = Math.round(244 * d), h = Math.round(144 * d);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // Photos fill the tile; logos with transparency sit whole on a dark plate instead.
        boolean logoLike = src.hasAlpha();
        if (logoLike) {
            Paint bg = new Paint(Paint.DITHER_FLAG);
            bg.setShader(new LinearGradient(0, 0, w, h, 0xFF3A3C42, 0xFF1E2024, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
        }
        float scale = logoLike
                ? Math.min(w * 0.78f / src.getWidth(), h * 0.62f / src.getHeight())
                : Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        android.graphics.RectF dst = new android.graphics.RectF((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return new BitmapDrawable(res, b);
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    private static void drawIcon(Canvas c, Drawable icon, int x, int y, int size) {
        if (icon == null) return;
        Drawable dr = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        dr.setBounds(x, y, x + size, y + size);
        dr.draw(c);
    }

    /** Average color of the app's icon (or banner), kept readable under white text. */
    static int brandColor(Drawable icon, Drawable banner) {
        Drawable src = icon != null ? icon : banner;
        if (src == null) return 0xFF2A2C30;
        try {
            Bitmap s = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Drawable dr = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
            dr.setBounds(0, 0, 24, 24);
            dr.draw(new Canvas(s));
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int px = s.getPixel(x, y);
                    if ((px >>> 24) < 200) continue;
                    int pr = (px >> 16) & 0xFF, pg = (px >> 8) & 0xFF, pb = px & 0xFF;
                    // Skip near-white and near-black so the hue wins over backgrounds.
                    int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
                    if (max > 235 && min > 220) continue;
                    if (max < 25) continue;
                    r += pr; g += pg; bl += pb; n++;
                }
            }
            s.recycle();
            if (n == 0) return 0xFF2A2C30;
            int c = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[2] = Math.min(hsv[2], 0.62f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0xFF2A2C30;
        }
    }

    private static int lighten(int c, float f) {
        return Color.rgb(mix(Color.red(c), 255, f), mix(Color.green(c), 255, f), mix(Color.blue(c), 255, f));
    }

    private static int darken(int c, float f) {
        return Color.rgb(mix(Color.red(c), 0, f), mix(Color.green(c), 0, f), mix(Color.blue(c), 0, f));
    }

    private static int mix(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }
}
