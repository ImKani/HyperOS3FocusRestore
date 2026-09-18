package com.hyperos3.focusrestore;

/** Pure HyperOS 4 display precedence shared by the Hook and unit tests. */
final class OS4FocusPriorityPolicy {
    static final int PRIORITY_ISLAND = 100;
    static final int PRIORITY_SMS_VERIFICATION = 150;
    static final int PRIORITY_ISLAND_WHITELIST = 200;
    static final int PRIORITY_NATIVE_FOCUS = 300;

    private OS4FocusPriorityPolicy() {
    }

    static boolean hasNativeStatusBarContent(boolean hasBarRemoteViews,
                                              boolean hasTicker,
                                              boolean hasIslandParam) {
        // A bar RemoteViews payload is native status-bar content even when the
        // same notification also carries island parameters. A ticker alone is
        // native only when no island JSON exists: HyperOS 4 marks PARAMS island
        // notifications as Focus and generates a category ticker (for example
        // "Weather"), which must not bypass island parsing or the whitelist.
        return hasBarRemoteViews || (hasTicker && !hasIslandParam);
    }

    static int compare(int firstPriority, long firstSequence,
                       int secondPriority, long secondSequence) {
        int priority = Integer.compare(firstPriority, secondPriority);
        return priority != 0 ? priority : Long.compare(firstSequence, secondSequence);
    }
}
