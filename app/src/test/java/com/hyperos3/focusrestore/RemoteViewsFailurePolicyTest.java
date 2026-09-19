package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemoteViewsFailurePolicyTest {
    @Test
    public void recoverableFailureUsesTextWhenAvailable() {
        IllegalArgumentException cause = new IllegalArgumentException("bad action");
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "android.widget.RemoteViews$ReflectionAction", "apply", "RemoteViews.java", 1)});
        Throwable failure = new RuntimeException(cause);
        assertEquals(RemoteViewsFailurePolicy.Action.TEXT_FALLBACK,
                RemoteViewsFailurePolicy.decide(failure, true));
    }

    @Test
    public void recoverableFailureDropsCandidateWithoutText() {
        assertEquals(RemoteViewsFailurePolicy.Action.DROP_CURRENT,
                RemoteViewsFailurePolicy.decide(new ClassCastException("wrong view"), false));
    }

    @Test
    public void unknownFailureIsNotSwallowed() {
        assertEquals(RemoteViewsFailurePolicy.Action.RETHROW,
                RemoteViewsFailurePolicy.decide(new IllegalStateException("OEM state"), true));
        assertEquals(RemoteViewsFailurePolicy.Action.RETHROW,
                RemoteViewsFailurePolicy.decide(new IllegalArgumentException("OEM state"), true));
        assertEquals(RemoteViewsFailurePolicy.Action.RETHROW,
                RemoteViewsFailurePolicy.decide(new OutOfMemoryError("fatal"), true));
    }
}
