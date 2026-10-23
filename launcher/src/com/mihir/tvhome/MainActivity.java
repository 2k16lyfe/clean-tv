package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        boolean system;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private java.io.File[] extWalls = new java.io.File[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;
    private FrameLayout rootView;
    private View scrimView;
    private TextView wallPill, wiiWallPill;
    private FrameLayout wallPanel;
    private int thumbGen = 0;
    private String shownKey = "";
    // Idle (ambient) screen: apps hide, wallpaper goes full screen with a small clock.
    private boolean idleOn;
    private long lastInput = SystemClock.elapsedRealtime();
    private FrameLayout idleLayer;
    private TextView idleClock, idleDate;
    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            int m = idleMinutes();
            if (!idleOn && m > 0 && !popupOpen() && movingPkg == null && !wallPanelShowing()
                    && SystemClock.elapsedRealtime() - lastInput >= m * 60_000L) enterIdle();
            handler.postDelayed(this, 10_000);
        }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        tf.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        if (wii()) {
            // The Apple dock is hidden in the Wii themes; focusing it would trap the D-pad offscreen.
            if (wiiLayer != null && wiiLayer.findFocus() == null) buildWiiGrid(0, 0);
        } else {
            View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
            if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
            if (last != null) last.requestFocus();
        }
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        long iv = wallIntervalMs();
        if (!wallRotating()) { if (!wallStaticKey().equals(shownKey)) nextWallpaper(); }
        else if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= iv) nextWallpaper();
        else scheduleWallpaper(iv - (SystemClock.elapsedRealtime() - wallChangedAt));
        lastInput = SystemClock.elapsedRealtime();
        handler.removeCallbacks(idleTick);
        handler.postDelayed(idleTick, 10_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(idleTick);
        if (idleOn) exitIdle();
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
        if (menuPopup != null) menuPopup.dismiss();
        movingPkg = null;
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        lastInput = SystemClock.elapsedRealtime();
        if (idleOn) { exitIdle(); return; }
        if (wallPanelShowing()) closeWallPanel();
        if (inputsPopup != null) inputsPopup.dismiss();
        if (wii()) { wiiPage = 0; buildWiiGrid(0, 0); return; }
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (wallPanelShowing()) { closeWallPanel(); return; }
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrimView = scrim;
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }
        java.io.File wd = getExternalFilesDir("wallpapers");
        if (wd != null) {
            java.io.File[] fs = wd.listFiles((dir, n) -> {
                String x = n.toLowerCase(Locale.ROOT);
                return x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png") || x.endsWith(".webp");
            });
            if (fs != null && fs.length > 0) { Arrays.sort(fs); extWalls = fs; }
        }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        wiiLayer = buildWii();
        root.addView(wiiLayer, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        themePill = pill("Apple", "\u25D0");
        themePill.setOnClickListener(v -> cycleTheme());
        wallPill = pill("Wallpapers", "\u25A3");
        wallPill.setOnClickListener(v -> showWallPanel(null));
        TextView inputs = pill("Inputs", "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", "◉");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill("Settings", "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{themePill, wallPill, inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || userHidden().contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.system = (ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockOrder()) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        List<App> ordered = new ArrayList<>(dock);
        ordered.addAll(rest);
        allApps = applyWiiOrder(ordered);
        applyTheme();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnLongClickListener(v -> { showAppMenu(card, app, inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- hold menu / layout

    private PopupWindow menuPopup;
    private String movingPkg;
    private int swallowKey = -1;

    private android.content.SharedPreferences layoutPrefs() { return getSharedPreferences("layout", MODE_PRIVATE); }

    private String[] dockOrder() {
        String s = layoutPrefs().getString("dock", null);
        if (s == null) return dockApps;
        return s.isEmpty() ? new String[0] : s.split(",");
    }

    private Set<String> userHidden() {
        String s = layoutPrefs().getString("hidden", "");
        return s.isEmpty() ? new HashSet<String>() : new HashSet<String>(Arrays.asList(s.split(",")));
    }

    private List<String> currentDock() {
        List<String> l = new ArrayList<>();
        for (View c : dockCards) l.add(((App) c.getTag()).pkg);
        return l;
    }

    private void saveDock(List<String> l) {
        layoutPrefs().edit().putString("dock", android.text.TextUtils.join(",", l)).apply();
    }

    private void focusDock(String pkg) {
        for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pkg)) { c.requestFocus(); return; }
    }

    private void moveBy(int d) {
        if (wii()) { moveWii(d); return; }
        List<String> l = currentDock();
        int i = l.indexOf(movingPkg), j = i + d;
        if (i < 0 || j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        saveDock(l);
        buildApps();
        focusDock(movingPkg);
    }

    /** Wii grid order: the saved order first, anything new after it in the default order. */
    private List<App> applyWiiOrder(List<App> ordered) {
        String s = layoutPrefs().getString("wiiorder", null);
        if (s == null || s.isEmpty()) return ordered;
        Map<String, App> byPkg = new HashMap<>();
        for (App a : ordered) byPkg.put(a.pkg, a);
        List<App> out = new ArrayList<>();
        for (String p : s.split(",")) {
            App a = byPkg.remove(p);
            if (a != null) out.add(a);
        }
        for (App a : ordered) if (byPkg.containsKey(a.pkg)) out.add(a);
        return out;
    }

    private void moveWii(int d) {
        int i = -1;
        for (int k = 0; k < allApps.size(); k++) if (allApps.get(k).pkg.equals(movingPkg)) { i = k; break; }
        int j = i + d;
        if (i < 0 || j < 0 || j >= allApps.size()) return;
        App a = allApps.remove(i);
        allApps.add(j, a);
        List<String> l = new ArrayList<>();
        for (App x : allApps) l.add(x.pkg);
        layoutPrefs().edit().putString("wiiorder", android.text.TextUtils.join(",", l)).apply();
        wiiPage = j / 12;
        buildWiiGrid((j % 12) / 4, j % 4);
    }

    private void showHiddenApps(View anchor) {
        PackageManager pm = getPackageManager();
        Set<String> hid = userHidden();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, String> found = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (hid.contains(p) && !found.containsKey(p)) found.put(p, String.valueOf(ri.loadLabel(pm)));
        }
        if (found.isEmpty()) {
            android.widget.Toast.makeText(this, "No hidden apps", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> pkgs = new ArrayList<>(found.keySet());
        Collections.sort(pkgs, (x, y) -> found.get(x).compareToIgnoreCase(found.get(y)));
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (pkgs.size() > 1) {
            labels.add("Unhide all");
            acts.add(() -> {
                Set<String> h = userHidden();
                h.removeAll(found.keySet());
                layoutPrefs().edit().putString("hidden", android.text.TextUtils.join(",", h)).apply();
                buildApps();
            });
        }
        for (String p : pkgs) {
            labels.add(found.get(p));
            acts.add(() -> {
                Set<String> h = userHidden();
                h.remove(p);
                layoutPrefs().edit().putString("hidden", android.text.TextUtils.join(",", h)).apply();
                buildApps();
            });
        }
        showMenuList(anchor, labels, acts);
    }

    private void showAppMenu(View anchor, App app, boolean inDock) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (wii()) {
            labels.add("Move");
            acts.add(() -> {
                movingPkg = app.pkg;
                android.widget.Toast.makeText(this, "Use the arrows to move, OK when done", android.widget.Toast.LENGTH_LONG).show();
            });
        } else if (inDock) {
            labels.add("Move");
            acts.add(() -> {
                movingPkg = app.pkg;
                android.widget.Toast.makeText(this, "Use \u25C0 \u25B6 to move, OK when done", android.widget.Toast.LENGTH_LONG).show();
            });
            labels.add("Remove from dock");
            acts.add(() -> { List<String> d = currentDock(); d.remove(app.pkg); saveDock(d); buildApps(); });
        } else {
            labels.add("Add to dock");
            acts.add(() -> { List<String> d = currentDock(); d.add(app.pkg); saveDock(d); buildApps(); });
        }
        labels.add("Hide from home");
        acts.add(() -> {
            List<String> d = currentDock(); d.remove(app.pkg); saveDock(d);
            Set<String> h = userHidden(); h.add(app.pkg);
            layoutPrefs().edit().putString("hidden", android.text.TextUtils.join(",", h)).apply();
            buildApps();
        });
        if (!userHidden().isEmpty()) {
            labels.add("Unhide apps\u2026");
            acts.add(() -> showHiddenApps(anchor));
        }
        if (!app.system) {
            labels.add("Delete app\u2026");
            acts.add(() -> launch(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.pkg))));
        }
        labels.add("Reset layout");
        acts.add(() -> { layoutPrefs().edit().clear().apply(); buildApps(); });
        showMenuList(anchor, labels, acts);
    }

    private void showMenuList(View anchor, List<String> labels, List<Runnable> acts) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(240), -2, true);
        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) rows.add(inputRow(labels.get(i), acts.get(i)));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(40)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { menuPopup = null; if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        menuPopup = pw;
        pw.showAtLocation(anchor.getRootView(), Gravity.CENTER, 0, 0);
        rows.get(0).requestFocus();
    }

    private Bitmap decodeSampled(java.io.File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2;
            BitmapFactory.Options p = new BitmapFactory.Options();
            p.inSampleSize = s;
            p.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Wii-inspired theme
    // Light, glossy channel grid with a bottom bar. Original artwork only, nothing from Nintendo.

    private FrameLayout wiiLayer;
    private LinearLayout wiiGrid;
    private TextView wiiName, wiiPageText, wiiLeft, wiiRight, wiiClock, wiiDate, wiiWifi;
    private int wiiTw, wiiTh, wiiGap, wiiPage = 0;
    private List<App> allApps = new ArrayList<>();
    private int wBg1, wBg2, wTile, wEdge, wText, wSub, wBar1, wBar2, wEmptyFill, wEmptyEdge, wBlue;
    private int wiiBuiltTheme = -1;
    private TextView themePill, wiiThemePill;
    private HandView wiiHand;
    private boolean wiiHandPlaced;

    private String theme() {
        String t = layoutPrefs().getString("theme", "apple");
        return ("wii".equals(t) || "wiidark".equals(t)) ? t : "apple";
    }

    private boolean wii() { return !"apple".equals(theme()); }

    private boolean wiiDark() { return "wiidark".equals(theme()); }

    /** One button cycles Apple -> Wii -> Wii Dark -> Apple. */
    private void cycleTheme() {
        String t = theme();
        String next = "apple".equals(t) ? "wii" : "wii".equals(t) ? "wiidark" : "apple";
        layoutPrefs().edit().putString("theme", next).apply();
        applyTheme();
        View f = wii() ? wiiThemePill : themePill;
        if (f != null) f.requestFocus();
    }

    private void updateThemePills() {
        String label = "wiidark".equals(theme()) ? "Wii Dark" : "wii".equals(theme()) ? "Wii" : "Apple";
        if (themePill != null) themePill.setText("\u25D0  " + label);
        if (wiiThemePill != null) wiiThemePill.setText("\u25D0  " + label);
    }

    private void rebuildWii() {
        ViewGroup parent = (ViewGroup) wiiLayer.getParent();
        int idx = parent.indexOfChild(wiiLayer);
        parent.removeView(wiiLayer);
        wiiLayer = buildWii();
        parent.addView(wiiLayer, idx, new FrameLayout.LayoutParams(-1, -1));
    }

    private void applyTheme() {
        if (wiiLayer == null || idleOn) return;
        boolean w = wii();
        wiiLayer.setVisibility(w ? View.VISIBLE : View.GONE);
        scroll.setVisibility(w ? View.GONE : View.VISIBLE);
        if (w && wiiBuiltTheme != (wiiDark() ? 1 : 0)) rebuildWii();
        // Whichever layer is hidden must not be able to hold focus, or the D-pad gets stuck on it.
        scroll.setDescendantFocusability(w ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        wiiLayer.setDescendantFocusability(w ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        updateThemePills();
        if (w) {
            buildWiiGrid(0, 0);
            updateWiiClock();
            updateWifi();
        } else {
            View f = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
            if (f != null) f.requestFocus();
            else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        }
    }

    private FrameLayout buildWii() {
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        boolean d = wiiDark();
        wiiBuiltTheme = d ? 1 : 0;
        wBg1 = d ? 0xFF1B2430 : 0xFFFFFFFF;  wBg2 = d ? 0xFF0D131A : 0xFFE6EBF0;
        wTile = d ? 0xFF263140 : 0xFFFFFFFF; wEdge = d ? 0xFF3A4757 : 0xFFC9D2DC;
        wText = d ? 0xFFD5DEE8 : 0xFF5B6670; wSub = d ? 0xFF8C99A8 : 0xFF7C8793;
        wBar1 = d ? 0xFF222D3A : 0xFFF7F9FB; wBar2 = d ? 0xFF141B24 : 0xFFDCE3EA;
        wEmptyFill = d ? 0xFF1C2531 : 0xFFF1F4F7; wEmptyEdge = d ? 0xFF2B3644 : 0xFFE0E6EC;
        wBlue = d ? 0xFF4FC3F7 : 0xFF36A9E1;
        wiiGap = dp(12);
        int byWidth = (int) ((W * 0.78f - 3 * wiiGap) / 4);
        int byHeight = (int) (((H * 0.60f - 2 * wiiGap) / 3) / 0.5625f);
        wiiTw = Math.min(byWidth, byHeight);
        wiiTh = (int) (wiiTw * 0.5625f);
        int gridW = 4 * wiiTw + 3 * wiiGap, gridH = 3 * wiiTh + 2 * wiiGap;
        int top = (int) (H * 0.12f), barH = (int) (H * 0.17f);

        FrameLayout w = new FrameLayout(this);
        w.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{wBg1, wBg2}));
        w.setClipChildren(false);

        wiiGrid = new LinearLayout(this);
        wiiGrid.setOrientation(LinearLayout.VERTICAL);
        wiiGrid.setClipChildren(false);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(gridW, gridH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gp.topMargin = top;
        w.addView(wiiGrid, gp);

        wiiName = text(18, "sans-serif-medium", wText);
        wiiName.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        np.topMargin = top + gridH + dp(16);
        w.addView(wiiName, np);

        wiiLeft = wiiArrow("\u2039");
        wiiRight = wiiArrow("\u203A");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.TOP);
        lp.leftMargin = Math.max(dp(8), (W - gridW) / 4 - dp(24));
        lp.topMargin = top + gridH / 2 - dp(24);
        w.addView(wiiLeft, lp);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.TOP);
        rp.rightMargin = lp.leftMargin;
        rp.topMargin = lp.topMargin;
        w.addView(wiiRight, rp);

        View bar = new View(this);
        GradientDrawable bb = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{wBar1, wBar2});
        bb.setStroke(dp(2), wEdge);
        bar.setBackground(bb);
        w.addView(bar, new FrameLayout.LayoutParams(-1, barH, Gravity.BOTTOM));

        wiiPageText = text(12, "sans-serif-medium", wSub);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.bottomMargin = barH + dp(6);
        w.addView(wiiPageText, pp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        wiiClock = text(40, "sans-serif-light", wText);
        wiiDate = text(13, "sans-serif-medium", wSub);
        mid.addView(wiiClock);
        mid.addView(wiiDate);
        w.addView(mid, new FrameLayout.LayoutParams(dp(360), barH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        TextView settings = wiiButton("\u2699", "Settings", true);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.START);
        sbp.leftMargin = (int) (W * 0.08f);
        sbp.bottomMargin = (barH - dp(64)) / 2;
        w.addView(settings, sbp);

        wiiWifi = wiiButton("\u25C9  Wi-Fi", "Wi-Fi", false);
        wiiWifi.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        FrameLayout.LayoutParams wbp = new FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM | Gravity.END);
        wbp.rightMargin = (int) (W * 0.08f);
        wbp.bottomMargin = (barH - dp(48)) / 2;
        w.addView(wiiWifi, wbp);
        wiiThemePill = wiiButton("\u25D0  Theme", "Change theme", false);
        wiiThemePill.setOnClickListener(v -> cycleTheme());
        wiiWallPill = wiiButton("\u25A3  Wallpapers", "Wallpapers", false);
        wiiWallPill.setOnClickListener(v -> showWallPanel(null));
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setClipChildren(false);
        topRow.addView(wiiThemePill, new LinearLayout.LayoutParams(-2, dp(38)));
        topRow.addView(wiiWallPill, llp(-2, dp(38), dp(10), 0, 0, 0));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.leftMargin = (int) (W * 0.08f);
        tp.topMargin = dp(16);
        w.addView(topRow, tp);

        // Pointing-hand cursor that glides to whatever is focused.
        wiiHand = new HandView(this);
        wiiHandPlaced = false;
        int hh = Math.max(dp(44), Math.min(dp(76), (int) (wiiTh * 0.55f)));
        wiiHand.setVisibility(View.INVISIBLE);
        w.addView(wiiHand, new FrameLayout.LayoutParams((int) (hh * 0.85f), hh, Gravity.TOP | Gravity.START));
        return w;
    }

    private TextView wiiArrow(String glyph) {
        TextView t = text(34, "sans-serif-light", wText);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(wTile);
        bg.setStroke(dp(2), wEdge);
        t.setBackground(bg);
        return t;
    }

    private TextView wiiButton(String label, String name, boolean circle) {
        TextView b = text(circle ? 26 : 14, "sans-serif-medium", wText);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        if (!circle) b.setPadding(dp(20), 0, dp(20), 0);
        b.setFocusable(true);
        b.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(circle ? 32 : 24));
        bg.setColor(wTile);
        bg.setStroke(dp(3), wEdge);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(3), has ? wBlue : wEdge);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
            if (has) { wiiName.setText(name); pointHand(v, 0.5f, 0.6f); }
        });
        return b;
    }

    private View wiiEmpty() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(wEmptyFill);
        g.setStroke(dp(2), wEmptyEdge);
        v.setBackground(g);
        return v;
    }

    private View wiiTile(App a, int row, int col) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setId(View.generateViewId());
        card.setTag(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(wTile);
        bg.setStroke(dp(2), wEdge);
        card.setBackground(bg);
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        Drawable.ConstantState cs = a.art.getConstantState();
        iv.setImageDrawable(cs != null ? cs.newDrawable(getResources()).mutate() : a.art);
        iv.setScaleType(a.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!a.isBanner) iv.setPadding(dp(18), dp(10), dp(18), dp(10));
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(dp(4), 0x00000000);
        card.setForeground(ring);
        card.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(dp(4), has ? wBlue : 0x00000000);
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(10) : 0);
            if (has) { wiiName.setText(a.label); pointHand(v, 0.5f, 0.62f); }
        });
        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnLongClickListener(v -> { showAppMenu(card, a, false); return true; });
        card.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pages = Math.max(1, (allApps.size() + 11) / 12);
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) {
                if (wiiPage > 0) { wiiPage--; buildWiiGrid(row, 3); }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT && col == 3) {
                if (wiiPage < pages - 1) { wiiPage++; buildWiiGrid(row, 0); }
                return true;
            }
            return false;
        });
        return card;
    }

    private void buildWiiGrid(int fr, int fc) {
        if (wiiGrid == null) return;
        wiiGrid.removeAllViews();
        int per = 12, pages = Math.max(1, (allApps.size() + per - 1) / per);
        if (wiiPage >= pages) wiiPage = pages - 1;
        View target = null, first = null;
        for (int r = 0; r < 3; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            for (int c = 0; c < 4; c++) {
                int idx = wiiPage * per + r * 4 + c;
                View v;
                if (idx < allApps.size()) {
                    v = wiiTile(allApps.get(idx), r, c);
                    if (first == null) first = v;
                    if (r == fr && c == fc) target = v;
                } else {
                    v = wiiEmpty();
                }
                line.addView(v, llp(wiiTw, wiiTh, c == 0 ? 0 : wiiGap, 0, 0, 0));
            }
            wiiGrid.addView(line, llp(-2, wiiTh, 0, r == 0 ? 0 : wiiGap, 0, 0));
        }
        wiiLeft.setAlpha(wiiPage > 0 ? 1f : 0.25f);
        wiiRight.setAlpha(wiiPage < pages - 1 ? 1f : 0.25f);
        wiiPageText.setText(pages > 1 ? (wiiPage + 1) + " / " + pages : "");
        if (target == null) target = first;
        if (target != null) target.requestFocus();
    }

    private void whenLaidOut(View v, Runnable r) {
        if (v.isLaidOut() && v.getWidth() > 0) { r.run(); return; }
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (v.getWidth() == 0) return;
                ViewTreeObserver o = v.getViewTreeObserver();
                if (o.isAlive()) o.removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
    }

    /** Glide the hand so its fingertip lands at (fx, fy) of the focused view. */
    private void pointHand(View v, float fx, float fy) {
        final HandView h = wiiHand;
        if (h == null) return;
        whenLaidOut(v, () -> {
            if (h != wiiHand || wiiLayer == null || !v.isAttachedToWindow()) return;
            int[] a = new int[2], b = new int[2];
            v.getLocationInWindow(a);
            wiiLayer.getLocationInWindow(b);
            float x = a[0] - b[0] + v.getWidth() * fx - h.tipX();
            float y = a[1] - b[1] + v.getHeight() * fy;
            if (!wiiHandPlaced) {
                h.setTranslationX(x);
                h.setTranslationY(y);
                h.setVisibility(View.VISIBLE);
                wiiHandPlaced = true;
            } else {
                h.animate().translationX(x).translationY(y).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
    }

    private void updateWiiClock() {
        if (wiiClock == null) return;
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        wiiClock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(now.getTime()));
        wiiDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now.getTime()));
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        updateIdleClock();
        updateWiiClock();
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        String name = "Offline";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
            if (nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    name = "Ethernet";
                } else {
                    name = "Wi-Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null && wm.getConnectionInfo() != null) {
                        String s = wm.getConnectionInfo().getSSID();
                        if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                    }
                }
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText("\u25C9  " + name);
        if (wiiWifi != null) wiiWifi.setText(wifiPill.getText());
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        scanCustomWalls();
        final String[] pool = wallPool();
        if (pool.length == 0) return;
        final boolean rotating = wallRotating();
        final String chosen = wallStaticKey();
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        final int start = rotating ? (wallIndex + 1) % pool.length : 0;
        wallChangedAt = SystemClock.elapsedRealtime();
        new Thread(() -> {
            Bitmap bm = null;
            String key = "";
            int used = start;
            if (!rotating && !chosen.isEmpty()) {
                bm = loadWall(chosen);
                if (bm != null) key = chosen;
            }
            // A photo that cannot be read is skipped, so one bad file never freezes the background.
            for (int i = 0; i < pool.length && bm == null; i++) {
                used = (start + i) % pool.length;
                bm = loadWall(pool[used]);
                if (bm != null) key = pool[used];
            }
            final Bitmap result = bm;
            final int shown = used;
            final String shownK = key;
            runOnUiThread(() -> {
                if (rotating) {
                    wallIndex = shown;
                    getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", shown).apply();
                } else if (chosen.isEmpty() && !shownK.isEmpty()) {
                    wallPrefs().edit().putString("staticKey", shownK).apply();
                }
                shownKey = shownK;
                showWallpaper(result);
            });
        }).start();
        if (rotating) scheduleWallpaper(wallIntervalMs());
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        lastInput = SystemClock.elapsedRealtime();
        if (idleOn) {
            // Any button wakes the home screen; the press that woke it does nothing else.
            if (e.getAction() == KeyEvent.ACTION_DOWN) { exitIdle(); swallowKey = k; }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && k == swallowKey) { swallowKey = -1; return true; }
        if (movingPkg != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) moveBy(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) moveBy(1);
                else if (wii() && k == KeyEvent.KEYCODE_DPAD_UP) moveBy(-4);
                else if (wii() && k == KeyEvent.KEYCODE_DPAD_DOWN) moveBy(4);
                else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                    movingPkg = null; swallowKey = k;
                }
            }
            return true;
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- wallpaper settings + idle screen

    private android.content.SharedPreferences wallPrefs() { return getSharedPreferences("wall", MODE_PRIVATE); }

    private boolean wallRotating() { return !"static".equals(wallPrefs().getString("mode", "rotate")); }

    private long wallIntervalMs() { return Math.max(1, wallPrefs().getInt("delayMin", 10)) * 60_000L; }

    private int idleMinutes() { return wallPrefs().getInt("idleMin", 5); }

    private String wallStaticKey() { return wallPrefs().getString("staticKey", ""); }

    private boolean popupOpen() {
        return (inputsPopup != null && inputsPopup.isShowing()) || (menuPopup != null && menuPopup.isShowing());
    }

    private boolean wallPanelShowing() { return wallPanel != null && wallPanel.getParent() != null; }

    private void scanCustomWalls() {
        java.io.File wd = getExternalFilesDir("wallpapers");
        java.io.File[] fs = wd == null ? null : wd.listFiles((dir, n) -> {
            String x = n.toLowerCase(Locale.ROOT);
            return x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png") || x.endsWith(".webp");
        });
        if (fs == null) fs = new java.io.File[0];
        Arrays.sort(fs);
        extWalls = fs;
    }

    /** What rotation cycles through: your photos if you pushed any, otherwise the built-in ones. */
    private String[] wallPool() {
        List<String> l = new ArrayList<>();
        if (extWalls.length > 0) { for (java.io.File f : extWalls) l.add("c:" + f.getName()); }
        else { for (String w : wallpapers) l.add("b:" + w); }
        return l.toArray(new String[0]);
    }

    private List<String> allWallKeys() {
        List<String> l = new ArrayList<>();
        for (java.io.File f : extWalls) l.add("c:" + f.getName());
        for (String w : wallpapers) l.add("b:" + w);
        return l;
    }

    private Bitmap loadWall(String key) {
        try {
            if (key.startsWith("c:")) return decodeSampled(new java.io.File(getExternalFilesDir("wallpapers"), key.substring(2)));
            try (java.io.InputStream in = getAssets().open("wallpapers/" + key.substring(2))) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int thumbSample(int w) {
        int s = 1;
        while (w / (s * 2) >= 360) s *= 2;
        return s;
    }

    private Bitmap decodeThumb(String key) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            if (key.startsWith("c:")) {
                String path = new java.io.File(getExternalFilesDir("wallpapers"), key.substring(2)).getPath();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                o.inSampleSize = thumbSample(o.outWidth);
                o.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(path, o);
            }
            String asset = "wallpapers/" + key.substring(2);
            try (java.io.InputStream in = getAssets().open(asset)) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inSampleSize = thumbSample(o.outWidth);
            o.inJustDecodeBounds = false;
            try (java.io.InputStream in = getAssets().open(asset)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private TextView panelLabel(String s) {
        TextView t = text(12, "sans-serif-medium", 0x99FFFFFF);
        t.setText(s.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.18f);
        return t;
    }

    private LinearLayout chipRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        return r;
    }

    private TextView chip(String label, boolean on, String tag, Runnable action) {
        TextView c = text(14, "sans-serif-medium", on ? 0xFF111111 : 0xE6FFFFFF);
        c.setText(label);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(18), 0, dp(18), 0);
        c.setFocusable(true);
        c.setId(View.generateViewId());
        c.setTag(tag);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(19));
        bg.setColor(on ? 0xF2FFFFFF : 0x33FFFFFF);
        bg.setStroke(dp(2), 0x00000000);
        c.setBackground(bg);
        c.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(2), has ? 0xFF4FC3F7 : 0x00000000);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
        });
        c.setOnClickListener(v -> action.run());
        return c;
    }

    private void showWallPanel(String focusTag) {
        scanCustomWalls();
        if (wallPanel == null || wallPanel.getParent() == null) {
            wallPanel = new FrameLayout(this);
            wallPanel.setBackgroundColor(0xF2101418);
            rootView.addView(wallPanel, new FrameLayout.LayoutParams(-1, -1));
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            wiiLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        wallPanel.removeAllViews();
        final boolean rotating = wallRotating();
        final int gen = ++thumbGen;
        int W = getResources().getDisplayMetrics().widthPixels;

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(48));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        wallPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(30, "sans-serif-light", Color.WHITE);
        title.setText("Wallpapers");
        col.addView(title);
        TextView sub = text(13, "sans-serif", 0x99FFFFFF);
        sub.setText(extWalls.length + " of your photos on this TV  \u00B7  " + wallpapers.length + " built in");
        col.addView(sub, llp(-2, -2, 0, dp(4), 0, dp(22)));

        col.addView(panelLabel("Mode"));
        LinearLayout modeRow = chipRow();
        modeRow.addView(chip("Rotating", rotating, "m-rot", () -> {
            wallPrefs().edit().putString("mode", "rotate").apply();
            nextWallpaper();
            showWallPanel("m-rot");
        }), llp(-2, dp(38), 0, 0, dp(10), 0));
        modeRow.addView(chip("Static", !rotating, "m-static", () -> {
            android.content.SharedPreferences.Editor ed = wallPrefs().edit().putString("mode", "static");
            if (wallStaticKey().isEmpty() && !shownKey.isEmpty()) ed.putString("staticKey", shownKey);
            ed.apply();
            nextWallpaper();
            showWallPanel("m-static");
        }), llp(-2, dp(38), 0, 0, dp(10), 0));
        col.addView(modeRow, llp(-2, -2, 0, dp(8), 0, dp(20)));

        if (rotating) {
            col.addView(panelLabel("Change photo every"));
            LinearLayout dr = chipRow();
            int cur = wallPrefs().getInt("delayMin", 10);
            for (int m : new int[]{1, 5, 10, 30, 60}) {
                final int mm = m;
                dr.addView(chip(m == 60 ? "1 hour" : m + " min", cur == m, "d-" + m, () -> {
                    wallPrefs().edit().putInt("delayMin", mm).apply();
                    scheduleWallpaper(wallIntervalMs());
                    showWallPanel("d-" + mm);
                }), llp(-2, dp(38), 0, 0, dp(10), 0));
            }
            col.addView(dr, llp(-2, -2, 0, dp(8), 0, dp(6)));
            TextView note = text(12, "sans-serif", 0x80FFFFFF);
            note.setText("Rotating through " + wallPool().length + " photos");
            col.addView(note, llp(-2, -2, 0, 0, 0, dp(20)));
        }

        col.addView(panelLabel("Idle screen after"));
        LinearLayout ir = chipRow();
        int curIdle = idleMinutes();
        for (int m : new int[]{0, 2, 5, 10, 30}) {
            final int mm = m;
            ir.addView(chip(m == 0 ? "Off" : m + " min", curIdle == m, "i-" + m, () -> {
                wallPrefs().edit().putInt("idleMin", mm).apply();
                lastInput = SystemClock.elapsedRealtime();
                showWallPanel("i-" + mm);
            }), llp(-2, dp(38), 0, 0, dp(10), 0));
        }
        col.addView(ir, llp(-2, -2, 0, dp(8), 0, dp(6)));
        TextView idleNote = text(12, "sans-serif", 0x80FFFFFF);
        idleNote.setText("When nothing is pressed on the home screen, the apps hide and the wallpaper fills the screen with the time.");
        col.addView(idleNote, llp(-2, -2, 0, 0, 0, dp(22)));

        if (!rotating) {
            col.addView(panelLabel("Pick a wallpaper"));
            final List<String> keys = allWallKeys();
            final List<ImageView> ivs = new ArrayList<>();
            String sel = wallStaticKey();
            int gap = dp(12), perRow = 4;
            int tw = (W - dp(112) - gap * (perRow - 1)) / perRow;
            int th = tw * 9 / 16;
            if (keys.isEmpty()) {
                TextView none = text(14, "sans-serif", 0xB3FFFFFF);
                none.setText("No photos found.");
                col.addView(none, llp(-2, -2, 0, dp(8), 0, 0));
            }
            LinearLayout rowL = null;
            for (int i = 0; i < keys.size(); i++) {
                if (i % perRow == 0) {
                    rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    rowL.setClipChildren(false);
                    col.addView(rowL, llp(-2, -2, 0, i == 0 ? dp(8) : gap, 0, 0));
                }
                final String key = keys.get(i);
                FrameLayout card = new FrameLayout(this);
                card.setFocusable(true);
                card.setId(View.generateViewId());
                card.setTag("t:" + key);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10));
                bg.setColor(0xFF1E252C);
                card.setBackground(bg);
                card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                card.setClipToOutline(true);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                ivs.add(iv);
                if (key.equals(sel)) {
                    TextView ck = text(15, "sans-serif-medium", Color.WHITE);
                    ck.setText("\u2713");
                    ck.setGravity(Gravity.CENTER);
                    GradientDrawable cb = new GradientDrawable();
                    cb.setShape(GradientDrawable.OVAL);
                    cb.setColor(0xFF4FC3F7);
                    ck.setBackground(cb);
                    card.addView(ck, lp(dp(26), dp(26), Gravity.TOP | Gravity.END, 0, dp(6), dp(6), 0));
                }
                final GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dp(10));
                ring.setStroke(dp(3), 0x00000000);
                card.setForeground(ring);
                card.setOnFocusChangeListener((v, has) -> {
                    ring.setStroke(dp(3), has ? 0xFF4FC3F7 : 0x00000000);
                    v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
                });
                card.setOnClickListener(v -> {
                    wallPrefs().edit().putString("mode", "static").putString("staticKey", key).apply();
                    nextWallpaper();
                    showWallPanel("t:" + key);
                });
                rowL.addView(card, llp(tw, th, i % perRow == 0 ? 0 : gap, 0, 0, 0));
            }
            new Thread(() -> {
                for (int i = 0; i < keys.size(); i++) {
                    if (gen != thumbGen) return;
                    final Bitmap b = decodeThumb(keys.get(i));
                    final ImageView target = ivs.get(i);
                    if (b != null) runOnUiThread(() -> { if (gen == thumbGen) target.setImageBitmap(b); });
                }
            }).start();
        }

        TextView done = chip("Done", false, "done", () -> closeWallPanel());
        col.addView(done, llp(-2, dp(38), 0, dp(26), 0, 0));

        View f = focusTag == null ? null : wallPanel.findViewWithTag(focusTag);
        if (f == null) f = wallPanel.findViewWithTag(rotating ? "m-rot" : "m-static");
        if (f != null) f.requestFocus();
    }

    private void closeWallPanel() {
        thumbGen++;
        if (wallPanel != null && wallPanel.getParent() != null) rootView.removeView(wallPanel);
        applyTheme();
        View f = wii() ? wiiWallPill : wallPill;
        if (f != null) f.requestFocus();
    }

    private void enterIdle() {
        if (idleOn || rootView == null) return;
        idleOn = true;
        if (inputsPopup != null) inputsPopup.dismiss();
        if (menuPopup != null) menuPopup.dismiss();
        if (idleLayer == null) {
            idleLayer = new FrameLayout(this);
            idleClock = text(72, "sans-serif-thin", Color.WHITE);
            idleClock.setShadowLayer(dp(14), 0, dp(2), 0xB3000000);
            idleDate = text(16, "sans-serif-light", 0xE6FFFFFF);
            idleDate.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.END);
            box.addView(idleClock);
            box.addView(idleDate);
            idleLayer.addView(box, lp(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, dp(56), dp(44)));
        }
        updateIdleClock();
        idleLayer.setAlpha(0f);
        rootView.addView(idleLayer, new FrameLayout.LayoutParams(-1, -1));
        idleLayer.animate().alpha(1f).setDuration(900).start();
        for (View v : new View[]{scroll, wiiLayer}) {
            v.animate().alpha(0f).setDuration(600).withEndAction(() -> { if (idleOn) v.setVisibility(View.INVISIBLE); }).start();
        }
        glow.setVisibility(View.INVISIBLE);
        gridShade.setVisibility(View.INVISIBLE);
        scrimView.animate().alpha(0f).setDuration(600).start();
    }

    private void exitIdle() {
        if (!idleOn) return;
        idleOn = false;
        lastInput = SystemClock.elapsedRealtime();
        if (idleLayer != null && idleLayer.getParent() != null) rootView.removeView(idleLayer);
        for (View v : new View[]{scroll, wiiLayer}) { v.animate().cancel(); v.setAlpha(1f); }
        glow.setVisibility(View.VISIBLE);
        gridShade.setVisibility(View.VISIBLE);
        scrimView.animate().cancel();
        scrimView.setAlpha(1f);
        applyTheme();
    }

    private void updateIdleClock() {
        if (idleClock == null) return;
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        idleClock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        idleDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now.getTime()));
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        lastInput = SystemClock.elapsedRealtime();
        if (idleOn) {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) exitIdle();
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        lastInput = SystemClock.elapsedRealtime();
        if (idleOn) { exitIdle(); return true; }
        return super.dispatchGenericMotionEvent(ev);
    }

    /** Simple pointing-hand cursor drawn from rounded shapes (original artwork). */
    static final class HandView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        HandView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3.2f * d);
            line.setColor(0xFF8E9BA8);
            shade.setStyle(Paint.Style.FILL);
            shade.setColor(0x40000000);
            setFocusable(false);
            setClickable(false);
        }

        /** Horizontal position of the fingertip inside this view. */
        float tipX() {
            ViewGroup.LayoutParams lp = getLayoutParams();
            return (lp != null ? lp.width : getWidth()) * 0.38f;
        }

        private void shapes(Canvas c, Paint p, float dx, float dy) {
            float w = getWidth(), h = getHeight();
            r.set(w * 0.26f + dx, dy, w * 0.50f + dx, h * 0.62f + dy);
            c.drawRoundRect(r, w * 0.12f, w * 0.12f, p);
            r.set(w * 0.14f + dx, h * 0.40f + dy, w * 0.88f + dx, h * 0.98f + dy);
            c.drawRoundRect(r, w * 0.20f, w * 0.20f, p);
            r.set(w * 0.48f + dx, h * 0.34f + dy, w * 0.66f + dx, h * 0.56f + dy);
            c.drawRoundRect(r, w * 0.09f, w * 0.09f, p);
            r.set(w * 0.64f + dx, h * 0.38f + dy, w * 0.82f + dx, h * 0.60f + dy);
            c.drawRoundRect(r, w * 0.09f, w * 0.09f, p);
            r.set(w * 0.78f + dx, h * 0.44f + dy, w * 0.94f + dx, h * 0.66f + dy);
            c.drawRoundRect(r, w * 0.08f, w * 0.08f, p);
            r.set(dx, h * 0.48f + dy, w * 0.30f + dx, h * 0.78f + dy);
            c.drawOval(r, p);
        }

        @Override protected void onDraw(Canvas c) {
            shapes(c, shade, 3 * d, 4 * d);
            shapes(c, line, 0, 0);
            shapes(c, fill, 0, 0);
        }
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
