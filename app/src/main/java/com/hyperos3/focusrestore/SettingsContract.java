package com.hyperos3.focusrestore;

/** Append-only Provider schema shared by producer, consumer and tests. */
final class SettingsContract {
    static final int LIMIT_TEXT_WIDTH = 0;
    static final int TEXT_WIDTH_DP = 1;
    static final int MARQUEE_DELAY_MS = 2;
    static final int COMPAT_RETRY = 3;
    static final int ISLAND_COMPAT = 4;
    static final int LEGACY_ISLAND_SEPARATOR = 5;
    static final int ALLOW_FOCUS_CLICK = 6;
    static final int ISLAND_GENERAL_SEPARATOR = 7;
    static final int ISLAND_SIDE_SEPARATOR = 8;
    static final int ISLAND_FORCE_PACKAGES = 9;
    static final int DISABLE_ISLAND_PROPERTY = 10;
    static final int DISABLE_ISLAND_FEATURE_CACHE = 11;
    static final int MARQUEE_BOUNCE = 12;
    static final int HOOK_MODE = 13;
    static final int HIDE_NOTIFICATION_ICONS = 14;
    static final int SHOW_FOCUS_DIVIDER = 15;
    static final int SHOW_ISLAND_ICON = 16;
    static final int TINT_ISLAND_ICON = 17;
    static final int EXPAND_ISLAND_ON_CLICK = 18;
    static final int USE_SMALL_ICON_FALLBACK = 19;

    static final String[] COLUMNS = {
            "limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry",
            "island_compat", "island_separator", "allow_focus_click",
            "island_general_separator", "island_side_separator", "island_force_packages",
            "disable_island_property", "disable_island_feature_cache", "marquee_bounce",
            "hook_mode", "hide_notification_icons", "show_focus_divider",
            "show_island_icon", "tint_island_icon", "expand_island_on_click",
            "use_small_icon_fallback"
    };

    private SettingsContract() {
    }
}
