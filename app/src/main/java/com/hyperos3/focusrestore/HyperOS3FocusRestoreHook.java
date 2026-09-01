package com.hyperos3.focusrestore;

import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.database.Cursor;
import android.graphics.Rect;
import org.json.JSONObject;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperOS3FocusRestoreHook implements IXposedHookLoadPackage {
    private static final String TAG = "HyperOS3FocusRestore";
    private static final String SYSTEM_UI = "com.android.systemui";

    // OS3 rejects the legacy miui.focus.rv when used as contentRemoteViews.
    private static final boolean FALLBACK_MAIN_RV_FOR_STATUS_BAR = false;
    // Let HyperOS own the prompt lifecycle; forcing true leaves stale icons after clicks.
    private static final boolean FORCE_SHOULD_SHOW = false;

    private static final long SETTINGS_REFRESH_INTERVAL_MS = 1000L;
    private static final long CONVERTED_KEY_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_CONVERTED_KEYS = 128;

    private static final Set<ClassLoader> INSTALLED_CLASS_LOADERS =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Object INSTALL_LOCK = new Object();
    private static final Object SETTINGS_READ_LOCK = new Object();
    private static final ExecutorService SETTINGS_EXECUTOR =
            Executors.newSingleThreadExecutor(command -> {
                Thread thread = new Thread(command, TAG + "-settings");
                thread.setDaemon(true);
                return thread;
            });

    private ClassLoader classLoader;
    private volatile Context systemUiContext;
    // FocusedTextView.startMarqueeLocal() copies this value into TextView.
    // -1 keeps long lyrics moving instead of stopping after one pass.
    private static final int MARQUEE_REPEAT_LIMIT = -1;
    private volatile HookSettings currentSettings = HookSettings.defaults();
    private long lastProviderReadAttemptMs = Long.MIN_VALUE;
    private long settingsReadGeneration;
    private boolean settingsReadQueued;
    private boolean hasSuccessfulProviderSettings;
    private int providerSettingsState = Integer.MIN_VALUE;
    private TextView pendingMarqueeText;
    private Runnable pendingMarqueeRunnable;
    private ValueAnimator fallbackMarqueeAnimator;
    private long marqueeGeneration;
    private final Set<Object> convertedBeans = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, Boolean> preMarkedOriginalFocus = Collections.synchronizedMap(
            new WeakHashMap<>());
    private final Set<Object> preMarkedIslands = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, OriginalBeanState> originalBeanStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final LinkedHashMap<String, Long> convertedNotificationKeys =
            new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)
                || !SYSTEM_UI.equals(lpparam.processName)) {
            return;
        }

        synchronized (INSTALL_LOCK) {
            if (!INSTALLED_CLASS_LOADERS.add(lpparam.classLoader)) {
                return;
            }
        }
        classLoader = lpparam.classLoader;
        hookApplicationAttach();
        reloadSettings(true);
        log("loading in " + lpparam.packageName + "/" + lpparam.processName
                + " settings=" + currentSettings.describe());
        logCapabilities();

        hookDynamicIslandSystemProperty();
        disableDynamicIslandFeatureCache();
        hookShowOnStatusBar();
        hookPromptViewSetData();
        hookFocusedParentParams();
        hookFocusedTextMarquee();
        hookPromptShouldShow();
        hookDisableConvertedFocusClick();
        hookRemoteViewsErrors();
    }

    private void logCapabilities() {
        Class<?> focusUtils = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.notification.utils.FocusUtils");
        Class<?> promptView = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.phone.FocusedNotifPromptView");
        Class<?> focusedText = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.widget.FocusedTextView");
        log("capabilities " + FocusReflection.capability(focusUtils, "showOnStatusBar")
                + " " + FocusReflection.capability(promptView, "setData")
                + " " + FocusReflection.capability(promptView, "onFocusNotifPromptClicked")
                + " " + FocusReflection.capability(focusedText, "startMarqueeLocal"));
    }

    private void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Context) {
                                systemUiContext = ((Context) param.args[0]).getApplicationContext();
                                log("SystemUI application context available; reloading settings");
                                reloadSettings(true);
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookApplicationAttach", t);
        }
    }

    private void hookDynamicIslandSystemProperty() {
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
                            if ((!com.hyperos3.focusrestore.BuildConfig.DEBUG || currentSettings.disableIslandProperty)
                                    && "feature.island.debug".equals(param.args[0])) {
                                param.setResult(false);
                                log("Dynamic Island property override: feature.island.debug=false");
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookDynamicIslandSystemProperty", t);
        }
    }

    private void disableDynamicIslandFeatureCache() {
        if (com.hyperos3.focusrestore.BuildConfig.DEBUG && !currentSettings.disableIslandFeatureCache) return;
        try {
            Class<?> config = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.DynamicFeatureConfig",
                    classLoader);
            XposedHelpers.setStaticBooleanField(config, "FEATURE_DYNAMIC_ISLAND", false);
            log("Dynamic Island feature cache disabled: FEATURE_DYNAMIC_ISLAND=false");
        } catch (Throwable t) {
            // The property hook still covers initialization if this class is not loaded yet.
            error("set FEATURE_DYNAMIC_ISLAND", t);
        }
    }

    private void hookShowOnStatusBar() {
        try {
            Class<?> utils = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.utils.FocusUtils",
                    classLoader);
            Class<?> expanded = FocusReflection.findClass(
                    "com.android.systemui.statusbar.notification.ExpandedNotification",
                    classLoader);

            XposedHelpers.findAndHookMethod(utils, "showOnStatusBar", expanded,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!currentSettings.islandCompat) return;
                            FocusData data = inspectExpanded(param.args[0]);
                            IslandText islandText = (data != null && shouldConvert(data))
                                    ? extractIslandContent(data) : null;
                            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
                                try {
                                    boolean originalFocus = getBooleanField(param.args[0],
                                            "mIsFocusNotification", false);
                                    preMarkedOriginalFocus.put(param.args[0], originalFocus);
                                    XposedHelpers.setBooleanField(param.args[0], "mIsFocusNotification", true);
                                    preMarkedIslands.add(param.args[0]);
                                    log("marked island notification for focus conversion source="
                                            + islandText.source);
                                } catch (Throwable t) {
                                    error("markIslandFocusBeforeShow", t);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FocusData data = inspectExpanded(param.args[0]);
                            if (data == null) return;

                            boolean original = Boolean.TRUE.equals(param.getResult());
                            IslandText islandText = (!data.isOriginalFocus && currentSettings.islandCompat)
                                    ? extractIslandContent(data) : null;
                            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
                                try {
                                    XposedHelpers.setBooleanField(param.args[0], "mIsFocusNotification", true);
                                } catch (Throwable t) {
                                    error("markIslandFocus", t);
                                }
                                param.setResult(true);
                                log("island converted to focus source=" + islandText.source
                                        + " content=" + islandText.text);
                                return;
                            }
                            clearPreMark(param.args[0], true);
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
            Class<?> view = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView",
                    classLoader);
            Class<?> bean = FocusReflection.findClass(
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
                            reloadSettings(false);
                            applyTextWidth(param.thisObject);
                            scheduleNativeMarquee(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptViewSetData", t);
        }
    }

    private synchronized void reloadSettings(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastProviderReadAttemptMs != Long.MIN_VALUE
                && now - lastProviderReadAttemptMs < SETTINGS_REFRESH_INTERVAL_MS) {
            return;
        }
        lastProviderReadAttemptMs = now;
        if (!force && settingsReadQueued) return;
        final long generation = ++settingsReadGeneration;
        if (force) {
            synchronized (SETTINGS_READ_LOCK) {
                readProviderSettings(generation);
            }
            return;
        }
        settingsReadQueued = true;
        SETTINGS_EXECUTOR.execute(() -> {
            try {
                synchronized (SETTINGS_READ_LOCK) {
                    readProviderSettings(generation);
                }
            } finally {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    settingsReadQueued = false;
                }
            }
        });
    }

    private void readProviderSettings(long generation) {
        try {
            Context context = systemUiContext;
            if (context == null) {
                Object currentApplication = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (currentApplication instanceof Context) {
                    context = (Context) currentApplication;
                    systemUiContext = context.getApplicationContext();
                }
            }
            if (context == null) {
                logProviderSettingsState(false, "application context unavailable");
                return;
            }
            HookSettings next = HookSettingsReader.read(context);
            if (next == null) {
                logProviderSettingsState(false, "provider query returned no settings");
                return;
            }
            synchronized (this) {
                if (generation != settingsReadGeneration) return;
                currentSettings = next;
            }
            hasSuccessfulProviderSettings = true;
            logProviderSettingsState(true, null);
        } catch (Throwable t) {
            logProviderSettingsState(false, t.getClass().getSimpleName());
            error("readProviderSettings", t);
        }
    }

    private void logProviderSettingsState(boolean available, String reason) {
        int state = available ? 1 : hasSuccessfulProviderSettings ? 0 : -1;
        if (providerSettingsState == state) return;
        providerSettingsState = state;
        if (available) {
            log("provider settings updated: " + currentSettings.describe());
        } else {
            log("provider settings unavailable: "
                    + (hasSuccessfulProviderSettings ? "keeping cached settings" : "using defaults")
                    + (TextUtils.isEmpty(reason) ? "" : " (" + reason + ")"));
        }
    }

    // Restored 0.7 behavior: constrain the text and its content slot, not the outer prompt.
    private void applyTextWidth(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            TextView textView = (TextView) value;
            if (!currentSettings.limitWidth) return;
            float density = textView.getResources().getDisplayMetrics().density;
            int widthPx = Math.max(1, Math.round(currentSettings.widthDp * density));
            ViewGroup.LayoutParams params = textView.getLayoutParams();
            boolean changed = textView.getMaxWidth() != widthPx;
            if (changed) textView.setMaxWidth(widthPx);
            if (params != null && params.width != widthPx) {
                params.width = widthPx;
                textView.setLayoutParams(params);
                changed = true;
            }
            if (changed) textView.requestLayout();
            log("applied 0.7 manual focus text width=" + currentSettings.widthDp + "dp px=" + widthPx);
        } catch (Throwable t) {
            error("applyTextWidth", t);
        }
    }

    private void startNativeMarquee(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (value instanceof TextView) startNativeMarquee((TextView) value);
        } catch (Throwable t) {
            error("startNativeMarquee", t);
        }
    }

    private boolean startNativeMarquee(TextView textView) {
        try {
            if (textView.getVisibility() != View.VISIBLE) return true;
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
            if (hasMarqueeOverflow(textView)) {
                stopNativeMarquee(textView);
                startFallbackMarquee(textView);
            }
            log("started native focus marquee width=" + textView.getWidth()
                    + " measured=" + textView.getMeasuredWidth()
                    + " selected=" + textView.isSelected()
                    + " focused=" + textView.isFocused()
                    + " textWidth=" + textView.getPaint().measureText(textView.getText().toString())
                    + " visibleContentWidth=" + getVisibleContentWidth(textView));
            return textView.getWidth() > 0;
        } catch (Throwable t) {
            error("startNativeMarqueeText", t);
            return true;
        }
    }

    private boolean startNativeMarquee(TextView textView, int attempt) {
        boolean ready = startNativeMarquee(textView);
        log("native focus marquee attempt=" + attempt);
        return ready;
    }

    private boolean hasMarqueeOverflow(TextView textView) {
        float textWidth = textView.getPaint().measureText(textView.getText().toString());
        float layoutWidth = textView.getLayout() == null
                ? 0f : textView.getLayout().getLineWidth(0);
        float availableWidth = getVisibleContentWidth(textView);
        return availableWidth > 0f && Math.max(textWidth, layoutWidth) > availableWidth;
    }

    private float getVisibleContentWidth(TextView textView) {
        float localWidth = textView.getWidth()
                - textView.getCompoundPaddingLeft() - textView.getCompoundPaddingRight();
        int[] location = new int[2];
        textView.getLocationOnScreen(location);
        int visibleLeft = location[0];
        int visibleRight = visibleLeft + textView.getWidth();

        Rect visibleRect = new Rect();
        if (textView.getGlobalVisibleRect(visibleRect) && visibleRect.width() > 0) {
            visibleLeft = Math.max(visibleLeft, visibleRect.left);
            visibleRight = Math.min(visibleRight, visibleRect.right);
        }

        ViewParent ancestor = textView.getParent();
        while (ancestor instanceof View && visibleRight > visibleLeft) {
            View parent = (View) ancestor;
            parent.getLocationOnScreen(location);
            visibleLeft = Math.max(visibleLeft, location[0]);
            visibleRight = Math.min(visibleRight, location[0] + parent.getWidth());
            ancestor = parent.getParent();
        }

        float visibleWidth = visibleRight - visibleLeft
                - textView.getCompoundPaddingLeft() - textView.getCompoundPaddingRight();
        return Math.max(0f, Math.min(localWidth, visibleWidth));
    }

    private void stopNativeMarquee(TextView textView) {
        try {
            Object marquee = getField(textView, "mMarquee");
            if (marquee != null) XposedHelpers.callMethod(marquee, "stop");
        } catch (Throwable t) {
            error("stopNativeMarquee", t);
        }
    }

    private void startFallbackMarquee(TextView textView) {
        try {
            float textWidth = textView.getPaint().measureText(textView.getText().toString());
            float layoutWidth = textView.getLayout() == null
                    ? 0f : textView.getLayout().getLineWidth(0);
            textWidth = Math.max(textWidth, layoutWidth);
            float availableWidth = getVisibleContentWidth(textView);
            final int distance = Math.round(textWidth - availableWidth);
            if (distance <= 0) return;
            if (fallbackMarqueeAnimator != null) fallbackMarqueeAnimator.cancel();
            fallbackMarqueeAnimator = currentSettings.marqueeBounce
                    ? ValueAnimator.ofInt(0, distance, 0)
                    : ValueAnimator.ofInt(0, distance);
            fallbackMarqueeAnimator.setDuration(Math.max(2500L, distance * 35L));
            fallbackMarqueeAnimator.setInterpolator(new LinearInterpolator());
            fallbackMarqueeAnimator.setRepeatCount(ValueAnimator.INFINITE);
            fallbackMarqueeAnimator.addUpdateListener(animation -> {
                if (textView.getVisibility() == View.VISIBLE) {
                    textView.scrollTo((Integer) animation.getAnimatedValue(), 0);
                }
            });
            fallbackMarqueeAnimator.start();
            log("started fallback focus marquee textWidth=" + textWidth
                    + " availableWidth=" + availableWidth + " distance=" + distance);
        } catch (Throwable t) {
            error("startFallbackMarquee", t);
        }
    }

    private void hookFocusedTextMarquee() {
        try {
            Class<?> textClass = FocusReflection.findClass(
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

    private synchronized void scheduleNativeMarquee(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            final TextView textView = (TextView) value;
            if (pendingMarqueeText != null && pendingMarqueeRunnable != null) {
                pendingMarqueeText.removeCallbacks(pendingMarqueeRunnable);
            }
            if (fallbackMarqueeAnimator != null) {
                fallbackMarqueeAnimator.cancel();
                fallbackMarqueeAnimator = null;
            }
            textView.scrollTo(0, 0);
            final long generation = ++marqueeGeneration;
            pendingMarqueeText = textView;
            pendingMarqueeRunnable = new Runnable() {
                private int attempts;

                @Override public void run() {
                    synchronized (HyperOS3FocusRestoreHook.this) {
                        if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                    }
                    if (Build.VERSION.SDK_INT >= 19 && !textView.isAttachedToWindow()) return;
                    attempts++;
                    if (textView.getText() == null || textView.getText().length() == 0) {
                        if (attempts < 3) textView.postDelayed(this, 100L);
                        return;
                    }
                    boolean ready = startNativeMarquee(textView, attempts);
                    // Wait briefly when RemoteViews has supplied text but the
                    // final one-line layout or Marquee instance is not ready yet.
                    if (!ready && attempts < 3) {
                        textView.postDelayed(this, 100L);
                        return;
                    }
                    // Compatibility mode adds one retry for ROMs that reset
                    // marquee state immediately after the first native start.
                    if (currentSettings.compatRetry && attempts < 2) {
                        textView.postDelayed(this, 150L);
                    }
                }
            };
            textView.postDelayed(pendingMarqueeRunnable,
                    Math.max(0, Math.min(5000, currentSettings.marqueeDelayMs)));
            log("scheduled native focus marquee delayMs=" + currentSettings.marqueeDelayMs);
        } catch (Throwable t) {
            error("scheduleNativeMarquee", t);
        }
    }

    private void hookFocusedParentParams() {
        try {
            Class<?> fragment = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
                    classLoader);
            XposedHelpers.findAndHookMethod(fragment, "updateFocusedParentParams", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyParentWidth(param.thisObject);
                        }
                    });
            log("hooked updateFocusedParentParams for 0.4 width behavior");
        } catch (Throwable t) {
            error("hookFocusedParentParams", t);
        }
    }

    private void applyParentWidth(Object fragment) {
        try {
            reloadSettings(false);
            Object value = XposedHelpers.getObjectField(fragment, "mFocusedNotifParent");
            if (!(value instanceof View)) return;
            View parent = (View) value;
            ViewGroup.LayoutParams params = parent.getLayoutParams();
            if (params == null) return;
            if (currentSettings.limitWidth) {
                int widthPx = Math.round(currentSettings.widthDp
                        * parent.getResources().getDisplayMetrics().density);
                if (params.width != widthPx) {
                    params.width = widthPx;
                    parent.setLayoutParams(params);
                    log("applied 0.4 manual focus parent width=" + currentSettings.widthDp
                            + "dp px=" + widthPx);
                }
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
            Class<?> controller = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            Class<?> bean = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);

            XposedHelpers.findAndHookMethod(controller, "shouldShow", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (currentSettings.islandCompat) patchBean(param.args[0], "before shouldShow");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.args[0];
                            FocusData data = inspectBean(value);
                            boolean result = Boolean.TRUE.equals(param.getResult());
                            log("shouldShow=" + result + (data == null ? " bean=null" : " " + data.summary()));

                            if (FORCE_SHOULD_SHOW && !result && data != null
                                    && data.isFocus && (data.hasDisplayContent()
                                    || hasConvertibleIslandContent(data))) {
                                param.setResult(true);
                                log("shouldShow forced=true key=" + data.key
                                        + " island=" + data.hasIslandParam);
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookPromptShouldShow", t);
        }
    }

    private void hookDisableConvertedFocusClick() {
        try {
            Class<?> promptView = FocusReflection.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptView", classLoader);
            XposedHelpers.findAndHookMethod(promptView, "onFocusNotifPromptClicked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (currentSettings.allowFocusClick) return;
                            Object bean = getField(param.thisObject, "mData");
                            FocusData data = inspectBean(bean);
                            boolean converted = convertedBeans.contains(bean)
                                    || isConvertedNotificationKey(data == null ? null : data.key);
                            boolean focus = data != null && (data.isFocus || data.hasExplicitFocusData);
                            if (focus || converted) {
                                param.setResult(null);
                                log("ignored focus click key=" + (data == null ? null : data.key)
                                        + " converted=" + converted);
                            }
                        }
                    });
            log("disabled converted focus click");
        } catch (Throwable t) {
            error("hookDisableConvertedFocusClick", t);
        }
    }

    private synchronized void rememberConvertedNotificationKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> iterator = convertedNotificationKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() >= CONVERTED_KEY_TTL_MS) iterator.remove();
        }
        convertedNotificationKeys.put(key, now);
        while (convertedNotificationKeys.size() > MAX_CONVERTED_KEYS) {
            iterator = convertedNotificationKeys.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private synchronized boolean isConvertedNotificationKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> iterator = convertedNotificationKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() >= CONVERTED_KEY_TTL_MS) iterator.remove();
        }
        Long seenAt = convertedNotificationKeys.get(key);
        return seenAt != null && now - seenAt < CONVERTED_KEY_TTL_MS;
    }

    private void hookRemoteViewsErrors() {
        try {
            Class<?> view = FocusReflection.findClass(
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

    private boolean shouldConvert(FocusData data) {
        if (data == null || !data.hasIslandParam || !currentSettings.islandCompat) return false;
        return !data.isOriginalFocus
                || currentSettings.islandForcePackages.contains(data.packageName)
                || isSmsVerificationCode(data);
    }

    private boolean isSmsVerificationCode(FocusData data) {
        if (data == null || !"com.android.mms".equals(data.packageName)
                || TextUtils.isEmpty(data.islandParam)
                || data.islandParam.length() > 256 * 1024) return false;
        try {
            JSONObject root = new JSONObject(data.islandParam);
            return root.optInt("protocol", -1) == 1
                    && "verifyCode".equals(root.optString("scene"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasConvertibleIslandContent(FocusData data) {
        if (!currentSettings.islandCompat || data == null || !data.hasIslandParam) return false;
        IslandText text = extractIslandContent(data);
        return text != null && !TextUtils.isEmpty(text.text);
    }

    private void patchBean(Object bean, String stage) {
        FocusData data = inspectBean(bean);
        if (data == null) {
            log(stage + " bean=null");
            return;
        }

        OriginalBeanState savedState;
        synchronized (originalBeanStates) {
            savedState = originalBeanStates.get(bean);
        }
        if (savedState != null) {
            data.isFocus = savedState.originalFocus || data.hasExplicitFocusData;
            data.isOriginalFocus = savedState.originalFocus || data.hasExplicitFocusData;
        }
        IslandText islandText = shouldConvert(data) ? extractIslandContent(data) : null;
        if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
            try {
                // Keep the OEM value so a reused Bean can be restored when the
                // payload, settings, or notification identity changes.
                String current = stringValue(getField(bean, "content"));
                OriginalBeanState state;
                synchronized (originalBeanStates) {
                    state = originalBeanStates.get(bean);
                    if (state == null) {
                        Object expanded = getField(bean, "sbn");
                        Boolean preMarkedFocus = preMarkedOriginalFocus.remove(expanded);
                        boolean originalFocus = preMarkedFocus != null
                                ? preMarkedFocus : getBooleanField(expanded,
                                "mIsFocusNotification", data.isFocus);
                        state = new OriginalBeanState(expanded, originalFocus, current);
                        originalBeanStates.put(bean, state);
                    } else if (!TextUtils.equals(current, state.lastConvertedContent)) {
                        state.originalContent = current;
                    }
                    state.lastConvertedContent = islandText.text;
                }
                XposedHelpers.setObjectField(bean, "content", islandText.text);
                Object expanded = state.expanded;
                preMarkedIslands.remove(expanded);
                preMarkedOriginalFocus.remove(expanded);
                data.content = islandText.text;
                data.isFocus = true;
                convertedBeans.add(bean);
                rememberConvertedNotificationKey(data.key);
                log(stage + " applied island focus source=" + islandText.source
                        + " replaced=" + !TextUtils.isEmpty(current));
            } catch (Throwable t) {
                error(stage + " applyIslandContent", t);
            }
        } else {
            restoreOriginalBean(bean, data, stage);
            Object expanded = getField(bean, "sbn");
            preMarkedIslands.remove(expanded);
            preMarkedOriginalFocus.remove(expanded);
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

    private void restoreOriginalBean(Object bean, FocusData data, String stage) {
        OriginalBeanState state;
        synchronized (originalBeanStates) {
            state = originalBeanStates.remove(bean);
        }
        if (state == null) return;
        clearPreMark(state.expanded, false);
        try {
            XposedHelpers.setObjectField(bean, "content", state.originalContent);
            if (state.expanded != null) {
                XposedHelpers.setBooleanField(state.expanded, "mIsFocusNotification", state.originalFocus);
            }
            if (data != null) {
                data.content = state.originalContent;
                data.isFocus = state.originalFocus;
            }
            convertedBeans.remove(bean);
            log(stage + " restored original focus content");
        } catch (Throwable t) {
            error(stage + " restoreOriginalContent", t);
        }
    }

    private IslandText extractIslandContent(FocusData data) {
        if (data == null || TextUtils.isEmpty(data.islandParam)) return null;
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(
                data.islandParam, currentSettings.generalSeparator, currentSettings.sideSeparator);
        return parsed == null ? null : new IslandText(parsed.text, parsed.source);
    }

    @SuppressWarnings("unused")
    private IslandText extractIslandContentLegacy(FocusData data) {
        if (data == null || TextUtils.isEmpty(data.islandParam)) return null;
        // A notification that already has focus data must use that data as-is.
        if (data.hasExplicitFocusData && !data.hasIslandParam) {
            log("island conversion skipped because explicit focus data already exists");
            return null;
        }
        try {
            JSONObject root = new JSONObject(data.islandParam);
            JSONObject v2 = root.optJSONObject("param_v2");
            if (v2 == null) v2 = root;

            // Older HyperOS focus payloads (notably SMS verification) use protocol 1.
            if (root.optInt("protocol", 3) == 1 || "verifyCode".equals(root.optString("scene"))) {
                String legacy = joinTexts(root, "protocol1", "title", "desc1", "desc2");
                if (!TextUtils.isEmpty(legacy)) {
                    log("island content source=protocol1:" + root.optString("scene", "legacy")
                            + " text=" + legacy);
                    return new IslandText(legacy, "protocol1:" + root.optString("scene", "legacy"));
                }
                return null;
            }

            JSONObject base = v2.optJSONObject("baseInfo");
            JSONObject highlight = v2.optJSONObject("highlightInfo");
            JSONObject highlightV3 = v2.optJSONObject("highlightInfoV3");
            JSONObject chat = v2.optJSONObject("chatInfo");
            JSONObject hint = v2.optJSONObject("hintInfo");

            String source = null;
            String result = joinTexts(base, "title", "subTitle", "specialTitle",
                    "extraTitle", "content", "subContent");
            if (base != null && !TextUtils.isEmpty(result)) {
                // Some HyperOS 3.0.5 builds drop BaseInfo.title while retaining
                // subTitle/content. The same primary title remains in the island
                // imageTextInfo payload, so restore it before displaying the focus text.
                String islandTitle = findPrimaryIslandTitle(v2.optJSONObject("param_island"));
                if (!TextUtils.isEmpty(islandTitle) && !result.startsWith(islandTitle)) {
                    result = joinText(islandTitle, result);
                    log("restored missing baseInfo title from param_island=" + islandTitle);
                }
                String islandExtra = findIslandText(v2.optJSONObject("param_island"));
                String hintExtra = joinTexts(hint, "hintInfo", "title", "content", "subContent");
                result = appendDistinctText(result, hintExtra);
                if (!TextUtils.isEmpty(hintExtra)) {
                    log("merged hintInfo content into baseInfo");
                }
                if (!TextUtils.isEmpty(islandExtra)) {
                    String merged = appendDistinctText(result, islandExtra);
                    if (!TextUtils.equals(result, merged)) {
                        result = merged;
                        log("merged additional param_island content into baseInfo");
                    }
                }
            }
            if (!TextUtils.isEmpty(result)) source = "baseInfo";
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(highlight, "title", "content", "subContent");
                if (!TextUtils.isEmpty(result)) source = "highlightInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(highlightV3, "primaryText", "secondaryText", "highLightText",
                        "label");
                if (!TextUtils.isEmpty(result)) source = "highlightInfoV3";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(chat, "title", "content");
                if (!TextUtils.isEmpty(result)) source = "chatInfo";
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject iconText = v2.optJSONObject("iconTextInfo");
                result = joinCompact(firstText(iconText, "title"), firstText(iconText, "content"));
                result = joinCompact(result, firstText(iconText, "subContent"));
                if (!TextUtils.isEmpty(result)) source = "iconTextInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("animTextInfo"), "title", "content");
                if (!TextUtils.isEmpty(result)) source = "animTextInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("coverInfo"), "title", "content", "subContent");
                if (!TextUtils.isEmpty(result)) source = "coverInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(hint, "title", "subTitle", "content", "subContent");
                if (!TextUtils.isEmpty(result)) {
                    String aodTitle = firstText(v2, "aodTitle");
                    if (!TextUtils.isEmpty(aodTitle)) {
                        result = joinText(aodTitle, result);
                    } else {
                        String ticker = cleanText(v2.optString("ticker", null));
                        if (!TextUtils.isEmpty(ticker)) result = ticker;
                    }
                    source = "hintInfo";
                }
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject multiProgress = v2.optJSONObject("multiProgressInfo");
                result = progressText(multiProgress);
                if (!TextUtils.isEmpty(result)) source = "multiProgressInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = progressText(v2.optJSONObject("progressInfo"));
                if (!TextUtils.isEmpty(result)) source = "progressInfo";
            }
            if (TextUtils.isEmpty(result)) {
                result = joinTexts(v2.optJSONObject("stepInfo"), "title", "content", "subContent", "step");
                if (!TextUtils.isEmpty(result)) source = "stepInfo";
            }
            if (TextUtils.isEmpty(result)) {
                JSONObject island = v2.optJSONObject("param_island");
                result = findIslandText(island);
                if (!TextUtils.isEmpty(result)) source = "param_island";
            }
            if (TextUtils.isEmpty(result)) {
                result = cleanText(v2.optString("ticker", null));
                if (!TextUtils.isEmpty(result)) source = "ticker";
            }
            if (TextUtils.isEmpty(result) && v2 != root) {
                result = cleanText(root.optString("ticker", null));
                if (!TextUtils.isEmpty(result)) source = "custom.ticker";
            }
            if (TextUtils.isEmpty(result)) return null;
            log("island content source=" + source + " text=" + result);
            return new IslandText(result, source);
        } catch (Throwable t) {
            log("island param parse failed");
            return null;
        }
    }

    private String joinTexts(JSONObject object, String ignoredSource, String... keys) {
        if (object == null) return null;
        String result = null;
        for (String key : keys) result = joinText(result, firstText(object, key));
        return result;
    }

    private String progressText(JSONObject object) {
        if (object == null) return null;
        String result = joinText(firstText(object, "title", "content", "label"),
                percentageText(object));
        if (!TextUtils.isEmpty(result)) return result;
        JSONObject nested = object.optJSONObject("progressInfo");
        return nested == null ? null : joinText(firstText(nested, "title", "content", "label"),
                percentageText(nested));
    }

    private static String percentageText(JSONObject object) {
        if (object == null || !object.has("progress")) return null;
        Object value = object.opt("progress");
        if (value == null || value == JSONObject.NULL) return null;
        String text = cleanText(String.valueOf(value));
        return TextUtils.isEmpty(text) ? null : (text.endsWith("%") ? text : text + "%");
    }

    private static String sourceFor(JSONObject v2, String result) {
        if (v2.has("baseInfo")) return "baseInfo";
        if (v2.has("highlightInfo")) return "highlightInfo";
        if (v2.has("chatInfo")) return "chatInfo";
        if (v2.has("hintInfo")) return "hintInfo";
        if (v2.has("multiProgressInfo")) return "multiProgressInfo";
        if (v2.has("param_island")) return "param_island";
        return "ticker";
    }

    private String findIslandText(JSONObject island) {
        if (island == null) return null;
        String result = null;
        result = appendDistinctText(result, joinTexts(island, "param_island", "title", "content", "frontTitle"));

        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        String leftText = joinTexts(text, "imageTextInfoLeft", "frontTitle", "title", "content", "subContent");

        // BigIslandArea is explicitly a two-sided payload. The side separator is
        // reserved for the boundary between the left and right areas.
        JSONObject right = big == null ? null : big.optJSONObject("imageTextInfoRight");
        text = right == null ? null : firstObject(right, "textInfo", "miui.focus.paramtextInfo");
        String rightText = joinTexts(text, "imageTextInfoRight", "frontTitle", "title", "content", "subContent");
        String sideText = appendSideText(leftText, rightText);
        result = appendDistinctText(result, sideText);

        result = appendDistinctText(result,
                progressText(big == null ? null : firstObject(big,
                        "progressTextInfo", "fixedWidthDigitInfo", "sameWidthDigitInfo")));
        JSONObject small = island.optJSONObject("smallIslandArea");
        result = appendDistinctText(result,
                joinTexts(small, "smallIslandArea", "title", "content", "subContent"));
        return result;
    }

    private String appendSideText(String first, String second) {
        return appendDistinctText(first, second, currentSettings.sideSeparator);
    }

    private String appendDistinctText(String first, String second) {
        return appendDistinctText(first, second, currentSettings.generalSeparator);
    }

    private static String appendDistinctText(String first, String second, String separator) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(second)) return first;
        if (TextUtils.isEmpty(first)) return second;
        if (first.equals(second) || first.contains(second)) return first;
        if (second.contains(first)) return second;
        return first + separator + second;
    }

    private static String findPrimaryIslandTitle(JSONObject island) {
        if (island == null) return null;
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        return firstText(text, "title", "frontTitle", "content");
    }

    private static JSONObject firstObject(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = cleanText(object.optString(key, null));
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private String joinCompact(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + currentSettings.generalSeparator + second;
    }

    private String joinText(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + currentSettings.generalSeparator + second;
    }

    private static String cleanText(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.length() == 0 ? null : value;
    }

    private FocusData inspectBean(Object bean) {
        if (bean == null) return null;
        try {
            FocusData data = inspectExpanded(getField(bean, "sbn"));
            if (data == null) data = new FocusData();
            data.key = stringValue(getField(bean, "notifKey"));
            if (TextUtils.isEmpty(data.packageName)) data.packageName = packageFromKey(data.key);
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
            data.packageName = notificationPackageName(expanded);
            boolean preMarked = preMarkedIslands.contains(expanded);
            boolean originalFocusField = getBooleanField(expanded, "mIsFocusNotification", false);
            data.isFocus = originalFocusField;

            Notification notification = null;
            try {
                notification = (Notification) XposedHelpers.callMethod(expanded, "getNotification");
            } catch (Throwable ignored) {
                Object sbnNotification = invokeNoArg(expanded, "getNotification");
                if (sbnNotification instanceof Notification) notification = (Notification) sbnNotification;
            }

            if (notification == null || notification.extras == null) return data;
            Bundle extras = notification.extras;
            boolean explicitFocus = extras.getBoolean("miui.focus.isFocus", false);
            data.isFocus = data.isFocus || explicitFocus;
            data.islandParam = extras.getString("miui.focus.param");
            if (TextUtils.isEmpty(data.islandParam)) {
                data.islandParam = extras.getString("miui.focus.param.custom");
            }
            data.hasIslandParam = !TextUtils.isEmpty(data.islandParam);
            data.ticker = extras.getString("miui.focus.ticker");
            data.mainRv = getRemoteViews(extras, "miui.focus.rv");
            data.mainNightRv = getRemoteViews(extras, "miui.focus.rvNight");
            data.barRv = getRemoteViews(extras, "miui.focus.rvBar");
            data.barNightRv = getRemoteViews(extras, "miui.focus.rvBarNight");
            data.hasMainRv = data.mainRv != null;
            data.hasBarRv = data.barRv != null;
            boolean hasTicker = !TextUtils.isEmpty(data.ticker);
            data.hasExplicitFocusData = FocusPriorityPolicy.hasExplicitFocusData(
                    explicitFocus, data.hasMainRv, data.hasBarRv, hasTicker, data.hasIslandParam);
            data.isOriginalFocus = FocusPriorityPolicy.isOriginalFocus(
                    originalFocusField, explicitFocus, data.hasMainRv, data.hasBarRv,
                    hasTicker, data.hasIslandParam);
            if (preMarked) data.isOriginalFocus = false;
            return data;
        } catch (Throwable t) {
            error("inspectExpanded", t);
            return null;
        }
    }

    private static String notificationPackageName(Object expanded) {
        try {
            Object value = invokeNoArg(expanded, "getPackageName");
            if (value != null) return String.valueOf(value);
        } catch (Throwable ignored) {
        }
        try {
            Object sbn = getField(expanded, "mSbn");
            if (sbn == null) sbn = getField(expanded, "sbn");
            if (sbn != null) {
                Object value = invokeNoArg(sbn, "getPackageName");
                if (value != null) return String.valueOf(value);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String packageFromKey(String key) {
        if (TextUtils.isEmpty(key)) return null;
        String[] parts = key.split("\\|", 5);
        return parts.length > 1 && parts[1].length() > 0 ? parts[1] : null;
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

    private void clearPreMark(Object expanded, boolean restoreOriginal) {
        if (expanded == null) return;
        Boolean original = preMarkedOriginalFocus.remove(expanded);
        preMarkedIslands.remove(expanded);
        if (restoreOriginal && original != null) {
            try {
                XposedHelpers.setBooleanField(expanded, "mIsFocusNotification", original);
            } catch (Throwable t) {
                error("restorePreMarkedFocus", t);
            }
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

    private static final class OriginalBeanState {
        final Object expanded;
        final boolean originalFocus;
        String originalContent;
        String lastConvertedContent;

        OriginalBeanState(Object expanded, boolean originalFocus, String originalContent) {
            this.expanded = expanded;
            this.originalFocus = originalFocus;
            this.originalContent = originalContent;
        }
    }

    private static final class IslandText {
        final String text;
        final String source;

        IslandText(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    private static final class FocusData {
        boolean isFocus;
        boolean isOriginalFocus;
        boolean hasMainRv;
        boolean hasBarRv;
        boolean hasIslandParam;
        boolean hasExplicitFocusData;
        String islandParam;
        String key;
        String packageName;
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
                    + " islandParam=" + hasIslandParam
                    + " contentRv=" + (contentRv != null)
                    + " contentNightRv=" + (contentNightRv != null);
        }
    }
}
