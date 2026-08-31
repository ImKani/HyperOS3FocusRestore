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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    static final String PREFS_NAME = "com.hyperos3.focusrestore_preferences";
    static final String KEY_LIMIT_WIDTH = "limit_text_width";
    static final String KEY_WIDTH_DP = "text_width_dp";
    static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    static final String KEY_COMPAT_RETRY = "compat_retry";
    static final String KEY_ISLAND_COMPAT = "island_compat";
    static final String KEY_ISLAND_SEPARATOR = "island_separator";
    static final String KEY_ALLOW_FOCUS_CLICK = "allow_focus_click";
    static final String KEY_ISLAND_GENERAL_SEPARATOR = "island_general_separator";
    static final String KEY_ISLAND_SIDE_SEPARATOR = "island_side_separator";
    static final String DEFAULT_ISLAND_SEPARATOR = "·";
    static final int DEFAULT_WIDTH_DP = 160;
    static final int MIN_WIDTH_DP = 80;
    static final int MAX_WIDTH_DP = 400;
    static final int DEFAULT_MARQUEE_DELAY_MS = 200;

    private SharedPreferences preferences;
    private LinearLayout pageContainer;
    private TextView pageTitle;
    private TextView statusHint;
    private Button saveButton;
    private int currentPage;

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
        outer.setBackgroundColor(Color.rgb(248, 249, 250));
        outer.addView(createTopBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1f));
        outer.addView(createBottomNavigation(), new LinearLayout.LayoutParams(-1, dp(64)));
        return outer;
    }

    private View createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), 0);
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
        saveButton.setTextColor(Color.rgb(26, 115, 232));
        saveButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_save_floppy, 0, 0, 0);
        saveButton.setCompoundDrawablePadding(dp(4));
        saveButton.setAllCaps(false);
        saveButton.setMinWidth(dp(68));
        saveButton.setOnClickListener(v -> saveSettings());
        bar.addView(saveButton, new LinearLayout.LayoutParams(dp(76), -1));
        return bar;
    }

    private View createBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(4), dp(8), dp(4));
        String[] names = {"设置", "自定义", "关于"};
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            Button item = new Button(this);
            item.setText(names[i]);
            item.setTextSize(14);
            item.setAllCaps(false);
            item.setMinHeight(0);
            item.setOnClickListener(v -> showPage(page));
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

    private void buildSettingsPage(LinearLayout root) {
        manualWidthSwitch = new Switch(this);
        manualWidthSwitch.setText("限制原生焦点歌词宽度");
        styleSwitch(manualWidthSwitch);
        root.addView(manualWidthSwitch, matchWrap(dp(10)));
        LinearLayout widthRow = valueRow("最大文字宽度", "160 dp");
        widthValue = (TextView) widthRow.getChildAt(1);
        root.addView(widthRow, matchWrap(0));
        widthSeekBar = new SeekBar(this);
        widthSeekBar.setMax(MAX_WIDTH_DP - MIN_WIDTH_DP);
        root.addView(widthSeekBar, matchWrap(dp(4)));
        root.addView(rangeRow("80 dp", "400 dp"), matchWrap(dp(16)));
        LinearLayout delayRow = valueRow("滚动启动延迟", "0.2 秒");
        delayValue = (TextView) delayRow.getChildAt(1);
        root.addView(delayRow, matchWrap(0));
        delaySeekBar = new SeekBar(this);
        delaySeekBar.setMax(50);
        root.addView(delaySeekBar, matchWrap(dp(2)));
        root.addView(rangeRow("0 秒", "5 秒"), matchWrap(dp(12)));
        compatRetrySwitch = new Switch(this);
        compatRetrySwitch.setText("启用兼容重试模式");
        styleSwitch(compatRetrySwitch);
        root.addView(compatRetrySwitch, matchWrap(dp(8)));
        islandCompatSwitch = new Switch(this);
        islandCompatSwitch.setText("超级岛内容转焦点通知");
        styleSwitch(islandCompatSwitch);
        root.addView(islandCompatSwitch, matchWrap(dp(8)));
        allowFocusClickSwitch = new Switch(this);
        allowFocusClickSwitch.setText("允许焦点通知点击");
        styleSwitch(allowFocusClickSwitch);
        root.addView(allowFocusClickSwitch, matchWrap(dp(8)));
        statusHint = text("修改后点击顶部保存，再重启 SystemUI 或设备生效。", 14, Color.rgb(95, 99, 104));
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
        root.addView(text("超级岛内容连接符", 15, Color.rgb(60, 64, 67)), matchWrap(dp(2)));
        generalSeparatorInput = input("默认：·，允许留空");
        generalSeparatorInput.setText(pendingGeneralSeparator);
        root.addView(generalSeparatorInput, matchWrap(dp(18)));
        root.addView(text("左右超级岛内容连接符", 15, Color.rgb(60, 64, 67)), matchWrap(dp(2)));
        sideSeparatorInput = input("默认：·，允许留空");
        sideSeparatorInput.setText(pendingSideSeparator);
        root.addView(sideSeparatorInput, matchWrap(dp(18)));
        statusHint = text("修改后点击顶部保存，再重启 SystemUI 或设备生效。", 14, Color.rgb(95, 99, 104));
        root.addView(statusHint, matchWrap(dp(8)));
    }

    private void buildAboutPage(LinearLayout root) {
        TextView about = text("焦点通知\n\n作者：ImKani\n酷安主页：https://www.coolapk.com/u/1205658\nGitHub：https://github.com/ImKani/HyperOS3FocusRestore", 15, Color.rgb(60, 64, 67));
        root.addView(about, matchWrap(dp(18)));
        Button github = new Button(this);
        github.setText("打开 GitHub");
        github.setAllCaps(false);
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

    private EditText input(String hint) { EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(15); e.setHint(hint); e.setOnFocusChangeListener((v, focus) -> { if (!focus) markPending(); }); return e; }

    private void loadSettings() {
        pendingManual = preferences.getBoolean(KEY_LIMIT_WIDTH, true);
        pendingWidthDp = clamp(preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP), MIN_WIDTH_DP, MAX_WIDTH_DP);
        pendingDelayMs = clamp(preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS), 0, 5000);
        pendingCompatRetry = preferences.getBoolean(KEY_COMPAT_RETRY, false);
        pendingIslandCompat = preferences.getBoolean(KEY_ISLAND_COMPAT, false);
        pendingAllowFocusClick = preferences.getBoolean(KEY_ALLOW_FOCUS_CLICK, false);
        pendingGeneralSeparator = preferences.getString(KEY_ISLAND_GENERAL_SEPARATOR, preferences.getString(KEY_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR));
        pendingSideSeparator = preferences.getString(KEY_ISLAND_SIDE_SEPARATOR, preferences.getString(KEY_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR));
    }

    private void captureCurrentInputs() {
        if (generalSeparatorInput != null) pendingGeneralSeparator = generalSeparatorInput.getText().toString();
        if (sideSeparatorInput != null) pendingSideSeparator = sideSeparatorInput.getText().toString();
    }

    private void saveSettings() {
        if (generalSeparatorInput != null) pendingGeneralSeparator = generalSeparatorInput.getText().toString();
        if (sideSeparatorInput != null) pendingSideSeparator = sideSeparatorInput.getText().toString();
        preferences.edit().putBoolean(KEY_LIMIT_WIDTH, pendingManual).putInt(KEY_WIDTH_DP, pendingWidthDp)
                .putInt(KEY_MARQUEE_DELAY_MS, pendingDelayMs).putBoolean(KEY_COMPAT_RETRY, pendingCompatRetry)
                .putBoolean(KEY_ISLAND_COMPAT, pendingIslandCompat).putBoolean(KEY_ALLOW_FOCUS_CLICK, pendingAllowFocusClick)
                .putString(KEY_ISLAND_GENERAL_SEPARATOR, pendingGeneralSeparator).putString(KEY_ISLAND_SIDE_SEPARATOR, pendingSideSeparator)
                .putString(KEY_ISLAND_SEPARATOR, pendingGeneralSeparator).commit();
        if (statusHint != null) statusHint.setText("设置已保存。请重启 SystemUI 或设备后生效。");
    }

    private void markPending() { if (statusHint != null) statusHint.setText("有未保存的修改，请点击顶部“保存”。"); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private LinearLayout valueRow(String label, String value) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(text(label, 15, Color.rgb(60,64,67)), new LinearLayout.LayoutParams(0, -2, 1f)); TextView val = text(value, 15, Color.rgb(26,115,232)); val.setTypeface(val.getTypeface(), 1); row.addView(val); return row; }
    private LinearLayout rangeRow(String left, String right) { LinearLayout row = new LinearLayout(this); row.addView(text(left, 12, Color.GRAY), new LinearLayout.LayoutParams(0, -2, 1f)); TextView r = text(right, 12, Color.GRAY); r.setGravity(Gravity.END); row.addView(r, new LinearLayout.LayoutParams(0, -2, 1f)); return row; }
    private void styleSwitch(Switch s) { if (Build.VERSION.SDK_INT >= 21) { int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}}; s.setThumbTintList(new ColorStateList(states, new int[]{Color.rgb(26,115,232), Color.rgb(189,193,198)})); s.setTrackTintList(new ColorStateList(states, new int[]{Color.rgb(144,184,244), Color.rgb(218,220,224)})); } }
    private void openExternalLink(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException e) { if (statusHint != null) statusHint.setText("设备没有可用的浏览器，无法打开链接。"); } }
    private void configureLightSystemBars(Window w) { w.setStatusBarColor(Color.rgb(248,249,250)); w.setNavigationBarColor(Color.rgb(248,249,250)); if (Build.VERSION.SDK_INT >= 23) { int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; w.getDecorView().setSystemUiVisibility(f); } }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private LinearLayout.LayoutParams matchWrap(int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = margin; return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
