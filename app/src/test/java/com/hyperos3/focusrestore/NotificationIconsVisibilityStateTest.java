package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationIconsVisibilityStateTest {
    private static final int VISIBLE = 0;
    private static final int INVISIBLE = 4;
    private static final int GONE = 8;

    @Test
    public void requestsPassThroughWhenNotHiding() {
        NotificationIconsVisibilityState state = new NotificationIconsVisibilityState(GONE);

        assertEquals(VISIBLE, state.onVisibilityRequested(VISIBLE));
        assertEquals(INVISIBLE, state.onVisibilityRequested(INVISIBLE));
        assertFalse(state.isHiding());
    }

    @Test
    public void beginHidingUsesCurrentVisibilityAsInitialDesiredState() {
        NotificationIconsVisibilityState state = new NotificationIconsVisibilityState(GONE);

        assertEquals(GONE, state.beginHiding(VISIBLE));
        assertTrue(state.isHiding());
        assertEquals(VISIBLE, state.desiredVisibility());
        assertEquals(VISIBLE, state.finishHiding());
    }

    @Test
    public void latestSystemUiRequestWinsAcrossLockAndUnlock() {
        NotificationIconsVisibilityState state = new NotificationIconsVisibilityState(GONE);
        state.beginHiding(VISIBLE);

        assertEquals(GONE, state.onVisibilityRequested(GONE));
        assertEquals(GONE, state.desiredVisibility());
        assertEquals(GONE, state.onVisibilityRequested(VISIBLE));
        assertEquals(VISIBLE, state.desiredVisibility());
        assertEquals(VISIBLE, state.finishHiding());
        assertFalse(state.isHiding());
    }

    @Test
    public void latestGoneRequestIsRestoredAfterStartingVisible() {
        NotificationIconsVisibilityState state = new NotificationIconsVisibilityState(GONE);
        state.beginHiding(VISIBLE);

        state.onVisibilityRequested(GONE);

        assertEquals(GONE, state.finishHiding());
    }

    @Test
    public void resetDiscardsPreviousContainerState() {
        NotificationIconsVisibilityState state = new NotificationIconsVisibilityState(GONE);
        state.beginHiding(VISIBLE);
        state.onVisibilityRequested(INVISIBLE);

        state.reset();
        assertFalse(state.isHiding());
        assertEquals(GONE, state.beginHiding(GONE));
        assertEquals(GONE, state.finishHiding());
    }
}
