package com.hyperos3.focusrestore;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.os.Build;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    // LSPosed XSharedPreferences reads the package default preferences file.
    static final String PREFS_NAME = "com.hyperos3.focusrestore_preferences";
    static final String KEY_LIMIT_WIDTH = "limit_text_width";
    static final String KEY_WIDTH_DP = "text_width_dp";
    static final int DEFAULT_WIDTH_DP = 160;
    static final int MIN_WIDTH_DP = 80;
    static final int MAX_WIDTH_DP = 400;
    static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    static final String KEY_COMPAT_RETRY = "compat_retry";
    static final String KEY_ISLAND_COMPAT = "island_compat";
    static final int DEFAULT_MARQUEE_DELAY_MS = 200;

    private SharedPreferences preferences;
    private TextView restartHint;
    private Switch manualWidthSwitch;
    private SeekBar widthSeekBar;
    private TextView widthValue;
    private SeekBar delaySeekBar;
    private TextView delayValue;
    private Switch compatRetrySwitch;
    private Switch islandCompatSwitch;
    private Button saveButton;
    private int pendingDelayMs;
    private boolean pendingManual;
    private int pendingWidthDp;
    private boolean pendingCompatRetry;
    private boolean pendingIslandCompat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLightSystemBars(getWindow());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setTitle("HyperOS3FocusRestore");
        setContentView(createContent());
        loadSettings();
    }

    private View createContent() {
        int padding = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(16), padding, padding);
        root.setBackgroundColor(Color.rgb(248, 249, 250));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding, insets.getSystemWindowInsetTop() + dp(16),
                    padding, insets.getSystemWindowInsetBottom() + padding);
            return insets;
        });

        TextView title = text("焦点通知设置", 24, Color.rgb(28, 28, 30));
        title.setTypeface(title.getTypeface(), 1);
        root.addView(title, matchWrap(dp(8)));

        TextView subtitle = text("原生焦点歌词可限制文字宽度；超级岛和 RemoteViews 保留系统原生布局", 14, Color.rgb(95, 99, 104));
        root.addView(subtitle, matchWrap(dp(20)));

        manualWidthSwitch = new Switch(this);
        manualWidthSwitch.setText("限制原生焦点歌词宽度");
        manualWidthSwitch.setTextSize(16);
        root.addView(manualWidthSwitch, matchWrap(dp(10)));

        LinearLayout widthRow = new LinearLayout(this);
        widthRow.setGravity(Gravity.CENTER_VERTICAL);
        widthRow.addView(text("最大文字宽度", 15, Color.rgb(60, 64, 67)), new LinearLayout.LayoutParams(0, -2, 1f));
        widthValue = text("160 dp", 15, Color.rgb(26, 115, 232));
        widthValue.setTypeface(widthValue.getTypeface(), 1);
        widthRow.addView(widthValue);
        root.addView(widthRow, matchWrap(dp(2)));
        widthSeekBar = new SeekBar(this);
        widthSeekBar.setMax(MAX_WIDTH_DP - MIN_WIDTH_DP);
        root.addView(widthSeekBar, matchWrap(dp(4)));
        LinearLayout widthRange = new LinearLayout(this);
        widthRange.addView(text("80 dp", 12, Color.GRAY), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView maxWidth = text("400 dp", 12, Color.GRAY);
        maxWidth.setGravity(Gravity.END);
        widthRange.addView(maxWidth, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(widthRange, matchWrap(dp(18)));

        LinearLayout delayValueRow = new LinearLayout(this);
        delayValueRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView delayLabel = text("滚动启动延迟", 15, Color.rgb(60, 64, 67));
        delayLabel.setTypeface(delayLabel.getTypeface(), 1);
        delayValueRow.addView(delayLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        delayValue = text("0.2 秒", 15, Color.rgb(26, 115, 232));
        delayValue.setTypeface(delayValue.getTypeface(), 1);
        delayValueRow.addView(delayValue);
        root.addView(delayValueRow, matchWrap(dp(2)));
        delaySeekBar = new SeekBar(this);
        delaySeekBar.setMax(50);
        delaySeekBar.setProgress(2);
        root.addView(delaySeekBar, matchWrap(dp(2)));
        LinearLayout delayRange = new LinearLayout(this);
        TextView minDelay = text("0 秒", 12, Color.rgb(117, 117, 117));
        TextView maxDelay = text("5 秒", 12, Color.rgb(117, 117, 117));
        delayRange.addView(minDelay, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        maxDelay.setGravity(Gravity.END);
        delayRange.addView(maxDelay, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(delayRange, matchWrap(dp(12)));

        compatRetrySwitch = new Switch(this);
        compatRetrySwitch.setText("启用兼容重试模式");
        compatRetrySwitch.setTextSize(16);
        root.addView(compatRetrySwitch, matchWrap(dp(2)));
        TextView retryHint = text("默认关闭：每条歌词只启动 1 次，画面更稳定。开启后会最多启动 2 次，可能出现轻微抖动，仅在歌词偶尔不滚动时使用。", 13, Color.rgb(95, 99, 104));
        retryHint.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(retryHint, matchWrap(dp(8)));

        islandCompatSwitch = new Switch(this);
        islandCompatSwitch.setText("超级岛内容转焦点通知");
        islandCompatSwitch.setTextSize(16);
        root.addView(islandCompatSwitch, matchWrap(dp(2)));
        TextView islandHint = text("默认关闭：只处理原生焦点通知。开启后关闭原生超级岛，并尝试提取取件码、配送进度、检票口等主体内容显示到焦点通知。", 13, Color.rgb(95, 99, 104));
        islandHint.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(islandHint, matchWrap(dp(8)));

        TextView stageHint = text("系统灵动舞台隐藏请使用其他工具，本模块不负责隐藏。", 13, Color.rgb(95, 99, 104));
        stageHint.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(stageHint, matchWrap(dp(8)));

        saveButton = new Button(this);
        saveButton.setText("保存设置");
        saveButton.setTextColor(Color.WHITE);
        saveButton.setAllCaps(false);
        saveButton.setMinHeight(dp(48));
        saveButton.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable saveBackground = new GradientDrawable();
        saveBackground.setColor(Color.rgb(26, 115, 232));
        saveBackground.setCornerRadius(dp(14));
        saveButton.setBackground(saveBackground);
        root.addView(saveButton, matchWrap(dp(8)));

        restartHint = text("修改后点击保存，再重启 SystemUI 或设备生效。", 14, Color.rgb(95, 99, 104));
        restartHint.setPadding(dp(12), dp(12), dp(12), dp(12));
        restartHint.setBackgroundColor(Color.rgb(232, 240, 254));
        root.addView(restartHint, matchWrap(0));

        scroll.addView(root);

        manualWidthSwitch.setOnCheckedChangeListener((button, checked) -> {
            pendingManual = checked;
            showPending();
        });
        widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int width = MIN_WIDTH_DP + progress;
                widthValue.setText(width + " dp");
                if (fromUser) {
                    pendingWidthDp = width;
                    showPending();
                }
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        compatRetrySwitch.setOnCheckedChangeListener((button, checked) -> {
            pendingCompatRetry = checked;
            showPending();
        });
        islandCompatSwitch.setOnCheckedChangeListener((button, checked) -> {
            pendingIslandCompat = checked;
            showPending();
        });
        saveButton.setOnClickListener(view -> saveSettings());
        delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int delay = progress * 100;
                delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", delay / 1000f));
                if (fromUser) {
                    pendingDelayMs = delay;
                    showPending();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        return scroll;
    }

    private void loadSettings() {
        boolean manual = preferences.getBoolean(KEY_LIMIT_WIDTH, true);
        int width = Math.max(MIN_WIDTH_DP, Math.min(MAX_WIDTH_DP, preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP)));
        int delay = Math.max(0, Math.min(5000, preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS)));
        boolean compatRetry = preferences.getBoolean(KEY_COMPAT_RETRY, false);
        boolean islandCompat = preferences.getBoolean(KEY_ISLAND_COMPAT, false);
        pendingManual = manual;
        pendingWidthDp = width;
        pendingDelayMs = delay;
        pendingCompatRetry = compatRetry;
        pendingIslandCompat = islandCompat;
        manualWidthSwitch.setChecked(manual);
        widthSeekBar.setProgress(width - MIN_WIDTH_DP);
        widthValue.setText(width + " dp");
        delaySeekBar.setProgress(delay / 100);
        delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", delay / 1000f));
        compatRetrySwitch.setChecked(compatRetry);
        islandCompatSwitch.setChecked(islandCompat);
    }

    private void showPending() {
        restartHint.setText("有未保存的修改，请点击“保存设置”。");
    }

    private void saveSettings() {
        preferences.edit()
                .putBoolean(KEY_LIMIT_WIDTH, pendingManual)
                .putInt(KEY_WIDTH_DP, pendingWidthDp)
                .putInt(KEY_MARQUEE_DELAY_MS, pendingDelayMs)
                .putBoolean(KEY_COMPAT_RETRY, pendingCompatRetry)
                .putBoolean(KEY_ISLAND_COMPAT, pendingIslandCompat)
                .commit();
        restartHint.setText("设置已保存。请重启 SystemUI 或设备后生效。");
    }

    private void configureLightSystemBars(Window window) {
        window.setStatusBarColor(Color.rgb(248, 249, 250));
        window.setNavigationBarColor(Color.rgb(248, 249, 250));
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
