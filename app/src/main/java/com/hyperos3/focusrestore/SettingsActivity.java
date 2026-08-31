package com.hyperos3.focusrestore;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

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
        saveButton.setBackground(roundedBg(COLOR_PRIMARY, 14));
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
            nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
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
        panel.setBackground(roundedBg(Color.WHITE, 12));
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
        islandCompatSwitch.setText("超级岛内容转焦点通知");
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
        TextView islandNotice = text("超级岛转换只处理通知实际提供的协议内容，不负责隐藏系统灵动舞台；需要隐藏时请使用其他工具。修改后请点击顶部保存，并重启 SystemUI 或设备生效。", 13, COLOR_TEXT_SECONDARY);
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
        TextView customHint = text("通用连接符用于同一元素的标题、时间、说明和进度拼接；左右连接符只用于超级岛左侧与右侧之间。两项都允许留空。修改后请点击顶部保存，并重启 SystemUI 或设备生效。", 13, COLOR_TEXT_SECONDARY);
        customHint.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(customHint, matchWrap(dp(8)));
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
        github.setBackground(roundedBg(COLOR_PRIMARY, 14));
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
        islandCompatSwitch.setOnCheckedChangeListener((b, c) -> { pendingIslandCompat = c; markPending(); });
        allowFocusClickSwitch.setOnCheckedChangeListener((b, c) -> { pendingAllowFocusClick = c; markPending(); });
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
                pendingGeneralSeparator, pendingSideSeparator);
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
            button.setBackground(active ? roundedBg(COLOR_PRIMARY_LIGHT, 14) : roundedBg(Color.TRANSPARENT, 14));
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
