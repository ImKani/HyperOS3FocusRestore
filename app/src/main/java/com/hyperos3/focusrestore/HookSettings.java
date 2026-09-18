package com.hyperos3.focusrestore;

import android.database.Cursor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Immutable SystemUI-side settings snapshot. */
final class HookSettings {
    final int hookMode;
    final boolean limitWidth;
    final int widthDp;
    final int marqueeDelayMs;
    final boolean compatRetry;
    final boolean marqueeBounce;
    final boolean islandCompat;
    final boolean disableIslandProperty;
    final boolean disableIslandFeatureCache;
    final boolean allowFocusClick;
    final boolean hideNotificationIcons;
    final boolean showFocusDivider;
    final String generalSeparator;
    final String sideSeparator;
    final Set<String> islandForcePackages;

    private HookSettings(int hookMode, boolean limitWidth, int widthDp, int marqueeDelayMs,
                         boolean compatRetry, boolean marqueeBounce, boolean islandCompat,
                         boolean disableIslandProperty, boolean disableIslandFeatureCache,
                         boolean allowFocusClick, boolean hideNotificationIcons,
                         boolean showFocusDivider, String generalSeparator,
                         String sideSeparator, Set<String> forcePackages) {
        this.hookMode = FocusRestoreSettings.normalizeHookMode(hookMode);
        this.limitWidth = limitWidth;
        this.widthDp = clamp(widthDp, FocusRestoreSettings.MIN_WIDTH_DP,
                FocusRestoreSettings.MAX_WIDTH_DP);
        this.marqueeDelayMs = clamp(marqueeDelayMs, 0, 5000);
        this.compatRetry = compatRetry;
        this.marqueeBounce = marqueeBounce;
        this.islandCompat = islandCompat;
        this.disableIslandProperty = disableIslandProperty;
        this.disableIslandFeatureCache = disableIslandFeatureCache;
        this.allowFocusClick = allowFocusClick;
        this.hideNotificationIcons = hideNotificationIcons;
        this.showFocusDivider = showFocusDivider;
        this.generalSeparator = generalSeparator == null
                ? FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR : generalSeparator;
        this.sideSeparator = sideSeparator == null
                ? FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR : sideSeparator;
        this.islandForcePackages = immutablePackages(forcePackages);
    }

    static HookSettings defaults() {
        return new HookSettings(FocusRestoreSettings.DEFAULT_HOOK_MODE,
                FocusRestoreSettings.DEFAULT_LIMIT_WIDTH,
                FocusRestoreSettings.DEFAULT_WIDTH_DP, FocusRestoreSettings.DEFAULT_MARQUEE_DELAY_MS,
                FocusRestoreSettings.DEFAULT_COMPAT_RETRY, FocusRestoreSettings.DEFAULT_MARQUEE_BOUNCE,
                 FocusRestoreSettings.DEFAULT_ISLAND_COMPAT,
                FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_PROPERTY,
                FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_FEATURE_CACHE,
                FocusRestoreSettings.DEFAULT_ALLOW_FOCUS_CLICK,
                FocusRestoreSettings.DEFAULT_HIDE_NOTIFICATION_ICONS,
                FocusRestoreSettings.DEFAULT_SHOW_FOCUS_DIVIDER,
                FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR, FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR,
                Collections.<String>emptySet());
    }

