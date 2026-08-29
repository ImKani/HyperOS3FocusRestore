package com.hyperos3.focusrestore;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperOS3FocusRestoreHook implements IXposedHookLoadPackage {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final String SYSTEM_UI = "com.android.systemui";

    // Disable this if a KernelSU module already sets feature.island.debug=false.
    private static final boolean FORCE_ISLAND_OFF = true;
    // OS3 rejects the legacy miui.focus.rv when used as contentRemoteViews.
    private static final boolean FALLBACK_MAIN_RV_FOR_STATUS_BAR = false;
    private static final boolean FORCE_SHOULD_SHOW = false;

    private ClassLoader classLoader;
    private XSharedPreferences settings;
    private static final int DEFAULT_WIDTH_DP = 160;
    // FocusedTextView.startMarqueeLocal() copies this value into TextView.
    // -1 keeps long lyrics moving instead of stopping after one pass.
    private static final int MARQUEE_REPEAT_LIMIT = -1;
    private boolean limitWidth;
    private int widthDp = DEFAULT_WIDTH_DP;
    private int marqueeDelayMs = SettingsProvider.DEFAULT_MARQUEE_DELAY_MS;
    private boolean compatRetry;
    private TextView pendingMarqueeText;
    private Runnable pendingMarqueeRunnable;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)
                || !SYSTEM_UI.equals(lpparam.processName)) {
            return;
        }

        classLoader = lpparam.classLoader;
        settings = new XSharedPreferences("com.hyperos3.focusrestore", "com.hyperos3.focusrestore_preferences");
        settings.makeWorldReadable();
        reloadSettings();
        readProviderSettings();
        log("loading in " + lpparam.packageName + "/" + lpparam.processName
                + " settings=" + describeSettings());

        hookIslandProperty();
        hookDynamicFeatureFlag();
        hookShowOnStatusBar();
        hookPromptViewSetData();
        hookFocusedParentParams();
        hookFocusedTextMarquee();
        hookPromptShouldShow();
        hookRemoteViewsErrors();
    }

    private void hookIslandProperty() {
        if (!FORCE_ISLAND_OFF) return;

        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    classLoader,
                    "getBoolean",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("feature.island.debug".equals(param.args[0])) {
                                param.setResult(false);
                                log("forced feature.island.debug=false");
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookIslandProperty", t);
        }
    }

    private void hookDynamicFeatureFlag() {
        if (!FORCE_ISLAND_OFF) return;

        try {
            Class<?> config = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.DynamicFeatureConfig",
                    classLoader);
            XposedHelpers.setStaticBooleanField(config, "FEATURE_DYNAMIC_ISLAND", false);
            log("set DynamicFeatureConfig.FEATURE_DYNAMIC_ISLAND=false");
        } catch (Throwable t) {
            // The property hook still covers initialization if this class is not loaded yet.
            error("set FEATURE_DYNAMIC_ISLAND", t);
        }
    }

    private void hookShowOnStatusBar() {
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.utils.FocusUtils",
                    classLoader);
            Class<?> expanded = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.ExpandedNotification",
                    classLoader);

            XposedHelpers.findAndHookMethod(utils, "showOnStatusBar", expanded,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FocusData data = inspectExpanded(param.args[0]);
                            if (data == null) return;

                            boolean original = Boolean.TRUE.equals(param.getResult());
                            boolean fallback = data.isFocus && data.hasMainRv;
                            if (!original && fallback) {
                                param.setResult(true);
                                log("showOnStatusBar fallback=true " + data.summary());
                            } else {
                                log("showOnStatusBar=" + original + " " + data.summary());
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookShowOnStatusBar", t);
        }
    }

    private void hookPromptViewSetData() {
        try {
            Class<?> view = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView",
                    classLoader);
            Class<?> bean = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);

            XposedHelpers.findAndHookMethod(view, "setData", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            patchBean(param.args[0], "before setData");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FocusData data = inspectBean(param.args[0]);
                            log("after setData "
                                    + (data == null ? "bean=null" : data.summary()));
                            reloadSettings();
                            applyTextWidth(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptViewSetData", t);
        }
    }

    private void reloadSettings() {
        if (settings != null) settings.reload();
        readProviderSettings();
    }

    private void readProviderSettings() {
        limitWidth = false;
        widthDp = DEFAULT_WIDTH_DP;
        try {
            Object context = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
            if (context == null) return;
            Cursor cursor = ((android.content.Context) context).getContentResolver().query(
                    SettingsProvider.URI, SettingsProvider.COLUMNS, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        limitWidth = cursor.getInt(0) != 0;
                        widthDp = cursor.getInt(1);
                        marqueeDelayMs = cursor.getInt(2);
                        compatRetry = cursor.getColumnCount() > 3 && cursor.getInt(3) != 0;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            error("readProviderSettings", t);
        }
    }

    private String describeSettings() {
        return "limit=" + limitWidth + " widthDp=" + widthDp
                + " delayMs=" + marqueeDelayMs + " compatRetry=" + compatRetry;
    }

    private void applyTextWidth(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            TextView textView = (TextView) value;
            boolean limited = limitWidth;
            if (limited) {
                int configuredWidthDp = widthDp;
                float density = textView.getResources().getDisplayMetrics().density;
                int parentWidthPx = Math.round(configuredWidthDp * density);
                int textWidthPx = calculateTextAvailableWidth(promptView, textView, parentWidthPx);
                textView.setMaxWidth(textWidthPx);
                ViewGroup.LayoutParams params = textView.getLayoutParams();
                if (params != null) {
                    params.width = textWidthPx;
                    textView.setLayoutParams(params);
                }
                textView.requestLayout();
                log("applied manual focus text width parent=" + widthDp + "dp/"
                        + parentWidthPx + "px text=" + textWidthPx + "px");
            } else {
                log("using system focus text width");
            }
        } catch (Throwable t) {
            error("applyTextWidth", t);
        }
    }

    private int calculateTextAvailableWidth(Object promptView, TextView textView,
                                            int parentWidthPx) {
        try {
            Object parentValue = XposedHelpers.getObjectField(promptView, "mFocusedParentView");
            if (parentValue instanceof View) {
                View focusedParent = (View) parentValue;
                int[] parentLocation = new int[2];
                int[] textLocation = new int[2];
                focusedParent.getLocationInWindow(parentLocation);
                textView.getLocationInWindow(textLocation);
                int consumedBeforeText = textLocation[0] - parentLocation[0];
                int available = parentWidthPx - consumedBeforeText - focusedParent.getPaddingRight();
                if (consumedBeforeText > 0 && available > 0 && available < parentWidthPx) {
                    log("calculated focus text width from layout consumed="
                            + consumedBeforeText + " available=" + available);
                    return available;
                }
            }
        } catch (Throwable t) {
            error("calculateTextWidthLayout", t);
        }

        int consumed = 0;
        if (promptView instanceof View) {
            View prompt = (View) promptView;
            consumed += prompt.getPaddingLeft() + prompt.getPaddingRight();
        }
        try {
            Object iconValue = XposedHelpers.getObjectField(promptView, "mIcon");
            if (iconValue instanceof View) {
                View icon = (View) iconValue;
                int iconWidth = icon.getWidth();
                if (iconWidth <= 0 && icon.getLayoutParams() != null) {
                    iconWidth = icon.getLayoutParams().width;
                }
                consumed += Math.max(0, iconWidth);
            }
        } catch (Throwable t) {
            error("calculateTextWidthIcon", t);
        }
        ViewGroup.LayoutParams contentParams = textView.getParent() instanceof View
                ? ((View) textView.getParent()).getLayoutParams() : null;
        if (contentParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) contentParams;
            consumed += margins.getMarginStart() + margins.getMarginEnd();
        }
        int available = Math.max(1, parentWidthPx - consumed);
        log("calculated focus text width from children consumed=" + consumed
                + " available=" + available);
        return available;
    }

    private void startNativeMarquee(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (value instanceof TextView) startNativeMarquee((TextView) value);
        } catch (Throwable t) {
            error("startNativeMarquee", t);
        }
    }

    private void startNativeMarquee(TextView textView) {
        try {
            if (textView.getVisibility() != View.VISIBLE) return;
            textView.setSingleLine(true);
            textView.setHorizontallyScrolling(true);
            textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            textView.setFocusable(true);
            textView.setFocusableInTouchMode(true);
            // Re-selecting resets a marquee left in a completed/stale state by
            // the previous RemoteViews update.
            textView.setSelected(false);
            textView.setSelected(true);
            textView.setMarqueeRepeatLimit(MARQUEE_REPEAT_LIMIT);
            XposedHelpers.callMethod(textView, "startMarqueeLocal");
            log("started native focus marquee width=" + textView.getWidth()
                    + " measured=" + textView.getMeasuredWidth()
                    + " selected=" + textView.isSelected()
                    + " focused=" + textView.isFocused()
                    + " textWidth=" + textView.getPaint().measureText(textView.getText().toString()));
        } catch (Throwable t) {
            error("startNativeMarqueeText", t);
        }
    }

    private void startNativeMarquee(TextView textView, int attempt) {
        startNativeMarquee(textView);
        log("native focus marquee attempt=" + attempt);
    }

    private boolean needsMarqueeRetry(TextView textView) {
        if (textView.getVisibility() != View.VISIBLE) return false;
        float textWidth = textView.getPaint().measureText(textView.getText().toString());
        int width = textView.getWidth();
        boolean needed = textWidth > width;
        log("marquee check textWidth=" + textWidth + " width=" + width
                + " measured=" + textView.getMeasuredWidth()
                + " scrollX=" + textView.getScrollX() + " needed=" + needed);
        return needed;
    }

    private void hookFocusedTextMarquee() {
        try {
            Class<?> textClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.widget.FocusedTextView",
                    classLoader);
            XposedBridge.hookAllMethods(textClass, "startMarqueeLocal", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // The OEM method copies its private marqueeLimit into
                    // TextView immediately before starting the animator.
                    XposedHelpers.setIntField(param.thisObject,
                            "marqueeLimit", MARQUEE_REPEAT_LIMIT);
                }
            });
            log("hooked FocusedTextView.startMarqueeLocal repeatLimit="
                    + MARQUEE_REPEAT_LIMIT);
        } catch (Throwable t) {
            error("hookFocusedTextMarquee", t);
        }
    }

    private void scheduleNativeMarquee(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            final TextView textView = (TextView) value;
            if (pendingMarqueeText != null && pendingMarqueeRunnable != null) {
                pendingMarqueeText.removeCallbacks(pendingMarqueeRunnable);
            }
            pendingMarqueeText = textView;
            pendingMarqueeRunnable = new Runnable() {
                private int attempts;

                @Override public void run() {
                    attempts++;
                    startNativeMarquee(textView, attempts);
                    // Compatibility mode adds one retry for ROMs that reset
                    // marquee state immediately after the first native start.
                    if (compatRetry && attempts < 2) {
                        textView.postDelayed(this, 150L);
                    }
                }
            };
            textView.postDelayed(pendingMarqueeRunnable,
                    Math.max(0, Math.min(5000, marqueeDelayMs)));
            log("scheduled native focus marquee delayMs=" + marqueeDelayMs);
        } catch (Throwable t) {
            error("scheduleNativeMarquee", t);
        }
    }

    private void hookFocusedParentParams() {
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
                    classLoader);
            XposedHelpers.findAndHookMethod(fragment, "updateFocusedParentParams", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyParentWidth(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            error("hookFocusedParentParams", t);
        }
    }

    private void applyParentWidth(Object fragment) {
        try {
            reloadSettings();
            boolean limited = limitWidth;
            Object value = XposedHelpers.getObjectField(fragment, "mFocusedNotifParent");
            if (!(value instanceof View)) return;
            View parent = (View) value;
            ViewGroup.LayoutParams params = parent.getLayoutParams();
            if (params == null) return;
            if (limited) {
                int configuredWidthDp = widthDp;
                params.width = Math.round(configuredWidthDp * parent.getResources().getDisplayMetrics().density);
                parent.setLayoutParams(params);
                log("applied manual focus parent width=" + configuredWidthDp + "dp");
            } else if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                parent.setLayoutParams(params);
                log("restored system focus parent width");
            }
        } catch (Throwable t) {
            error("applyParentWidth", t);
        }
    }

    private void hookPromptShouldShow() {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            Class<?> bean = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);

            XposedHelpers.findAndHookMethod(controller, "shouldShow", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.args[0];
                            FocusData data = inspectBean(value);
                            boolean result = Boolean.TRUE.equals(param.getResult());
                            log("shouldShow=" + result + (data == null ? " bean=null" : " " + data.summary()));

                            if (FORCE_SHOULD_SHOW && !result && data != null
                                    && data.isFocus && data.hasDisplayContent()) {
                                param.setResult(true);
                                log("shouldShow forced=true key=" + data.key);
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptShouldShow", t);
        }
    }

    private void hookRemoteViewsErrors() {
        try {
            Class<?> view = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView",
                    classLoader);
            XposedBridge.hookAllMethods(view, "updateRemoteViews", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object bean = getField(param.thisObject, "mData");
                    FocusData data = inspectBean(bean);
                    log("updateRemoteViews begin " + (data == null ? "bean=null" : data.summary()));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        error("updateRemoteViews throwable", param.getThrowable());
                    } else {
                        log("updateRemoteViews end");
                        scheduleNativeMarquee(param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            error("hookRemoteViewsErrors", t);
        }
    }

    private void patchBean(Object bean, String stage) {
        FocusData data = inspectBean(bean);
        if (data == null) {
            log(stage + " bean=null");
            return;
        }

        if (FALLBACK_MAIN_RV_FOR_STATUS_BAR && data.isFocus) {
            try {
                Object contentRv = getField(bean, "contentRemoteViews");
                if (contentRv == null && data.mainRv != null) {
                    XposedHelpers.setObjectField(bean, "contentRemoteViews", data.mainRv);
                    data.contentRv = data.mainRv;
                    log(stage + " filled contentRemoteViews from miui.focus.rv");
                }

                Object nightRv = getField(bean, "contentNightRemoteViews");
                if (nightRv == null && data.mainNightRv != null) {
                    XposedHelpers.setObjectField(bean, "contentNightRemoteViews", data.mainNightRv);
                    data.contentNightRv = data.mainNightRv;
                    log(stage + " filled contentNightRemoteViews from miui.focus.rvNight");
                }
            } catch (Throwable t) {
                error(stage + " patchBean", t);
            }
        }

        log(stage + " " + data.summary());
    }

    private FocusData inspectBean(Object bean) {
        if (bean == null) return null;
        try {
            FocusData data = inspectExpanded(getField(bean, "sbn"));
            if (data == null) data = new FocusData();
            data.key = stringValue(getField(bean, "notifKey"));
            data.content = stringValue(getField(bean, "content"));
            data.contentRv = asRemoteViews(getField(bean, "contentRemoteViews"));
            data.contentNightRv = asRemoteViews(getField(bean, "contentNightRemoteViews"));
            return data;
        } catch (Throwable t) {
            error("inspectBean", t);
            return null;
        }
    }

    private FocusData inspectExpanded(Object expanded) {
        if (expanded == null) return null;
        try {
            FocusData data = new FocusData();
            data.isFocus = getBooleanField(expanded, "mIsFocusNotification", false);

            Notification notification = null;
            try {
                notification = (Notification) XposedHelpers.callMethod(expanded, "getNotification");
            } catch (Throwable ignored) {
                Object sbnNotification = invokeNoArg(expanded, "getNotification");
                if (sbnNotification instanceof Notification) notification = (Notification) sbnNotification;
            }

            if (notification == null || notification.extras == null) return data;
            Bundle extras = notification.extras;
            data.isFocus = data.isFocus || extras.getBoolean("miui.focus.isFocus", false);
            data.ticker = extras.getString("miui.focus.ticker");
            data.mainRv = getRemoteViews(extras, "miui.focus.rv");
            data.mainNightRv = getRemoteViews(extras, "miui.focus.rvNight");
            data.barRv = getRemoteViews(extras, "miui.focus.rvBar");
            data.barNightRv = getRemoteViews(extras, "miui.focus.rvBarNight");
            data.hasMainRv = data.mainRv != null;
            data.hasBarRv = data.barRv != null;
            return data;
        } catch (Throwable t) {
            error("inspectExpanded", t);
            return null;
        }
    }

    private static RemoteViews getRemoteViews(Bundle extras, String key) {
        try {
            Parcelable value = extras.getParcelable(key);
            return value instanceof RemoteViews ? (RemoteViews) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RemoteViews asRemoteViews(Object value) {
        return value instanceof RemoteViews ? (RemoteViews) value : null;
    }

    private static Object getField(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean getBooleanField(Object target, String name, boolean fallback) {
        if (target == null) return fallback;
        try {
            return XposedHelpers.getBooleanField(target, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        Method value = target.getClass().getMethod(method);
        value.setAccessible(true);
        return value.invoke(target);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void log(String value) {
        Log.i(TAG, value);
        XposedBridge.log(TAG + ": " + value);
    }

    private static void error(String stage, Throwable t) {
        Log.e(TAG, stage, t);
        XposedBridge.log(TAG + " ERROR " + stage + ": " + Log.getStackTraceString(t));
    }

    private static final class FocusData {
        boolean isFocus;
        boolean hasMainRv;
        boolean hasBarRv;
        String key;
        String ticker;
        String content;
        RemoteViews mainRv;
        RemoteViews mainNightRv;
        RemoteViews barRv;
        RemoteViews barNightRv;
        RemoteViews contentRv;
        RemoteViews contentNightRv;

        boolean hasDisplayContent() {
            return hasMainRv || hasBarRv || !TextUtils.isEmpty(ticker)
                    || !TextUtils.isEmpty(content) || contentRv != null;
        }

        String summary() {
            return "key=" + key
                    + " focus=" + isFocus
                    + " ticker=" + !TextUtils.isEmpty(ticker)
                    + " content=" + !TextUtils.isEmpty(content)
                    + " rv=" + (mainRv != null)
                    + " rvNight=" + (mainNightRv != null)
                    + " rvBar=" + (barRv != null)
                    + " rvBarNight=" + (barNightRv != null)
                    + " contentRv=" + (contentRv != null)
                    + " contentNightRv=" + (contentNightRv != null);
        }
    }
}
