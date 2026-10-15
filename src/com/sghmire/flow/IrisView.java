package com.sghmire.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Full-screen black cover with a soft round hole (a camera-iris / pinhole). The hole is a small
 * pre-rendered texture stretched to the current radius and the rest is plain black rectangles,
 * so each frame is a few quads: no path clipping or stencil work for the GPU.
 */
public class IrisView extends View {
    private static final int TEX = 256;
    /** Fraction of the texture's half-size where the hole is fully clear. */
    private static final float CLEAR = 0.80f;

    private final Bitmap hole;
    private final Paint black = new Paint();
    private final Paint tex = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private float cx, cy, radius;

    public IrisView(Context context) {
        super(context);
        black.setColor(0xFF000000);
        hole = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(TEX / 2f, TEX / 2f, TEX / 2f,
                new int[] {0x00000000, 0x00000000, 0xFF000000},
                new float[] {0f, CLEAR, 1f}, Shader.TileMode.CLAMP));
        new Canvas(hole).drawRect(0, 0, TEX, TEX, p);
    }

    public void setCenter(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    /** Radius of the clear part of the hole, in pixels; 0 means fully black. */
    public void setRadius(float r) {
        radius = r;
        invalidate();
    }

    public float getRadius() {
        return radius;
    }

    /** Radius at which the hole clears every corner of the screen around the current center. */
    public float openRadius() {
        float dx = Math.max(cx, getWidth() - cx), dy = Math.max(cy, getHeight() - cy);
        return (float) Math.hypot(dx, dy) / CLEAR + 4f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (radius <= 0.5f) {
            canvas.drawRect(0, 0, w, h, black);
            return;
        }
        float half = radius / CLEAR;
        dst.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawBitmap(hole, null, dst, tex);
        // Black everywhere outside the hole texture.
        if (dst.top > 0) canvas.drawRect(0, 0, w, dst.top, black);
        if (dst.bottom < h) canvas.drawRect(0, dst.bottom, w, h, black);
        float top = Math.max(0, dst.top), bottom = Math.min(h, dst.bottom);
        if (dst.left > 0) canvas.drawRect(0, top, dst.left, bottom, black);
        if (dst.right < w) canvas.drawRect(dst.right, top, w, bottom, black);
    }
}
