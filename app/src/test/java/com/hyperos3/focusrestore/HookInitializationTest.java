package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class HookInitializationTest {
    @Test
    public void entryPointConstructionDoesNotRequireAndroidLooper() {
        assertNotNull(new HyperOS3FocusRestoreHook());
    }
}
