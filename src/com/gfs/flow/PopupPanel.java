package com.gfs.flow;

import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Flow's own popup: a small dark panel that grows out of a tile (used for the tile menu and
 * the icon picker) instead of the stock Android dialog. Back closes it.
 */
public class PopupPanel extends FrameLayout {
    private static final PathInterpolator OPEN = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final HomeActivity home;
    private final LinearLayout panel;
    private final TextView title;
    private final FrameLayout body;
    private View anchor;
    private boolean showing;

    public PopupPanel(HomeActivity home) {
        super(home);
        this.home = home;
        setBackgroundColor(0x80000000);
        setVisibility(INVISIBLE);
        setClipChildren(false);

        panel = new LinearLayout(home);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(home.px(12), home.px(14), home.px(12), home.px(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(home.px(22));
        bg.setColor(0xF51D1E22);
        bg.setStroke(Math.max(1, home.px(0.75f)), 0x24FFFFFF);
        panel.setBackground(bg);

        title = new TextView(home);
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setPadding(home.px(12), 0, home.px(12), home.px(8));
        panel.addView(title);

        body = new FrameLayout(home);
        body.setClipChildren(false);
        panel.addView(body);

        addView(panel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public boolean isShowing() {
        return showing;
    }

    /** Shows {@code content} in the panel, growing out of {@code from}. */
    public void show(View from, String heading, View content) {
        showing = true;
        anchor = from;
        title.setText(heading);
        body.removeAllViews();
        body.addView(content);
        setVisibility(VISIBLE);
        setAlpha(0f);
        panel.setAlpha(0f);
        post(() -> {
            RectF r = home.boundsOf(anchor);
            int w = panel.getWidth(), h = panel.getHeight(), margin = home.px(24);
            float x = Math.max(margin, Math.min(getWidth() - w - margin, r.centerX() - w / 2f));
            float y = r.top - h - home.px(18);
            if (y < margin) y = Math.min(getHeight() - h - margin, r.bottom + home.px(18));
            panel.setX(x);
            panel.setY(y);
            panel.setPivotX(Math.max(0, Math.min(w, r.centerX() - x)));
            panel.setPivotY(r.centerY() < y ? 0 : h);
            panel.setScaleX(0.7f);
            panel.setScaleY(0.7f);
            animate().alpha(1f).setDuration(HomeActivity.ms(200)).start();
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(HomeActivity.ms(300))
                    .setInterpolator(OPEN).withLayer().start();
            View first = content.focusSearch(View.FOCUS_DOWN);
            if (content.isFocusable()) content.requestFocus();
            else if (content instanceof ViewGroup) ((ViewGroup) content).getChildAt(0).requestFocus();
            else if (first != null) first.requestFocus();
        });
    }

    public void hide(boolean animate) {
        if (!showing) return;
        showing = false;
        if (!animate) {
            setVisibility(INVISIBLE);
            body.removeAllViews();
            home.onPopupHidden(anchor);
            return;
        }
        animate().alpha(0f).setDuration(HomeActivity.ms(180)).start();
        panel.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(HomeActivity.ms(180))
                .setInterpolator(new DecelerateInterpolator()).withLayer().withEndAction(() -> {
                    setVisibility(INVISIBLE);
                    body.removeAllViews();
                }).start();
        home.onPopupHidden(anchor);
    }

    /** A vertical list of menu actions. */
    public LinearLayout menu(String[] labels, Runnable[] actions) {
        LinearLayout list = new LinearLayout(home);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            TextView item = new TextView(home);
            item.setText(labels[i]);
            item.setTextColor(Color.WHITE);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinWidth(home.px(220));
            item.setPadding(home.px(14), home.px(10), home.px(14), home.px(10));
            item.setFocusable(true);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(home.px(12));
            pill.setColor(Color.TRANSPARENT);
            item.setBackground(pill);
            item.setOnFocusChangeListener((v, f) -> {
                pill.setColor(f ? 0x2EFFFFFF : Color.TRANSPARENT);
                v.animate().translationX(f ? home.px(3) : 0).setDuration(HomeActivity.ms(150)).start();
            });
            Runnable action = actions[i];
            item.setOnClickListener(v -> {
                hide(true);
                post(action);
            });
            list.addView(item);
        }
        return list;
    }
}
