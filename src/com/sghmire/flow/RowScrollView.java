package com.sghmire.flow;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * Horizontal row that follows the focused item with a critically damped spring. Retargeting
 * mid-flight keeps the current velocity, so holding the D-pad glides instead of stuttering
 * through restarted animations. With {@code anchorStart} the focused item is pinned to the
 * left edge (cards row); otherwise it scrolls only as much as needed (dock).
 */
public class RowScrollView extends HorizontalScrollView {
    /** Spring stiffness; settles in roughly 4 / OMEGA seconds. */
    private static final float OMEGA = 17f;

    private final boolean anchorStart;
    private float pos, vel;
    private int target;
    private boolean running;
    private long lastFrameNanos;
    /** When set, the row scrolls in whole-tile steps so exactly {@code perView} tiles show. */
    private int step, perView;

    private final Runnable frame = this::step;

    public RowScrollView(Context context, boolean anchorStart) {
        super(context);
        this.anchorStart = anchorStart;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
        setSmoothScrollingEnabled(false);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        glideTo(targetFor(focused));
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        // Scrolling is driven by requestChildFocus so the motion stays on one spring.
        return false;
    }

    private int targetFor(View focused) {
        if (getChildCount() == 0) return 0;
        View content = getChildAt(0);
        int left = focused.getLeft(), right = focused.getRight();
        int max = Math.max(0, content.getWidth() - getWidth());
        int pad = content.getPaddingLeft();
        int t;
        if (anchorStart) {
            t = left - pad;
        } else if (step > 0 && content instanceof android.view.ViewGroup) {
            int index = ((android.view.ViewGroup) content).indexOfChild(focused);
            int count = ((android.view.ViewGroup) content).getChildCount();
            int first = Math.round((running ? target : getScrollX()) / (float) step);
            if (index < first) first = index;
            if (index > first + perView - 1) first = index - perView + 1;
            first = Math.max(0, Math.min(Math.max(0, count - perView), first));
            t = first * step;
        } else {
            // Measure against where the spring is heading, not where it is mid-flight.
            int x = running ? target : getScrollX();
            if (left - pad < x) t = left - pad;
            else if (right + pad > x + getWidth()) t = right + pad - getWidth();
            else t = x;
        }
        return Math.max(0, Math.min(max, t));
    }

    private void glideTo(int t) {
        target = t;
        if (running) return;
        if (getScrollX() == t) return;
        pos = getScrollX();
        vel = 0;
        running = true;
        lastFrameNanos = System.nanoTime();
        postOnAnimation(frame);
    }

    private void step() {
        if (!running) return;
        long now = System.nanoTime();
        float dt = Math.min(0.034f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        // Two half-steps keep the integration stable when a frame runs long.
        for (int i = 0; i < 2; i++) {
            float h = dt / 2f;
            float accel = -OMEGA * OMEGA * (pos - target) - 2f * OMEGA * vel;
            vel += accel * h;
            pos += vel * h;
        }
        if (Math.abs(pos - target) < 0.5f && Math.abs(vel) < 8f) {
            running = false;
            scrollTo(target, 0);
            return;
        }
        scrollTo(Math.round(pos), 0);
        postOnAnimation(frame);
    }

    public void setPaging(int step, int perView) {
        this.step = step;
        this.perView = perView;
    }

    public void reset() {
        running = false;
        removeCallbacks(frame);
        scrollTo(0, 0);
    }
}
