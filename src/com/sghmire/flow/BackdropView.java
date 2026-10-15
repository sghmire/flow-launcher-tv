package com.sghmire.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Matte charcoal backdrop with a broad, subtle cool sheen and softly darkened edges.
 * Gradients are dithered to reduce banding.
 */
public class BackdropView extends View {
    /** Gradients are rasterised once; per-frame GPU work is then a single texture blit. */
    private Bitmap cached;

    public BackdropView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (cached == null || cached.getWidth() != w || cached.getHeight() != h) {
            cached = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            paint(new Canvas(cached), w, h);
        }
        canvas.drawBitmap(cached, 0, 0, null);
    }

    private static void paint(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF1B1E24, 0xFF14171C, 0xFF0D1014},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Soft sheen falling in from the top-left.
        p.setShader(new LinearGradient(0, 0, w * 0.9f, h,
                new int[] {0x0CBCD0E8, 0x04BCD0E8, 0x00BCD0E8},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Darker edges.
        p.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) * 0.6f,
                new int[] {0x00000000, 0x00000000, 0x45000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }
}
