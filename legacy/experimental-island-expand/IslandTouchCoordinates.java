package com.hyperos3.focusrestore;

final class IslandTouchCoordinates {
    private IslandTouchCoordinates() {
    }

    static float chooseX(float displayWidth, float[] cutoutCenters) {
        float displayCenter = displayWidth / 2f;
        float bestCenter = displayCenter;
        float bestDistance = Float.MAX_VALUE;
        if (cutoutCenters != null) {
            for (float center : cutoutCenters) {
                float distance = Math.abs(center - displayCenter);
                if (distance < bestDistance) {
                    bestCenter = center;
                    bestDistance = distance;
                }
            }
        }
        return bestDistance <= displayWidth / 4f ? bestCenter : displayCenter;
    }
}
