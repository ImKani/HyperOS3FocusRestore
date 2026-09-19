package com.hyperos3.focusrestore;

/** Tracks the latest SystemUI-requested visibility while module hiding is active. */
final class NotificationIconsVisibilityState {
    private final int hiddenVisibility;
    private boolean hiding;
    private int desiredVisibility;

    NotificationIconsVisibilityState(int hiddenVisibility) {
        this.hiddenVisibility = hiddenVisibility;
        this.desiredVisibility = hiddenVisibility;
    }

    int beginHiding(int currentVisibility) {
        desiredVisibility = currentVisibility;
        hiding = true;
        return hiddenVisibility;
    }

    int onVisibilityRequested(int requestedVisibility) {
        if (!hiding) return requestedVisibility;
        desiredVisibility = requestedVisibility;
        return hiddenVisibility;
    }

    int finishHiding() {
        int result = desiredVisibility;
        reset();
        return result;
    }

    void reset() {
        hiding = false;
        desiredVisibility = hiddenVisibility;
    }

    boolean isHiding() {
        return hiding;
    }

    int desiredVisibility() {
        return desiredVisibility;
    }
}
