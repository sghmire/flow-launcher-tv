package com.sghmire.flow;

import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.List;

/**
 * The Hidden apps folder: a panel that grows out of the Hidden tile in the dock and shows the
 * hidden apps in a grid. OK opens an app, holding OK (or Menu) offers "Show on home" and
 * App info, Back folds the panel back into its tile.
 */
public class FolderOverlay extends FrameLayout {
    private static final int COLUMNS = 3;
    private static final PathInterpolator OPEN = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final HomeActivity home;
    private final LinearLayout panel;
    private final LinearLayout grid;
    private View origin;
    private boolean showing;

    public FolderOverlay(HomeActivity home) {
        super(home);
        this.home = home;
        setBackgroundColor(0x99000000);
        setVisibility(INVISIBLE);
        setClipChildren(false);

        panel = new LinearLayout(home);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = home.px(28);
        panel.setPadding(pad, home.px(22), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(home.px(28));
        bg.setColor(0xF21D1E22);
        bg.setStroke(Math.max(1, home.px(0.75f)), 0x24FFFFFF);
        panel.setBackground(bg);

        LinearLayout header = new LinearLayout(home);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(home);
        mark.setImageResource(R.drawable.ic_flow_mark);
        header.addView(mark, new LinearLayout.LayoutParams(home.px(34), home.px(21)));
        panel.addView(header);

        grid = new LinearLayout(home);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = home.px(18);
        panel.addView(grid, glp);

        addView(panel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    public boolean isShowing() {
        return showing;
    }

    public void show(List<String> pkgs, View from) {
        showing = true;
        origin = from;
        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < pkgs.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(home);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = home.px(18);
                grid.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(home.px(150), home.px(88));
            if (i % COLUMNS > 0) lp.leftMargin = home.px(18);
            row.addView(tile(pkgs.get(i)), lp);
        }

        setVisibility(VISIBLE);
        setAlpha(0f);
        panel.setAlpha(0f);
        // Lay out first so the panel can grow from the exact spot of the Hidden tile.
        post(() -> {
            RectF r = home.boundsOf(origin);
            float dx = r.centerX() - (panel.getLeft() + panel.getWidth() / 2f);
            float dy = r.centerY() - (panel.getTop() + panel.getHeight() / 2f);
            float s = Math.max(0.15f, r.width() / Math.max(1f, panel.getWidth()));
            panel.setTranslationX(dx);
            panel.setTranslationY(dy);
            panel.setScaleX(s);
            panel.setScaleY(s);
            animate().alpha(1f).setDuration(HomeActivity.ms(260)).start();
            panel.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(HomeActivity.ms(420)).setInterpolator(OPEN).withLayer().start();
            View first = firstTile();
            if (first != null) first.requestFocus();
        });
    }

    public void hide(boolean animate) {
        if (!showing) return;
        showing = false;
        if (!animate || origin == null || !origin.isAttachedToWindow()) {
            setVisibility(INVISIBLE);
            grid.removeAllViews();
            home.onFolderHidden();
            return;
        }
        RectF r = home.boundsOf(origin);
        float dx = r.centerX() - (panel.getLeft() + panel.getWidth() / 2f);
        float dy = r.centerY() - (panel.getTop() + panel.getHeight() / 2f);
        float s = Math.max(0.15f, r.width() / Math.max(1f, panel.getWidth()));
        animate().alpha(0f).setDuration(HomeActivity.ms(300)).start();
        panel.animate().translationX(dx).translationY(dy).scaleX(s).scaleY(s).alpha(0f)
                .setDuration(HomeActivity.ms(320)).setInterpolator(new DecelerateInterpolator(1.5f))
                .withLayer().withEndAction(() -> {
                    setVisibility(INVISIBLE);
                    grid.removeAllViews();
                }).start();
        home.onFolderHidden();
    }

    private View firstTile() {
        if (grid.getChildCount() == 0) return null;
        ViewGroup row = (ViewGroup) grid.getChildAt(0);
        return row.getChildCount() > 0 ? row.getChildAt(0) : null;
    }

    private View tile(String pkg) {
        float radius = home.px(12);
        FrameLayout tile = new FrameLayout(home);
        tile.setTag(pkg);
        tile.setFocusable(true);
        tile.setContentDescription(home.labelFor(pkg));
        home.roundCorners(tile, radius);
        tile.setBackgroundColor(home.brandColorFor(pkg));
        ImageView iv = new ImageView(home);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(home.bannerFor(pkg));
        tile.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.setOnFocusChangeListener((v, f) -> v.animate()
                .scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f)
                .setDuration(HomeActivity.ms(f ? 220 : 180))
                .setInterpolator(new DecelerateInterpolator(2f)).start());
        tile.setOnClickListener(v -> home.openHidden(v, pkg, radius, origin));
        tile.setOnLongClickListener(v -> {
            home.showHiddenMenu(pkg);
            return true;
        });
        return tile;
    }
}
