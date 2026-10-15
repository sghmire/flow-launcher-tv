package com.sghmire.flow;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;

/**
 * Horizontal row that glides to keep the focused item in view. With {@code anchorStart}
 * the focused item is pinned to the left edge (cards row); otherwise it scrolls only as
 * much as needed (dock).
 */
public class RowScrollView extends HorizontalScrollView {
    private final boolean anchorStart;
    private ValueAnimator scroller;

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
        // Scrolling is driven by requestChildFocus so the motion stays on one animator.
        return false;
    }

    private int targetFor(View focused) {
        if (getChildCount() == 0) return 0;
        View content = getChildAt(0);
        int left = focused.getLeft(), right = focused.getRight();
        int max = Math.max(0, content.getWidth() - getWidth());
        int target;
        if (anchorStart) {
            target = left - content.getPaddingLeft();
        } else {
            int pad = content.getPaddingLeft();
            int x = getScrollX();
            if (left - pad < x) target = left - pad;
            else if (right + pad > x + getWidth()) target = right + pad - getWidth();
            else target = x;
        }
        return Math.max(0, Math.min(max, target));
    }

    private void glideTo(int target) {
        if (scroller != null) scroller.cancel();
        int from = getScrollX();
        if (from == target) return;
        scroller = ValueAnimator.ofInt(from, target);
        scroller.setDuration(320);
        scroller.setInterpolator(new DecelerateInterpolator(1.8f));
        scroller.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
        scroller.start();
    }

    public void reset() {
        if (scroller != null) scroller.cancel();
        scrollTo(0, 0);
    }
}
