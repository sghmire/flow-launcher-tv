package com.gfs.flow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A stand-in for the tile being opened or returned to. It grows from the tile's exact spot on
 * screen to full screen (and back), so the tile itself appears to maximize into the app.
 *
 * Every frame only changes RenderNode properties (outline bounds, translation, scale), so the
 * animation never re-draws content. The only extra memory is while it runs: the artwork it
 * shows is the tile's own drawable, released by {@link #clear()} when the animation ends.
 */
public class HeroView extends FrameLayout {
    /** How large an app's banner grows when it reaches full screen. */
    private static final float BANNER_GROWTH = 1.9f;

    private final ImageView art;
    private final RectF from = new RectF();
    private final RectF now = new RectF();
    private float fromRadius;
    private float radius;
    /** Card pictures fill the whole hero; app banners float centered on their brand color. */
    private boolean fill;

    public HeroView(Context context) {
        super(context);
        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setPivotX(0);
        art.setPivotY(0);
        addView(art);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(Math.round(now.left), Math.round(now.top),
                        Math.round(now.right), Math.round(now.bottom), radius);
            }
        });
        setClipToOutline(true);
        setVisibility(INVISIBLE);
    }

    /** Sets up the hero over {@code bounds} (window coordinates) showing {@code drawable}. */
    public void prepare(Drawable drawable, int background, boolean fillScreen, RectF bounds, float cornerRadius) {
        fill = fillScreen;
        from.set(bounds);
        fromRadius = cornerRadius;
        setBackgroundColor(background);
        art.setImageDrawable(drawable);
        LayoutParams lp = fill
                ? new LayoutParams(getWidth(), getHeight())
                : new LayoutParams(Math.round(bounds.width()), Math.round(bounds.height()));
        art.setLayoutParams(lp);
        // Lay the art out immediately so the first frame already has it at the right size.
        art.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY));
        art.layout(0, 0, lp.width, lp.height);
        setProgress(0f);
    }

    /** 0 = exactly over the tile, 1 = full screen. */
    public void setProgress(float t) {
        int w = getWidth(), h = getHeight();
        now.set(lerp(from.left, 0, t), lerp(from.top, 0, t), lerp(from.right, w, t), lerp(from.bottom, h, t));
        radius = lerp(fromRadius, 0, t);
        invalidateOutline();
        if (fill) {
            // The picture is laid out full screen and scaled down to the current bounds.
            art.setScaleX(now.width() / Math.max(1, w));
            art.setScaleY(now.height() / Math.max(1, h));
            art.setTranslationX(now.left);
            art.setTranslationY(now.top);
        } else {
            float s = lerp(1f, BANNER_GROWTH, t);
            float cx = now.centerX(), cy = now.centerY();
            art.setScaleX(s);
            art.setScaleY(s);
            art.setTranslationX(cx - from.width() * s / 2f);
            art.setTranslationY(cy - from.height() * s / 2f);
        }
    }

    public void clear() {
        setVisibility(INVISIBLE);
        setAlpha(1f);
        art.setImageDrawable(null);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** The color at the left edge of a drawable, used to fill the screen around its banner. */
    public static int edgeColor(Drawable d, int fallback) {
        if (d == null) return fallback;
        try {
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 32, 18);
            copy.draw(new Canvas(b));
            int c = b.getPixel(1, 9);
            b.recycle();
            return (c >>> 24) < 200 ? fallback : c | 0xFF000000;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
