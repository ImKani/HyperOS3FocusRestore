package com.hyperos3.focusrestore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

/** Sizes icons and optionally applies alpha-outline and status-bar tint. */
final class FocusIconStyler {
    static final class Result {
        final Icon icon;
        final Drawable drawable;

        Result(Icon icon, Drawable drawable) {
            this.icon = icon;
            this.drawable = drawable;
        }
    }

    private FocusIconStyler() {
    }

    static Result load(Context context, Icon source, boolean outline,
                       boolean tint, int tintColor, int sizeDp) {
        if (context == null || source == null) return null;
        Drawable drawable = source.loadDrawable(context);
        if (drawable == null) return null;
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.min(128, Math.round(sizeDp * density)));
        int outlinePx = outline ? Math.max(1, Math.round(density)) : 0;
        Bitmap original = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas sourceCanvas = new Canvas(original);
        drawable.setBounds(outlinePx, outlinePx, size - outlinePx, size - outlinePx);
        drawable.draw(sourceCanvas);

        int[] pixels = new int[size * size];
        original.getPixels(pixels, 0, size, 0, 0, size, size);
        int[] output = new int[pixels.length];
        if (outline) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int maxAlpha = 0;
                    int left = Math.max(0, x - outlinePx);
                    int right = Math.min(size - 1, x + outlinePx);
                    int top = Math.max(0, y - outlinePx);
                    int bottom = Math.min(size - 1, y + outlinePx);
                    for (int sy = top; sy <= bottom; sy++) {
                        for (int sx = left; sx <= right; sx++) {
                            maxAlpha = Math.max(maxAlpha,
                                    Color.alpha(pixels[sy * size + sx]));
                        }
                    }
                    output[y * size + x] = Color.argb(maxAlpha, 0, 0, 0);
                }
            }
        }
        Bitmap styled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        styled.setPixels(output, 0, size, 0, 0, size, size);
        Canvas canvas = new Canvas(styled);
        if (tint) {
            int color = tintColor & 0x00ffffff;
            for (int index = 0; index < pixels.length; index++) {
                int alpha = Color.alpha(pixels[index]);
                pixels[index] = alpha == 0 ? Color.TRANSPARENT : (alpha << 24) | color;
            }
            Bitmap tinted = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(tinted, 0f, 0f, null);
        } else {
            canvas.drawBitmap(original, 0f, 0f, null);
        }
        return new Result(Icon.createWithBitmap(styled),
                new BitmapDrawable(context.getResources(), styled));
    }
}
