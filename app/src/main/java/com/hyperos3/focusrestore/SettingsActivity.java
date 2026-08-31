package com.hyperos3.focusrestore;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import android.text.Editable;
import android.text.TextWatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SettingsActivity extends Activity {
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

    private SharedPreferences preferences;
    private FocusRestoreSettings settings;
    private LinearLayout pageContainer;
    private TextView pageTitle;
    private TextView statusHint;
    private Button saveButton;
    private LinearLayout bottomNav;
    private Button[] navButtons;
    private int currentPage;

    private static final int COLOR_PRIMARY = Color.rgb(26, 115, 232);
    private static final int COLOR_PRIMARY_LIGHT = Color.rgb(232, 240, 254);
    private static final int COLOR_BACKGROUND = Color.rgb(248, 249, 250);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(32, 33, 36);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(95, 99, 104);
    private static final int COLOR_DIVIDER = Color.rgb(218, 220, 224);
    private static final int COLOR_INPUT_BACKGROUND = Color.rgb(242, 244, 247);

    private Switch manualWidthSwitch;
    private SeekBar widthSeekBar;
    private TextView widthValue;
    private SeekBar delaySeekBar;
    private TextView delayValue;
    private Switch compatRetrySwitch;
    private Switch islandCompatSwitch;
    private Switch allowFocusClickSwitch;
    private EditText generalSeparatorInput;
    private EditText sideSeparatorInput;
    private boolean pendingManual, pendingCompatRetry, pendingIslandCompat, pendingAllowFocusClick;
    private int pendingWidthDp, pendingDelayMs;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLightSystemBars(getWindow());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setContentView(createContent());
        loadSettings();
        showPage(0);
    }

    private View createContent() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(COLOR_BACKGROUND);
        applyRootInsets(outer);
        View topBar = createTopBar();
        outer.addView(topBar, new LinearLayout.LayoutParams(-1, dp(64)));
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1f));
        bottomNav = (LinearLayout) createBottomNavigation();
        outer.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(64)));
        return outer;
    }

    private View createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), 0);
        bar.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(2));
        ImageView icon = new ImageView(this);
        Drawable appIcon = getApplicationInfo().loadIcon(getPackageManager());
        icon.setImageDrawable(appIcon);
        bar.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView brand = text("焦点通知", 18, Color.rgb(28, 28, 30));
        brand.setTypeface(brand.getTypeface(), 1);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = dp(10);
        bar.addView(brand, brandParams);
        pageTitle = text("设置", 16, Color.rgb(60, 64, 67));
        pageTitle.setGravity(Gravity.CENTER);
        bar.addView(pageTitle, new LinearLayout.LayoutParams(0, -1, 1f));
        saveButton = new Button(this);
        saveButton.setText("保存");
        saveButton.setTextSize(14);
        saveButton.setTextColor(Color.WHITE);
        saveButton.setBackground(roundedBg(COLOR_PRIMARY, 10));
        saveButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_save_floppy, 0, 0, 0);
        saveButton.setCompoundDrawablePadding(dp(4));
        saveButton.setAllCaps(false);
        saveButton.setMinWidth(dp(68));
        saveButton.setPadding(dp(8), 0, dp(8), 0);
        saveButton.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(76), dp(48));
        saveParams.gravity = Gravity.CENTER_VERTICAL;
        bar.addView(saveButton, saveParams);
        return bar;
    }

    private View createBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(COLOR_BACKGROUND);
        nav.setPadding(dp(8), dp(4), dp(8), dp(4));
        String[] names = {"设置", "自定义", "关于"};
        navButtons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            Button item = new Button(this);
            item.setText(names[i]);
            item.setTextSize(14);
            item.setAllCaps(false);
            item.setMinHeight(0);
            item.setPadding(dp(8), 0, dp(8), 0);
            item.setOnClickListener(v -> showPage(page));
            navButtons[i] = item;
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
            itemParams.gravity = Gravity.CENTER_VERTICAL;
            nav.addView(item, itemParams);
        }
        return nav;
    }

    private void showPage(int page) {
        captureCurrentInputs();
        currentPage = page;
        pageContainer.removeAllViews();
        pageTitle.setText(page == 0 ? "设置" : page == 1 ? "自定义" : "关于");
        saveButton.setVisibility(page == 2 ? View.GONE : View.VISIBLE);
        updateNavButtons(page);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        if (page == 0) buildSettingsPage(content);
        else if (page == 1) buildCustomPage(content);
        else buildAboutPage(content);
        scroll.addView(content);
        pageContainer.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(10));
        panel.setBackground(roundedBg(Color.WHITE, 10));
        return panel;
    }

    private void buildSettingsPage(LinearLayout root) {
        TextView intro = text("焦点通知显示与兼容设置", 14, COLOR_TEXT_SECONDARY);
        root.addView(intro, matchWrap(dp(10)));
        LinearLayout widthPanel = panel();
        manualWidthSwitch = new Switch(this);
        manualWidthSwitch.setText("限制原生焦点歌词宽度");
        styleSwitch(manualWidthSwitch);
        widthPanel.addView(manualWidthSwitch, matchWrap(dp(4)));
        LinearLayout widthRow = valueRow("最大文字宽度", "160 dp");
        widthValue = (TextView) widthRow.getChildAt(1);
        widthPanel.addView(widthRow, matchWrap(0));
        widthSeekBar = new SeekBar(this);
        styleSeekBar(widthSeekBar);
        widthSeekBar.setMax(MAX_WIDTH_DP - MIN_WIDTH_DP);
        widthPanel.addView(widthSeekBar, matchWrap(dp(4)));
        widthPanel.addView(rangeRow("80 dp", "400 dp"), matchWrap(0));
        root.addView(widthPanel, matchWrap(dp(12)));
        LinearLayout delayPanel = panel();
        LinearLayout delayRow = valueRow("滚动启动延迟", "0.2 秒");
        delayValue = (TextView) delayRow.getChildAt(1);
        delayPanel.addView(delayRow, matchWrap(0));
        delaySeekBar = new SeekBar(this);
        styleSeekBar(delaySeekBar);
        delaySeekBar.setMax(50);
        delayPanel.addView(delaySeekBar, matchWrap(dp(2)));
        delayPanel.addView(rangeRow("0 秒", "5 秒"), matchWrap(0));
        root.addView(delayPanel, matchWrap(dp(12)));
        LinearLayout compatPanel = panel();
        compatRetrySwitch = new Switch(this);
        compatRetrySwitch.setText("启用兼容重试模式");
        styleSwitch(compatRetrySwitch);
        compatPanel.addView(compatRetrySwitch, matchWrap(dp(4)));
        islandCompatSwitch = new Switch(this);
        islandCompatSwitch.setText("转换超级岛内容为焦点通知");
        styleSwitch(islandCompatSwitch);
        compatPanel.addView(islandCompatSwitch, matchWrap(dp(4)));
        allowFocusClickSwitch = new Switch(this);
        allowFocusClickSwitch.setText("允许焦点通知点击");
        styleSwitch(allowFocusClickSwitch);
        compatPanel.addView(allowFocusClickSwitch, matchWrap(0));
        root.addView(compatPanel, matchWrap(dp(12)));
        TextView widthNotice = text("• 宽度限制：默认开启，最大文字宽度为 160dp；关闭后恢复系统原生宽度测量。", 13, COLOR_TEXT_SECONDARY);
        widthNotice.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(widthNotice, matchWrap(0));
        TextView delayNotice = text("• 滚动延迟：默认 0.2 秒，用于避免歌词布局刷新后立即启动造成显示抖动。", 13, COLOR_TEXT_SECONDARY);
        delayNotice.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(delayNotice, matchWrap(0));
        TextView retryNotice = text("• 兼容重试：默认关闭；开启后歌词最多尝试启动两次，适合偶尔不滚动的 ROM，但可能产生轻微抖动。", 13, COLOR_TEXT_SECONDARY);
        retryNotice.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(retryNotice, matchWrap(0));
        TextView clickWarning = text("• 点击风险：HyperOS 3 上基本所有焦点通知都不支持点击。点击可能导致焦点通知消失或不可见，相关系统逻辑也可能无法正常处理。默认关闭点击；只有确认接受风险后才建议开启。", 13, COLOR_TEXT_SECONDARY);
        clickWarning.setPadding(dp(12), dp(4), dp(12), dp(8));
        root.addView(clickWarning, matchWrap(dp(8)));
        TextView islandNotice = text("• 超级岛屏蔽：模块始终尝试关闭 HyperOS 超级岛显示路径，避免其占用状态栏区域。\n• 内容转换：上方开关只控制是否读取协议内容并转换为 Focus，不控制超级岛屏蔽开关。\n• 灵动舞台：本模块不负责隐藏 MIUIStrongToast（灵动舞台）；如有需要，请使用其他专用工具。修改后请点击顶部保存，并重启 SystemUI 或设备生效。", 13, COLOR_TEXT_SECONDARY);
        islandNotice.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(islandNotice, matchWrap(dp(8)));
        statusHint = text("修改后点击顶部保存，再重启 SystemUI 或设备生效。", 14, COLOR_TEXT_SECONDARY);
        root.addView(statusHint, matchWrap(dp(8)));
        manualWidthSwitch.setChecked(pendingManual);
        widthSeekBar.setProgress(pendingWidthDp - MIN_WIDTH_DP);
        widthValue.setText(pendingWidthDp + " dp");
        delaySeekBar.setProgress(pendingDelayMs / 100);
        delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", pendingDelayMs / 1000f));
        compatRetrySwitch.setChecked(pendingCompatRetry);
        islandCompatSwitch.setChecked(pendingIslandCompat);
        allowFocusClickSwitch.setChecked(pendingAllowFocusClick);
        installSettingsListeners();
    }

    private void buildCustomPage(LinearLayout root) {
        root.addView(text("文本拼接和超级岛双侧内容设置", 14, COLOR_TEXT_SECONDARY), matchWrap(dp(10)));
        LinearLayout customPanel = panel();
        customPanel.addView(text("超级岛内容连接符", 15, COLOR_TEXT_PRIMARY), matchWrap(dp(2)));
        generalSeparatorInput = input("默认：·，允许留空");
        generalSeparatorInput.setText(pendingGeneralSeparator);
        customPanel.addView(generalSeparatorInput, matchWrap(dp(12)));
        customPanel.addView(text("左右超级岛内容连接符", 15, COLOR_TEXT_PRIMARY), matchWrap(dp(2)));
        sideSeparatorInput = input("默认：·，允许留空");
        sideSeparatorInput.setText(pendingSideSeparator);
        customPanel.addView(sideSeparatorInput, matchWrap(0));
        root.addView(customPanel, matchWrap(dp(12)));
        LinearLayout whitelistPanel = panel();
        whitelistPanel.addView(text("超级岛强制转换白名单", 15, COLOR_TEXT_PRIMARY), matchWrap(dp(2)));
        forcePackagesButton = new Button(this);
        forcePackagesButton.setText(forcePackagesLabel());
        forcePackagesButton.setAllCaps(false);
        forcePackagesButton.setTextSize(14);
        forcePackagesButton.setOnClickListener(v -> showForcePackagesDialog());
        whitelistPanel.addView(forcePackagesButton, matchWrap(0));
        root.addView(whitelistPanel, matchWrap(dp(12)));
        TextView customHint = text("通用连接符用于同一元素的标题、时间、说明和进度拼接；左右连接符只用于超级岛左侧与右侧之间。两项都允许留空。", 13, COLOR_TEXT_SECONDARY);
        customHint.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(customHint, matchWrap(dp(8)));
        updateForcePackagesButton();
        statusHint = text("修改后点击顶部保存，再重启 SystemUI 或设备生效。", 14, COLOR_TEXT_SECONDARY);
        root.addView(statusHint, matchWrap(dp(8)));
    }

    private void buildAboutPage(LinearLayout root) {
        TextView about = text("焦点通知\n\n用于 HyperOS 3 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的 Focus（焦点通知）状态栏显示路径。\n\n本模块由 AI 辅助反编译分析与编写，代码通过 LSPosed Hook 介入系统界面，存在 ROM 版本差异、系统崩溃、状态栏显示异常、功能失效、数据丢失或其他不可控风险。使用前请自行备份，并自行承担使用风险。模块不保证适用于所有设备、系统版本或第三方通知。\n\n\n作者：ImKani\n酷安主页：https://www.coolapk.com/u/1205658\nGitHub：https://github.com/ImKani/HyperOS3FocusRestore\n\n许可证：GNU General Public License v3.0 only（GPL-3.0-only）", 15, COLOR_TEXT_PRIMARY);
        root.addView(about, matchWrap(dp(18)));
        Button github = new Button(this);
        github.setText("打开 GitHub");
        github.setAllCaps(false);
        github.setTextColor(Color.WHITE);
        github.setBackground(roundedBg(COLOR_PRIMARY, 10));
        github.setMinHeight(dp(48));
        github.setPadding(dp(16), 0, dp(16), 0);
        github.setOnClickListener(v -> openExternalLink("https://github.com/ImKani/HyperOS3FocusRestore"));
        root.addView(github, matchWrap(dp(12)));
    }

    private void installSettingsListeners() {
        manualWidthSwitch.setOnCheckedChangeListener((b, checked) -> { pendingManual = checked; markPending(); });
        widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { int w = MIN_WIDTH_DP + p; widthValue.setText(w + " dp"); if (user) { pendingWidthDp = w; markPending(); } }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { int d = p * 100; delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", d / 1000f)); if (user) { pendingDelayMs = d; markPending(); } }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        compatRetrySwitch.setOnCheckedChangeListener((b, c) -> { pendingCompatRetry = c; markPending(); });
        islandCompatSwitch.setOnCheckedChangeListener((b, c) -> {
            pendingIslandCompat = c;
            updateForcePackagesButton();
            markPending();
        });
        allowFocusClickSwitch.setOnCheckedChangeListener((b, c) -> { pendingAllowFocusClick = c; markPending(); });
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
        forcePackagesButton.setText(forcePackagesLabel());
        forcePackagesButton.setTextColor(enabled ? COLOR_PRIMARY : Color.rgb(170, 174, 180));
        forcePackagesButton.setBackground(roundedBg(enabled ? COLOR_PRIMARY_LIGHT : Color.rgb(232, 234, 237), 10));
    }

    private void showForcePackagesDialog() {
        if (!pendingIslandCompat) return;
        dialogAllApps = readCachedApps();
        dialogVisibleApps.clear();
        dialogSelectedPackages = new HashSet<>(pendingForcePackages);
        dialogAppsLoaded = !dialogAllApps.isEmpty();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(createWhitelistDialogView(dialog));
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        filterDialogApps();
        window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private View createWhitelistDialogView(final Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBg(Color.WHITE, 10));
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView title = text("强制转换超级岛应用", 18, COLOR_TEXT_PRIMARY);
        title.setTypeface(title.getTypeface(), 1);
        root.addView(title, matchWrap(dp(8)));

        dialogSearchInput = input("刷新后搜索应用名称或包名");
        dialogSearchInput.setTextSize(14);
        dialogSearchInput.setMinHeight(dp(48));
        dialogSearchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filterDialogApps(); }
            public void afterTextChanged(Editable s) { }
        });
        root.addView(dialogSearchInput, matchWrap(dp(6)));

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
        refresh.setBackground(roundedBg(COLOR_PRIMARY_LIGHT, 10));
        refresh.setMinHeight(dp(40));
        refresh.setOnClickListener(v -> loadDialogApps());
        options.addView(refresh, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(options, matchWrap(dp(4)));

        dialogListView = new ListView(this);
        dialogListView.setDivider(null);
        dialogListView.setDividerHeight(0);
        dialogAdapter = new ForcePackageAdapter();
        dialogListView.setAdapter(dialogAdapter);
        dialogListView.setVisibility(View.GONE);
        dialogListView.setOnItemClickListener((parent, view, position, id) -> {
            ApplicationInfo app = dialogVisibleApps.get(position);
            if (!dialogSelectedPackages.add(app.packageName)) dialogSelectedPackages.remove(app.packageName);
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
        cancel.setBackgroundColor(Color.TRANSPARENT); cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(dp(76), dp(48)));
        Button done = new Button(this);
        done.setText("完成"); done.setAllCaps(false); done.setTextColor(Color.WHITE);
        done.setBackground(roundedBg(COLOR_PRIMARY, 12));
        done.setOnClickListener(v -> { pendingForcePackages = new HashSet<>(dialogSelectedPackages); updateForcePackagesButton(); markPending(); dialog.dismiss(); });
        buttons.addView(done, new LinearLayout.LayoutParams(dp(76), dp(48)));
        root.addView(buttons, matchWrap(0));
        return root;
    }

    private void loadDialogApps() {
        List<ApplicationInfo> refreshed = new ArrayList<>(getPackageManager().getInstalledApplications(0));
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(FocusRestoreSettings.KEY_ISLAND_APP_CACHE, encodeAppCache(refreshed))
                .apply();
        dialogAllApps = refreshed;
        dialogAppsLoaded = true;
        filterDialogApps();
    }

    private List<ApplicationInfo> readCachedApps() {
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
                    .replace(APP_CACHE_SEPARATOR, " ").replace("\\n", " ");
            if (result.length() > 0) result.append('\n');
            result.append(app.packageName).append(APP_CACHE_SEPARATOR)
                    .append(label).append(APP_CACHE_SEPARATOR).append(app.flags);
        }
        return result.toString();
    }

    private void filterDialogApps() {
        if (!dialogAppsLoaded || dialogAdapter == null) return;
        String query = dialogSearchInput == null ? "" : dialogSearchInput.getText().toString().toLowerCase(Locale.getDefault()).trim();
        boolean includeSystem = dialogShowSystemSwitch != null && dialogShowSystemSwitch.isChecked();
        dialogVisibleApps = new ArrayList<>();
        for (ApplicationInfo app : dialogAllApps) {
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (!includeSystem && system && !dialogSelectedPackages.contains(app.packageName)) continue;
            String label = String.valueOf(app.loadLabel(getPackageManager()));
            if (query.length() > 0 && !label.toLowerCase(Locale.getDefault()).contains(query)
                    && !app.packageName.toLowerCase(Locale.getDefault()).contains(query)) continue;
            dialogVisibleApps.add(app);
        }
        Collections.sort(dialogVisibleApps, (a, b) -> {
            boolean as = dialogSelectedPackages.contains(a.packageName), bs = dialogSelectedPackages.contains(b.packageName);
            if (as != bs) return as ? -1 : 1;
            return String.valueOf(a.loadLabel(getPackageManager())).compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
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
            name.setText(String.valueOf(app.loadLabel(getPackageManager())));
            packageName.setText(app.packageName);
            boolean selected = dialogSelectedPackages.contains(app.packageName);
            row.setBackground(roundedBg(selected ? Color.rgb(210, 229, 255) : Color.WHITE, 10));
            accent.setBackgroundColor(selected ? COLOR_PRIMARY : Color.TRANSPARENT);
            return row;
        }
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setMinHeight(dp(56));
        e.setTextSize(15);
        e.setHint(hint);
        e.setTextColor(COLOR_TEXT_PRIMARY);
        e.setHintTextColor(COLOR_TEXT_SECONDARY);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(roundedBg(COLOR_INPUT_BACKGROUND, 10));
        e.setOnFocusChangeListener((v, focus) -> { if (!focus) markPending(); });
        return e;
    }

    private void loadSettings() {
        settings = FocusRestoreSettings.fromPreferences(preferences);
        pendingManual = settings.limitWidth;
        pendingWidthDp = settings.widthDp;
        pendingDelayMs = settings.marqueeDelayMs;
        pendingCompatRetry = settings.compatRetry;
        pendingIslandCompat = settings.islandCompat;
        pendingAllowFocusClick = settings.allowFocusClick;
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
        settings = FocusRestoreSettings.withValues(pendingManual, pendingWidthDp, pendingDelayMs,
                pendingCompatRetry, pendingIslandCompat, pendingAllowFocusClick,
                pendingGeneralSeparator, pendingSideSeparator, pendingForcePackages);
        settings.save(preferences);
        if (statusHint != null) statusHint.setText("设置已保存。请重启 SystemUI 或设备后生效。");
    }

    private void markPending() { if (statusHint != null) statusHint.setText("有未保存的修改，请点击顶部“保存”。"); }
    private LinearLayout valueRow(String label, String value) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(text(label, 15, Color.rgb(60,64,67)), new LinearLayout.LayoutParams(0, -2, 1f)); TextView val = text(value, 15, Color.rgb(26,115,232)); val.setTypeface(val.getTypeface(), 1); row.addView(val); return row; }
    private LinearLayout rangeRow(String left, String right) { LinearLayout row = new LinearLayout(this); row.addView(text(left, 12, Color.GRAY), new LinearLayout.LayoutParams(0, -2, 1f)); TextView r = text(right, 12, Color.GRAY); r.setGravity(Gravity.END); row.addView(r, new LinearLayout.LayoutParams(0, -2, 1f)); return row; }
    private Drawable roundedBg(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp((int) radiusDp));
        return drawable;
    }

    private void updateNavButtons(int selected) {
        if (navButtons == null) return;
        for (int i = 0; i < navButtons.length; i++) {
            Button button = navButtons[i];
            boolean active = i == selected;
            button.setBackground(active ? roundedBg(COLOR_PRIMARY_LIGHT, 10) : roundedBg(Color.TRANSPARENT, 14));
            button.setTextColor(active ? COLOR_PRIMARY : COLOR_TEXT_SECONDARY);
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

    private void styleSwitch(Switch s) { if (Build.VERSION.SDK_INT >= 21) { int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}}; s.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, Color.rgb(189,193,198)})); s.setTrackTintList(new ColorStateList(states, new int[]{COLOR_PRIMARY, Color.rgb(218,220,224)})); } }
    private void styleSeekBar(SeekBar s) { if (Build.VERSION.SDK_INT >= 21) { s.setProgressTintList(ColorStateList.valueOf(COLOR_PRIMARY)); s.setThumbTintList(ColorStateList.valueOf(COLOR_PRIMARY)); } }
    private void openExternalLink(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException e) { if (statusHint != null) statusHint.setText("设备没有可用的浏览器，无法打开链接。"); } }
    private void configureLightSystemBars(Window w) { w.setStatusBarColor(Color.rgb(248,249,250)); w.setNavigationBarColor(Color.rgb(248,249,250)); if (Build.VERSION.SDK_INT >= 23) { int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; w.getDecorView().setSystemUiVisibility(f); } }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private LinearLayout.LayoutParams matchWrap(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = margin; return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
