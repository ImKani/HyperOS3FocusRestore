package com.hyperos3.focusrestore;

/** Restricts SystemUI exception recovery to known RemoteViews bind failures. */
final class RemoteViewsFailurePolicy {
    enum Action {
        RETHROW,
        TEXT_FALLBACK,
        DROP_CURRENT
    }

    private RemoteViewsFailurePolicy() {
    }

    static Action decide(Throwable throwable, boolean hasFallbackText) {
        if (!isRecoverable(throwable)) return Action.RETHROW;
        return hasFallbackText ? Action.TEXT_FALLBACK : Action.DROP_CURRENT;
    }

    private static boolean isRecoverable(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String name = current.getClass().getName();
            if ("android.view.InflateException".equals(name)
                    || "android.widget.RemoteViews$ActionException".equals(name)
                    || "android.content.res.Resources$NotFoundException".equals(name)
                    || ClassCastException.class.getName().equals(name)) {
                return true;
            }
            if (IllegalArgumentException.class.getName().equals(name)
                    && hasRemoteViewsStack(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasRemoteViewsStack(Throwable throwable) {
        for (StackTraceElement frame : throwable.getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("android.widget.RemoteViews")
                    || owner.startsWith("android.view.LayoutInflater")
                    || owner.startsWith("android.content.res.Resources")) {
                return true;
            }
        }
        return false;
    }
}
