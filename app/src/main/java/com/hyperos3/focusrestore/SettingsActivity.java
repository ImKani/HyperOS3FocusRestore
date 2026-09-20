package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.text.Editable;
import android.text.TextWatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SettingsActivity extends Activity {
    private static final String TAG = "HyperOS3FocusRestore";
    // Deprecated aliases retained for the existing Hook source/API surface.
    static final String PREFS_NAME = FocusRestoreSettings.PREFS_NAME;
    static final String KEY_LIMIT_WIDTH = FocusRestoreSettings.KEY_LIMIT_WIDTH;
    static final String KEY_WIDTH_DP = FocusRestoreSettings.KEY_WIDTH_DP;
    static final String KEY_MARQUEE_DELAY_MS = FocusRestoreSettings.KEY_MARQUEE_DELAY_MS;
    static final String KEY_COMPAT_RETRY = FocusRestoreSettings.KEY_COMPAT_RETRY;
    static final String KEY_ISLAND_COMPAT = FocusRestoreSettings.KEY_ISLAND_COMPAT;
    static final String KEY_ISLAND_SEPARATOR = FocusRestoreSettings.KEY_ISLAND_SEPARATOR;
    static final String KEY_ALLOW_FOCUS_CLICK = FocusRestoreSettings.KEY_ALLOW_FOCUS_CLICK;
    static final String KEY_ISLAND_GENERAL_SEPARATOR = FocusRestoreSettings.KEY_ISLAND_GENERAL_SEPARATOR;
    static final String KEY_ISLAND_SIDE_SEPARATOR = FocusRestoreSettings.KEY_ISLAND_SIDE_SEPARATOR;
    static final String DEFAULT_ISLAND_SEPARATOR = FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR;
    static final int DEFAULT_WIDTH_DP = FocusRestoreSettings.DEFAULT_WIDTH_DP;
    static final int MIN_WIDTH_DP = FocusRestoreSettings.MIN_WIDTH_DP;
    static final int MAX_WIDTH_DP = FocusRestoreSettings.MAX_WIDTH_DP;
    static final int DEFAULT_MARQUEE_DELAY_MS = FocusRestoreSettings.DEFAULT_MARQUEE_DELAY_MS;

    private static final Object STORE_WRITE_LOCK = new Object();
    private static long lastAllocatedGeneration;

    private SharedPreferences preferences;
    private SharedPreferences hookPreferences;
    private FocusRestoreSettings settings;
    private final Object saveLock = new Object();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(command -> {
        Thread thread = new Thread(command, TAG + "-save");
        thread.setDaemon(true);
        return thread;
    });
    private FocusRestoreSettings queuedSettings;
    private long queuedSettingsGeneration;
    private long settingsGeneration;
    private boolean saveWorkerRunning;
    private Future<?> saveFuture;
    private volatile boolean destroyed;
    private LinearLayout pageContainer;
    private LinearLayout bottomNav;
    private ImageButton[] navButtons;
    private int currentPage;

    private int COLOR_PRIMARY = 0xFF3E5F7A;
    private int COLOR_PRIMARY_LIGHT = 0xFFC7DCEB;
    private int COLOR_BACKGROUND = 0xFFF2F5F8;
    private int COLOR_TEXT_PRIMARY = 0xFF191C1E;
    private int COLOR_TEXT_SECONDARY = 0xFF42474B;
    private int COLOR_DIVIDER = 0xFFC2C7CB;
    private int COLOR_INPUT_BACKGROUND = 0xFFE2E8ED;
    private int COLOR_SURFACE = 0xFFF7FAFC;
    private int COLOR_SURFACE_HIGH = 0xFFE9EEF2;
    private int COLOR_NAV_SELECTED = 0xFFD6E4EE;
    private boolean nightMode;
    private Dialog activeDialog;
    private Toast feedbackToast;
    private ScrollView pageScroll;
    private final int[] scrollPositions = new int[2];

    private Button os3ModeButton;
    private Button os4ModeButton;
    private Switch manualWidthSwitch;
    private SeekBar widthSeekBar;
    private TextView widthValue;
    private View widthValueRow;
    private View widthRangeRow;
    private SeekBar delaySeekBar;
    private TextView delayValue;
    private Switch compatRetrySwitch;
    private Switch marqueeBounceSwitch;
    private Switch islandCompatSwitch;
    private Switch disableIslandPropertySwitch;
    private Switch disableIslandFeatureCacheSwitch;
    private Switch allowFocusClickSwitch;
    private Switch hideNotificationIconsSwitch;
    private Switch showFocusDividerSwitch;
    private Switch showIslandIconSwitch;
    private Switch tintIslandIconSwitch;
    private Switch expandIslandOnClickSwitch;
    private EditText generalSeparatorInput;
    private EditText sideSeparatorInput;
    private boolean pendingManual, pendingCompatRetry, pendingMarqueeBounce, pendingIslandCompat,
            pendingDisableIslandProperty, pendingDisableIslandFeatureCache, pendingAllowFocusClick,
            pendingHideNotificationIcons, pendingShowFocusDivider, pendingShowIslandIcon,
            pendingTintIslandIcon, pendingExpandIslandOnClick;
    private int pendingHookMode, pendingWidthDp, pendingDelayMs;
    private String pendingGeneralSeparator, pendingSideSeparator;
    private Set<String> pendingForcePackages = new HashSet<>();
    private Button forcePackagesButton;
    private List<ApplicationInfo> dialogAllApps = new ArrayList<>();
    private List<ApplicationInfo> dialogVisibleApps = new ArrayList<>();
    private Set<String> dialogSelectedPackages;
    private ListView dialogListView;
    private ForcePackageAdapter dialogAdapter;
    private EditText dialogSearchInput;
    private Switch dialogShowSystemSwitch;
    private TextView dialogEmptyView;
    private boolean dialogAppsLoaded;
    private static final String APP_CACHE_SEPARATOR = "\u001e";
    private final Map<String, String> appLabels = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean systemNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(systemNight ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        applySystemPalette();
        configureSystemBars(getWindow());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        hookPreferences = FocusRestoreSettings.hookPreferences(this);
        reconcileSettingsStores();
        setContentView(createContent());
        loadSettings();
        if (savedInstanceState != null) restorePendingState(savedInstanceState);
        showPage(currentPage);
        if (savedInstanceState != null && savedInstanceState.getBoolean("m3.savePending", false)) {
            saveSettings();
        }
    }

    @Override
    protected void onStop() {
        Future<?> pendingSave;
        synchronized (saveLock) {
            pendingSave = saveFuture;
        }
        if (pendingSave != null && !pendingSave.isDone()) {
            try {
                pendingSave.get(1500L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                android.util.Log.w(TAG, "settings save still pending after onStop timeout", timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                android.util.Log.w(TAG, "settings save wait interrupted", interrupted);
            } catch (Exception failure) {
                android.util.Log.e(TAG, "settings save wait failed", failure);
            }
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (feedbackToast != null) feedbackToast.cancel();
        saveExecutor.shutdown();
        super.onDestroy();
    }

    private void reconcileSettingsStores() {
        synchronized (STORE_WRITE_LOCK) {
            long credentialGeneration = FocusRestoreSettings.generation(preferences);
            long hookGeneration = FocusRestoreSettings.generation(hookPreferences);
            settingsGeneration = Math.max(credentialGeneration, hookGeneration);
            if (!FocusRestoreSettings.hasHookSettings(hookPreferences)) {
                FocusRestoreSettings initial = FocusRestoreSettings.fromPreferences(preferences);
                settingsGeneration = nextSettingsGenerationLocked();
                boolean credentialSaved = initial.save(preferences, settingsGeneration);
                boolean hookSaved = initial.save(hookPreferences, settingsGeneration);
                android.util.Log.i(TAG, "settings initialized generation=" + settingsGeneration
                        + " credential=" + credentialSaved + " deviceProtected=" + hookSaved
                        + " " + initial.describe());
                return;
            }
            if (credentialGeneration == hookGeneration && credentialGeneration > 0L) return;
            FocusRestoreSettings newest = hookGeneration > credentialGeneration
                    ? FocusRestoreSettings.fromPreferences(hookPreferences)
                    : FocusRestoreSettings.fromPreferences(preferences);
            settingsGeneration = nextSettingsGenerationLocked();
            boolean credentialSaved = newest.save(preferences, settingsGeneration);
            boolean hookSaved = newest.save(hookPreferences, settingsGeneration);
            android.util.Log.i(TAG, "settings reconciled generation=" + settingsGeneration
                    + " source=" + (hookGeneration > credentialGeneration
                    ? "deviceProtected" : "credential")
                    + " credential=" + credentialSaved + " deviceProtected=" + hookSaved
                    + " " + newest.describe());
        }
    }

    private long nextSettingsGenerationLocked() {
        long persisted = Math.max(FocusRestoreSettings.generation(preferences),
                FocusRestoreSettings.generation(hookPreferences));
        long floor = Math.max(Math.max(lastAllocatedGeneration, settingsGeneration),
                Math.max(persisted, System.currentTimeMillis()));
        lastAllocatedGeneration = floor == Long.MAX_VALUE ? Long.MAX_VALUE : floor + 1L;
        return lastAllocatedGeneration;
    }

    private void applySystemPalette() {
        nightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (!nightMode) return;
        COLOR_PRIMARY = 0xFF52758C;
        COLOR_PRIMARY_LIGHT = 0xFF2D4657;
        COLOR_BACKGROUND = 0xFF101417;
        COLOR_TEXT_PRIMARY = 0xFFE5E9EC;
        COLOR_TEXT_SECONDARY = 0xFFB8C1C7;
        COLOR_DIVIDER = 0xFF495057;
        COLOR_INPUT_BACKGROUND = 0xFF242B30;
        COLOR_SURFACE = 0xFF181D21;
        COLOR_SURFACE_HIGH = 0xFF20272C;
        COLOR_NAV_SELECTED = 0xFF304B5D;
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);
        if (Build.VERSION.SDK_INT >= 29) root.setForceDarkAllowed(false);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        applyTopInsets(shell);
        shell.addView(createTopBar(), new LinearLayout.LayoutParams(-1, -2));
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        shell.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1f));
        bottomNav = (LinearLayout) createBottomNavigation();
        applyBottomInsets(bottomNav);
        shell.addView(bottomNav, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private View createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(64));
        bar.setPadding(dp(16), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(COLOR_SURFACE);
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(2));
        ImageView icon = new ImageView(this);
        Drawable appIcon = getApplicationInfo().loadIcon(getPackageManager());
        icon.setImageDrawable(appIcon);
        icon.setElevation(0);
        bar.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView brand = text("FocusRestore", 19, COLOR_TEXT_PRIMARY);
        brand.setTypeface(brand.getTypeface(), 1);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = dp(10);
        bar.addView(brand, brandParams);
        return bar;
    }

    private View createBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setMinimumHeight(dp(72));
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(COLOR_BACKGROUND);
        nav.setPadding(0, dp(8), 0, dp(8));
        String[] names = {"主页", "高级"};
        navButtons = new ImageButton[names.length];
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            FrameLayout segment = new FrameLayout(this);
            ImageButton item = new ImageButton(this);
            item.setContentDescription(names[i]);
            item.setMinimumHeight(dp(44));
            item.setMinimumWidth(dp(68));
            item.setPadding(0, 0, 0, 0);
            item.setScaleType(ImageView.ScaleType.CENTER);
            item.setBackground(roundedBg(Color.TRANSPARENT, 22));
            item.setImageResource(page == 0 ? R.drawable.nav_home_off : R.drawable.nav_advanced_off);
            flattenButton(item);
            item.setOnClickListener(v -> showPage(page));
            navButtons[i] = item;
            segment.addView(item, new FrameLayout.LayoutParams(dp(68), dp(44), Gravity.CENTER));
            nav.addView(segment, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        return nav;
    }

    private void showPage(int page) {
        captureCurrentInputs();
        if (pageScroll != null) scrollPositions[currentPage] = pageScroll.getScrollY();
        currentPage = page;
        pageContainer.removeAllViews();
        updateNavButtons(page);
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        if (page == 0) buildSettingsPage(content);
        else buildAdvancedPage(content);
        scroll.addView(content);
        pageContainer.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        scroll.post(() -> scroll.scrollTo(0, scrollPositions[page]));
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(roundedBg(COLOR_SURFACE, 12));
        panel.setElevation(0);
        return panel;
    }

    private TextView sectionHeader(String value) {
        TextView header = text(value, 14, COLOR_TEXT_SECONDARY);
        header.setTypeface(header.getTypeface(), 1);
        header.setPadding(dp(4), dp(4), dp(4), dp(2));
        return header;
    }

    private Switch createSwitch(String label) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(15);
        control.setMinHeight(dp(48));
        control.setPadding(0, 0, 0, 0);
        styleSwitch(control);
        return control;
    }

    private void buildSettingsPage(LinearLayout root) {
        root.addView(sectionHeader("系统界面版本"), matchWrap(dp(8)));
        LinearLayout modePanel = panel();
        LinearLayout modeSelector = new LinearLayout(this);
        modeSelector.setOrientation(LinearLayout.HORIZONTAL);
        os3ModeButton = createModeButton("HyperOS 3", FocusRestoreSettings.HOOK_MODE_OS3);
        os4ModeButton = createModeButton("HyperOS 4", FocusRestoreSettings.HOOK_MODE_OS4);
        modeSelector.addView(os3ModeButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams os4Params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        os4Params.leftMargin = dp(8);
        modeSelector.addView(os4ModeButton, os4Params);
        modePanel.addView(modeSelector, matchWrap(dp(8)));
        modePanel.addView(text("切换后重启系统界面或设备生效", 13, COLOR_TEXT_SECONDARY),
                matchWrap(0));
        root.addView(modePanel, matchWrap(dp(12)));

        root.addView(sectionHeader("超级岛转换"), matchWrap(dp(8)));
        LinearLayout islandPanel = panel();
        islandCompatSwitch = createSwitch("转换超级岛内容为焦点通知");
        islandPanel.addView(islandCompatSwitch, matchWrap(dp(4)));
        forcePackagesButton = new Button(this);
        forcePackagesButton.setText(forcePackagesLabel());
        forcePackagesButton.setAllCaps(false);
        forcePackagesButton.setTextSize(14);
        flattenButton(forcePackagesButton);
        forcePackagesButton.setMinHeight(dp(52));
        forcePackagesButton.setOnClickListener(v -> showForcePackagesDialog());
        islandPanel.addView(forcePackagesButton, matchWrap(0));
        root.addView(islandPanel, matchWrap(dp(12)));

        root.addView(sectionHeader("焦点显示"), matchWrap(dp(8)));
        LinearLayout focusPanel = panel();
        manualWidthSwitch = createSwitch("限制焦点通知宽度");
        focusPanel.addView(manualWidthSwitch, matchWrap(dp(4)));
        LinearLayout widthRow = valueRow("最大宽度", pendingWidthDp + " dp");
        widthValueRow = widthRow;
        widthValue = (TextView) widthRow.getChildAt(1);
        focusPanel.addView(widthRow, matchWrap(0));
        widthSeekBar = new SeekBar(this);
        styleSeekBar(widthSeekBar);
        widthSeekBar.setMax(MAX_WIDTH_DP - MIN_WIDTH_DP);
        focusPanel.addView(widthSeekBar, matchWrap(dp(2)));
        widthRangeRow = rangeRow("80 dp", "400 dp");
        focusPanel.addView(widthRangeRow, matchWrap(dp(4)));
        hideNotificationIconsSwitch = createSwitch("隐藏其他通知图标（HyperOS 4）");
        showFocusDividerSwitch = createSwitch("显示焦点分隔线（HyperOS 4）");
        focusPanel.addView(hideNotificationIconsSwitch, matchWrap(dp(4)));
        focusPanel.addView(showFocusDividerSwitch, matchWrap(0));
        root.addView(focusPanel, matchWrap(dp(12)));

        manualWidthSwitch.setChecked(pendingManual);
        widthSeekBar.setProgress(pendingWidthDp - MIN_WIDTH_DP);
        widthValue.setText(pendingWidthDp + " dp");
        islandCompatSwitch.setChecked(pendingIslandCompat);
        hideNotificationIconsSwitch.setChecked(pendingHideNotificationIcons);
        showFocusDividerSwitch.setChecked(pendingShowFocusDivider);
        updateModeButtons();
        updateWidthControls();
        updateForcePackagesButton();
        updateExperimentalControls();
        installSettingsListeners();
    }

    private void buildAdvancedPage(LinearLayout root) {
        root.addView(sectionHeader("实验性图标"), matchWrap(dp(8)));
        LinearLayout iconPanel = panel();
        showIslandIconSwitch = createSwitch("显示超级岛图标（实验性）");
        tintIslandIconSwitch = createSwitch("图标跟随状态栏反色（实验性）");
        iconPanel.addView(showIslandIconSwitch, matchWrap(dp(4)));
        iconPanel.addView(tintIslandIconSwitch, matchWrap(0));
        root.addView(iconPanel, matchWrap(dp(12)));

        root.addView(sectionHeader("点击行为"), matchWrap(dp(8)));
        LinearLayout interactionPanel = panel();
        allowFocusClickSwitch = createSwitch("旧版：打开通知内容（实验性）");
        expandIslandOnClickSwitch = createSwitch("点击后展开超级岛通知（实验性）");
        interactionPanel.addView(allowFocusClickSwitch, matchWrap(dp(4)));
        interactionPanel.addView(expandIslandOnClickSwitch, matchWrap(0));
        root.addView(interactionPanel, matchWrap(dp(12)));

        root.addView(sectionHeader("滚动与兼容"), matchWrap(dp(8)));
        LinearLayout delayPanel = panel();
        marqueeBounceSwitch = createSwitch("启用往返滚动");
        compatRetrySwitch = createSwitch("兼容重试模式");
        delayPanel.addView(marqueeBounceSwitch, matchWrap(dp(4)));
        delayPanel.addView(compatRetrySwitch, matchWrap(dp(4)));
        LinearLayout delayRow = valueRow("滚动启动延迟", "0.2 秒");
        delayValue = (TextView) delayRow.getChildAt(1);
        delayPanel.addView(delayRow, matchWrap(0));
        delaySeekBar = new SeekBar(this);
        styleSeekBar(delaySeekBar);
        delaySeekBar.setMax(50);
        delayPanel.addView(delaySeekBar, matchWrap(dp(2)));
        delayPanel.addView(rangeRow("0 秒", "5 秒"), matchWrap(0));
        root.addView(delayPanel, matchWrap(dp(12)));

        root.addView(sectionHeader("内容连接符"), matchWrap(dp(8)));
        LinearLayout separatorPanel = panel();
        separatorPanel.addView(text("普通内容", 15, COLOR_TEXT_PRIMARY), matchWrap(dp(4)));
        generalSeparatorInput = input("默认：·，允许留空");
        generalSeparatorInput.setText(pendingGeneralSeparator);
        separatorPanel.addView(generalSeparatorInput, matchWrap(dp(8)));
        separatorPanel.addView(text("左右区域", 15, COLOR_TEXT_PRIMARY), matchWrap(dp(4)));
        sideSeparatorInput = input("默认：·，允许留空");
        sideSeparatorInput.setText(pendingSideSeparator);
        separatorPanel.addView(sideSeparatorInput, matchWrap(0));
        root.addView(separatorPanel, matchWrap(dp(12)));

        showIslandIconSwitch.setChecked(pendingShowIslandIcon);
        tintIslandIconSwitch.setChecked(pendingTintIslandIcon);
        allowFocusClickSwitch.setChecked(pendingAllowFocusClick);
        expandIslandOnClickSwitch.setChecked(pendingExpandIslandOnClick);
        marqueeBounceSwitch.setChecked(pendingMarqueeBounce);
        compatRetrySwitch.setChecked(pendingCompatRetry);
        delaySeekBar.setProgress(pendingDelayMs / 100);
        delayValue.setText(String.format(Locale.US, "%.1f 秒", pendingDelayMs / 1000f));

        if (BuildConfig.DEBUG) {
            root.addView(sectionHeader("内部兼容开关"), matchWrap(dp(8)));
            LinearLayout debugPanel = panel();
            disableIslandPropertySwitch = createSwitch("覆盖 feature.island.debug");
            disableIslandFeatureCacheSwitch = createSwitch("禁用 FEATURE_DYNAMIC_ISLAND");
            debugPanel.addView(disableIslandPropertySwitch, matchWrap(dp(4)));
            debugPanel.addView(disableIslandFeatureCacheSwitch, matchWrap(0));
            root.addView(debugPanel, matchWrap(dp(12)));
            disableIslandPropertySwitch.setChecked(pendingDisableIslandProperty);
            disableIslandFeatureCacheSwitch.setChecked(pendingDisableIslandFeatureCache);
        }
        updateExperimentalControls();
        buildAboutSections(root);
        installSettingsListeners();
    }

    private void buildAboutSections(LinearLayout root) {
        root.addView(sectionHeader("关于"), matchWrap(dp(8)));
        LinearLayout aboutPanel = panel();
        TextView about = text("FocusRestore\n\n用于 HyperOS 3/4 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的焦点通知状态栏显示路径。\n\n本模块通过 LSPosed Hook 介入系统界面，存在 ROM 版本差异、系统崩溃、状态栏显示异常、功能失效、数据丢失或其他不可控风险。使用前请自行备份，并自行承担使用风险。\n\n作者：ImKani", 15, COLOR_TEXT_PRIMARY);
        aboutPanel.addView(about, matchWrap(dp(8)));
        aboutPanel.addView(text("当前版本：v" + BuildConfig.VERSION_NAME, 14, COLOR_TEXT_SECONDARY), matchWrap(0));
        root.addView(aboutPanel, matchWrap(dp(12)));

        root.addView(sectionHeader("链接"), matchWrap(dp(8)));
        LinearLayout links = panel();
        Button github = actionButton("打开 GitHub", COLOR_PRIMARY, Color.WHITE);
        github.setOnClickListener(v -> openExternalLink("https://github.com/ImKani/FocusRestore"));
        links.addView(github, matchWrap(dp(8)));
        Button coolapk = actionButton("酷安主页", COLOR_SURFACE_HIGH, COLOR_PRIMARY);
        coolapk.setOnClickListener(v -> openExternalLink("https://www.coolapk.com/u/1205658"));
        links.addView(coolapk, matchWrap(0));
        root.addView(links, matchWrap(dp(12)));

        root.addView(sectionHeader("许可证"), matchWrap(dp(8)));
        LinearLayout license = panel();
        license.addView(text("GNU General Public License v3.0 only（GPL-3.0-only）", 15, COLOR_TEXT_SECONDARY), matchWrap(0));
        root.addView(license, matchWrap(dp(12)));
    }

    private Button actionButton(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(button.getTypeface(), 1);
        button.setTextColor(foreground);
        button.setBackground(roundedBg(background, 12));
        flattenButton(button);
        button.setMinHeight(dp(40));
        button.setPadding(dp(24), 0, dp(24), 0);
        return button;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureCurrentInputs();
        if (pageScroll != null) scrollPositions[currentPage] = pageScroll.getScrollY();
        outState.putInt("m3.page", currentPage);
        outState.putInt("m3.mode", pendingHookMode);
        outState.putBoolean("m3.manual", pendingManual);
        outState.putInt("m3.width", pendingWidthDp);
        outState.putInt("m3.delay", pendingDelayMs);
        outState.putBoolean("m3.retry", pendingCompatRetry);
        outState.putBoolean("m3.bounce", pendingMarqueeBounce);
        outState.putBoolean("m3.island", pendingIslandCompat);
        outState.putBoolean("m3.property", pendingDisableIslandProperty);
        outState.putBoolean("m3.cache", pendingDisableIslandFeatureCache);
        outState.putBoolean("m3.click", pendingAllowFocusClick);
        outState.putBoolean("m3.hide", pendingHideNotificationIcons);
        outState.putBoolean("m3.divider", pendingShowFocusDivider);
        outState.putBoolean("m3.showIslandIcon", pendingShowIslandIcon);
        outState.putBoolean("m3.tintIslandIcon", pendingTintIslandIcon);
        outState.putBoolean("m3.expandIsland", pendingExpandIslandOnClick);
        outState.putString("m3.general", pendingGeneralSeparator);
        outState.putString("m3.side", pendingSideSeparator);
        outState.putStringArrayList("m3.packages", new ArrayList<>(pendingForcePackages));
        synchronized (saveLock) {
            outState.putBoolean("m3.savePending", saveWorkerRunning || queuedSettings != null);
        }
        super.onSaveInstanceState(outState);
    }

    private void restorePendingState(Bundle state) {
        currentPage = Math.max(0, Math.min(1, state.getInt("m3.page", 0)));
        pendingHookMode = state.getInt("m3.mode", pendingHookMode);
        pendingManual = state.getBoolean("m3.manual", pendingManual);
        pendingWidthDp = state.getInt("m3.width", pendingWidthDp);
        pendingDelayMs = state.getInt("m3.delay", pendingDelayMs);
        pendingCompatRetry = state.getBoolean("m3.retry", pendingCompatRetry);
        pendingMarqueeBounce = state.getBoolean("m3.bounce", pendingMarqueeBounce);
        pendingIslandCompat = state.getBoolean("m3.island", pendingIslandCompat);
        pendingDisableIslandProperty = state.getBoolean("m3.property", pendingDisableIslandProperty);
        pendingDisableIslandFeatureCache = state.getBoolean("m3.cache", pendingDisableIslandFeatureCache);
        pendingAllowFocusClick = state.getBoolean("m3.click", pendingAllowFocusClick);
        pendingHideNotificationIcons = state.getBoolean("m3.hide", pendingHideNotificationIcons);
        pendingShowFocusDivider = state.getBoolean("m3.divider", pendingShowFocusDivider);
        pendingShowIslandIcon = state.getBoolean("m3.showIslandIcon", pendingShowIslandIcon);
        pendingTintIslandIcon = state.getBoolean("m3.tintIslandIcon", pendingTintIslandIcon);
        pendingExpandIslandOnClick = state.getBoolean("m3.expandIsland",
                pendingExpandIslandOnClick) && !pendingAllowFocusClick;
        pendingGeneralSeparator = state.getString("m3.general", pendingGeneralSeparator);
        pendingSideSeparator = state.getString("m3.side", pendingSideSeparator);
        ArrayList<String> packages = state.getStringArrayList("m3.packages");
        if (packages != null) {
            pendingForcePackages = new HashSet<>(InputLimits.sanitizePackages(
                    new java.util.LinkedHashSet<>(packages)));
        }
    }

    private void installSettingsListeners() {
        if (manualWidthSwitch != null) manualWidthSwitch.setOnCheckedChangeListener((b, checked) -> {
            pendingManual = checked; updateWidthControls(); markPending();
        });
        if (widthSeekBar != null) widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                int w = MIN_WIDTH_DP + p;
                if (widthValue != null) widthValue.setText(w + " dp");
                if (user) pendingWidthDp = w;
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { markPending(); }
        });
        if (delaySeekBar != null) delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                int d = p * 100;
                if (delayValue != null) delayValue.setText(String.format(Locale.US, "%.1f 秒", d / 1000f));
                if (user) pendingDelayMs = d;
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { markPending(); }
        });
        if (compatRetrySwitch != null) compatRetrySwitch.setOnCheckedChangeListener((b, c) -> { pendingCompatRetry = c; markPending(); });
        if (marqueeBounceSwitch != null) marqueeBounceSwitch.setOnCheckedChangeListener((b, c) -> { pendingMarqueeBounce = c; markPending(); });
        if (islandCompatSwitch != null) islandCompatSwitch.setOnCheckedChangeListener((b, c) -> {
            pendingIslandCompat = c;
            updateForcePackagesButton();
            updateExperimentalControls();
            markPending();
        });
        if (disableIslandPropertySwitch != null) disableIslandPropertySwitch.setOnCheckedChangeListener((b, c) -> { pendingDisableIslandProperty = c; markPending(); });
        if (disableIslandFeatureCacheSwitch != null) disableIslandFeatureCacheSwitch.setOnCheckedChangeListener((b, c) -> { pendingDisableIslandFeatureCache = c; markPending(); });
        if (showIslandIconSwitch != null) showIslandIconSwitch.setOnCheckedChangeListener((b, c) -> {
            pendingShowIslandIcon = c;
            updateExperimentalControls();
            markPending();
        });
        if (tintIslandIconSwitch != null) tintIslandIconSwitch.setOnCheckedChangeListener((b, c) -> {
            pendingTintIslandIcon = c;
            markPending();
        });
        if (allowFocusClickSwitch != null) allowFocusClickSwitch.setOnCheckedChangeListener((b, c) -> {
            pendingAllowFocusClick = c;
            if (c) {
                pendingExpandIslandOnClick = false;
                if (expandIslandOnClickSwitch != null) expandIslandOnClickSwitch.setChecked(false);
            }
            markPending();
        });
        if (expandIslandOnClickSwitch != null) {
            expandIslandOnClickSwitch.setOnCheckedChangeListener((b, c) -> {
                pendingExpandIslandOnClick = c;
                if (c) {
                    pendingAllowFocusClick = false;
                    if (allowFocusClickSwitch != null) allowFocusClickSwitch.setChecked(false);
                }
                markPending();
            });
        }
        if (hideNotificationIconsSwitch != null) hideNotificationIconsSwitch.setOnCheckedChangeListener((b, c) -> { pendingHideNotificationIcons = c; markPending(); });
        if (showFocusDividerSwitch != null) showFocusDividerSwitch.setOnCheckedChangeListener((b, c) -> { pendingShowFocusDivider = c; markPending(); });
    }

    private String forcePackagesLabel() {
        return pendingForcePackages.isEmpty()
                ? "强制转换白名单（未选择）"
                : "强制转换白名单（已选 " + pendingForcePackages.size() + " 个应用）";
    }

    private void updateForcePackagesButton() {
        if (forcePackagesButton == null) return;
        boolean enabled = pendingIslandCompat;
        forcePackagesButton.setEnabled(enabled);
        forcePackagesButton.setAlpha(enabled ? 1f : 0.38f);
        forcePackagesButton.setText(forcePackagesLabel());
        forcePackagesButton.setTextColor(enabled ? COLOR_PRIMARY : COLOR_TEXT_SECONDARY);
        forcePackagesButton.setBackground(roundedBg(enabled ? COLOR_PRIMARY_LIGHT : COLOR_SURFACE_HIGH, 12));
    }

    private void updateExperimentalControls() {
        boolean islandIconEnabled = pendingIslandCompat;
        setModeSpecificSwitchEnabled(showIslandIconSwitch, islandIconEnabled);
        setModeSpecificSwitchEnabled(tintIslandIconSwitch,
                islandIconEnabled && pendingShowIslandIcon);
        setModeSpecificSwitchEnabled(expandIslandOnClickSwitch, true);
    }

    private void updateWidthControls() {
        boolean enabled = pendingManual;
        if (widthSeekBar != null) { widthSeekBar.setEnabled(enabled); widthSeekBar.setAlpha(enabled ? 1f : 0.38f); }
        if (widthValueRow != null) { widthValueRow.setEnabled(enabled); widthValueRow.setAlpha(enabled ? 1f : 0.38f); }
        if (widthRangeRow != null) { widthRangeRow.setEnabled(enabled); widthRangeRow.setAlpha(enabled ? 1f : 0.38f); }
        if (widthValue != null) { widthValue.setEnabled(enabled); widthValue.setAlpha(enabled ? 1f : 0.38f); }
    }

    private void showForcePackagesDialog() {
        if (!pendingIslandCompat) return;
        dialogAllApps = readCachedApps();
        dialogVisibleApps.clear();
        dialogSelectedPackages = new HashSet<>(pendingForcePackages);
        dialogAppsLoaded = !dialogAllApps.isEmpty();

        final Dialog dialog = new Dialog(this);
        dialog.setOnDismissListener(d -> { activeDialog = null; clearDialogState(); });
        activeDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createWhitelistDialogView(dialog));
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        filterDialogApps();
        window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
            window.setLayout(width, height);
        }
    }

    private View createWhitelistDialogView(final Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBg(COLOR_SURFACE_HIGH, 12));
        if (Build.VERSION.SDK_INT >= 29) root.setForceDarkAllowed(false);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView title = text("强制转换超级岛应用", 18, COLOR_TEXT_PRIMARY);
        title.setTypeface(title.getTypeface(), 1);
        root.addView(title, matchWrap(dp(8)));

        FrameLayout searchBox = new FrameLayout(this);
        dialogSearchInput = input("搜索应用名称或包名");
        dialogSearchInput.setTextSize(16);
        dialogSearchInput.setContentDescription("搜索应用名称或包名");
        searchBox.addView(dialogSearchInput, new FrameLayout.LayoutParams(-1, dp(48)));
        Button clearSearch = new Button(this);
        clearSearch.setText("×");
        clearSearch.setTextSize(20);
        clearSearch.setAllCaps(false);
        clearSearch.setTextColor(COLOR_TEXT_SECONDARY);
        clearSearch.setBackgroundColor(Color.TRANSPARENT);
        flattenButton(clearSearch);
        clearSearch.setContentDescription("清除搜索内容");
        clearSearch.setVisibility(View.GONE);
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        searchBox.addView(clearSearch, clearParams);
        dialogSearchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                filterDialogApps();
            }
            public void afterTextChanged(Editable s) { }
        });
        clearSearch.setOnClickListener(v -> { dialogSearchInput.setText(""); dialogSearchInput.requestFocus(); });
        root.addView(searchBox, matchWrap(dp(6)));

        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER_VERTICAL);
        dialogShowSystemSwitch = new Switch(this);
        dialogShowSystemSwitch.setText("显示系统应用");
        dialogShowSystemSwitch.setTextSize(14);
        styleSwitch(dialogShowSystemSwitch);
        dialogShowSystemSwitch.setOnCheckedChangeListener((button, checked) -> filterDialogApps());
        options.addView(dialogShowSystemSwitch, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        refresh.setTextColor(COLOR_PRIMARY);
        refresh.setBackgroundColor(Color.TRANSPARENT);
        flattenButton(refresh);
        refresh.setMinHeight(dp(40));
        refresh.setPadding(dp(16), 0, dp(16), 0);
        refresh.setOnClickListener(v -> loadDialogApps());
        options.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        root.addView(options, matchWrap(dp(4)));

        dialogListView = new ListView(this);
        dialogListView.setDivider(new ColorDrawable(COLOR_SURFACE_HIGH));
        dialogListView.setDividerHeight(dp(6));
        dialogAdapter = new ForcePackageAdapter();
        dialogListView.setAdapter(dialogAdapter);
        dialogListView.setVisibility(View.GONE);
        dialogListView.setOnItemClickListener((parent, view, position, id) -> {
            ApplicationInfo app = dialogVisibleApps.get(position);
            if (dialogSelectedPackages.contains(app.packageName)) {
                dialogSelectedPackages.remove(app.packageName);
            } else if (dialogSelectedPackages.size() >= InputLimits.MAX_FORCE_PACKAGES) {
                showFeedback("白名单最多选择 " + InputLimits.MAX_FORCE_PACKAGES + " 个应用");
            } else {
                dialogSelectedPackages.add(app.packageName);
            }
            filterDialogApps();
        });
        root.addView(dialogListView, new LinearLayout.LayoutParams(-1, 0, 1f));

        dialogEmptyView = text("尚未加载应用，请点击“刷新”", 14, COLOR_TEXT_SECONDARY);
        dialogEmptyView.setGravity(Gravity.CENTER);
        root.addView(dialogEmptyView, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = new Button(this);
        cancel.setText("取消"); cancel.setAllCaps(false); cancel.setTextColor(COLOR_TEXT_SECONDARY);
        cancel.setBackgroundColor(Color.TRANSPARENT); flattenButton(cancel); cancel.setOnClickListener(v -> dialog.dismiss());
        cancel.setMinHeight(dp(40));
        cancel.setPadding(dp(16), 0, dp(16), 0);
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, dp(40)));
        Button done = actionButton("完成", COLOR_PRIMARY, Color.WHITE);
        done.setOnClickListener(v -> {
            pendingForcePackages = new HashSet<>(InputLimits.sanitizePackages(
                    dialogSelectedPackages));
            updateForcePackagesButton();
            markPending();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-2, dp(40));
        doneParams.leftMargin = dp(8);
        buttons.addView(done, doneParams);
        root.addView(buttons, matchWrap(0));
        return root;
    }

    private void clearDialogState() {
        dialogAllApps = new ArrayList<>();
        dialogVisibleApps = new ArrayList<>();
        dialogSelectedPackages = null;
        dialogListView = null;
        dialogAdapter = null;
        dialogSearchInput = null;
        dialogShowSystemSwitch = null;
        dialogEmptyView = null;
        dialogAppsLoaded = false;
    }

    private void loadDialogApps() {
        List<ApplicationInfo> refreshed = new ArrayList<>(getPackageManager().getInstalledApplications(0));
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(FocusRestoreSettings.KEY_ISLAND_APP_CACHE, encodeAppCache(refreshed))
                .apply();
        dialogAllApps = refreshed;
        appLabels.clear();
        for (ApplicationInfo app : refreshed) {
            if (app != null && app.packageName != null) {
                appLabels.put(app.packageName, String.valueOf(app.loadLabel(getPackageManager())));
            }
        }
        dialogAppsLoaded = true;
        filterDialogApps();
    }

    private List<ApplicationInfo> readCachedApps() {
        appLabels.clear();
        String encoded = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(FocusRestoreSettings.KEY_ISLAND_APP_CACHE, "");
        List<ApplicationInfo> result = new ArrayList<>();
        if (encoded.length() == 0) return result;
        for (String record : encoded.split("\\n")) {
            String[] fields = record.split(java.util.regex.Pattern.quote(APP_CACHE_SEPARATOR), -1);
            if (fields.length < 3) continue;
            try {
                ApplicationInfo app = new ApplicationInfo();
                app.packageName = fields[0];
                app.name = fields[1];
                app.flags = Integer.parseInt(fields[2]);
                appLabels.put(app.packageName, fields[1]);
                result.add(app);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private String encodeAppCache(List<ApplicationInfo> apps) {
        StringBuilder result = new StringBuilder();
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null) continue;
            String label = String.valueOf(app.loadLabel(getPackageManager()))
                    .replace(APP_CACHE_SEPARATOR, " ").replace('\n', ' ').replace('\r', ' ');
            if (result.length() > 0) result.append('\n');
            result.append(app.packageName).append(APP_CACHE_SEPARATOR)
                    .append(label).append(APP_CACHE_SEPARATOR).append(app.flags);
        }
        return result.toString();
    }

    private void filterDialogApps() {
        if (!dialogAppsLoaded || dialogAdapter == null) return;
        String query = dialogSearchInput == null ? "" : dialogSearchInput.getText().toString().toLowerCase(Locale.ROOT).trim();
        boolean includeSystem = dialogShowSystemSwitch != null && dialogShowSystemSwitch.isChecked();
        dialogVisibleApps = new ArrayList<>();
        for (ApplicationInfo app : dialogAllApps) {
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (!includeSystem && system && !dialogSelectedPackages.contains(app.packageName)) continue;
            String label = appLabels.get(app.packageName);
            if (label == null) {
                label = String.valueOf(app.loadLabel(getPackageManager()));
                appLabels.put(app.packageName, label);
            }
            if (query.length() > 0 && !label.toLowerCase(Locale.ROOT).contains(query)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
            dialogVisibleApps.add(app);
        }
        Collections.sort(dialogVisibleApps, (a, b) -> {
            boolean as = dialogSelectedPackages.contains(a.packageName), bs = dialogSelectedPackages.contains(b.packageName);
            if (as != bs) return as ? -1 : 1;
            String aLabel = appLabels.get(a.packageName);
            String bLabel = appLabels.get(b.packageName);
            if (aLabel == null) aLabel = String.valueOf(a.loadLabel(getPackageManager()));
            if (bLabel == null) bLabel = String.valueOf(b.loadLabel(getPackageManager()));
            return aLabel.compareToIgnoreCase(bLabel);
        });
        dialogAdapter.notifyDataSetChanged();
        boolean empty = dialogVisibleApps.isEmpty();
        dialogListView.setVisibility(empty ? View.GONE : View.VISIBLE);
        dialogEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) dialogEmptyView.setText(dialogAppsLoaded ? "没有找到匹配的应用" : "尚未加载应用，请点击“刷新”");
    }

    private final class ForcePackageAdapter extends BaseAdapter {
        public int getCount() { return dialogVisibleApps.size(); }
        public ApplicationInfo getItem(int position) { return dialogVisibleApps.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView packageName;
            View accent;
            if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 2) {
                row = (LinearLayout) convertView;
                accent = row.getChildAt(0);
                LinearLayout textBox = (LinearLayout) row.getChildAt(1);
                name = (TextView) textBox.getChildAt(0);
                packageName = (TextView) textBox.getChildAt(1);
            } else {
                row = new LinearLayout(SettingsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                accent = new View(SettingsActivity.this);
                row.addView(accent, new LinearLayout.LayoutParams(dp(4), -1));
                LinearLayout textBox = new LinearLayout(SettingsActivity.this);
                textBox.setOrientation(LinearLayout.VERTICAL);
                textBox.setPadding(dp(14), dp(8), dp(12), dp(8));
                name = text("", 15, COLOR_TEXT_PRIMARY);
                name.setTypeface(name.getTypeface(), 1);
                packageName = text("", 12, COLOR_TEXT_SECONDARY);
                textBox.addView(name, matchWrap(1));
                textBox.addView(packageName, matchWrap(0));
                row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            ApplicationInfo app = getItem(position);
            String label = appLabels.get(app.packageName);
            if (label == null) {
                label = String.valueOf(app.loadLabel(getPackageManager()));
                appLabels.put(app.packageName, label);
            }
            name.setText(label);
            packageName.setText(app.packageName);
            boolean selected = dialogSelectedPackages.contains(app.packageName);
            row.setBackground(roundedBg(selected ? COLOR_PRIMARY_LIGHT : COLOR_SURFACE, 12));
            row.setElevation(0);
            accent.setBackgroundColor(selected ? COLOR_PRIMARY : Color.TRANSPARENT);
            android.view.ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).setMargins(0, dp(3), 0, dp(3));
            }
            return row;
        }
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setMinHeight(dp(48));
        e.setTextSize(16);
        e.setHint(hint);
        e.setTextColor(COLOR_TEXT_PRIMARY);
        e.setHintTextColor(COLOR_TEXT_SECONDARY);
        e.setPadding(dp(16), 0, dp(16), 0);
        e.setBackground(inputBackground());
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean settingInput = e == generalSeparatorInput || e == sideSeparatorInput;
                if (e == generalSeparatorInput) pendingGeneralSeparator = s.toString();
                if (e == sideSeparatorInput) pendingSideSeparator = s.toString();
                if (settingInput && e.hasFocus()) markPending();
            }
            public void afterTextChanged(Editable s) { }
        });
        return e;
    }

    private void loadSettings() {
        settings = FocusRestoreSettings.fromPreferences(preferences);
        pendingHookMode = settings.hookMode;
        pendingManual = settings.limitWidth;
        pendingWidthDp = settings.widthDp;
        pendingDelayMs = settings.marqueeDelayMs;
        pendingCompatRetry = settings.compatRetry;
         pendingMarqueeBounce = settings.marqueeBounce;
        pendingIslandCompat = settings.islandCompat;
        pendingDisableIslandProperty = settings.disableIslandProperty;
        pendingDisableIslandFeatureCache = settings.disableIslandFeatureCache;
        pendingAllowFocusClick = settings.allowFocusClick;
        pendingHideNotificationIcons = settings.hideNotificationIcons;
        pendingShowFocusDivider = settings.showFocusDivider;
        pendingShowIslandIcon = settings.showIslandIcon;
        pendingTintIslandIcon = settings.tintIslandIcon;
        pendingExpandIslandOnClick = settings.expandIslandOnClick;
        pendingGeneralSeparator = settings.islandGeneralSeparator;
        pendingSideSeparator = settings.islandSideSeparator;
        pendingForcePackages = new HashSet<>(settings.islandForcePackages);
    }

    private void captureCurrentInputs() {
        if (generalSeparatorInput != null) pendingGeneralSeparator = generalSeparatorInput.getText().toString();
        if (sideSeparatorInput != null) pendingSideSeparator = sideSeparatorInput.getText().toString();
    }

    private void saveSettings() {
        if (generalSeparatorInput != null) pendingGeneralSeparator = generalSeparatorInput.getText().toString();
        if (sideSeparatorInput != null) pendingSideSeparator = sideSeparatorInput.getText().toString();
        settings = FocusRestoreSettings.withValues(pendingHookMode, pendingManual,
                pendingWidthDp, pendingDelayMs,
                pendingCompatRetry, pendingMarqueeBounce, pendingIslandCompat, pendingDisableIslandProperty,
                pendingDisableIslandFeatureCache, pendingAllowFocusClick,
                pendingHideNotificationIcons, pendingShowFocusDivider,
                pendingShowIslandIcon, pendingTintIslandIcon,
                pendingExpandIslandOnClick,
                pendingGeneralSeparator, pendingSideSeparator, pendingForcePackages);
        pendingForcePackages = new HashSet<>(settings.islandForcePackages);
        pendingExpandIslandOnClick = settings.expandIslandOnClick;
        updateForcePackagesButton();
        long generation;
        synchronized (STORE_WRITE_LOCK) {
            generation = settingsGeneration = nextSettingsGenerationLocked();
        }
        synchronized (saveLock) {
            queuedSettings = settings;
            queuedSettingsGeneration = generation;
            if (saveWorkerRunning) return;
            saveWorkerRunning = true;
        }
        Future<?> future = saveExecutor.submit(this::drainSettingsSaves);
        synchronized (saveLock) {
            saveFuture = future;
        }
    }

    private void drainSettingsSaves() {
        while (true) {
            FocusRestoreSettings snapshot;
            long generation;
            synchronized (saveLock) {
                snapshot = queuedSettings;
                generation = queuedSettingsGeneration;
                queuedSettings = null;
                if (snapshot == null) {
                    saveWorkerRunning = false;
                    return;
                }
            }
            boolean credentialSaved;
            boolean hookSaved;
            boolean stale;
            synchronized (STORE_WRITE_LOCK) {
                long persisted = Math.max(FocusRestoreSettings.generation(preferences),
                        FocusRestoreSettings.generation(hookPreferences));
                stale = persisted > generation;
                if (stale) {
                    credentialSaved = false;
                    hookSaved = false;
                } else {
                    credentialSaved = snapshot.save(preferences, generation);
                    hookSaved = snapshot.save(hookPreferences, generation);
                    if (!credentialSaved) {
                        credentialSaved = snapshot.save(preferences, generation);
                    }
                    if (!hookSaved) hookSaved = snapshot.save(hookPreferences, generation);
                }
            }
            android.util.Log.i(TAG, "settings saved generation=" + generation
                    + " stale=" + stale + " credential=" + credentialSaved
                    + " deviceProtected=" + hookSaved + " " + snapshot.describe());
            boolean latest;
            synchronized (saveLock) {
                latest = generation == settingsGeneration && queuedSettings == null;
            }
            if (latest && !destroyed && !stale) {
                final boolean success = credentialSaved && hookSaved;
                runOnUiThread(() -> {
                    if (!destroyed) showFeedback(success
                            ? "设置已保存，请重启系统界面生效"
                            : "设置保存失败，请检查存储状态后重试");
                });
            }
        }
    }

    private void markPending() {
        saveSettings();
    }

    private void showFeedback(String message) {
        if (feedbackToast != null) feedbackToast.cancel();
        feedbackToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        feedbackToast.show();
    }

    private LinearLayout valueRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 15, COLOR_TEXT_PRIMARY),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = text(value, 15, COLOR_PRIMARY);
        val.setTypeface(val.getTypeface(), 1);
        row.addView(val);
        return row;
    }

    private LinearLayout rangeRow(String left, String right) {
        LinearLayout row = new LinearLayout(this);
        row.addView(text(left, 12, COLOR_TEXT_SECONDARY),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = text(right, 12, COLOR_TEXT_SECONDARY);
        r.setGravity(Gravity.END);
        row.addView(r, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }
    private Drawable roundedBg(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp((int) radiusDp));
        return drawable;
    }

    private Drawable inputBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(COLOR_INPUT_BACKGROUND);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), COLOR_DIVIDER);
        return drawable;
    }

    private void flattenButton(View button) {
        if (button == null) return;
        button.setElevation(0);
        button.setTranslationZ(0f);
        if (Build.VERSION.SDK_INT >= 21) button.setStateListAnimator(null);
    }

    private Button createModeButton(String label, int mode) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(v -> {
            if (pendingHookMode == mode) return;
            pendingHookMode = mode;
            updateModeButtons();
            markPending();
        });
        return button;
    }

    private void updateModeButtons() {
        styleModeButton(os3ModeButton, pendingHookMode == FocusRestoreSettings.HOOK_MODE_OS3);
        styleModeButton(os4ModeButton, pendingHookMode == FocusRestoreSettings.HOOK_MODE_OS4);
        updateModeSpecificControls();
    }

    private void updateModeSpecificControls() {
        boolean os4 = pendingHookMode == FocusRestoreSettings.HOOK_MODE_OS4;
        setModeSpecificSwitchEnabled(hideNotificationIconsSwitch, os4);
        setModeSpecificSwitchEnabled(showFocusDividerSwitch, os4);
        updateExperimentalControls();
    }

    private void setModeSpecificSwitchEnabled(Switch control, boolean enabled) {
        if (control == null) return;
        control.setEnabled(enabled);
        control.setAlpha(enabled ? 1f : 0.42f);
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) return;
        button.setSelected(selected);
        button.setContentDescription(button.getText() + (selected ? "，已选择" : "，未选择"));
        button.setTextColor(selected ? 0xFF041E2F : COLOR_TEXT_SECONDARY);
        button.setBackground(roundedBg(selected ? COLOR_PRIMARY_LIGHT : COLOR_SURFACE_HIGH, 12));
        flattenButton(button);
    }

    private void updateNavButtons(int selected) {
        if (navButtons == null) return;
        for (int i = 0; i < navButtons.length; i++) {
            ImageButton button = navButtons[i];
            boolean active = i == selected;
            button.setSelected(active);
            int icon = i == 0
                    ? (active ? R.drawable.nav_home_on : R.drawable.nav_home_off)
                    : (active ? R.drawable.nav_advanced_on : R.drawable.nav_advanced_off);
            button.setBackground(roundedBg(active ? COLOR_NAV_SELECTED : Color.TRANSPARENT, 22));
            button.setImageResource(icon);
            button.setImageTintList(ColorStateList.valueOf(
                    active ? COLOR_TEXT_PRIMARY : COLOR_TEXT_SECONDARY));
            button.setContentDescription((i == 0 ? "主页" : "高级")
                    + (active ? "，已选择" : "，未选择"));
            flattenButton(button);
        }
    }

    private void applyRootInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 23) {
                int bottomInset = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 29) {
                    bottomInset = Math.max(bottomInset,
                            insets.getSystemGestureInsets().bottom);
                }
                v.setPadding(left, top + insets.getSystemWindowInsetTop(), right,
                        bottom + bottomInset);
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    private void applyTopInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 23) {
                v.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom);
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    private void applyBottomInsets(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 23) {
                v.setPadding(left, top, right, bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    private void styleSwitch(Switch s) { if (Build.VERSION.SDK_INT >= 21) { int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}}; int offThumb = nightMode ? 0xFF8A949A : Color.rgb(189,193,198); int offTrack = nightMode ? 0xFF3A4248 : Color.rgb(218,220,224); s.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, offThumb})); s.setTrackTintList(new ColorStateList(states, new int[]{COLOR_PRIMARY, offTrack})); } }
    private void styleSeekBar(SeekBar s) { if (Build.VERSION.SDK_INT >= 21) { s.setProgressTintList(ColorStateList.valueOf(COLOR_PRIMARY)); s.setThumbTintList(ColorStateList.valueOf(COLOR_PRIMARY)); } }
    private void openExternalLink(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException e) { showFeedback("设备没有可用的浏览器，无法打开链接"); } }
    private void configureSystemBars(Window w) { w.setStatusBarColor(COLOR_BACKGROUND); w.setNavigationBarColor(COLOR_BACKGROUND); if (Build.VERSION.SDK_INT >= 23) { int f = 0; if (!nightMode) { f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; } w.getDecorView().setSystemUiVisibility(f); } }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private LinearLayout.LayoutParams matchWrap(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = margin; return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
