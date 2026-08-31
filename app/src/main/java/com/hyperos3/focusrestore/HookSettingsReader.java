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
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(SettingsProvider.URI, SettingsProvider.COLUMNS,
                null, null, null);
        if (cursor == null) return null;
        try {
            if (!cursor.moveToFirst()) return null;
            return HookSettings.fromCursor(cursor);
        } finally {
            cursor.close();
        }
    }
}
