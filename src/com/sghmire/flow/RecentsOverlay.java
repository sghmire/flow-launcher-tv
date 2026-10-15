package com.sghmire.flow;

import android.animation.LayoutTransition;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Recent apps, opened by pressing Home twice. Left/right to browse, OK to open,
 * Up to swipe an app away (closes it), "Clear all" to close everything listed.
 */
public class RecentsOverlay extends FrameLayout {
    /** One recently used app. */
    public static final class Recent {
        final String pkg;
        final long lastUsed;

        public Recent(String pkg, long lastUsed) {
            this.pkg = pkg;
            this.lastUsed = lastUsed;
        }
    }

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final HomeActivity home;
    private final LinearLayout row;
    private final RowScrollView scroll;
    private final TextView empty;
    private final TextView clearAll;
    private boolean showing;

    public RecentsOverlay(HomeActivity home) {
        super(home);
        this.home = home;
        setBackgroundColor(0xF2060A14);
        setVisibility(INVISIBLE);
        setClipChildren(false);

        LinearLayout header = new LinearLayout(home);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(home.px(45), home.px(48), 0, 0);
        TextView title = new TextView(home);
        title.setText("Recent apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title);
        TextView hint = new TextView(home);
        hint.setText("OK to open  ·  Up to close an app  ·  Back to return");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, home.px(6), 0, 0);
        header.addView(hint);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new RowScrollView(home, false);
        row = new LinearLayout(home);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(home.px(45), home.px(30), home.px(45), home.px(30));
        LayoutTransition lt = new LayoutTransition();
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        lt.setDuration(HomeActivity.ms(260));
        row.setLayoutTransition(lt);
        scroll.addView(row);
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        addView(scroll, slp);

        empty = new TextView(home);
        empty.setText("No recent apps");
        empty.setTextColor(0x99FFFFFF);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        clearAll = new TextView(home);
        clearAll.setText("Clear all");
        clearAll.setTextColor(Color.WHITE);
        clearAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setPadding(home.px(22), home.px(8), home.px(22), home.px(8));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(home.px(20));
        pill.setColor(0x26FFFFFF);
        clearAll.setBackground(pill);
        clearAll.setFocusable(true);
        clearAll.setOnFocusChangeListener((v, f) -> {
            pill.setColor(f ? 0x4DFFFFFF : 0x26FFFFFF);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(HomeActivity.ms(180)).start();
        });
        clearAll.setOnClickListener(v -> clearEverything());
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = home.px(48);
        addView(clearAll, clp);
    }

    public boolean isShowing() {
        return showing;
    }

    public void show(List<Recent> recents) {
        showing = true;
        row.removeAllViews();
        for (Recent r : recents) row.addView(card(r));
        boolean none = recents.isEmpty();
        empty.setVisibility(none ? VISIBLE : GONE);
        clearAll.setVisibility(none ? GONE : VISIBLE);
        scroll.reset();

        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(HomeActivity.ms(220)).setInterpolator(EASE).start();
        // Cards rise into place one after another.
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            c.setTranslationY(home.px(36));
            c.setAlpha(0f);
            c.animate().translationY(0).alpha(1f).setStartDelay(HomeActivity.ms(40L * Math.min(i, 6)))
                    .setDuration(HomeActivity.ms(320)).setInterpolator(EASE).start();
        }
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        else clearAll.requestFocus();
    }

    public void hide(boolean animate) {
        if (!showing) return;
        showing = false;
        if (!animate) {
            setVisibility(INVISIBLE);
            row.removeAllViews();
            home.onRecentsHidden();
            return;
        }
        animate().alpha(0f).setDuration(HomeActivity.ms(200)).setInterpolator(EASE).withEndAction(() -> {
            setVisibility(INVISIBLE);
            row.removeAllViews();
        }).start();
        home.onRecentsHidden();
    }

    private View card(Recent r) {
        LinearLayout card = new LinearLayout(home);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setTag(r.pkg);
        card.setClipChildren(false);

        FrameLayout art = new FrameLayout(home);
        float radius = home.px(16);
        home.roundCorners(art, radius);
        art.setBackgroundColor(home.brandColorFor(r.pkg));
        ImageView iv = new ImageView(home);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(home.bannerFor(r.pkg));
        art.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(art, new LinearLayout.LayoutParams(home.px(256), home.px(144)));

        TextView label = new TextView(home);
        label.setText(home.labelFor(r.pkg));
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setPadding(home.px(4), home.px(12), 0, 0);
        card.addView(label);
        TextView when = new TextView(home);
        when.setText(DateUtils.getRelativeTimeSpanString(r.lastUsed, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS));
        when.setTextColor(0x80FFFFFF);
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        when.setPadding(home.px(4), home.px(2), 0, 0);
        card.addView(when);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = home.px(30);
        card.setLayoutParams(lp);

        card.setOnFocusChangeListener((v, f) -> v.animate()
                .scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f)
                .setDuration(HomeActivity.ms(f ? 220 : 180)).setInterpolator(EASE).start());
        card.setOnClickListener(v -> home.openRecent(art, r.pkg, radius));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) dismiss(card);
                return true;
            }
            return false;
        });
        return card;
    }

    /** Swipe a card up and away, closing its app. */
    private void dismiss(View card) {
        if (card.getTag(R.id.flow_dismissing) != null) return;
        card.setTag(R.id.flow_dismissing, Boolean.TRUE);
        String pkg = (String) card.getTag();
        int index = row.indexOfChild(card);
        View next = index + 1 < row.getChildCount() ? row.getChildAt(index + 1)
                : index > 0 ? row.getChildAt(index - 1) : clearAll;
        card.animate().translationY(-home.px(160)).alpha(0f).setStartDelay(0)
                .setDuration(HomeActivity.ms(280)).setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    next.requestFocus();
                    row.removeView(card);
                    home.closeApp(pkg);
                    if (row.getChildCount() == 0) {
                        empty.setVisibility(VISIBLE);
                        clearAll.setVisibility(GONE);
                        postDelayed(() -> hide(true), HomeActivity.ms(450));
                    }
                }).start();
    }

    private void clearEverything() {
        int n = row.getChildCount();
        if (n == 0) {
            hide(true);
            return;
        }
        for (int i = 0; i < n; i++) {
            View c = row.getChildAt(i);
            home.closeApp((String) c.getTag());
            c.animate().translationY(-home.px(160)).alpha(0f).setStartDelay(HomeActivity.ms(45L * i))
                    .setDuration(HomeActivity.ms(260)).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
        postDelayed(() -> hide(true), HomeActivity.ms(260 + 45L * n));
    }
}
