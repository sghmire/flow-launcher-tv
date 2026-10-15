package com.sghmire.flow;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {
    private static final String TAG = "Flow";
    private static final int REQ_TV_LISTINGS = 1;
    private static final String PERM_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    /** Dock order copied from the ChillHub setup; everything else follows alphabetically. */
    private static final List<String> FAVORITES = Arrays.asList(
            "com.tubs.tubplayer",
            "com.netflix.ninja",
            "space.nuvio.nativelegacy",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tv");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageLoader images;
    private float dp;

    private FrameLayout content;
    private TextView cardsLabel;
    private RowScrollView cardsScroll, dockScroll;
    private LinearLayout cardsRow, dockRow;

    private View lastLaunched;
    private boolean returningFromApp;
    private boolean firstAppsLoad = true;
    private long lastWatchNextLoad;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    private final ContentObserver watchNextObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            loadWatchNext();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        images = new ImageLoader(getContentResolver());
        setContentView(buildUi());
        // The backdrop covers the whole window, so skip painting the theme background under it.
        getWindow().setBackgroundDrawable(null);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        loadApps();
        if (checkSelfPermission(PERM_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED) {
            startWatchNext();
        } else {
            requestPermissions(new String[] {PERM_TV_LISTINGS}, REQ_TV_LISTINGS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_TV_LISTINGS) startWatchNext();
    }

    private void startWatchNext() {
        try {
            getContentResolver().registerContentObserver(
                    TvContract.WatchNextPrograms.CONTENT_URI, true, watchNextObserver);
        } catch (Exception e) {
            Log.w(TAG, "watch next observer: " + e);
        }
        loadWatchNext();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        getContentResolver().unregisterContentObserver(watchNextObserver);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returningFromApp) {
            returningFromApp = false;
            playReturnAnimation();
        }
        if (System.currentTimeMillis() - lastWatchNextLoad > 15_000) loadWatchNext();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: glide back to the start of the dock.
        if (hasWindowFocus() && !returningFromApp && dockRow.getChildCount() > 0) {
            cardsScroll.reset();
            dockScroll.reset();
            dockRow.getChildAt(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to.
    }

    // ---------------------------------------------------------------- UI

    private int px(float v) {
        return Math.round(v * dp);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new BackdropView(this), match());

        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, match());

        // Top-right: settings button and clock.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setAlpha(0.75f);
        int gp = px(6);
        gear.setPadding(gp, gp, gp, gp);
        GradientDrawable gearBg = new GradientDrawable();
        gearBg.setShape(GradientDrawable.OVAL);
        gearBg.setColor(Color.TRANSPARENT);
        gear.setBackground(gearBg);
        gear.setFocusable(true);
        gear.setFocusedByDefault(false);
        gear.setOnFocusChangeListener((v, f) -> {
            gearBg.setColor(f ? 0x33FFFFFF : Color.TRANSPARENT);
            v.animate().alpha(f ? 1f : 0.75f).scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f)
                    .setDuration(160).start();
        });
        gear.setOnClickListener(v -> launch(v, new Intent(Settings.ACTION_SETTINGS)));
        top.addView(gear, new LinearLayout.LayoutParams(px(32), px(32)));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = px(14);
        top.addView(clock, clp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.topMargin = px(18);
        tlp.rightMargin = px(40);
        content.addView(top, tlp);

        // Continue Watching row.
        LinearLayout upper = new LinearLayout(this);
        upper.setOrientation(LinearLayout.VERTICAL);
        upper.setClipChildren(false);
        cardsLabel = new TextView(this);
        cardsLabel.setText("Continue Watching");
        cardsLabel.setTextColor(0xB3FFFFFF);
        cardsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cardsLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cardsLabel.setPadding(px(45), 0, 0, 0);
        upper.addView(cardsLabel);

        cardsScroll = new RowScrollView(this, true);
        cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setClipChildren(false);
        cardsRow.setPadding(px(45), px(16), px(45), px(16));
        cardsScroll.addView(cardsRow);
        upper.addView(cardsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = px(62);
        content.addView(upper, ulp);

        // App dock.
        FrameLayout dock = new FrameLayout(this);
        dock.setClipChildren(false);
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0x14FFFFFF);
        dockBg.setStroke(Math.max(1, px(0.75f)), 0x1FFFFFFF);
        dockBg.setCornerRadius(px(28));
        dock.setBackground(dockBg);
        roundCorners(dock, px(28));
        dockScroll = new RowScrollView(this, false);
        dockScroll.setClipChildren(true);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setPadding(px(20), 0, px(20), 0);
        dockScroll.addView(dockRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dock.addView(dockScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(112), Gravity.BOTTOM);
        dlp.leftMargin = px(30);
        dlp.rightMargin = px(30);
        dlp.bottomMargin = px(34);
        content.addView(dock, dlp);

        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void roundCorners(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** Focus treatment shared by cards and app tiles: lift and a thin white ring (no shadows: they cost GPU time). */
    private void attachFocusEffect(View v, float scale, float radius) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(px(2), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        ring.setAlpha(0);
        v.setForeground(ring);
        v.setOnFocusChangeListener((view, focused) -> {
            view.animate()
                    .scaleX(focused ? scale : 1f)
                    .scaleY(focused ? scale : 1f)
                    .setDuration(focused ? 240 : 180)
                    .setInterpolator(focused ? new OvershootInterpolator(1.1f) : new DecelerateInterpolator())
                    .start();
            ring.setAlpha(focused ? 230 : 0);
        });
    }

    // ---------------------------------------------------------------- Apps

    private static final class AppEntry {
        final String pkg;
        final ComponentName component;
        final String label;
        final Drawable banner;
        final Drawable icon;

        AppEntry(String pkg, ComponentName component, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
            List<AppEntry> favorites = new ArrayList<>();
            List<AppEntry> rest = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                AppEntry e = new AppEntry(pkg, new ComponentName(pkg, ri.activityInfo.name),
                        String.valueOf(ri.loadLabel(pm)), banner, ri.loadIcon(pm));
                (FAVORITES.contains(pkg) ? favorites : rest).add(e);
            }
            Collections.sort(favorites, (a, b) -> FAVORITES.indexOf(a.pkg) - FAVORITES.indexOf(b.pkg));
            Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
            List<AppEntry> all = new ArrayList<>(favorites);
            all.addAll(rest);
            main.post(() -> showApps(all));
        });
    }

    private void showApps(List<AppEntry> apps) {
        String focusedPkg = null;
        View f = dockRow.findFocus();
        if (f != null && f.getTag() instanceof String) focusedPkg = (String) f.getTag();
        dockRow.removeAllViews();
        View toFocus = null;
        for (AppEntry app : apps) {
            View tile = appTile(app);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(122), px(72));
            lp.leftMargin = px(9);
            lp.rightMargin = px(9);
            dockRow.addView(tile, lp);
            if (app.pkg.equals(focusedPkg)) toFocus = tile;
        }
        if (toFocus != null) toFocus.requestFocus();
        else if (dockRow.getChildCount() > 0 && (firstAppsLoad || getCurrentFocus() == null)) {
            dockRow.getChildAt(0).requestFocus();
        }
        firstAppsLoad = false;
    }

    private View appTile(AppEntry app) {
        float radius = px(12);
        FrameLayout tile = new FrameLayout(this);
        tile.setTag(app.pkg);
        tile.setFocusable(true);
        tile.setContentDescription(app.label);
        roundCorners(tile, radius);
        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            tile.addView(iv, match());
        } else {
            tile.setBackground(new ColorDrawable(0xFF27324A));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(app.icon);
            col.addView(iv, new LinearLayout.LayoutParams(px(30), px(30)));
            TextView label = new TextView(this);
            label.setText(app.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setSingleLine(true);
            label.setPadding(px(6), px(3), px(6), 0);
            col.addView(label);
            tile.addView(col, match());
        }
        attachFocusEffect(tile, 1.16f, radius);
        tile.setOnClickListener(v -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
            if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
            launch(v, i);
        });
        return tile;
    }

    // ---------------------------------------------------------------- Continue Watching

    private static final class WatchItem {
        String title, image, intentUri, pkg;
        long engaged, position, duration;
    }

    private void loadWatchNext() {
        lastWatchNextLoad = System.currentTimeMillis();
        io.execute(() -> {
            List<WatchItem> items = new ArrayList<>();
            try (Cursor c = getContentResolver().query(
                    TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (getInt(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                        WatchItem w = new WatchItem();
                        w.title = getString(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                        w.image = getString(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                        if (w.image == null) w.image = getString(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                        w.intentUri = getString(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                        w.pkg = getString(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                        w.engaged = getLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                        w.position = getLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                        w.duration = getLong(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                        items.add(w);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "watch next query failed: " + e);
            }
            Collections.sort(items, (a, b) -> Long.compare(b.engaged, a.engaged));
            if (items.size() > 20) items.subList(20, items.size()).clear();
            Log.i(TAG, "watch next items: " + items.size());
            main.post(() -> showCards(items));
        });
    }

    private static String getString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static int getInt(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? def : c.getInt(i);
    }

    private void showCards(List<WatchItem> items) {
        boolean hadFocus = cardsRow.hasFocus();
        int focusedIndex = hadFocus ? cardsRow.indexOfChild(cardsRow.getFocusedChild()) : -1;
        cardsRow.removeAllViews();
        cardsLabel.setVisibility(items.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        for (WatchItem item : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(400), px(225));
            lp.rightMargin = px(30);
            cardsRow.addView(card(item), lp);
        }
        if (hadFocus && cardsRow.getChildCount() > 0) {
            cardsRow.getChildAt(Math.min(Math.max(focusedIndex, 0), cardsRow.getChildCount() - 1)).requestFocus();
        }
    }

    private View card(WatchItem item) {
        float radius = px(14);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setBackground(new ColorDrawable(0xFF1B2538));
        card.setContentDescription(item.title);
        roundCorners(card, radius);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(0xCCFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setGravity(Gravity.CENTER);
        title.setPadding(px(16), 0, px(16), 0);
        card.addView(title, match());

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, match());
        if (item.image != null) images.load(item.image, iv, px(400), px(225));

        if (item.duration > 0 && item.position > 0) {
            float progress = Math.min(1f, item.position / (float) item.duration);
            View track = new View(this);
            track.setBackground(new ColorDrawable(0x55FFFFFF));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(4), Gravity.BOTTOM);
            card.addView(track, tl);
            View bar = new View(this);
            bar.setBackground(new ColorDrawable(0xFFE53935));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                    Math.round(px(400) * progress), px(4), Gravity.BOTTOM);
            card.addView(bar, bl);
        }

        attachFocusEffect(card, 1.05f, radius);
        card.setOnClickListener(v -> {
            Intent i = null;
            if (item.intentUri != null) {
                try {
                    i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                } catch (Exception e) {
                    Log.w(TAG, "bad intent uri: " + e);
                }
            }
            if (i == null && item.pkg != null) i = getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
            if (i != null) launch(v, i);
        });
        return card;
    }

    // ---------------------------------------------------------------- Launch / return animations

    /** Opens the app growing out of the tile that was clicked. */
    private void launch(View from, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(
                from, 0, 0, from.getWidth(), from.getHeight());
        // Quick press feedback on the tile while the app window grows out of it.
        from.animate().scaleX(from.getScaleX() * 0.94f).scaleY(from.getScaleY() * 0.94f)
                .setDuration(90).withEndAction(() -> from.animate()
                        .scaleX(from.hasFocus() ? from.getScaleX() / 0.94f : 1f)
                        .scaleY(from.hasFocus() ? from.getScaleY() / 0.94f : 1f)
                        .setDuration(140).start())
                .start();
        try {
            startActivity(intent, opts.toBundle());
            lastLaunched = from;
            returningFromApp = true;
        } catch (Exception e) {
            Log.w(TAG, "launch failed: " + e);
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    /** Coming home: the screen settles back down into the tile the app was opened from. */
    private void playReturnAnimation() {
        View tile = lastLaunched;
        if (tile == null || !tile.isAttachedToWindow()) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(300).start();
            return;
        }
        int[] loc = new int[2];
        tile.getLocationInWindow(loc);
        content.setPivotX(loc[0] + tile.getWidth() / 2f);
        content.setPivotY(loc[1] + tile.getHeight() / 2f);
        content.setScaleX(1.18f);
        content.setScaleY(1.18f);
        content.setAlpha(0f);
        content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(520)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();
        tile.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu on an app tile opens its system App info page.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", (String) f.getTag(), null));
                launch(f, i);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
