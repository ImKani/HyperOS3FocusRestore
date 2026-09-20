package com.hyperos3.focusrestore;

import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import org.json.JSONObject;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
    private static final long MARQUEE_ATTACH_TIMEOUT_MS = 2000L;
    private static final int MAX_CONVERTED_KEYS = 128;
    private static final String[] REMOTE_VIEWS_CONTAINER_FIELDS = {
            "mRemoteView", "mRemoteViews", "mRemoteViewContainer",
            "mContentRemoteView", "mContentRemoteViews", "mCustomViewContainer"
    };

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
    private boolean modeHooksInstalled;
    private int installedHookMode;
    private HyperOS4FocusController os4Controller;
    private volatile Object dynamicIslandTouchHandler;
    private volatile Object miuiShadeTouchHandler;
    private Handler mainHandler;
    private TextView pendingMarqueeText;
    private Runnable pendingMarqueeRunnable;
    private View.OnAttachStateChangeListener pendingMarqueeAttachListener;
    private Runnable pendingMarqueeAttachTimeout;
    private TextView activeMarqueeText;
    private View.OnAttachStateChangeListener activeMarqueeDetachListener;
    private ValueAnimator fallbackMarqueeAnimator;
    private long marqueeGeneration;
    private final Map<TextView, OriginalWidthState> originalTextWidths =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, ParentWidthState> originalParentWidths =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> convertedBeans = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, Boolean> preMarkedOriginalFocus = Collections.synchronizedMap(
            new WeakHashMap<>());
    private final Set<Object> preMarkedIslands = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, OriginalBeanState> originalBeanStates =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> remoteViewsHiddenPrompts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Map<View, Integer>> remoteViewsHiddenContainers =
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
        log("entry loaded in " + lpparam.packageName + "/" + lpparam.processName);
        hookApplicationAttach();
        hookDynamicIslandSystemProperty();
        disableDynamicIslandFeatureCache();
        log("loading in " + lpparam.packageName + "/" + lpparam.processName
                + " awaiting persisted hook mode; default=OS"
                + FocusRestoreSettings.DEFAULT_HOOK_MODE);
    }

    private void logCapabilities(int hookMode) {
        Class<?> focusUtils = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.notification.utils.FocusUtils");
        if (hookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
            Class<?> pipeline = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.NotifPipeline");
            Class<?> statusBar = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            log("capabilities configuredMode=OS4 installedMode=OS4 "
                    + FocusReflection.capability(focusUtils, "showOnStatusBar")
                    + " " + FocusReflection.capability(pipeline, "addCollectionListener")
                    + " " + FocusReflection.capability(statusBar, "onFinishInflate"));
            return;
        }
        Class<?> promptView = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.phone.FocusedNotifPromptView");
        Class<?> focusedText = FocusReflection.findClass(classLoader,
                "com.android.systemui.statusbar.widget.FocusedTextView");
        log("capabilities configuredMode=OS3 installedMode=OS3 "
                + FocusReflection.capability(focusUtils, "showOnStatusBar")
                + " " + FocusReflection.capability(promptView, "setData")
                + " " + FocusReflection.capability(promptView, "onFocusNotifPromptClicked")
                + " " + FocusReflection.capability(focusedText, "startMarqueeLocal"));
    }

    private synchronized void installConfiguredModeHooks() {
        if (modeHooksInstalled) {
            log("mode hooks already installed installedMode=OS" + installedHookMode
                    + " configuredMode=OS" + currentSettings.hookMode);
            return;
        }
        installedHookMode = currentSettings.hookMode;
        modeHooksInstalled = true;
        hookDynamicIslandTouchHandler();
        log("installing configuredMode=OS" + installedHookMode
                + " settings=" + currentSettings.describe());
        try {
            logCapabilities(installedHookMode);
        } catch (Throwable throwable) {
            error("logCapabilities", throwable);
        }
        if (installedHookMode == FocusRestoreSettings.HOOK_MODE_OS4) {
            installOS4Hooks();
        } else {
            installOS3Hooks();
        }
    }

    private void installOS3Hooks() {
        hookShowOnStatusBar();
        hookPromptViewSetData();
        hookFocusedParentParams();
        hookFocusedTextMarquee();
        hookPromptShouldShow();
        hookDisableConvertedFocusClick();
        hookRemoteViewsErrors();
        log("installedMode=OS3");
    }

    private void installOS4Hooks() {
        Context context = systemUiContext;
        if (context == null) {
            error("installOS4Hooks", new IllegalStateException("SystemUI context unavailable"));
            return;
        }
        os4Controller = new HyperOS4FocusController(classLoader, context,
                new HyperOS4FocusController.ItemFactory() {
                    @Override
                    public HyperOS4FocusController.DisplayItem create(Object notificationEntry) {
                        return createOS4DisplayItem(notificationEntry);
                    }

                    @Override
                    public HookSettings settings() {
                        return currentSettings;
                    }

                    @Override
                    public void expandIsland(View source, String key) {
                        dispatchIslandTap(source, key, "OS4");
                    }
                }, new HyperOS4FocusController.Logger() {
                    @Override
                    public void log(String message) {
                        HyperOS3FocusRestoreHook.this.log(message);
                    }

                    @Override
                    public void error(String stage, Throwable throwable) {
                        HyperOS3FocusRestoreHook.this.error(stage, throwable);
                    }
                });
        os4Controller.install();
        log("installedMode=OS4");
    }

    private void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Context) {
                                Context attachedContext = (Context) param.args[0];
                                Context applicationContext = attachedContext.getApplicationContext();
                                systemUiContext = applicationContext != null
                                        ? applicationContext : attachedContext;
                                mainHandler = new Handler(attachedContext.getMainLooper());
                                log("SystemUI attach context available class="
                                        + systemUiContext.getClass().getName()
                                        + " applicationContext=" + (applicationContext != null)
                                        + "; loading persisted hook mode");
                                if (reloadSettings(true)) {
                                    installConfiguredModeHooks();
                                } else {
                                    log("mode hooks not installed: persisted settings unavailable; "
                                            + "restart SystemUI or device after settings storage is available");
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            error("hookApplicationAttach", t);
        }
    }

    private void hookDynamicIslandTouchHandler() {
        try {
            Class<?> touchHandlerClass = FocusReflection.findClass(classLoader,
                    "com.miui.systemui.notification.island.DynamicIslandTouchHandlerImpl");
            XposedBridge.hookAllConstructors(touchHandlerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dynamicIslandTouchHandler = param.thisObject;
                    log("dynamic island touch handler captured");
                }
            });
            Class<?> shadeHandlerClass = FocusReflection.findClass(classLoader,
                    "com.miui.systemui.shade.MiuiShadeTouchHandlerImpl");
            XposedBridge.hookAllConstructors(shadeHandlerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    miuiShadeTouchHandler = param.thisObject;
                    log("MIUI shade touch handler captured");
                }
            });
        } catch (Throwable throwable) {
            error("hookDynamicIslandTouchHandler", throwable);
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

    private synchronized boolean reloadSettings(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastProviderReadAttemptMs != Long.MIN_VALUE
                && now - lastProviderReadAttemptMs < SETTINGS_REFRESH_INTERVAL_MS) {
            return hasSuccessfulProviderSettings;
        }
        lastProviderReadAttemptMs = now;
        if (!force && settingsReadQueued) return hasSuccessfulProviderSettings;
        final long generation = ++settingsReadGeneration;
        if (force) {
            synchronized (SETTINGS_READ_LOCK) {
                return readProviderSettings(generation);
            }
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
        return hasSuccessfulProviderSettings;
    }

    private boolean readProviderSettings(long generation) {
        try {
            Context context = systemUiContext;
            if (context == null) {
                Object currentApplication = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (currentApplication instanceof Context) {
                    Context application = (Context) currentApplication;
                    Context applicationContext = application.getApplicationContext();
                    context = applicationContext != null ? applicationContext : application;
                    systemUiContext = context;
                }
            }
            if (context == null) {
                logProviderSettingsState(false, "application context unavailable");
                return false;
            }
            HookSettings next = HookSettingsReader.read(context);
            if (next == null) {
                logProviderSettingsState(false, "provider query returned no settings");
                return false;
            }
            synchronized (this) {
                if (generation != settingsReadGeneration) return false;
                currentSettings = next;
                if (modeHooksInstalled && next.hookMode != installedHookMode) {
                    log("hook mode change saved configuredMode=OS" + next.hookMode
                            + " installedMode=OS" + installedHookMode
                            + "; restart SystemUI or device to apply");
                }
            }
            hasSuccessfulProviderSettings = true;
            logProviderSettingsState(true, null);
            return true;
        } catch (Throwable t) {
            logProviderSettingsState(false, t.getClass().getSimpleName());
            error("readProviderSettings", t);
            return false;
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
            ViewGroup.LayoutParams params = textView.getLayoutParams();
            if (!currentSettings.limitWidth) {
                OriginalWidthState original;
                synchronized (originalTextWidths) {
                    original = originalTextWidths.remove(textView);
                }
                if (original == null) return;
                boolean changed = textView.getMaxWidth() != original.maxWidth;
                if (changed) textView.setMaxWidth(original.maxWidth);
                if (params != null && original.hasLayoutParams
                        && params.width != original.layoutWidth) {
                    params.width = original.layoutWidth;
                    textView.setLayoutParams(params);
                    changed = true;
                }
                if (changed) textView.requestLayout();
                log("restored system focus text width maxWidth=" + original.maxWidth
                        + " layoutWidth=" + (original.hasLayoutParams
                        ? original.layoutWidth : "unavailable"));
                return;
            }
            float density = textView.getResources().getDisplayMetrics().density;
            int widthPx = Math.max(1, Math.round(currentSettings.widthDp * density));
            synchronized (originalTextWidths) {
                OriginalWidthState original = originalTextWidths.get(textView);
                if (original == null) {
                    original = new OriginalWidthState(textView.getMaxWidth(),
                            params == null ? 0 : params.width, params != null, widthPx);
                    originalTextWidths.put(textView, original);
                } else {
                    if (textView.getMaxWidth() != original.lastAppliedWidth) {
                        original.maxWidth = textView.getMaxWidth();
                    }
                    if (params != null && params.width != original.lastAppliedWidth) {
                        original.layoutWidth = params.width;
                        original.hasLayoutParams = true;
                    }
                    original.lastAppliedWidth = widthPx;
                }
            }
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
            registerActiveMarqueeOwner(textView);
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

    private synchronized void registerActiveMarqueeOwner(final TextView textView) {
        if (activeMarqueeText == textView && activeMarqueeDetachListener != null) return;
        clearActiveMarqueeOwnerLocked();
        activeMarqueeText = textView;
        activeMarqueeDetachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    if (activeMarqueeText == textView) clearActiveMarqueeOwnerLocked();
                }
            }
        };
        textView.addOnAttachStateChangeListener(activeMarqueeDetachListener);
    }

    private void clearActiveMarqueeOwnerLocked() {
        TextView textView = activeMarqueeText;
        if (textView != null && activeMarqueeDetachListener != null) {
            textView.removeOnAttachStateChangeListener(activeMarqueeDetachListener);
        }
        activeMarqueeText = null;
        activeMarqueeDetachListener = null;
        if (fallbackMarqueeAnimator != null) {
            fallbackMarqueeAnimator.cancel();
            fallbackMarqueeAnimator = null;
        }
        if (textView != null) {
            stopNativeMarquee(textView);
            textView.scrollTo(0, 0);
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
        clearPendingMarqueeLocked();
        final long generation = ++marqueeGeneration;
        clearActiveMarqueeOwnerLocked();
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            final TextView textView = (TextView) value;
            textView.scrollTo(0, 0);
            pendingMarqueeText = textView;
            pendingMarqueeRunnable = new Runnable() {
                private int attempts;

                @Override public void run() {
                    synchronized (HyperOS3FocusRestoreHook.this) {
                        if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                    }
                    if (Build.VERSION.SDK_INT >= 19 && !textView.isAttachedToWindow()) {
                        waitForMarqueeAttach(textView, generation, this);
                        return;
                    }
                    attempts++;
                    if (textView.getText() == null || textView.getText().length() == 0) {
                        if (attempts < 3) {
                            textView.postDelayed(this, 100L);
                        } else {
                            finishPendingMarquee(textView, generation);
                        }
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
                        return;
                    }
                    finishPendingMarquee(textView, generation);
                }
            };
            textView.postDelayed(pendingMarqueeRunnable,
                    Math.max(0, Math.min(5000, currentSettings.marqueeDelayMs)));
            log("scheduled native focus marquee delayMs=" + currentSettings.marqueeDelayMs);
        } catch (Throwable t) {
            error("scheduleNativeMarquee", t);
        }
    }

    private synchronized void cancelCurrentMarquee() {
        clearPendingMarqueeLocked();
        marqueeGeneration++;
        clearActiveMarqueeOwnerLocked();
    }

    private synchronized void waitForMarqueeAttach(final TextView textView,
                                                    final long generation,
                                                    final Runnable startRunnable) {
        if (generation != marqueeGeneration || pendingMarqueeText != textView
                || pendingMarqueeAttachListener != null) return;
        pendingMarqueeAttachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                synchronized (HyperOS3FocusRestoreHook.this) {
                    if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                    clearMarqueeAttachWaitLocked();
                }
                mainHandler.post(startRunnable);
            }

            @Override public void onViewDetachedFromWindow(View view) {
            }
        };
        pendingMarqueeAttachTimeout = () -> {
            synchronized (HyperOS3FocusRestoreHook.this) {
                if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
                clearMarqueeAttachWaitLocked();
                pendingMarqueeText = null;
                pendingMarqueeRunnable = null;
            }
            log("focus marquee attach wait timed out");
        };
        textView.addOnAttachStateChangeListener(pendingMarqueeAttachListener);
        mainHandler.postDelayed(pendingMarqueeAttachTimeout, MARQUEE_ATTACH_TIMEOUT_MS);
        log("waiting for focus text attach before marquee");
    }

    private synchronized void finishPendingMarquee(TextView textView, long generation) {
        if (generation != marqueeGeneration || pendingMarqueeText != textView) return;
        clearMarqueeAttachWaitLocked();
        pendingMarqueeText = null;
        pendingMarqueeRunnable = null;
    }

    private void clearPendingMarqueeLocked() {
        if (pendingMarqueeText != null && pendingMarqueeRunnable != null) {
            pendingMarqueeText.removeCallbacks(pendingMarqueeRunnable);
        }
        clearMarqueeAttachWaitLocked();
        pendingMarqueeText = null;
        pendingMarqueeRunnable = null;
    }

    private void clearMarqueeAttachWaitLocked() {
        if (pendingMarqueeText != null && pendingMarqueeAttachListener != null) {
            pendingMarqueeText.removeOnAttachStateChangeListener(pendingMarqueeAttachListener);
        }
        if (pendingMarqueeAttachTimeout != null) {
            mainHandler.removeCallbacks(pendingMarqueeAttachTimeout);
        }
        pendingMarqueeAttachListener = null;
        pendingMarqueeAttachTimeout = null;
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
                synchronized (originalParentWidths) {
                    ParentWidthState original = originalParentWidths.get(parent);
                    if (original == null) {
                        originalParentWidths.put(parent,
                                new ParentWidthState(params.width, widthPx));
                    } else {
                        if (params.width != original.lastAppliedWidth) {
                            original.originalWidth = params.width;
                        }
                        original.lastAppliedWidth = widthPx;
                    }
                }
                if (params.width != widthPx) {
                    params.width = widthPx;
                    parent.setLayoutParams(params);
                    log("applied 0.4 manual focus parent width=" + currentSettings.widthDp
                            + "dp px=" + widthPx);
                }
            } else {
                ParentWidthState original;
                synchronized (originalParentWidths) {
                    original = originalParentWidths.remove(parent);
                }
                if (original != null && params.width != original.originalWidth) {
                    params.width = original.originalWidth;
                    parent.setLayoutParams(params);
                    log("restored system focus parent width=" + original.originalWidth);
                }
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
                                if (currentSettings.expandIslandOnClick
                                        && param.thisObject instanceof View) {
                                    dispatchIslandTap((View) param.thisObject,
                                            data == null ? null : data.key, "OS3");
                                } else {
                                    log("ignored focus click key=" + (data == null ? null : data.key)
                                            + " converted=" + converted);
                                }
                            }
                        }
                    });
            log("disabled converted focus click");
        } catch (Throwable t) {
            error("hookDisableConvertedFocusClick", t);
        }
    }

    private void dispatchIslandTap(View source, String key, String mode) {
        MotionEvent down = null;
        MotionEvent up = null;
        try {
            int[] location = new int[2];
            source.getLocationOnScreen(location);
            float x = location[0] + source.getWidth() / 2f;
            float y = location[1] + source.getHeight() / 2f;
            long time = SystemClock.uptimeMillis();
            down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
            up = MotionEvent.obtain(time, time + 32L, MotionEvent.ACTION_UP, x, y, 0);
            if (dispatchViaShadeTouchHandler(down, up, key, mode)) return;

            Class<?> dependencyClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.Dependency");
            Class<?> touchHandlerClass = FocusReflection.findClass(classLoader,
                    "com.miui.systemui.notification.island.DynamicIslandTouchHandlerImpl");
            Object touchHandler = dynamicIslandTouchHandler;
            try {
                if (touchHandler == null) touchHandler = XposedHelpers.callStaticMethod(
                        dependencyClass, "get", touchHandlerClass);
                if (touchHandler == null) {
                    throw new IllegalStateException("Dependency returned null island touch handler");
                }
            } catch (Throwable directFailure) {
                Class<?> pluginControllerClass = FocusReflection.findClass(classLoader,
                        "com.android.systemui.statusbar.notification.DynamicIslandPluginController");
                Object pluginController = XposedHelpers.callStaticMethod(
                        dependencyClass, "get", pluginControllerClass);
                touchHandler = XposedHelpers.newInstance(touchHandlerClass, pluginController);
            }
            Object downIntercept = XposedHelpers.callMethod(touchHandler,
                    "onIntercept", down, "status_bar");
            Object downResult = null;
            Object upResult = null;
            if (Boolean.TRUE.equals(downIntercept)) {
                downResult = XposedHelpers.callMethod(touchHandler,
                        "onTouch", down, "status_bar");
                upResult = XposedHelpers.callMethod(touchHandler,
                        "onTouch", up, "status_bar");
            }
            log(mode + " experimental island tap fallback key=" + key
                    + " downIntercept=" + downIntercept + " down=" + downResult
                    + " up=" + upResult);
        } catch (Throwable throwable) {
            error(mode + " experimental island tap key=" + key, throwable);
        } finally {
            if (down != null) down.recycle();
            if (up != null) up.recycle();
        }
    }

    private boolean dispatchViaShadeTouchHandler(MotionEvent down, MotionEvent up,
                                                  String key, String mode) {
        try {
            Object shadeHandler = miuiShadeTouchHandler;
            if (shadeHandler == null) {
                Class<?> dependencyClass = FocusReflection.findClass(classLoader,
                        "com.android.systemui.Dependency");
                Class<?> shadeHandlerClass = FocusReflection.findClass(classLoader,
                        "com.miui.systemui.shade.MiuiShadeTouchHandlerImpl");
                shadeHandler = XposedHelpers.callStaticMethod(
                        dependencyClass, "get", shadeHandlerClass);
            }
            if (shadeHandler == null) return false;
            Class<?> functionClass = FocusReflection.findClass(classLoader,
                    "kotlin.jvm.functions.Function1");
            Class<?> unitClass = FocusReflection.findClass(classLoader, "kotlin.Unit");
            Object unit = XposedHelpers.getStaticObjectField(unitClass, "INSTANCE");
            Object noOpCallback = Proxy.newProxyInstance(classLoader,
                    new Class<?>[]{functionClass}, (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) return unit;
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        return "FocusRestoreIslandTouchCallback";
                    });
            Object downResult = XposedHelpers.callMethod(shadeHandler,
                    "handleExternalTouch", down, "status_bar", noOpCallback);
            Object upResult = XposedHelpers.callMethod(shadeHandler,
                    "handleExternalTouch", up, "status_bar", noOpCallback);
            log(mode + " experimental island tap key=" + key
                    + " path=shade down=" + downResult + " up=" + upResult);
            return true;
        } catch (Throwable throwable) {
            error(mode + " experimental shade tap fallback key=" + key, throwable);
            return false;
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
                    restoreRemoteViewsPrompt(param.thisObject);
                    Object bean = getField(param.thisObject, "mData");
                    FocusData data = inspectBean(bean);
                    log("updateRemoteViews begin " + (data == null ? "bean=null" : data.summary()));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        Throwable failure = param.getThrowable();
                        error("updateRemoteViews throwable", failure);
                        Object bean = getField(param.thisObject, "mData");
                        FocusData data = inspectBean(bean);
                        String fallback = data == null ? null : data.content;
                        if (TextUtils.isEmpty(fallback) && data != null) fallback = data.ticker;
                        RemoteViewsFailurePolicy.Action action = RemoteViewsFailurePolicy.decide(
                                failure, !TextUtils.isEmpty(fallback));
                        if (action == RemoteViewsFailurePolicy.Action.RETHROW) return;
                        param.setResult(null);
                        Object content = getField(param.thisObject, "mContentText");
                        RemoteViewsFailurePolicy.Action appliedAction = action;
                        if (action == RemoteViewsFailurePolicy.Action.TEXT_FALLBACK
                                && content instanceof TextView) {
                            TextView textView = (TextView) content;
                            restoreRemoteViewsPrompt(param.thisObject);
                            hideKnownRemoteViewsContainers(param.thisObject, textView);
                            textView.setText(fallback);
                            textView.setVisibility(View.VISIBLE);
                            scheduleNativeMarquee(param.thisObject);
                        } else {
                            appliedAction = RemoteViewsFailurePolicy.Action.DROP_CURRENT;
                            if (content instanceof TextView) ((TextView) content).setText(null);
                            hideRemoteViewsPrompt(param.thisObject);
                            cancelCurrentMarquee();
                        }
                        log("updateRemoteViews recovered action=" + appliedAction
                                + " key=" + (data == null ? null : data.key));
                    } else {
                        restoreRemoteViewsPrompt(param.thisObject);
                        log("updateRemoteViews end");
                        scheduleNativeMarquee(param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            error("hookRemoteViewsErrors", t);
        }
    }

    private void hideRemoteViewsPrompt(Object promptObject) {
        if (!(promptObject instanceof View)) return;
        View prompt = (View) promptObject;
        synchronized (remoteViewsHiddenPrompts) {
            if (!remoteViewsHiddenPrompts.containsKey(prompt)) {
                remoteViewsHiddenPrompts.put(prompt, prompt.getVisibility());
            }
        }
        prompt.setVisibility(View.GONE);
    }

    private void restoreRemoteViewsPrompt(Object promptObject) {
        if (!(promptObject instanceof View)) return;
        View prompt = (View) promptObject;
        Integer visibility;
        synchronized (remoteViewsHiddenPrompts) {
            visibility = remoteViewsHiddenPrompts.remove(prompt);
        }
        if (visibility != null) prompt.setVisibility(visibility);
        Map<View, Integer> containers;
        synchronized (remoteViewsHiddenContainers) {
            containers = remoteViewsHiddenContainers.remove(prompt);
        }
        if (containers != null) {
            for (Map.Entry<View, Integer> entry : containers.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
        }
    }

    private void hideKnownRemoteViewsContainers(Object promptObject, TextView contentText) {
        if (!(promptObject instanceof View)) return;
        View prompt = (View) promptObject;
        Map<View, Integer> containers = new WeakHashMap<>();
        int hidden = 0;
        for (String fieldName : REMOTE_VIEWS_CONTAINER_FIELDS) {
            Object value = getField(promptObject, fieldName);
            if (!(value instanceof View)) continue;
            View candidate = (View) value;
            if (candidate == contentText || isViewAncestor(candidate, contentText)
                    || containers.containsKey(candidate)) continue;
            containers.put(candidate, candidate.getVisibility());
            candidate.setVisibility(View.GONE);
            hidden++;
        }
        if (!containers.isEmpty()) {
            synchronized (remoteViewsHiddenContainers) {
                remoteViewsHiddenContainers.put(prompt, containers);
            }
        }
        log("updateRemoteViews textFallback hiddenRemoteContainers=" + hidden);
    }

    private static boolean isViewAncestor(View ancestor, View child) {
        ViewParent parent = child == null ? null : child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
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
                || !InputLimits.isPayloadAllowed(data.islandParam)) return false;
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
                        state = new OriginalBeanState(expanded, originalFocus, current,
                                getField(bean, "icon"), getField(bean, "iconDark"),
                                getField(bean, "drawable"), getField(bean, "drawableDark"));
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
                applyConvertedBeanIcon(bean, state, data, stage);
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

    private void applyConvertedBeanIcon(Object bean, OriginalBeanState state,
                                        FocusData data, String stage) {
        SelectedFocusIcon light = selectFocusIcon(data.notification, data.islandParam,
                false, false);
        if (light == null) {
            restoreConvertedBeanIcon(bean, state, stage);
            return;
        }
        if (systemUiContext == null) return;
        SelectedFocusIcon dark = selectFocusIcon(data.notification, data.islandParam,
                true, false);
        if (dark == null) dark = light;
        final FocusIconStyler.Result styledLight;
        final FocusIconStyler.Result styledDark;
        try {
            styledLight = FocusIconStyler.load(systemUiContext, light.icon,
                    light.islandIcon, light.tint, Color.WHITE, 18);
            styledDark = FocusIconStyler.load(systemUiContext, dark.icon,
                    dark.islandIcon, dark.tint, Color.BLACK, 18);
            if (styledLight == null || styledDark == null) {
                log(stage + " island focus icon load returned null package=" + data.packageName
                        + " lightType=" + light.icon.getType()
                        + " darkType=" + dark.icon.getType());
                restoreConvertedBeanIcon(bean, state, stage + " nullIslandIcon");
                return;
            }
        } catch (Throwable throwable) {
            error(stage + " loadIslandIcon", throwable);
            return;
        }
        Icon lightIcon = styledLight.icon;
        Icon darkIcon = styledDark.icon;
        Drawable drawable = styledLight.drawable;
        Drawable drawableDark = styledDark.drawable;

        refreshOriginalBeanIconState(bean, state);
        boolean success = true;
        if (hasField(bean, "icon")) {
            state.patchedIcon = setObjectField(bean, "icon", lightIcon,
                    stage + " setIcon");
            if (state.patchedIcon) state.lastConvertedIcon = lightIcon;
            else success = false;
        }
        if (hasField(bean, "iconDark")) {
            state.patchedIconDark = setObjectField(bean, "iconDark", darkIcon,
                    stage + " setIconDark");
            if (state.patchedIconDark) state.lastConvertedIconDark = darkIcon;
            else success = false;
        }
        if (hasField(bean, "drawable")) {
            state.patchedDrawable = setObjectField(bean, "drawable", drawable,
                    stage + " setDrawable");
            if (state.patchedDrawable) state.lastConvertedDrawable = drawable;
            else success = false;
        }
        if (hasField(bean, "drawableDark")) {
            state.patchedDrawableDark = setObjectField(bean, "drawableDark", drawableDark,
                    stage + " setDrawableDark");
            if (state.patchedDrawableDark) state.lastConvertedDrawableDark = drawableDark;
            else success = false;
        }
        if (!success) {
            restoreConvertedBeanIcon(bean, state, stage + " rollbackIslandIcon");
            return;
        }
        log(stage + " applied island focus icon light=" + light.source
                + " dark=" + dark.source);
    }

    private void refreshOriginalBeanIconState(Object bean, OriginalBeanState state) {
        Object current = getField(bean, "icon");
        if (!state.patchedIcon || current != state.lastConvertedIcon) state.originalIcon = current;
        current = getField(bean, "iconDark");
        if (!state.patchedIconDark || current != state.lastConvertedIconDark) {
            state.originalIconDark = current;
        }
        current = getField(bean, "drawable");
        if (!state.patchedDrawable || current != state.lastConvertedDrawable) {
            state.originalDrawable = current;
        }
        current = getField(bean, "drawableDark");
        if (!state.patchedDrawableDark || current != state.lastConvertedDrawableDark) {
            state.originalDrawableDark = current;
        }
    }

    private boolean restoreConvertedBeanIcon(Object bean, OriginalBeanState state, String stage) {
        refreshOriginalBeanIconState(bean, state);
        boolean success = true;
        if (state.patchedIcon) {
            boolean restored = setObjectField(bean, "icon", state.originalIcon, stage + " icon");
            state.patchedIcon = !restored;
            if (restored) state.lastConvertedIcon = null;
            success &= restored;
        }
        if (state.patchedIconDark) {
            boolean restored = setObjectField(bean, "iconDark", state.originalIconDark,
                    stage + " iconDark");
            state.patchedIconDark = !restored;
            if (restored) state.lastConvertedIconDark = null;
            success &= restored;
        }
        if (state.patchedDrawable) {
            boolean restored = setObjectField(bean, "drawable", state.originalDrawable,
                    stage + " drawable");
            state.patchedDrawable = !restored;
            if (restored) state.lastConvertedDrawable = null;
            success &= restored;
        }
        if (state.patchedDrawableDark) {
            boolean restored = setObjectField(bean, "drawableDark", state.originalDrawableDark,
                    stage + " drawableDark");
            state.patchedDrawableDark = !restored;
            if (restored) state.lastConvertedDrawableDark = null;
            success &= restored;
        }
        return success;
    }

    private void restoreOriginalBean(Object bean, FocusData data, String stage) {
        OriginalBeanState state;
        synchronized (originalBeanStates) {
            state = originalBeanStates.get(bean);
        }
        if (state == null) return;
        clearPreMark(state.expanded, false);
        boolean contentRestored = setObjectField(bean, "content", state.originalContent,
                stage + " restoreContent");
        boolean iconRestored = restoreConvertedBeanIcon(bean, state, stage + " restoreIcon");
        boolean focusRestored = true;
        if (state.expanded != null && hasField(state.expanded, "mIsFocusNotification")) {
            try {
                XposedHelpers.setBooleanField(state.expanded, "mIsFocusNotification",
                        state.originalFocus);
            } catch (Throwable throwable) {
                focusRestored = false;
                error(stage + " restoreFocusField", throwable);
            }
        }
        if (data != null) {
            if (contentRestored) data.content = state.originalContent;
            if (focusRestored) data.isFocus = state.originalFocus;
        }
        if (contentRestored && iconRestored && focusRestored) {
            synchronized (originalBeanStates) {
                if (originalBeanStates.get(bean) == state) originalBeanStates.remove(bean);
            }
            convertedBeans.remove(bean);
            log(stage + " restored original focus content and icon");
        }
    }

    private static boolean hasField(Object target, String fieldName) {
        if (target == null) return false;
        try {
            return XposedHelpers.findFieldIfExists(target.getClass(), fieldName) != null;
        } catch (Throwable throwable) {
            error("probe field " + fieldName, throwable);
            return false;
        }
    }

    private static boolean setObjectField(Object target, String fieldName, Object value,
                                          String stage) {
        try {
            XposedHelpers.setObjectField(target, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            error(stage, throwable);
            return false;
        }
    }

    private IslandText extractIslandContent(FocusData data) {
        if (data == null || TextUtils.isEmpty(data.islandParam)) return null;
        IslandPayloadParser.ParsedText parsed = IslandPayloadParser.parse(
                data.islandParam, currentSettings.generalSeparator, currentSettings.sideSeparator);
        return parsed == null ? null : new IslandText(parsed.text, parsed.source);
    }

    private SelectedFocusIcon selectFocusIcon(Notification notification, String islandParam,
                                                boolean dark, boolean allowSmallFallback) {
        if (notification == null) return null;
        Bundle extras = notification.extras;
        Bundle pictures = extras == null ? null : extras.getBundle("miui.focus.pics");
        String directReference = extras == null ? null : extras.getString(
                dark ? "miui.focus.pic_ticker_dark" : "miui.focus.pic_ticker");
        Icon icon = null;
        if (currentSettings.showIslandIcon) {
            icon = iconFromBundle(pictures, directReference);
            if (icon != null) return new SelectedFocusIcon(icon,
                    currentSettings.tintIslandIcon, true, "ticker:" + directReference);

            String payloadReference = IslandPayloadParser.findPictureReference(islandParam, dark);
            icon = iconFromBundle(pictures, payloadReference);
            if (icon != null) return new SelectedFocusIcon(icon,
                    currentSettings.tintIslandIcon, true, "island:" + payloadReference);
        }

        if (dark && currentSettings.showIslandIcon) {
            String lightReference = extras == null ? null
                    : extras.getString("miui.focus.pic_ticker");
            icon = iconFromBundle(pictures, lightReference);
            if (icon != null) return new SelectedFocusIcon(icon,
                    currentSettings.tintIslandIcon, true,
                    "tickerLight:" + lightReference);
        }
        if (!allowSmallFallback) return null;
        icon = notification.getSmallIcon();
        return icon == null ? null : new SelectedFocusIcon(icon, false, false,
                "notificationSmallIcon");
    }

    private static Icon iconFromBundle(Bundle pictures, String reference) {
        if (pictures == null || TextUtils.isEmpty(reference)) return null;
        try {
            Parcelable value = pictures.getParcelable(reference);
            return value instanceof Icon ? (Icon) value : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private HyperOS4FocusController.DisplayItem createOS4DisplayItem(Object entry) {
        reloadSettings(false);
        Object expanded = getField(entry, "mSbn");
        if (expanded == null) expanded = getField(entry, "sbn");
        if (expanded == null) return null;
        FocusData data = inspectExpanded(expanded);
        if (data == null) return null;
        Object keyValue = getField(entry, "key");
        if (keyValue == null) keyValue = getField(entry, "mKey");
        String key = stringValue(keyValue);
        if (TextUtils.isEmpty(key)) return null;

        Notification notification = null;
        try {
            Object value = XposedHelpers.callMethod(expanded, "getNotification");
            if (value instanceof Notification) notification = (Notification) value;
        } catch (Throwable t) {
            error("OS4 getNotification key=" + key, t);
        }
        PendingIntent contentIntent = notification == null ? null : notification.contentIntent;

        boolean hasNativeStatusBarContent = OS4FocusPriorityPolicy.hasNativeStatusBarContent(
                data.barRv != null || data.barNightRv != null,
                !TextUtils.isEmpty(data.ticker), data.hasIslandParam);
        log("OS4 classification key=" + key + " package=" + data.packageName
                + " originalFocusField=" + data.originalFocusField
                + " explicitFocus=" + data.explicitFocus
                + " isOriginalFocus=" + data.isOriginalFocus
                + " hasBarRemoteViews=" + (data.barRv != null || data.barNightRv != null)
                + " ticker=" + data.ticker
                + " hasIslandParam=" + data.hasIslandParam
                + " islandParam=" + data.islandParam
                + " nativeStatusBarContent=" + hasNativeStatusBarContent
                + " forcePackage=" + currentSettings.islandForcePackages.contains(data.packageName));
        if (data.isOriginalFocus && hasNativeStatusBarContent) {
            boolean showOnStatusBar = false;
            try {
                Class<?> utils = FocusReflection.findClass(classLoader,
                        "com.android.systemui.statusbar.notification.utils.FocusUtils");
                Object result = XposedHelpers.callStaticMethod(utils, "showOnStatusBar", expanded);
                showOnStatusBar = Boolean.TRUE.equals(result);
            } catch (Throwable t) {
                error("OS4 native showOnStatusBar key=" + key, t);
            }
            if (!showOnStatusBar) {
                log("OS4 native Focus rejected by showOnStatusBar key=" + key
                        + " " + data.summary());
                return null;
            }
            SelectedFocusIcon focusIcon = selectFocusIcon(notification, data.islandParam,
                    false, true);
            SelectedFocusIcon focusIconDark = selectFocusIcon(notification, data.islandParam,
                    true, true);
            log("OS4 native focus icon key=" + key + " light="
                    + (focusIcon == null ? "none" : focusIcon.source) + " dark="
                    + (focusIconDark == null ? "none" : focusIconDark.source));
            return new HyperOS4FocusController.DisplayItem(key, data.packageName,
                    cleanText(data.ticker), "nativeFocus", data.barRv, data.barNightRv,
                    contentIntent, focusIcon == null ? null : focusIcon.icon,
                    focusIconDark == null ? null : focusIconDark.icon,
                    focusIcon != null && focusIcon.tint,
                    focusIconDark != null && focusIconDark.tint,
                    focusIcon != null && focusIcon.islandIcon,
                    focusIconDark != null && focusIconDark.islandIcon,
                    OS4FocusPriorityPolicy.PRIORITY_NATIVE_FOCUS);
        }

        // OS4 may mark an island notification as Focus before it has any native
        // status-bar content. Classify without mutating mIsFocusNotification.
        if (data.hasIslandParam && !hasNativeStatusBarContent) {
            data.isOriginalFocus = false;
        }
        if (!shouldConvert(data)) return null;
        IslandText islandText = extractIslandContent(data);
        if (islandText == null || TextUtils.isEmpty(islandText.text)) return null;
        int priority;
        String source;
        if (currentSettings.islandForcePackages.contains(data.packageName)) {
            priority = OS4FocusPriorityPolicy.PRIORITY_ISLAND_WHITELIST;
            source = "islandWhitelist:" + islandText.source;
        } else if (isSmsVerificationCode(data)) {
            priority = OS4FocusPriorityPolicy.PRIORITY_SMS_VERIFICATION;
            source = "smsVerification:" + islandText.source;
        } else {
            priority = OS4FocusPriorityPolicy.PRIORITY_ISLAND;
            source = "island:" + islandText.source;
        }
        SelectedFocusIcon focusIcon = selectFocusIcon(notification, data.islandParam,
                false, true);
        SelectedFocusIcon focusIconDark = selectFocusIcon(notification, data.islandParam,
                true, true);
        log("OS4 island focus icon key=" + key + " light="
                + (focusIcon == null ? "none" : focusIcon.source) + " dark="
                + (focusIconDark == null ? "none" : focusIconDark.source));
        return new HyperOS4FocusController.DisplayItem(key, data.packageName,
                islandText.text, source, null, null, contentIntent,
                focusIcon == null ? null : focusIcon.icon,
                focusIconDark == null ? null : focusIconDark.icon,
                focusIcon != null && focusIcon.tint,
                focusIconDark != null && focusIconDark.tint,
                focusIcon != null && focusIcon.islandIcon,
                focusIconDark != null && focusIconDark.islandIcon, priority);
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
            data.originalFocusField = originalFocusField;
            data.isFocus = originalFocusField;

            Notification notification = null;
            try {
                notification = (Notification) XposedHelpers.callMethod(expanded, "getNotification");
            } catch (Throwable ignored) {
                Object sbnNotification = invokeNoArg(expanded, "getNotification");
                if (sbnNotification instanceof Notification) notification = (Notification) sbnNotification;
            }

            data.notification = notification;
            if (notification == null || notification.extras == null) return data;
            Bundle extras = notification.extras;
            boolean explicitFocus = extras.getBoolean("miui.focus.isFocus", false);
            data.explicitFocus = explicitFocus;
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
            data.hasMainRv = data.mainRv != null || data.mainNightRv != null;
            data.hasBarRv = data.barRv != null || data.barNightRv != null;
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

    private static final class OriginalWidthState {
        int maxWidth;
        int layoutWidth;
        boolean hasLayoutParams;
        int lastAppliedWidth;

        OriginalWidthState(int maxWidth, int layoutWidth, boolean hasLayoutParams,
                           int lastAppliedWidth) {
            this.maxWidth = maxWidth;
            this.layoutWidth = layoutWidth;
            this.hasLayoutParams = hasLayoutParams;
            this.lastAppliedWidth = lastAppliedWidth;
        }
    }

    private static final class ParentWidthState {
        int originalWidth;
        int lastAppliedWidth;

        ParentWidthState(int originalWidth, int lastAppliedWidth) {
            this.originalWidth = originalWidth;
            this.lastAppliedWidth = lastAppliedWidth;
        }
    }

    private static final class OriginalBeanState {
        final Object expanded;
        final boolean originalFocus;
        String originalContent;
        String lastConvertedContent;
        Object originalIcon;
        Object originalIconDark;
        Object originalDrawable;
        Object originalDrawableDark;
        Object lastConvertedIcon;
        Object lastConvertedIconDark;
        Object lastConvertedDrawable;
        Object lastConvertedDrawableDark;
        boolean patchedIcon;
        boolean patchedIconDark;
        boolean patchedDrawable;
        boolean patchedDrawableDark;

        OriginalBeanState(Object expanded, boolean originalFocus, String originalContent,
                          Object originalIcon, Object originalIconDark,
                          Object originalDrawable, Object originalDrawableDark) {
            this.expanded = expanded;
            this.originalFocus = originalFocus;
            this.originalContent = originalContent;
            this.originalIcon = originalIcon;
            this.originalIconDark = originalIconDark;
            this.originalDrawable = originalDrawable;
            this.originalDrawableDark = originalDrawableDark;
        }
    }

    private static final class SelectedFocusIcon {
        final Icon icon;
        final boolean tint;
        final boolean islandIcon;
        final String source;

        SelectedFocusIcon(Icon icon, boolean tint, boolean islandIcon, String source) {
            this.icon = icon;
            this.tint = tint;
            this.islandIcon = islandIcon;
            this.source = source;
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
        boolean originalFocusField;
        boolean explicitFocus;
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
        Notification notification;

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
