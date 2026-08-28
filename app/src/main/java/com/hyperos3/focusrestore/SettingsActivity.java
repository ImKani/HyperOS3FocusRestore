package com.hyperos3.focusrestore;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    // LSPosed XSharedPreferences reads the package default preferences file.
    static final String PREFS_NAME = "com.hyperos3.focusrestore_preferences";
    static final String KEY_LIMIT_WIDTH = "limit_text_width";
    static final String KEY_WIDTH_DP = "text_width_dp";
    static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    static final int DEFAULT_WIDTH_DP = 170;
    static final int DEFAULT_MARQUEE_DELAY_MS = 500;
    private static final int MIN_WIDTH_DP = 80;
    private static final int MAX_WIDTH_DP = 400;

    private SharedPreferences preferences;
    private RadioButton systemMode;
    private RadioButton manualMode;
    private SeekBar widthSeekBar;
    private TextView widthValue;
    private TextView restartHint;
    private SeekBar delaySeekBar;
    private TextView delayValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setTitle("HyperOS3FocusRestore");
        setContentView(createContent());
        loadSettings();
    }

    private View createContent() {
        int padding = dp(20);
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

        TextView subtitle = text("控制状态栏焦点歌词的最大显示宽度", 14, Color.rgb(95, 99, 104));
        root.addView(subtitle, matchWrap(dp(24)));

        TextView modeLabel = text("宽度模式", 15, Color.rgb(60, 64, 67));
        modeLabel.setTypeface(modeLabel.getTypeface(), 1);
        root.addView(modeLabel, matchWrap(dp(8)));

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        systemMode = new RadioButton(this);
        systemMode.setId(View.generateViewId());
        systemMode.setText("跟随系统（不限制）");
        systemMode.setTextSize(16);
        manualMode = new RadioButton(this);
        manualMode.setId(View.generateViewId());
        manualMode.setText("手动限制宽度");
        manualMode.setTextSize(16);
        modes.addView(systemMode);
        modes.addView(manualMode);
        root.addView(modes, matchWrap(dp(18)));

        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView widthLabel = text("最大宽度", 15, Color.rgb(60, 64, 67));
        valueRow.addView(widthLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        widthValue = text("170 dp", 15, Color.rgb(26, 115, 232));
        widthValue.setTypeface(widthValue.getTypeface(), 1);
        valueRow.addView(widthValue);
        root.addView(valueRow, matchWrap(dp(4)));

        widthSeekBar = new SeekBar(this);
        widthSeekBar.setMax(MAX_WIDTH_DP - MIN_WIDTH_DP);
        root.addView(widthSeekBar, matchWrap(dp(6)));

        LinearLayout range = new LinearLayout(this);
        range.setGravity(Gravity.CENTER_VERTICAL);
        TextView minRange = text("80 dp", 12, Color.rgb(117, 117, 117));
        TextView maxRange = text("400 dp", 12, Color.rgb(117, 117, 117));
        range.addView(minRange, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        maxRange.setGravity(Gravity.END);
        range.addView(maxRange, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(range, matchWrap(dp(24)));

        TextView delayLabel = text("滚动启动延迟", 15, Color.rgb(60, 64, 67));
        delayLabel.setTypeface(delayLabel.getTypeface(), 1);
        root.addView(delayLabel, matchWrap(dp(4)));
        LinearLayout delayRow = new LinearLayout(this);
        delayRow.setGravity(Gravity.CENTER_VERTICAL);
        delayValue = text("0.5 秒", 15, Color.rgb(26, 115, 232));
        delayValue.setTypeface(delayValue.getTypeface(), 1);
        delayRow.addView(delayValue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        delaySeekBar = new SeekBar(this);
        delaySeekBar.setMax(50);
        delaySeekBar.setProgress(5);
        delayRow.addView(delaySeekBar, new LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(delayRow, matchWrap(dp(4)));
        TextView delayRange = text("0 秒", 12, Color.rgb(117, 117, 117));
        delayRange.setGravity(Gravity.END);
        delayRange.setText("0 秒                         5 秒");
        root.addView(delayRange, matchWrap(dp(24)));

        restartHint = text("设置已保存。重启 SystemUI 或设备后生效。", 14, Color.rgb(95, 99, 104));
        restartHint.setPadding(dp(12), dp(12), dp(12), dp(12));
        restartHint.setBackgroundColor(Color.rgb(232, 240, 254));
        root.addView(restartHint, matchWrap(0));

        modes.setOnCheckedChangeListener((group, checkedId) -> {
            boolean manual = checkedId == manualMode.getId();
            setManualEnabled(manual);
            preferences.edit().putBoolean(KEY_LIMIT_WIDTH, manual).commit();
            showSaved();
        });
        widthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int width = MIN_WIDTH_DP + progress;
                widthValue.setText(width + " dp");
                if (fromUser) {
                    preferences.edit().putInt(KEY_WIDTH_DP, width).commit();
                    showSaved();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        delaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int delay = progress * 100;
                delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", delay / 1000f));
                if (fromUser) preferences.edit().putInt(KEY_MARQUEE_DELAY_MS, delay).commit();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        return root;
    }

    private void loadSettings() {
        boolean manual = preferences.getBoolean(KEY_LIMIT_WIDTH, false);
        int width = clamp(preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP));
        int delay = Math.max(0, Math.min(5000, preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS)));
        widthSeekBar.setProgress(width - MIN_WIDTH_DP);
        delaySeekBar.setProgress(delay / 100);
        delayValue.setText(String.format(java.util.Locale.US, "%.1f 秒", delay / 1000f));
        if (manual) {
            manualMode.setChecked(true);
        } else {
            systemMode.setChecked(true);
        }
        setManualEnabled(manual);
    }

    private void setManualEnabled(boolean enabled) {
        widthSeekBar.setEnabled(enabled);
        widthValue.setEnabled(enabled);
        widthValue.setAlpha(enabled ? 1f : 0.45f);
    }

    private void showSaved() {
        restartHint.setText("设置已保存。重启 SystemUI 或设备后生效。");
    }

    private int clamp(int value) {
        return Math.max(MIN_WIDTH_DP, Math.min(MAX_WIDTH_DP, value));
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
