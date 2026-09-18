package com.hyperos3.focusrestore;

import android.content.Context;
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
    public static final String KEY_MARQUEE_BOUNCE = "marquee_bounce";
    public static final String KEY_ISLAND_COMPAT = "island_compat";
    public static final String KEY_ISLAND_SEPARATOR = "island_separator";
    public static final String KEY_ALLOW_FOCUS_CLICK = "allow_focus_click";
    public static final String KEY_ISLAND_GENERAL_SEPARATOR = "island_general_separator";
    public static final String KEY_ISLAND_SIDE_SEPARATOR = "island_side_separator";
    public static final String KEY_ISLAND_FORCE_PACKAGES = "island_force_packages";
    public static final String KEY_ISLAND_APP_CACHE = "island_app_cache";
    public static final String KEY_DISABLE_ISLAND_PROPERTY = "disable_island_property";
    public static final String KEY_DISABLE_ISLAND_FEATURE_CACHE = "disable_island_feature_cache";
    public static final String KEY_HOOK_MODE = "hook_mode";
    public static final String KEY_HIDE_NOTIFICATION_ICONS = "hide_notification_icons";
    static final String KEY_HOOK_SETTINGS_READY = "hook_settings_ready";
    public static final String PACKAGE_SET_SEPARATOR = "\u001f";

    public static final int HOOK_MODE_OS3 = 3;
    public static final int HOOK_MODE_OS4 = 4;
    public static final int DEFAULT_HOOK_MODE = HOOK_MODE_OS3;

    public static final boolean DEFAULT_LIMIT_WIDTH = true;
    public static final int DEFAULT_WIDTH_DP = 160;
    public static final int MIN_WIDTH_DP = 80;
    public static final int MAX_WIDTH_DP = 400;
    public static final int DEFAULT_MARQUEE_DELAY_MS = 200;
    public static final boolean DEFAULT_COMPAT_RETRY = false;
    public static final boolean DEFAULT_MARQUEE_BOUNCE = true;
    public static final boolean DEFAULT_ISLAND_COMPAT = false;
    public static final boolean DEFAULT_DISABLE_ISLAND_PROPERTY = true;
    public static final boolean DEFAULT_DISABLE_ISLAND_FEATURE_CACHE = true;
    public static final boolean DEFAULT_ALLOW_FOCUS_CLICK = false;
    public static final boolean DEFAULT_HIDE_NOTIFICATION_ICONS = true;
    public static final String DEFAULT_ISLAND_SEPARATOR = "·";

    public final int hookMode;
    public final boolean limitWidth;
    public final int widthDp;
    public final int marqueeDelayMs;
    public final boolean compatRetry;
    public final boolean marqueeBounce;
    public final boolean islandCompat;
    public final boolean disableIslandProperty;
    public final boolean disableIslandFeatureCache;
    public final boolean allowFocusClick;
    public final boolean hideNotificationIcons;
    public final String islandGeneralSeparator;
    public final String islandSideSeparator;
    public final Set<String> islandForcePackages;

    private FocusRestoreSettings(int hookMode, boolean limitWidth, int widthDp, int marqueeDelayMs,
                                 boolean compatRetry, boolean marqueeBounce, boolean islandCompat,
                                 boolean disableIslandProperty, boolean disableIslandFeatureCache,
                                 boolean allowFocusClick, boolean hideNotificationIcons,
                                 String islandGeneralSeparator, String islandSideSeparator,
                                 Set<String> islandForcePackages) {
        this.hookMode = normalizeHookMode(hookMode);
        this.limitWidth = limitWidth;
        this.widthDp = clamp(widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP);
        this.marqueeDelayMs = clamp(marqueeDelayMs, 0, 5000);
        this.compatRetry = compatRetry;
        this.marqueeBounce = marqueeBounce;
        this.islandCompat = islandCompat;
        this.disableIslandProperty = disableIslandProperty;
        this.disableIslandFeatureCache = disableIslandFeatureCache;
        this.allowFocusClick = allowFocusClick;
        this.hideNotificationIcons = hideNotificationIcons;
        this.islandGeneralSeparator = valueOrDefault(islandGeneralSeparator);
        this.islandSideSeparator = valueOrDefault(islandSideSeparator);
        this.islandForcePackages = immutablePackages(islandForcePackages);
    }

    public static FocusRestoreSettings defaults() {
        return new FocusRestoreSettings(DEFAULT_HOOK_MODE, DEFAULT_LIMIT_WIDTH, DEFAULT_WIDTH_DP,
                DEFAULT_MARQUEE_DELAY_MS, DEFAULT_COMPAT_RETRY, DEFAULT_MARQUEE_BOUNCE,
                DEFAULT_ISLAND_COMPAT,
                DEFAULT_DISABLE_ISLAND_PROPERTY, DEFAULT_DISABLE_ISLAND_FEATURE_CACHE,
                DEFAULT_ALLOW_FOCUS_CLICK, DEFAULT_HIDE_NOTIFICATION_ICONS,
                DEFAULT_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR,
                Collections.<String>emptySet());
    }

    public static FocusRestoreSettings withValues(int hookMode, boolean limitWidth, int widthDp,
                                                   int marqueeDelayMs,
                                                  boolean compatRetry, boolean marqueeBounce, boolean islandCompat,
                                                  boolean disableIslandProperty, boolean disableIslandFeatureCache,
                                                  boolean allowFocusClick, boolean hideNotificationIcons,
                                                  String islandGeneralSeparator, String islandSideSeparator,
                                                  Set<String> islandForcePackages) {
        return new FocusRestoreSettings(hookMode, limitWidth, widthDp, marqueeDelayMs,
                compatRetry, marqueeBounce,
                islandCompat, disableIslandProperty, disableIslandFeatureCache,
                allowFocusClick, hideNotificationIcons, islandGeneralSeparator,
                islandSideSeparator, islandForcePackages);
    }

    public static SharedPreferences hookPreferences(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean hasHookSettings(SharedPreferences preferences) {
        return preferences.getBoolean(KEY_HOOK_SETTINGS_READY, false);
    }

    public static FocusRestoreSettings fromPreferences(SharedPreferences preferences) {
        String legacy = preferences.getString(KEY_ISLAND_SEPARATOR, DEFAULT_ISLAND_SEPARATOR);
        return new FocusRestoreSettings(
                preferences.getInt(KEY_HOOK_MODE, DEFAULT_HOOK_MODE),
                preferences.getBoolean(KEY_LIMIT_WIDTH, DEFAULT_LIMIT_WIDTH),
                preferences.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP),
                preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS),
                preferences.getBoolean(KEY_COMPAT_RETRY, DEFAULT_COMPAT_RETRY),
                preferences.getBoolean(KEY_MARQUEE_BOUNCE, DEFAULT_MARQUEE_BOUNCE),
                 preferences.getBoolean(KEY_ISLAND_COMPAT, DEFAULT_ISLAND_COMPAT),
                preferences.getBoolean(KEY_DISABLE_ISLAND_PROPERTY, DEFAULT_DISABLE_ISLAND_PROPERTY),
                preferences.getBoolean(KEY_DISABLE_ISLAND_FEATURE_CACHE, DEFAULT_DISABLE_ISLAND_FEATURE_CACHE),
                preferences.getBoolean(KEY_ALLOW_FOCUS_CLICK, DEFAULT_ALLOW_FOCUS_CLICK),
                preferences.getBoolean(KEY_HIDE_NOTIFICATION_ICONS, DEFAULT_HIDE_NOTIFICATION_ICONS),
                preferences.getString(KEY_ISLAND_GENERAL_SEPARATOR, legacy),
                preferences.getString(KEY_ISLAND_SIDE_SEPARATOR, legacy),
                preferences.getStringSet(KEY_ISLAND_FORCE_PACKAGES, Collections.<String>emptySet()));
    }

    String describe() {
        return "hookMode=OS" + hookMode + " limit=" + limitWidth + " widthDp=" + widthDp
                + " delayMs=" + marqueeDelayMs + " compatRetry=" + compatRetry
                + " marqueeBounce=" + marqueeBounce + " islandCompat=" + islandCompat
                + " disableIslandProperty=" + disableIslandProperty
                + " disableIslandFeatureCache=" + disableIslandFeatureCache
                + " allowFocusClick=" + allowFocusClick
                + " hideNotificationIcons=" + hideNotificationIcons
                + " forcePackages=" + islandForcePackages
                + " islandSeparator=" + displaySeparator(islandGeneralSeparator)
                + " islandSideSeparator=" + displaySeparator(islandSideSeparator);
    }

    public boolean save(SharedPreferences preferences) {
        return preferences.edit()
                .putInt(KEY_HOOK_MODE, hookMode)
                .putBoolean(KEY_LIMIT_WIDTH, limitWidth)
                .putInt(KEY_WIDTH_DP, widthDp)
                .putInt(KEY_MARQUEE_DELAY_MS, marqueeDelayMs)
                .putBoolean(KEY_COMPAT_RETRY, compatRetry)
                .putBoolean(KEY_MARQUEE_BOUNCE, marqueeBounce)
                .putBoolean(KEY_ISLAND_COMPAT, islandCompat)
                .putBoolean(KEY_DISABLE_ISLAND_PROPERTY, disableIslandProperty)
                .putBoolean(KEY_DISABLE_ISLAND_FEATURE_CACHE, disableIslandFeatureCache)
                .putBoolean(KEY_ALLOW_FOCUS_CLICK, allowFocusClick)
                .putBoolean(KEY_HIDE_NOTIFICATION_ICONS, hideNotificationIcons)
                .putString(KEY_ISLAND_GENERAL_SEPARATOR, islandGeneralSeparator)
                .putString(KEY_ISLAND_SIDE_SEPARATOR, islandSideSeparator)
                .putString(KEY_ISLAND_SEPARATOR, islandGeneralSeparator)
                .putStringSet(KEY_ISLAND_FORCE_PACKAGES, islandForcePackages)
                .putBoolean(KEY_HOOK_SETTINGS_READY, true)
                .commit();
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

    private static String displaySeparator(String value) {
        return value.length() == 0 ? "<empty>" : value;
    }

    static int normalizeHookMode(int value) {
        return value == HOOK_MODE_OS4 ? HOOK_MODE_OS4 : HOOK_MODE_OS3;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
