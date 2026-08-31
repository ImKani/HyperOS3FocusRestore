package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SettingsProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String[] COLUMNS = {"limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry", "island_compat", "island_separator", "allow_focus_click", "island_general_separator", "island_side_separator"};
    static final String KEY_MARQUEE_DELAY_MS = "marquee_delay_ms";
    static final int DEFAULT_MARQUEE_DELAY_MS = 200;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri) || getContext() == null) return null;
        android.content.SharedPreferences preferences = getContext().getSharedPreferences(
                SettingsActivity.PREFS_NAME, 0);
        boolean limit = preferences.getBoolean(SettingsActivity.KEY_LIMIT_WIDTH, true);
        int width = preferences.getInt(SettingsActivity.KEY_WIDTH_DP, SettingsActivity.DEFAULT_WIDTH_DP);
        int delay = preferences.getInt(KEY_MARQUEE_DELAY_MS, DEFAULT_MARQUEE_DELAY_MS);
        boolean compatRetry = preferences.getBoolean(SettingsActivity.KEY_COMPAT_RETRY, false);
        boolean islandCompat = preferences.getBoolean(SettingsActivity.KEY_ISLAND_COMPAT, false);
        boolean allowFocusClick = preferences.getBoolean(SettingsActivity.KEY_ALLOW_FOCUS_CLICK, false);
        String generalSeparator = preferences.getString(SettingsActivity.KEY_ISLAND_GENERAL_SEPARATOR,
                preferences.getString(SettingsActivity.KEY_ISLAND_SEPARATOR, SettingsActivity.DEFAULT_ISLAND_SEPARATOR));
        String sideSeparator = preferences.getString(SettingsActivity.KEY_ISLAND_SIDE_SEPARATOR,
                preferences.getString(SettingsActivity.KEY_ISLAND_SEPARATOR, SettingsActivity.DEFAULT_ISLAND_SEPARATOR));
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        String legacySeparator = preferences.getString(SettingsActivity.KEY_ISLAND_SEPARATOR,
                SettingsActivity.DEFAULT_ISLAND_SEPARATOR);
        cursor.addRow(new Object[]{limit ? 1 : 0, width, delay, compatRetry ? 1 : 0,
                islandCompat ? 1 : 0, legacySeparator, allowFocusClick ? 1 : 0,
                generalSeparator, sideSeparator});
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.hyperos3.settings"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
