package com.gfs.flow;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
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

    private static final DecelerateInterpolator EASE_OUT = new DecelerateInterpolator(2f);

    private static final int DOCK_PER_VIEW = 6;
    private static final int DOCK_TILE_W = 122;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageLoader images;
    private float dp;

    private FrameLayout content;
    /** Backdrop + content; the part of the screen the pinhole opens and closes over. */
    private FrameLayout stage;
    /** Copy of the tile being opened or returned to; grows to full screen and back. */
    private HeroView hero;
    private RecentsOverlay recents;
    private FolderOverlay folder;
    private PopupPanel popup;
    private View focusBeforeFolder;
    /** Where the return animation should land when an app was opened from Recents. */
    private View returnTarget;
    private View focusBeforeRecents;
    private long lastHomePress;
    private final java.util.Map<String, AppEntry> appsByPkg = new java.util.HashMap<>();
    private SharedPreferences prefs;
    private String pendingFocusPkg;
    private ValueAnimator shrinkAnim;
    /**
     * Dark cover shown behind a closing app. It is the same color as the Recents backdrop, so a
     * double-press of Home goes app -> dark -> Recents without home showing in between.
     */
    private View returnCover;
    private Drawable lastArt;
    private boolean lastFill;
    private float lastRadius;
    private int lastBg;
    private boolean launching;
    private boolean revealPending;
    private TextView cardsLabel;
    private View upperRow;
    private RowScrollView cardsScroll, dockScroll;
    private LinearLayout cardsRow, dockRow;

    private View lastLaunched;
    private boolean returningFromApp;
    private boolean firstAppsLoad = true;
    private boolean returnStaged;
    private String cardsSignature;
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
        prefs = getSharedPreferences("flow", MODE_PRIVATE);
        setContentView(buildUi());
        // The backdrop covers the whole window, so skip painting the theme background under it.
        getWindow().setBackgroundDrawable(null);
        // The top row follows whichever app is focused in the dock.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null && newF.getParent() == dockRow) {
                onDockFocus(newF.getTag() instanceof String ? (String) newF.getTag() : null);
            }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        loadApps();
        handleTileImage(getIntent());
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
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        // The system has finished fading the app away; open the pinhole now if still waiting.
        if (revealPending) {
            main.removeCallbacks(revealNow);
            revealNow.run();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (returningFromApp) stageReturnAnimation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean animating = returningFromApp;
        if (returningFromApp) {
            returningFromApp = false;
            playReturnAnimation();
        }
        if (System.currentTimeMillis() - lastWatchNextLoad > 15_000) {
            // Refresh after the return animation so a row rebuild never lands mid-motion.
            if (animating) main.postDelayed(this::loadWatchNext, 900);
            else loadWatchNext();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleTileImage(intent)) return;
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            long now = SystemClock.uptimeMillis();
            boolean doublePress = now - lastHomePress < 550;
            lastHomePress = doublePress ? 0 : now;
            if (doublePress && !recents.isShowing()) {
                showRecents();
                return;
            }
            if (moveTile != null) endMove(false);
            if (popup.isShowing()) popup.hide(false);
            if (recents.isShowing()) {
                recents.hide(true);
                return;
            }
            if (folder.isShowing()) {
                folder.hide(true);
                return;
            }
        }
        // Home pressed while already home: glide back to the start of the dock.
        if (hasWindowFocus() && !returningFromApp && dockRow.getChildCount() > 0) {
            cardsScroll.reset();
            dockScroll.reset();
            dockRow.getChildAt(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to; Back only closes the Recents screen.
        if (popup.isShowing()) popup.hide(true);
        else if (recents.isShowing()) recents.hide(true);
        else if (folder.isShowing()) folder.hide(true);
    }

    // ---------------------------------------------------------------- UI

    int px(float v) {
        return Math.round(v * dp);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        root.addView(stage, match());
        stage.addView(new BackdropView(this), match());

        content = new FrameLayout(this);
        content.setClipChildren(false);
        stage.addView(content, match());

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
                    .setDuration(ms(160)).start();
        });
        gear.setContentDescription("Settings");
        gear.setOnClickListener(v -> showPopup(v, "Settings", popup.menu(
                new String[] {"Screensaver — Flow Clock", "Android settings"},
                new Runnable[] {
                        () -> openScreensaverSettings(),
                        () -> startActivity(new Intent(Settings.ACTION_SETTINGS))})));
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
        upperRow = upper;
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
        cardsRow.setClipToPadding(false);
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
        dockRow.setClipToPadding(false);
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

        moveHint = new TextView(this);
        moveHint.setText("\u25C0 \u25B6  Move      OK  Done      Back  Cancel");
        moveHint.setTextColor(0xCCFFFFFF);
        moveHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        moveHint.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.bottomMargin = px(34 + 112 + 14);
        content.addView(moveHint, mlp);

        returnCover = new View(this);
        returnCover.setBackgroundColor(RecentsOverlay.BACKDROP);
        returnCover.setVisibility(View.INVISIBLE);
        root.addView(returnCover, match());
        folder = new FolderOverlay(this);
        root.addView(folder, match());
        recents = new RecentsOverlay(this);
        root.addView(recents, match());
        popup = new PopupPanel(this);
        root.addView(popup, match());
        hero = new HeroView(this);
        root.addView(hero, match());
        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    void roundCorners(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** Focus treatment shared by cards and app tiles: a smooth zoom, nothing else. */
    private void attachFocusEffect(View v, float scale) {
        v.setOnFocusChangeListener((view, focused) -> view.animate()
                .scaleX(focused ? scale : 1f)
                .scaleY(focused ? scale : 1f)
                .setDuration(ms(focused ? 220 : 180))
                .setInterpolator(EASE_OUT)
                .start());
    }

    // ---------------------------------------------------------------- Apps

    private static final class AppEntry {
        final String pkg;
        final ComponentName component;
        final String label;
        /** What the tile shows: the app's banner, or the style the user picked for it. */
        Drawable banner;
        /** The app's own banner (may be null), kept for the icon picker. */
        final Drawable origBanner;
        final Drawable icon;

        AppEntry(String pkg, ComponentName component, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.origBanner = banner;
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
            // The user's own order (from Move) wins; anything not placed yet keeps the default order.
            List<String> order = savedOrder();
            Collections.sort(all, (a, b) -> Integer.compare(orderIndex(order, a.pkg), orderIndex(order, b.pkg)));
            for (AppEntry e : all) {
                int style = prefs.getInt("style:" + e.pkg, TileStyles.BANNER);
                Drawable styled = null;
                if (style == TileStyles.LOGO) {
                    Logos.Logo logo = Logos.cached(this, e.pkg);
                    if (logo != null) styled = TileStyles.renderLogo(getResources(), logo);
                } else if (style == TileStyles.IMAGE) {
                    Bitmap img = Logos.image(this, e.pkg, px(244), px(144));
                    if (img != null) {
                        styled = TileStyles.renderImage(getResources(), img);
                        img.recycle();
                    }
                } else if (style != TileStyles.BANNER || e.origBanner == null) {
                    styled = TileStyles.render(getResources(), style, e.origBanner, e.icon, e.label);
                }
                if (styled == null && e.origBanner == null) {
                    styled = TileStyles.render(getResources(), TileStyles.ICON_AND_NAME, null, e.icon, e.label);
                }
                if (styled != null) e.banner = styled;
            }
            // Look up brand logos once, in the background, so "Logo" is ready in Change icon.
            List<AppEntry> toFetch = new ArrayList<>(all);
            io.execute(() -> {
                for (AppEntry e : toFetch) Logos.prefetch(this, e.pkg, e.label);
            });
            main.post(() -> {
                appsByPkg.clear();
                for (AppEntry e : all) appsByPkg.put(e.pkg, e);
                Set<String> hidden = hiddenApps();
                List<AppEntry> visible = new ArrayList<>();
                List<AppEntry> hiddenList = new ArrayList<>();
                for (AppEntry e : all) (hidden.contains(e.pkg) ? hiddenList : visible).add(e);
                showApps(visible, hiddenList);
            });
        });
    }

    private void showApps(List<AppEntry> apps, List<AppEntry> hidden) {
        String focusedPkg = null;
        View f = dockRow.findFocus();
        if (f != null && f.getTag() instanceof String) focusedPkg = (String) f.getTag();
        if (pendingFocusPkg != null) {
            focusedPkg = pendingFocusPkg;
            pendingFocusPkg = null;
        }
        dockRow.removeAllViews();
        View toFocus = null;
        List<View> tiles = new ArrayList<>();
        for (AppEntry app : apps) {
            View tile = appTile(app);
            tiles.add(tile);
            if (app.pkg.equals(focusedPkg)) toFocus = tile;
        }
        if (!hidden.isEmpty()) tiles.add(hiddenAppsTile(hidden));

        // Exactly six tiles fit across the dock; fewer than six sit centered.
        int viewW = getResources().getDisplayMetrics().widthPixels - px(60);
        int tileW = px(DOCK_TILE_W), side = px(26);
        int gap = (viewW - 2 * side - DOCK_PER_VIEW * tileW) / (DOCK_PER_VIEW - 1);
        int n = tiles.size();
        int used = n * tileW + Math.max(0, n - 1) * gap;
        int left = n < DOCK_PER_VIEW ? (viewW - used) / 2 : side;
        dockRow.setPadding(left, 0, side, 0);
        dockScroll.setPaging(tileW + gap, DOCK_PER_VIEW);
        dockGap = gap;
        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, px(72));
            lp.rightMargin = i < n - 1 ? gap : 0;
            dockRow.addView(tiles.get(i), lp);
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
            tile.setBackground(new ColorDrawable(0xFF2A2C30));
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
        attachFocusEffect(tile, 1.16f);
        tile.setOnClickListener(v -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
            if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
            launch(v, i, app.banner != null ? app.banner : app.icon, false, radius);
        });
        tile.setOnLongClickListener(v -> {
            showTileMenu(app, v);
            return true;
        });
        return tile;
    }

    // ---------------------------------------------------------------- Continue Watching

    private static final class WatchItem {
        String title, image, intentUri, pkg;
        long engaged, position, duration, weight;
    }

    /**
     * Reads what apps publish for the home screen: "Watch Next" (things you're in the middle
     * of) and preview programs (each app's own recommendation rows), grouped by app.
     */
    private void loadWatchNext() {
        lastWatchNextLoad = System.currentTimeMillis();
        io.execute(() -> {
            List<WatchItem> watchNext = new ArrayList<>();
            java.util.Map<String, List<WatchItem>> byPkg = new java.util.HashMap<>();
            try (Cursor c = getContentResolver().query(
                    TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (getInt(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                    WatchItem w = readItem(c);
                    w.engaged = getLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                    w.position = getLong(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                    w.duration = getLong(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                    watchNext.add(w);
                }
            } catch (Exception e) {
                Log.w(TAG, "watch next query failed: " + e);
            }
            Collections.sort(watchNext, (a, b) -> Long.compare(b.engaged, a.engaged));
            for (WatchItem w : watchNext) {
                if (w.pkg != null) byPkg.computeIfAbsent(w.pkg, k -> new ArrayList<>()).add(w);
            }
            try (Cursor c = getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
                java.util.Map<String, List<WatchItem>> previews = new java.util.HashMap<>();
                while (c != null && c.moveToNext()) {
                    if (getInt(c, TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                    WatchItem w = readItem(c);
                    w.weight = getLong(c, TvContract.PreviewPrograms.COLUMN_WEIGHT);
                    if (w.pkg != null && w.image != null) previews.computeIfAbsent(w.pkg, k -> new ArrayList<>()).add(w);
                }
                for (java.util.Map.Entry<String, List<WatchItem>> e : previews.entrySet()) {
                    List<WatchItem> list = e.getValue();
                    Collections.sort(list, (a, b) -> Long.compare(b.weight, a.weight));
                    byPkg.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(list);
                }
            } catch (Exception e) {
                Log.w(TAG, "preview programs query failed: " + e);
            }
            for (List<WatchItem> list : byPkg.values()) {
                if (list.size() > 16) list.subList(16, list.size()).clear();
            }
            if (watchNext.size() > 16) watchNext.subList(16, watchNext.size()).clear();
            Log.i(TAG, "home content: watch next " + watchNext.size() + ", apps " + byPkg.keySet());
            main.post(() -> {
                continueWatching = watchNext;
                contentByPkg = byPkg;
                contextPkg = focusedDockPkg;
                showContext(contextPkg, false);
            });
        });
    }

    private static WatchItem readItem(Cursor c) {
        WatchItem w = new WatchItem();
        w.title = getString(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
        w.image = getString(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
        if (w.image == null) w.image = getString(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
        w.intentUri = getString(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
        w.pkg = getString(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
        return w;
    }

    // ---------------------------------------------------------------- Context row

    /** App whose content the top row shows; null means the row is left blank. */
    private String contextPkg;
    private String shownContext = "\u0000";
    private List<WatchItem> continueWatching = new ArrayList<>();
    private java.util.Map<String, List<WatchItem>> contentByPkg = new java.util.HashMap<>();
    private final Runnable applyContext = () -> showContext(contextPkg, true);
    private String focusedDockPkg;

    /** Called when focus lands on a dock tile: after a short rest, the top row follows that app. */
    private void onDockFocus(String pkg) {
        focusedDockPkg = pkg;
        // Apps that publish nothing show just their name over an empty row.
        String next = pkg;
        if (next == null ? contextPkg == null : next.equals(contextPkg)) {
            main.removeCallbacks(applyContext);
            return;
        }
        contextPkg = next;
        main.removeCallbacks(applyContext);
        main.postDelayed(applyContext, 280);
    }

    private void showContext(String pkg, boolean animate) {
        List<WatchItem> items = pkg == null ? null : contentByPkg.get(pkg);
        if (items == null) items = Collections.emptyList();
        String label = pkg == null ? "" : labelFor(pkg);
        boolean nameOnly = items.isEmpty();
        String key = pkg == null ? "" : pkg;
        boolean contextChanged = !key.equals(shownContext);
        shownContext = key;
        List<WatchItem> show = items;
        if (!animate || !contextChanged || cardsRow.hasFocus()) {
            cardsLabel.setText(label);
            showCards(show);
            if (nameOnly) cardsLabel.setVisibility(label.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            return;
        }
        // Crossfade the row to the new app's content.
        upperRow.animate().cancel();
        upperRow.animate().alpha(0f).translationY(px(6)).setDuration(ms(130)).setInterpolator(EASE_OUT)
                .withEndAction(() -> {
                    cardsLabel.setText(label);
                    cardsScroll.reset();
                    showCards(show);
                    if (nameOnly) cardsLabel.setVisibility(label.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                    upperRow.setTranslationY(-px(6));
                    upperRow.animate().alpha(1f).translationY(0).setDuration(ms(240))
                            .setInterpolator(EASE_OUT).start();
                }).start();
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
        StringBuilder sig = new StringBuilder();
        for (WatchItem w : items) sig.append(w.title).append('|').append(w.image).append('|').append(w.position).append('\n');
        if (sig.toString().equals(cardsSignature)) return; // nothing changed: keep views and images as they are
        cardsSignature = sig.toString();
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
        card.setBackground(new ColorDrawable(0xFF1E2023));
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

        attachFocusEffect(card, 1.06f);
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
            if (i != null) launch(v, i, iv.getDrawable(), true, radius);
        });
        return card;
    }

    // ---------------------------------------------------------------- Launch / return animations

    /**
     * Durations here are designed in real milliseconds; undo the system animator speed setting
     * so motion in Flow stays the same whatever that setting is.
     */
    static long ms(long designed) {
        if (Build.VERSION.SDK_INT >= 33) {
            float scale = ValueAnimator.getDurationScale();
            if (scale > 0f) return (long) (designed / scale);
        }
        return designed;
    }

    /** On-screen bounds of a view, including its current focus zoom. */
    RectF boundsOf(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return new RectF(loc[0], loc[1], loc[0] + v.getWidth() * v.getScaleX(), loc[1] + v.getHeight() * v.getScaleY());
    }

    private static final PathInterpolator EXPAND = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private static final PathInterpolator COLLAPSE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private void launch(View from, Intent intent) {
        launch(from, intent, null, false, 0f);
    }

    /**
     * Opening: the tile itself grows from its spot to fill the screen (its banner floating
     * up on its own brand color, or its picture for Continue Watching), then the app fades
     * in over it. Nothing else is on screen at the hand-over, so there is no double image.
     */
    private void launch(View from, Intent intent, Drawable art, boolean fill, float cornerRadius) {
        if (launching) return;
        launching = true;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        lastLaunched = returnTarget != null ? returnTarget : from;
        returnTarget = null;
        lastArt = art;
        lastFill = fill;
        lastRadius = cornerRadius;
        int bg = fill ? Color.BLACK : HeroView.edgeColor(art, 0xFF1E2023);
        lastBg = bg;
        RectF r = boundsOf(from);
        hero.prepare(art, bg, fill, r, cornerRadius * from.getScaleX());
        hero.setVisibility(View.VISIBLE);
        from.setAlpha(0f);

        final boolean[] started = {false};
        ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f);
        grow.setDuration(ms(520));
        grow.setInterpolator(EXPAND);
        grow.addUpdateListener(an -> {
            hero.setProgress((float) an.getAnimatedValue());
            // Hand over to the app near the end, so its window is ready as the tile lands.
            if (!started[0] && an.getAnimatedFraction() >= 0.8f) {
                started[0] = true;
                startApp(intent);
            }
        });
        grow.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!started[0]) {
                    started[0] = true;
                    startApp(intent);
                }
            }
        });
        // Home recedes slightly behind the growing tile.
        stage.setPivotX(r.centerX());
        stage.setPivotY(r.centerY());
        stage.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.4f).setDuration(ms(520))
                .setInterpolator(EXPAND).withLayer().start();
        grow.start();
    }

    private void startApp(Intent intent) {
        launching = false;
        try {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.home_hold);
            startActivity(intent, opts.toBundle());
            returningFromApp = true;
            if (recents.isShowing()) recents.hide(false);
            if (folder.isShowing()) folder.hide(false);
        } catch (Exception e) {
            Log.w(TAG, "launch failed: " + e);
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
            resetStage();
        }
    }

    private void resetStage() {
        stage.animate().cancel();
        stage.setScaleX(1f);
        stage.setScaleY(1f);
        stage.setAlpha(1f);
        stage.setLayerType(View.LAYER_TYPE_NONE, null);
        hero.clear();
        hero.setAlpha(1f);
        returnCover.animate().cancel();
        returnCover.setVisibility(View.INVISIBLE);
        if (lastLaunched != null) lastLaunched.setAlpha(1f);
    }

    /**
     * Called while the launcher is hidden: home waits behind the full-screen tile, so as the
     * app fades away it fades into its own tile, which then shrinks back into place.
     */
    private void stageReturnAnimation() {
        launching = false;
        revealPending = false;
        main.removeCallbacks(revealNow);
        stage.animate().cancel();
        View tile = lastLaunched;
        if (tile == null || !tile.isAttachedToWindow()) {
            resetStage();
            returnStaged = true;
            return;
        }
        tile.requestFocus();
        // Render home into a GPU layer now, while it's hidden behind the full-screen tile, so
        // the shrink-back starts on a ready texture instead of stalling to build one.
        stage.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        stage.setScaleX(0.94f);
        stage.setScaleY(0.94f);
        stage.setAlpha(0.4f);
        tile.setAlpha(0f);
        // No app-looking splash behind the closing app: if Home is pressed twice, the system only
        // tells us after its fade, and a splash would flash up before Recents. Dimmed home
        // is safe for both cases; the tile flies back in once the fade is over.
        hero.setVisibility(View.INVISIBLE);
        returnCover.animate().cancel();
        returnCover.setAlpha(1f);
        returnCover.setVisibility(View.VISIBLE);
        returnStaged = true;
    }

    /**
     * Closing: wait for the system to fade the app out (or ~0.34 s), then the full-screen
     * tile shrinks back into its spot while home comes forward again.
     */
    private void playReturnAnimation() {
        if (!returnStaged) stageReturnAnimation();
        returnStaged = false;
        revealPending = true;
        main.removeCallbacks(revealNow);
        main.postDelayed(revealNow, 340);
    }

    private final Runnable revealNow = this::revealHome;

    private void revealHome() {
        if (!revealPending) return;
        revealPending = false;
        View tile = lastLaunched;
        if (tile == null || !tile.isAttachedToWindow() || stage.getAlpha() >= 1f) {
            resetStage();
            return;
        }
        // The tile may sit somewhere new (rows refresh); re-aim the hero at where it is now,
        // using its focused size since it will be focused when it lands.
        float zoom = tile.isFocused() ? 1.16f : 1f;
        int[] loc = new int[2];
        tile.getLocationInWindow(loc);
        float sx = tile.getScaleX();
        float w = tile.getWidth() * zoom, h = tile.getHeight() * zoom;
        float left = loc[0] - tile.getWidth() * (zoom - sx) / 2f, top = loc[1] - tile.getHeight() * (zoom - sx) / 2f;
        hero.prepare(lastArt, lastBg, lastFill, new RectF(left, top, left + w, top + h), lastRadius * zoom);
        // The tile flies back from a large size, fading in over the first part of the motion.
        final float startAt = 0.55f;
        hero.setProgress(startAt);
        hero.setAlpha(0f);
        hero.setVisibility(View.VISIBLE);

        ValueAnimator shrink = ValueAnimator.ofFloat(startAt, 0f);
        shrinkAnim = shrink;
        shrink.setDuration(ms(460));
        shrink.setInterpolator(COLLAPSE);
        shrink.addUpdateListener(an -> {
            hero.setProgress((float) an.getAnimatedValue());
            hero.setAlpha(Math.min(1f, an.getAnimatedFraction() / 0.35f));
        });
        shrink.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                tile.setAlpha(1f);
                hero.clear();
                lastArt = null;
                stage.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        shrink.start();
        stage.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ms(460))
                .setInterpolator(COLLAPSE).start();
        returnCover.animate().alpha(0f).setDuration(ms(320)).setInterpolator(COLLAPSE)
                .withEndAction(() -> returnCover.setVisibility(View.INVISIBLE)).start();
    }

    // ---------------------------------------------------------------- Recents

    private void showRecents() {
        // Double-press from inside an app: go straight to Recents, no shrink-back animation,
        // and put the Recents backdrop up at once so the app fades directly into it.
        boolean fromApp = revealPending || returnStaged || stage.getAlpha() < 1f;
        cancelReturnAnimation();
        if (fromApp) recents.coverNow();
        focusBeforeRecents = getCurrentFocus();
        stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        io.execute(() -> {
            List<RecentsOverlay.Recent> list = recentApps();
            main.post(() -> recents.show(list));
        });
    }

    private void cancelReturnAnimation() {
        revealPending = false;
        returnStaged = false;
        main.removeCallbacks(revealNow);
        if (shrinkAnim != null) {
            shrinkAnim.cancel();
            shrinkAnim = null;
        }
        if (hero.getVisibility() == View.VISIBLE || stage.getAlpha() < 1f) resetStage();
    }

    void onRecentsHidden() {
        stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View f = focusBeforeRecents;
        focusBeforeRecents = null;
        if (f != null && f.isAttachedToWindow()) f.requestFocus();
        else if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
    }

    /** Apps used in the last three days, newest first, minus any swiped away since. */
    private List<RecentsOverlay.Recent> recentApps() {
        List<RecentsOverlay.Recent> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        java.util.Map<String, Long> last = new java.util.HashMap<>();
        try {
            UsageEvents events = usm.queryEvents(now - 3 * DateUtils.DAY_IN_MILLIS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
            }
        } catch (Exception ex) {
            Log.w(TAG, "usage stats: " + ex);
        }
        for (java.util.Map.Entry<String, Long> en : last.entrySet()) {
            String pkg = en.getKey();
            if (pkg.equals(getPackageName()) || !appsByPkg.containsKey(pkg)) continue;
            if (en.getValue() <= prefs.getLong("dismissed:" + pkg, 0)) continue;
            out.add(new RecentsOverlay.Recent(pkg, en.getValue()));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        if (out.size() > 12) out.subList(12, out.size()).clear();
        return out;
    }

    void openRecent(View art, String pkg, float radius) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) return;
        // Coming back from this app should land on its dock tile, not on the Recents card.
        View tile = null;
        for (int k = 0; k < dockRow.getChildCount(); k++) {
            if (pkg.equals(dockRow.getChildAt(k).getTag())) tile = dockRow.getChildAt(k);
        }
        returnTarget = tile;
        launch(art, i, bannerFor(pkg), false, radius);
    }

    /** Closes an app's background processes and drops it from Recents until it's used again. */
    void closeApp(String pkg) {
        Log.i(TAG, "closing " + pkg);
        prefs.edit().putLong("dismissed:" + pkg, System.currentTimeMillis()).apply();
        try {
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
        } catch (Exception e) {
            Log.w(TAG, "close " + pkg + ": " + e);
        }
    }

    Drawable bannerFor(String pkg) {
        AppEntry e = appsByPkg.get(pkg);
        return e == null ? null : e.banner != null ? e.banner : e.icon;
    }

    String labelFor(String pkg) {
        AppEntry e = appsByPkg.get(pkg);
        return e == null ? pkg : e.label;
    }

    int brandColorFor(String pkg) {
        return HeroView.edgeColor(bannerFor(pkg), 0xFF1E2023);
    }

    // ---------------------------------------------------------------- Hidden apps folder

    void onFolderHidden() {
        stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View f = focusBeforeFolder;
        focusBeforeFolder = null;
        if (f != null && f.isAttachedToWindow()) f.requestFocus();
        else if (dockRow.getChildCount() > 0) dockRow.getChildAt(dockRow.getChildCount() - 1).requestFocus();
    }

    /** Opens a hidden app from the folder; coming back lands on the Hidden tile. */
    void openHidden(View tile, String pkg, float radius, View hiddenTile) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) {
            AppEntry e = appsByPkg.get(pkg);
            if (e == null) return;
            i = new Intent(Intent.ACTION_MAIN).setComponent(e.component);
        }
        returnTarget = hiddenTile;
        focusBeforeFolder = hiddenTile;
        launch(tile, i, bannerFor(pkg), false, radius);
    }

    void showHiddenMenu(String pkg, View tile) {
        AppEntry app = appsByPkg.get(pkg);
        showPopup(tile, labelFor(pkg), popup.menu(
                new String[] {"Show on home", "Change icon", "App info"},
                new Runnable[] {
                        () -> {
                            folder.hide(false);
                            pendingFocusPkg = pkg; // land on the app that came back
                            setHidden(pkg, false);
                        },
                        () -> {
                            if (app != null) showIconPicker(app, tile);
                        },
                        () -> openAppInfo(pkg)}));
    }

    // ---------------------------------------------------------------- Hiding apps

    private Set<String> hiddenApps() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> hidden = hiddenApps();
        if (hide) hidden.add(pkg);
        else hidden.remove(pkg);
        prefs.edit().putStringSet("hidden", hidden).apply();
        loadApps();
    }

    /** Hold OK (or press Menu) on an app tile. */
    private void showTileMenu(AppEntry app, View tile) {
        showPopup(tile, app.label, popup.menu(
                new String[] {"Move", "Change icon", "Hide from home", "App info"},
                new Runnable[] {
                        () -> startMove(tile),
                        () -> showIconPicker(app, tile),
                        () -> setHidden(app.pkg, true),
                        () -> openAppInfo(app.pkg)}));
    }

    private void openScreensaverSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            android.widget.Toast.makeText(this,
                    "Choose Flow Clock as your screensaver", android.widget.Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this,
                    "Open Screensaver in your TV settings and choose Flow Clock",
                    android.widget.Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openAppInfo(String pkg) {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showPopup(View anchor, String heading, View content) {
        stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        folder.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        popup.show(anchor, heading, content);
    }

    void onPopupHidden(View anchor) {
        folder.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (!folder.isShowing() && !recents.isShowing()) {
            stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
    }

    /**
     * Sets a tile's picture from a link: am start -n com.gfs.flow/.HomeActivity
     *   --es flow.tile_image "<package> <https url>"   ("<package> clear" removes it)
     */
    private boolean handleTileImage(Intent intent) {
        String spec = intent.getStringExtra("flow.tile_image");
        if (spec == null) return false;
        intent.removeExtra("flow.tile_image");
        String[] parts = spec.trim().split("\\s+", 2);
        if (parts.length == 2 && parts[1].equals("clear")) {
            String pkg = parts[0];
            Logos.imageFile(this, pkg).delete();
            if (prefs.getInt("style:" + pkg, TileStyles.BANNER) == TileStyles.IMAGE) {
                prefs.edit().remove("style:" + pkg).apply();
            }
            loadApps();
            return true;
        }
        if (parts.length != 2 || !parts[1].startsWith("https://")) return true;
        String pkg = parts[0], url = parts[1];
        io.execute(() -> {
            boolean ok = Logos.downloadImage(this, pkg, url);
            Log.i(TAG, "tile image for " + pkg + ": " + (ok ? "saved" : "failed"));
            if (ok) {
                prefs.edit().putInt("style:" + pkg, TileStyles.IMAGE).apply();
                main.post(() -> {
                    pendingFocusPkg = pkg;
                    loadApps();
                });
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- Change icon

    private void showIconPicker(AppEntry app, View tile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(px(8), px(6), px(8), px(4));
        int current = prefs.getInt("style:" + app.pkg, TileStyles.BANNER);
        View currentView = null;
        Logos.Logo logo = Logos.cached(this, app.pkg);
        boolean hasImage = Logos.imageFile(this, app.pkg).exists();
        for (int style = 0; style < TileStyles.NAMES.length; style++) {
            if (style == TileStyles.BANNER && app.origBanner == null) continue;
            if (style == TileStyles.LOGO && logo == null) continue;
            if (style == TileStyles.IMAGE && !hasImage) continue;
            final int chosen = style;
            LinearLayout option = new LinearLayout(this);
            option.setOrientation(LinearLayout.VERTICAL);
            option.setGravity(Gravity.CENTER_HORIZONTAL);
            option.setFocusable(true);
            option.setClipChildren(false);
            FrameLayout preview = new FrameLayout(this);
            roundCorners(preview, px(12));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable look;
            if (style == TileStyles.LOGO) {
                look = TileStyles.renderLogo(getResources(), logo);
            } else if (style == TileStyles.IMAGE) {
                Bitmap img = Logos.image(this, app.pkg, px(244), px(144));
                look = img != null ? TileStyles.renderImage(getResources(), img) : null;
            } else {
                look = TileStyles.render(getResources(), style, app.origBanner, app.icon, app.label);
            }
            iv.setImageDrawable(look);
            preview.addView(iv, match());
            option.addView(preview, new LinearLayout.LayoutParams(px(DOCK_TILE_W), px(72)));
            TextView name = new TextView(this);
            name.setText(TileStyles.NAMES[style] + (style == current ? "  \u2713" : ""));
            name.setTextColor(0xCCFFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setPadding(0, px(10), 0, 0);
            option.addView(name);
            option.setOnFocusChangeListener((v, f) -> preview.animate()
                    .scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f)
                    .setDuration(ms(f ? 220 : 180)).setInterpolator(EASE_OUT).start());
            option.setOnClickListener(v -> {
                popup.hide(true);
                if (folder.isShowing()) folder.hide(false);
                prefs.edit().putInt("style:" + app.pkg, chosen).apply();
                pendingFocusPkg = app.pkg;
                loadApps();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) lp.leftMargin = px(20);
            row.addView(option, lp);
            if (style == current) currentView = option;
        }
        showPopup(tile, "Choose a look for " + app.label, row);
        View focusFirst = currentView;
        if (focusFirst != null) row.post(() -> focusFirst.requestFocus());
    }

    // ---------------------------------------------------------------- Move

    private View moveTile;
    private TextView moveHint;
    private int dockGap;

    private List<String> savedOrder() {
        String raw = prefs.getString("order", "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static int orderIndex(List<String> order, String pkg) {
        int i = order.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Lifts the tile; left/right slides it along the dock, OK places it, Back cancels. */
    private void startMove(View tile) {
        if (!tile.isAttachedToWindow()) return;
        moveTile = tile;
        tile.requestFocus();
        tile.animate().scaleX(1.26f).scaleY(1.26f).translationY(-px(8))
                .setDuration(ms(220)).setInterpolator(EASE_OUT).start();
        moveHint.setVisibility(View.VISIBLE);
        moveHint.setAlpha(0f);
        moveHint.animate().alpha(1f).setDuration(ms(200)).start();
    }

    private void shiftMoveTile(int dir) {
        int i = dockRow.indexOfChild(moveTile);
        int j = i + dir;
        if (j < 0 || j >= dockRow.getChildCount() || !(dockRow.getChildAt(j).getTag() instanceof String)) return;
        View other = dockRow.getChildAt(j);
        int mine = moveTile.getLeft(), theirs = other.getLeft();
        // Move the neighbour rather than the lifted tile, so focus never leaves it.
        dockRow.removeViewAt(j);
        dockRow.addView(other, i);
        fixDockMargins();
        other.setTranslationX(theirs - mine);
        other.animate().translationX(0).setDuration(ms(240)).setInterpolator(EASE_OUT).start();
        moveTile.setTranslationX(mine - theirs);
        moveTile.animate().translationX(0).setDuration(ms(240)).setInterpolator(EASE_OUT).start();
        dockScroll.follow(moveTile);
    }

    private void endMove(boolean keep) {
        View tile = moveTile;
        moveTile = null;
        moveHint.animate().alpha(0f).setDuration(ms(160))
                .withEndAction(() -> moveHint.setVisibility(View.INVISIBLE)).start();
        tile.animate().scaleX(1.16f).scaleY(1.16f).translationY(0)
                .setDuration(ms(200)).setInterpolator(EASE_OUT).start();
        if (keep) {
            List<String> order = new ArrayList<>();
            for (int k = 0; k < dockRow.getChildCount(); k++) {
                Object tag = dockRow.getChildAt(k).getTag();
                if (tag instanceof String) order.add((String) tag);
            }
            for (String p : savedOrder()) if (!order.contains(p)) order.add(p);
            prefs.edit().putString("order", TextUtils.join(",", order)).apply();
        } else {
            pendingFocusPkg = (String) tile.getTag();
            loadApps();
        }
    }

    private void fixDockMargins() {
        int n = dockRow.getChildCount();
        for (int k = 0; k < n; k++) {
            View c = dockRow.getChildAt(k);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            lp.rightMargin = k < n - 1 ? dockGap : 0;
            c.setLayoutParams(lp);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moveTile != null) {
            int k = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) shiftMoveTile(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (!down) endMove(true);
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) endMove(false);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_MENU) {
                return true;
            }
        }
        // Entering the top row from the dock (or the gear) always lands on its first card,
        // instead of whichever card happens to sit above the focused tile.
        if (e.getAction() == KeyEvent.ACTION_DOWN && cardsRow.getChildCount() > 0
                && !popup.isShowing() && !folder.isShowing() && !recents.isShowing()) {
            View f = getCurrentFocus();
            boolean fromDock = f != null && f.getParent() == dockRow && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP;
            boolean fromTop = f != null && !cardsRow.hasFocus() && f.getParent() != dockRow
                    && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN;
            if (fromDock || fromTop) {
                cardsRow.getChildAt(0).requestFocus();
                return true;
            }
        }
        // Leaving the top row downwards returns to the app whose row it is.
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && cardsRow.hasFocus() && focusedDockPkg != null) {
            for (int k = 0; k < dockRow.getChildCount(); k++) {
                View t = dockRow.getChildAt(k);
                if (focusedDockPkg.equals(t.getTag())) {
                    t.requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private View hiddenAppsTile(List<AppEntry> hidden) {
        float radius = px(12);
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        roundCorners(tile, radius);
        // Styled like an app banner so it sits naturally in the dock.
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF3B3E45, 0xFF24262B});
        tile.setBackground(bg);
        // Just the Flow mark: the tile reads as Flow's own folder, no label needed.
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_flow_mark);
        tile.setContentDescription("Hidden apps");
        tile.addView(icon, new FrameLayout.LayoutParams(px(46), px(28), Gravity.CENTER));
        attachFocusEffect(tile, 1.16f);
        tile.setOnClickListener(v -> {
            List<String> pkgs = new ArrayList<>();
            for (AppEntry e : hidden) pkgs.add(e.pkg);
            focusBeforeFolder = v;
            stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            folder.show(pkgs, v);
        });
        return tile;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu on an app tile opens its system App info page.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String && appsByPkg.containsKey(f.getTag())
                    && dockRow.indexOfChild(f) >= 0) {
                showTileMenu(appsByPkg.get(f.getTag()), f);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
