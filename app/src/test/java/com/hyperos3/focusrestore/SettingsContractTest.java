package com.hyperos3.focusrestore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SettingsContractTest {
    @Test
    public void providerColumnsRemainAppendOnlyAndIndexStable() {
        assertArrayEquals(new String[]{
                "limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry",
                "island_compat", "island_separator", "allow_focus_click",
                "island_general_separator", "island_side_separator", "island_force_packages",
                "disable_island_property", "disable_island_feature_cache", "marquee_bounce",
                "hook_mode", "hide_notification_icons", "show_focus_divider",
                "show_island_icon", "tint_island_icon", "expand_island_on_click",
                "use_small_icon_fallback", "notification_row_click_fallback"
        }, SettingsContract.COLUMNS);
        assertEquals(13, SettingsContract.HOOK_MODE);
        assertEquals(14, SettingsContract.HIDE_NOTIFICATION_ICONS);
        assertEquals(15, SettingsContract.SHOW_FOCUS_DIVIDER);
        assertEquals(16, SettingsContract.SHOW_ISLAND_ICON);
        assertEquals(17, SettingsContract.TINT_ISLAND_ICON);
        assertEquals(18, SettingsContract.EXPAND_ISLAND_ON_CLICK);
        assertEquals(19, SettingsContract.USE_SMALL_ICON_FALLBACK);
        assertEquals(20, SettingsContract.NOTIFICATION_ROW_CLICK_FALLBACK);
    }
}