    static HookSettings fromCursor(Cursor cursor) {
        if (cursor == null || cursor.getColumnCount() < 3) {
            throw new IllegalArgumentException("settings cursor requires at least 3 columns");
        }
        if (cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) {
            throw new IllegalArgumentException("required settings column is null");
        }

        int columnCount = cursor.getColumnCount();
        boolean limitWidth = cursor.getInt(0) != 0;
        int widthDp = cursor.getInt(1);
        int marqueeDelayMs = cursor.getInt(2);
        boolean compatRetry = columnCount > 3 && !cursor.isNull(3) && cursor.getInt(3) != 0;
        boolean marqueeBounce = columnCount > 12 && !cursor.isNull(12)
                 ? cursor.getInt(12) != 0 : FocusRestoreSettings.DEFAULT_MARQUEE_BOUNCE;
        boolean islandCompat = columnCount > 4 && !cursor.isNull(4) && cursor.getInt(4) != 0;
        boolean disableIslandProperty = columnCount > 10 && !cursor.isNull(10)
                ? cursor.getInt(10) != 0 : FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_PROPERTY;
        boolean disableIslandFeatureCache = columnCount > 11 && !cursor.isNull(11)
                ? cursor.getInt(11) != 0 : FocusRestoreSettings.DEFAULT_DISABLE_ISLAND_FEATURE_CACHE;
        String legacySeparator = columnCount > 5 && !cursor.isNull(5)
                ? cursor.getString(5) : FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR;
        boolean allowFocusClick = columnCount > 6 && !cursor.isNull(6) && cursor.getInt(6) != 0;
        String generalSeparator = columnCount > 7 && !cursor.isNull(7)
                ? cursor.getString(7) : legacySeparator;
        String sideSeparator = columnCount > 8 && !cursor.isNull(8)
                ? cursor.getString(8) : legacySeparator;
        Set<String> forcePackages = columnCount > 9 && !cursor.isNull(9)
                ? splitPackages(cursor.getString(9)) : Collections.<String>emptySet();
        int hookMode = columnCount > 13 && !cursor.isNull(13)
                ? cursor.getInt(13) : FocusRestoreSettings.DEFAULT_HOOK_MODE;
        boolean hideNotificationIcons = columnCount > 14 && !cursor.isNull(14)
                ? cursor.getInt(14) != 0 : FocusRestoreSettings.DEFAULT_HIDE_NOTIFICATION_ICONS;
        boolean showFocusDivider = columnCount > 15 && !cursor.isNull(15)
                ? cursor.getInt(15) != 0 : FocusRestoreSettings.DEFAULT_SHOW_FOCUS_DIVIDER;

        return new HookSettings(hookMode, limitWidth, widthDp, marqueeDelayMs,
                compatRetry, marqueeBounce,
                islandCompat, disableIslandProperty, disableIslandFeatureCache,
                allowFocusClick, hideNotificationIcons, showFocusDivider,
                generalSeparator, sideSeparator, forcePackages);
    }

    String describe() {
        return "hookMode=OS" + hookMode + " limit=" + limitWidth + " widthDp=" + widthDp
                + " delayMs=" + marqueeDelayMs + " compatRetry=" + compatRetry
                 + " marqueeBounce=" + marqueeBounce
                + " islandCompat=" + islandCompat
                 + " disableIslandProperty=" + disableIslandProperty
                 + " disableIslandFeatureCache=" + disableIslandFeatureCache
                 + " allowFocusClick=" + allowFocusClick
                + " hideNotificationIcons=" + hideNotificationIcons
                + " showFocusDivider=" + showFocusDivider
                + " forcePackages=" + islandForcePackages
                + " islandSeparator=" + displaySeparator(generalSeparator)
                + " islandSideSeparator=" + displaySeparator(sideSeparator);
    }

    private static Set<String> splitPackages(String value) {
        if (value == null || value.length() == 0) return Collections.emptySet();
        HashSet<String> result = new HashSet<>();
        String[] parts = value.split(java.util.regex.Pattern.quote(
                FocusRestoreSettings.PACKAGE_SET_SEPARATOR));
        for (String part : parts) {
            if (part != null && part.trim().length() > 0) result.add(part.trim());
        }
        return result;
    }

    private static Set<String> immutablePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) return Collections.emptySet();
        HashSet<String> copy = new HashSet<>();
        for (String value : packages) {
            if (value != null && value.trim().length() > 0) copy.add(value.trim());
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String displaySeparator(String value) {
        return value.length() == 0 ? "<empty>" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
