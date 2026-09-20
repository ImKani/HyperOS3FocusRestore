package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IslandTouchCoordinatesTest {
    @Test
    public void ignoresEdgeCutoutWhenChoosingIslandCenter() {
        assertEquals(600f, IslandTouchCoordinates.chooseX(1200f,
                new float[]{70f}), 0.01f);
    }

    @Test
    public void prefersCenteredCutout() {
        assertEquals(602f, IslandTouchCoordinates.chooseX(1200f,
                new float[]{70f, 602f}), 0.01f);
    }
}
