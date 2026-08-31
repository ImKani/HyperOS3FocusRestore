package com.hyperos3.focusrestore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Collections;

public final class SettingsProvider extends ContentProvider {
    static final String AUTHORITY = "com.hyperos3.focusrestore.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String[] COLUMNS = {"limit_text_width", "text_width_dp", "marquee_delay_ms", "compat_retry", "island_compat", "island_separator", "allow_focus_click", "island_general_separator", "island_side_separator", "island_force_packages", "disable_island_property", "disable_island_feature_cache"};
    static final String KEY_MARQUEE_DELAY_MS = FocusRestoreSettings.KEY_MARQUEE_DELAY_MS;
    static final int DEFAULT_MARQUEE_DELAY_MS = FocusRestoreSettings.DEFAULT_MARQUEE_DELAY_MS;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri) || getContext() == null) return null;
        android.content.SharedPreferences preferences = getContext().getSharedPreferences(
                FocusRestoreSettings.PREFS_NAME, 0);
        FocusRestoreSettings settings = FocusRestoreSettings.fromPreferences(preferences);
        String legacySeparator = preferences.getString(FocusRestoreSettings.KEY_ISLAND_SEPARATOR,
                FocusRestoreSettings.DEFAULT_ISLAND_SEPARATOR);
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{settings.limitWidth ? 1 : 0, settings.widthDp,
                settings.marqueeDelayMs, settings.compatRetry ? 1 : 0,
                settings.islandCompat ? 1 : 0, legacySeparator,
                settings.allowFocusClick ? 1 : 0, settings.islandGeneralSeparator,
                settings.islandSideSeparator, joinPackages(settings.islandForcePackages),
                 settings.disableIslandProperty ? 1 : 0,
                 settings.disableIslandFeatureCache ? 1 : 0});
        return cursor;
    }

    private static String joinPackages(java.util.Set<String> packages) {
        if (packages == null || packages.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String value : packages) {
            if (result.length() > 0) result.append(FocusRestoreSettings.PACKAGE_SET_SEPARATOR);
            result.append(value);
        }
        return result.toString();
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.hyperos3.settings"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
