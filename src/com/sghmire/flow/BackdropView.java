package com.sghmire.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Dark navy gradient with a soft light pool above the app dock, like ChillHub's backdrop. */
public class BackdropView extends View {
    private final Paint base = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Gradients are rasterised once; per-frame GPU work is then a single texture blit. */
    private Bitmap cached;

    public BackdropView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        base.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF101A2E, 0xFF0C1424, 0xFF080D18},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        float r = w * 0.32f;
        glow.setShader(new RadialGradient(w / 2f, h * 0.66f, r,
                new int[] {0x55B8CCFF, 0x1A8FA8E0, 0x00000000},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
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

    private void paint(Canvas canvas, int w, int h) {
        canvas.drawRect(0, 0, w, h, base);
        canvas.save();
        canvas.scale(1f, 0.28f, w / 2f, h * 0.66f);
        canvas.drawCircle(w / 2f, h * 0.66f, w * 0.32f, glow);
        canvas.restore();
    }
}
