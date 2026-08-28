package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SettingsProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String[] COLUMNS = {"limit_text_width", "text_width_dp", "marquee_delay_ms"};
    static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    static final int DEFAULT_MARQUEE_DELAY_MS = 500;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri) || getContext() == null) return null;
        boolean limit = getContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, 0)
                .getBoolean(SettingsActivity.KEY_LIMIT_WIDTH, false);
        int width = getContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, 0)
                .getInt(SettingsActivity.KEY_WIDTH_DP, SettingsActivity.DEFAULT_WIDTH_DP);
        int delay = getContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, 0)
                .getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS);
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{limit ? 1 : 0, width, delay});
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.hyperos3.settings"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
