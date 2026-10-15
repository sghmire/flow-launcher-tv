package com.sghmire.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Matte dark backdrop: near-black charcoal with a fine light grain, a soft diagonal sheen from
 * the top-left, a gentle light pool above the dock and darker edges.
 */
public class BackdropView extends View {
    /** Gradients and grain are rasterised once; per-frame GPU work is then a single texture blit. */
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
                new int[] {0xFF17181B, 0xFF111214, 0xFF0C0D0E},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Soft sheen falling in from the top-left.
        p.setShader(new LinearGradient(0, 0, w * 0.55f, h * 0.9f,
                new int[] {0x16FFFFFF, 0x06FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Faint light pool above the dock.
        float cx = w / 2f, cy = h * 0.66f;
        c.save();
        c.scale(1f, 0.3f, cx, cy);
        p.setShader(new RadialGradient(cx, cy, w * 0.34f,
                new int[] {0x22FFFFFF, 0x0AFFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, w * 0.34f, p);
        c.restore();

        // Darker edges.
        p.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) * 0.6f,
                new int[] {0x00000000, 0x00000000, 0x73000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Fine light grain for a matte, slightly textured finish.
        p.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        c.drawRect(0, 0, w, h, p);
    }

    private static Bitmap grain() {
        int size = 256;
        int[] px = new int[size * size];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int a = r.nextInt(100) < 55 ? r.nextInt(9) : 0;
            px[i] = (a << 24) | 0xFFFFFF;
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }
}
