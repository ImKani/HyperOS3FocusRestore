package com.hyperos3.focusrestore;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

/** Reads one complete settings snapshot from the module provider. */
final class HookSettingsReader {
    private HookSettingsReader() {
    }

    static HookSettings read(Context context) {
        if (context == null) return null;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(SettingsProvider.URI, SettingsProvider.COLUMNS,
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return null;
            return HookSettings.fromCursor(cursor);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
