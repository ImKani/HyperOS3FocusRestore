package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FocusPriorityPolicyTest {
    @Test
    public void islandTickerAloneDoesNotBlockConversion() {
        assertFalse(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, false, true, true));
    }

    @Test
    public void standaloneTickerIsExistingFocusContent() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, false, true, false));
    }

    @Test
    public void explicitFocusAlwaysHasPriority() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, true, false, false, true, true));
    }

    @Test
    public void focusRemoteViewsAlwaysHavePriority() {
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, true, false, true, true));
        assertTrue(FocusPriorityPolicy.isOriginalFocus(
                false, false, false, true, true, true));
    }
}
