package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OS4FocusPriorityPolicyTest {
    @Test
    public void prioritiesFollowRequiredOrder() {
        assertTrue(OS4FocusPriorityPolicy.PRIORITY_NATIVE_FOCUS
                > OS4FocusPriorityPolicy.PRIORITY_ISLAND_WHITELIST);
        assertTrue(OS4FocusPriorityPolicy.PRIORITY_ISLAND_WHITELIST
                > OS4FocusPriorityPolicy.PRIORITY_SMS_VERIFICATION);
        assertTrue(OS4FocusPriorityPolicy.PRIORITY_SMS_VERIFICATION
                > OS4FocusPriorityPolicy.PRIORITY_ISLAND);
    }

    @Test
    public void paramsTickerGeneratedByOs4RemainsConvertible() {
        assertFalse(OS4FocusPriorityPolicy.hasNativeStatusBarContent(false, true, true));
        assertFalse(OS4FocusPriorityPolicy.hasNativeStatusBarContent(false, false, true));
    }

    @Test
    public void barRemoteViewsRemainNativeEvenWithIslandParams() {
        assertTrue(OS4FocusPriorityPolicy.hasNativeStatusBarContent(true, false, true));
        assertTrue(OS4FocusPriorityPolicy.hasNativeStatusBarContent(true, true, true));
    }

    @Test
    public void standaloneTickerRemainsNativeFocusContent() {
        assertTrue(OS4FocusPriorityPolicy.hasNativeStatusBarContent(false, true, false));
        assertFalse(OS4FocusPriorityPolicy.hasNativeStatusBarContent(false, false, false));
    }

    @Test
    public void priorityWinsBeforeRecency() {
        assertTrue(OS4FocusPriorityPolicy.compare(
                OS4FocusPriorityPolicy.PRIORITY_NATIVE_FOCUS, 1,
                OS4FocusPriorityPolicy.PRIORITY_ISLAND, 99) > 0);
    }

    @Test
    public void newestCandidateWinsAtSamePriority() {
        assertTrue(OS4FocusPriorityPolicy.compare(100, 11, 100, 10) > 0);
        assertEquals(0, OS4FocusPriorityPolicy.compare(100, 10, 100, 10));
    }

    @Test
    public void unknownHookModeDefaultsToOs3() {
        assertEquals(FocusRestoreSettings.HOOK_MODE_OS3,
                FocusRestoreSettings.normalizeHookMode(99));
        assertEquals(FocusRestoreSettings.HOOK_MODE_OS4,
                FocusRestoreSettings.normalizeHookMode(FocusRestoreSettings.HOOK_MODE_OS4));
    }
}
