package com.hyperos3.focusrestore;

/** Pure original-Focus precedence rules shared by the Hook and unit tests. */
final class FocusPriorityPolicy {
    private FocusPriorityPolicy() {
    }

    static boolean hasExplicitFocusData(boolean explicitFocus, boolean hasMainRemoteViews,
                                        boolean hasBarRemoteViews, boolean hasTicker,
                                        boolean hasIslandParam) {
        return explicitFocus || hasMainRemoteViews || hasBarRemoteViews
                || (hasTicker && !hasIslandParam);
    }

    static boolean isOriginalFocus(boolean originalFocusField, boolean explicitFocus,
                                   boolean hasMainRemoteViews, boolean hasBarRemoteViews,
                                   boolean hasTicker, boolean hasIslandParam) {
        return originalFocusField || hasExplicitFocusData(explicitFocus, hasMainRemoteViews,
                hasBarRemoteViews, hasTicker, hasIslandParam);
    }
}
