package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExperimentalSettingsTest {
    @Test
    public void iconFallbackDefaultsMatchStatusBarPolicy() {
        FocusRestoreSettings settings = FocusRestoreSettings.defaults();
        assertFalse(settings.showIslandIcon);
        assertTrue(settings.tintIslandIcon);
        assertFalse(settings.useSmallIconFallback);
        assertFalse(settings.expandIslandOnClick);
    }

    @Test
    public void legacyClickWinsWhenBothModesAreRequested() {
        FocusRestoreSettings settings = FocusRestoreSettings.withValues(
                FocusRestoreSettings.HOOK_MODE_OS4, true, 160, 200,
                false, true, true, true, true,
                true, true, true, true, true, true, false,
                "·", "·", Collections.<String>emptySet());
        assertTrue(settings.allowFocusClick);
        assertFalse(settings.expandIslandOnClick);
    }
}
