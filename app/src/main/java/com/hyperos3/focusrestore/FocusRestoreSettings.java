package com.hyperos3.focusrestore;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Centralized persisted settings and compatibility defaults. */
public final class FocusRestoreSettings {
    public static final String PREFS_NAME = "com.hyperos3.focusrestore_preferences";

    public static final String KEY_LIMIT_WIDTH = "limit_text_width";
    public static final String KEY_WIDTH_DP = "text_width_dp";
    public static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    public static final String KEY_COMPAT_RETRY = "compat_retry";
    public static final String KEY_ISLAND_COMPAT = "island_compat";
    public static final String KEY_ISLAND_SEPARATOR = "island_separator";
    public static final String KEY_ALLOW_FOCUS_CLICK = "allow_focus_click";
    public static final String KEY_ISLAND_GENERAL_SEPARATOR = "island_general_separator";
    public static final String KEY_ISLAND_SIDE_SEPARATOR = "island_side_separator";
    public static final String KEY_ISLAND_FORCE_PACKAGES = "island_force_packages";
    public static final String PACKAGE_SET_SEPARATOR = "\u001f";

    public static final boolean DEFAULT_LIMIT_WIDTH = true;
    public static final int DEFAULT_WIDTH_DP = 160;
    public static final int MIN_WIDTH_DP = 80;
    public static final int MAX_WIDTH_DP = 400;
    public static final int DEFAULT_MARQUEE_DELAY_MS = 200;
    public static final boolean DEFAULT_COMPAT_RETRY = false;
    public static final boolean DEFAULT_ISLAND_COMPAT = false;
    public static final boolean DEFAULT_ALLOW_FOCUS_CLICK = false;
    public static final String DEFAULT_ISLAND_SEPARATOR = "·";

    public final boolean limitWidth;
    public final int widthDp;
    public final int marqueeDelayMs;
    public final boolean compatRetry;
    public final boolean islandCompat;
    public final boolean allowFocusClick;
    public final String islandGeneralSeparator;
    public final String islandSideSeparator;
    public final Set<String> islandForcePackages;

    private FocusRestoreSettings(boolean limitWidth, int widthDp, int marqueeDelayMs,
                                 boolean compatRetry, boolean islandCompat,
                                 boolean allowFocusClick, String islandGeneralSeparator,
                                 String islandSideSeparator, Set<String> islandForcePackages) {
        this.limitWidth = limitWidth;
        this.widthDp = clamp(widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP);
        this.marqueeDelayMs = clamp(marqueeDelayMs, 0, 5000);
        this.compatRetry = compatRetry;
        this.islandCompat = islandCompat;
        this.allowFocusClick = allowFocusClick;
        this.islandGeneralSeparator = valueOrDefault(islandGeneralSeparator);
        this.islandSideSeparator = valueOrDefault(islandSideSeparator);
        this.islandForcePackages = immutablePackages(islandForcePackages);
    }

    public static FocusRestoreSettings defaults() {
        return new FocusRestoreSettings(DEFAULT_LIMIT_WIDTH, DEFAULT_WIDTH_DP,
                DEFAULT_MARQUEE_DELAY_MS, DEFAULT_COMPAT_RETRY, DEFAULT_ISLAND_COMPAT,
                DEFAULT_ALLOW_FOCUS_CLICK, DEFAULT_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR,
                Collections.<String>emptySet());
    }

    public static FocusRestoreSettings withValues(boolean limitWidth, int widthDp, int marqueeDelayMs,
                                                  boolean compatRetry, boolean islandCompat,
                                                  boolean allowFocusClick, String islandGeneralSeparator,
                                                  String islandSideSeparator, Set<String> islandForcePackages) {
        return new FocusRestoreSettings(limitWidth, widthDp, marqueeDelayMs, compatRetry,
                islandCompat, allowFocusClick, islandGeneralSeparator, islandSideSeparator,
                islandForcePackages);
    }

    public static FocusRestoreSettings fromPreferences(SharedPreferences preferences) {
        String legacy = preferences.getString(KEY_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR);
        return new FocusRestoreSettings(
                preferences.getBoolean(KEY_LIMIT_WIDTH, DEFAULT_LIMIT_WIDTH),
                preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP),
                preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS),
                preferences.getBoolean(KEY_COMPAT_RETRY, DEFAULT_COMPAT_RETRY),
                preferences.getBoolean(KEY_ISLAND_COMPAT, DEFAULT_ISLAND_COMPAT),
                preferences.getBoolean(KEY_ALLOW_FOCUS_CLICK, DEFAULT_ALLOW_FOCUS_CLICK),
                preferences.getString(KEY_ISLAND_GENERAL_SEPARATOR, legacy),
                preferences.getString(KEY_ISLAND_SIDE_SEPARATOR, legacy),
                preferences.getStringSet(KEY_ISLAND_FORCE_PACKAGES, Collections.<String>emptySet()));
    }

    public void save(SharedPreferences preferences) {
        preferences.edit()
                .putBoolean(KEY_LIMIT_WIDTH, limitWidth)
                .putInt(KEY_WIDTH_DP, widthDp)
                .putInt(KEY_MARQUEE_DELAY_MS, marqueeDelayMs)
                .putBoolean(KEY_COMPAT_RETRY, compatRetry)
                .putBoolean(KEY_ISLAND_COMPAT, islandCompat)
                .putBoolean(KEY_ALLOW_FOCUS_CLICK, allowFocusClick)
                .putString(KEY_ISLAND_GENERAL_SEPARATOR, islandGeneralSeparator)
                .putString(KEY_ISLAND_SIDE_SEPARATOR, islandSideSeparator)
                .putString(KEY_ISLAND_SEPARATOR, islandGeneralSeparator)
                .putStringSet(KEY_ISLAND_FORCE_PACKAGES, islandForcePackages)
                .apply();
    }

    private static Set<String> immutablePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) return Collections.emptySet();
        HashSet<String> copy = new HashSet<>();
        for (String value : packages) {
            if (value != null && value.trim().length() > 0) copy.add(value.trim());
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String valueOrDefault(String value) {
        return value == null ? DEFAULT_ISLAND_SEPARATOR : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
