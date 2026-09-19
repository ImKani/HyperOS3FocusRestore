package com.hyperos3.focusrestore;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** HyperOS 4 notification state and status-bar rendering bridge. */
final class HyperOS4FocusController {
    interface ItemFactory {
        DisplayItem create(Object notificationEntry);
        HookSettings settings();
    }

    interface Logger {
        void log(String message);
        void error(String stage, Throwable throwable);
    }

    static final class DisplayItem {
        final String key;
        final String packageName;
        final String text;
        final String source;
        final RemoteViews remoteViews;
        final RemoteViews remoteViewsNight;
        final PendingIntent contentIntent;
        final int priority;
        long updateSequence;

        DisplayItem(String key, String packageName, String text, String source,
                    RemoteViews remoteViews, RemoteViews remoteViewsNight,
                    PendingIntent contentIntent, int priority) {
            this.key = key;
            this.packageName = packageName;
            this.text = text;
            this.source = source;
            this.remoteViews = remoteViews;
            this.remoteViewsNight = remoteViewsNight;
            this.contentIntent = contentIntent;
            this.priority = priority;
        }

        boolean hasContent() {
            return remoteViews != null || remoteViewsNight != null || !TextUtils.isEmpty(text);
        }
    }

    private final ClassLoader classLoader;
    private final Context context;
    private final ItemFactory itemFactory;
    private final Logger logger;
    private final Map<String, DisplayItem> items = new LinkedHashMap<>();
    private final Set<Object> registeredPipelines = Collections.newSetFromMap(
            new WeakHashMap<Object, Boolean>());
    private long updateSequence;
    private ViewGroup statusBarRoot;
    private ViewGroup primarySlot;
    private FocusHostView focusHost;
    private TextView statusBarClock;
    private View notificationIcons;
    private int notificationIconsOriginalVisibility;
    private float notificationIconsOriginalAlpha = 1f;
    private boolean notificationIconsHidden;
    private boolean notificationIconsHideRequested;
    private boolean notificationIconsMissingLogged;
    private View statusBarPreDrawRoot;
    private ViewTreeObserver.OnPreDrawListener statusBarPreDrawListener;
    private Object darkDispatcher;
    private Object darkReceiver;
    private Class<?> darkDispatcherClass;
    private int currentTint = Color.WHITE;

    HyperOS4FocusController(ClassLoader classLoader, Context context,
                            ItemFactory itemFactory, Logger logger) {
        this.classLoader = classLoader;
        this.context = context;
        this.itemFactory = itemFactory;
        this.logger = logger;
    }

    void install() {
        hookNotifPipeline();
        hookStatusBarView();
        hookClockTint();
    }

    private void hookNotifPipeline() {
        try {
            Class<?> pipelineClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.NotifPipeline");
            XposedBridge.hookAllConstructors(pipelineClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    registerPipeline(param.thisObject);
                }
            });
            logger.log("OS4 notifPipelineListener=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookNotifPipeline", throwable);
        }
    }

    private void registerPipeline(Object pipeline) {
        if (pipeline == null) return;
        synchronized (registeredPipelines) {
            if (!registeredPipelines.add(pipeline)) return;
        }
        try {
            Class<?> listenerClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener");
            Object listener = Proxy.newProxyInstance(classLoader, new Class<?>[]{listenerClass},
                    new NotificationListener());
            Method addListener = pipeline.getClass().getMethod("addCollectionListener", listenerClass);
            addListener.invoke(pipeline, listener);
            logger.log("OS4 notifPipelineListener=registered");
            Object existing = XposedHelpers.callMethod(pipeline, "getAllNotifs");
            if (existing instanceof Collection) {
                for (Object entry : new ArrayList<>((Collection<?>) existing)) {
                    updateEntry(entry, "initial");
                }
            }
        } catch (Throwable throwable) {
            logger.error("OS4 registerNotifPipeline", throwable);
        }
    }

    private final class NotificationListener implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return "HyperOS4FocusRestoreNotifListener";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
            try {
                if (("onEntryAdded".equals(name) || "onEntryUpdated".equals(name)
                        || "onEntryBind".equals(name)) && args != null && args.length > 0) {
                    updateEntry(args[0], name);
                } else if (("onEntryRemoved".equals(name) || "onEntryCleanUp".equals(name))
                        && args != null && args.length > 0) {
                    removeEntry(args[0], name);
                }
            } catch (Throwable throwable) {
                logger.error("OS4 listener " + name, throwable);
            }
            return null;
        }
    }

    private void updateEntry(Object entry, String stage) {
        if (entry == null) return;
        DisplayItem item = itemFactory.create(entry);
        String key = item == null ? entryKey(entry) : item.key;
        synchronized (items) {
            if (item == null || TextUtils.isEmpty(key) || !item.hasContent()) {
                if (!TextUtils.isEmpty(key)) items.remove(key);
            } else {
                item.updateSequence = ++updateSequence;
                items.put(key, item);
                logger.log("OS4 candidate " + stage + " key=" + key
                        + " priority=" + item.priority + " source=" + item.source
                        + " remoteViews=" + (item.remoteViews != null)
                        + " remoteViewsNight=" + (item.remoteViewsNight != null)
                        + " text=" + item.text);
            }
        }
        renderBest();
    }

    private void removeEntry(Object entry, String stage) {
        String key = entryKey(entry);
        if (TextUtils.isEmpty(key)) return;
        synchronized (items) {
            items.remove(key);
        }
        logger.log("OS4 candidate " + stage + " key=" + key);
        renderBest();
    }

    private String entryKey(Object entry) {
        Object value = field(entry, "key");
        if (value == null) value = field(entry, "mKey");
        return value == null ? null : String.valueOf(value);
    }

    private void hookStatusBarView() {
        try {
            Class<?> statusBarView = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            XposedBridge.hookAllMethods(statusBarView, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        attachStatusBar((ViewGroup) param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(statusBarView, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    renderBest();
                }
            });
            logger.log("OS4 statusBarPrimarySlot=hooked");
        } catch (Throwable throwable) {
            logger.error("OS4 hookStatusBarView", throwable);
        }
    }

    private void hookClockTint() {
        try {
            Class<?> clockClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.statusbar.views.MiuiClock");
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    clockClass, "onDarkChanged", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == statusBarClock) {
                                updateTint(((TextView) param.thisObject).getCurrentTextColor(),
                                        "MiuiClock.onDarkChanged");
                            }
                        }
                    });
            logger.log("OS4 clockTintHook=" + (hooks.isEmpty() ? "missing" : "hooked")
                    + " count=" + hooks.size());
        } catch (Throwable throwable) {
            logger.error("OS4 hookClockTint", throwable);
        }
    }

    private void attachStatusBar(ViewGroup statusBarView) {
        try {
            int primaryId = context.getResources().getIdentifier(
                    "ongoing_activity_chip_primary", "id", "com.android.systemui");
            View view = primaryId == 0 ? null : statusBarView.findViewById(primaryId);
            if (!(view instanceof ViewGroup)) {
                logger.log("OS4 statusBarPrimarySlot=missing id=" + primaryId);
                return;
            }
            ViewGroup slot = (ViewGroup) view;
            unregisterStatusBarPreDrawListener();
            restoreNotificationIcons();
            FocusHostView oldHost = focusHost;
            ViewGroup oldSlot = primarySlot;
            if (oldHost != null && oldHost.getParent() instanceof ViewGroup) {
                oldHost.clearContent();
                ((ViewGroup) oldHost.getParent()).removeView(oldHost);
            }
            if (oldSlot != null && oldSlot != slot) {
                oldSlot.setVisibility(View.GONE);
            }
            FocusHostView host = new FocusHostView(slot.getContext());
            host.setId(View.generateViewId());
            host.setVisibility(View.GONE);
            slot.addView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            statusBarRoot = statusBarView;
            primarySlot = slot;
            focusHost = host;
            registerStatusBarPreDrawListener();
            resolveStatusBarClock();
            registerDarkReceiver();
            // HyperOS 4 hides this legacy XML slot before installing its Compose
            // chip. Preserve that inactive state so the XML defaults (phone icon,
            // 00:00:00 chronometer and background) never leak between candidates.
            slot.setVisibility(View.GONE);
            logger.log("OS4 statusBarPrimarySlot=attached id=" + primaryId
                    + " legacyInactiveVisibility=GONE children=" + slot.getChildCount());
            renderBest();
        } catch (Throwable throwable) {
            logger.error("OS4 attachStatusBar", throwable);
        }
    }

    private void renderBest() {
        final DisplayItem best;
        synchronized (items) {
            DisplayItem selected = null;
            for (DisplayItem candidate : items.values()) {
                if (selected == null || OS4FocusPriorityPolicy.compare(
                        candidate.priority, candidate.updateSequence,
                        selected.priority, selected.updateSequence) > 0) {
                    selected = candidate;
                }
            }
            best = selected;
        }
        final FocusHostView host = focusHost;
        if (host == null) return;
        host.post(() -> render(host, best));
    }

    private void render(FocusHostView host, DisplayItem item) {
        if (host != focusHost) return;
        if (item == null) {
            host.clearContent();
            host.setVisibility(View.GONE);
            ViewGroup slot = primarySlot;
            if (slot != null) slot.setVisibility(View.GONE);
            restoreNotificationIcons();
            logger.log("OS4 focus hidden; no eligible notification legacySlot=GONE"
                    + " notificationIconsRestored=true");
            return;
        }
        HookSettings settings = itemFactory.settings();
        hideOriginalChildren();
        setNotificationIconsHidden(settings.hideNotificationIcons);
        host.setVisibility(View.VISIBLE);
        boolean night = (host.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        RemoteViews selected = night
                ? (item.remoteViewsNight != null ? item.remoteViewsNight : item.remoteViews)
                : (item.remoteViews != null ? item.remoteViews : item.remoteViewsNight);
        View content = null;
        if (selected != null) {
            try {
                content = selected.apply(host.getContext(), host);
            } catch (Throwable throwable) {
                logger.error("OS4 applyRemoteViews key=" + item.key, throwable);
            }
        }
        if (content == null && !TextUtils.isEmpty(item.text)) {
            TextView textView = new TextView(host.getContext());
            textView.setText(item.text);
            textView.setTextSize(14f);
            textView.setTextColor(currentTint);
            textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            textView.setSingleLine(true);
            textView.setEllipsize(null);
            textView.setIncludeFontPadding(false);
            content = textView;
        }
        if (content == null) {
            synchronized (items) {
                items.remove(item.key);
            }
            renderBest();
            return;
        }
        host.showContent(content, item, settings);
        logger.log("OS4 focus shown key=" + item.key + " package=" + item.packageName
                + " source=" + item.source + " priority=" + item.priority
                + " widthDp=" + settings.widthDp + " limit=" + settings.limitWidth
                + " maxWidthPx=" + host.maxWidthPx
                + " hideNotificationIcons=" + settings.hideNotificationIcons
                + " showFocusDivider=" + settings.showFocusDivider
                + " tint=0x" + Integer.toHexString(currentTint)
                + " click=" + settings.allowFocusClick);
    }

    private void resolveStatusBarClock() {
        ViewGroup root = statusBarRoot;
        if (root == null) return;
        int id = root.getResources().getIdentifier("clock", "id", context.getPackageName());
        View view = id == 0 ? null : root.findViewById(id);
        statusBarClock = view instanceof TextView ? (TextView) view : null;
        if (statusBarClock != null) {
            currentTint = statusBarClock.getCurrentTextColor();
            logger.log("OS4 statusBarClock=resolved tint=0x"
                    + Integer.toHexString(currentTint));
        } else {
            logger.log("OS4 statusBarClock=missing id=" + id);
        }
    }

    private void registerDarkReceiver() {
        unregisterDarkReceiver();
        try {
            darkDispatcherClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.plugins.DarkIconDispatcher");
            Class<?> receiverClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.plugins.DarkIconDispatcher$DarkReceiver");
            Class<?> dependencyClass = FocusReflection.findClass(classLoader,
                    "com.android.systemui.Dependency");
            darkDispatcher = XposedHelpers.callStaticMethod(
                    dependencyClass, "get", darkDispatcherClass);
            darkReceiver = Proxy.newProxyInstance(classLoader,
                    new Class<?>[]{receiverClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if (("onDarkChanged".equals(name)
                                    || "onDarkChangedWithContrast".equals(name))
                                    && args != null && args.length >= 3
                                    && args[2] instanceof Integer) {
                                int tint = resolveTint(args[0], (Integer) args[2]);
                                updateTint(tint, "DarkIconDispatcher." + name);
                            } else if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            } else if ("equals".equals(name)) {
                                return args != null && args.length == 1 && proxy == args[0];
                            } else if ("toString".equals(name)) {
                                return "FocusRestoreDarkReceiver";
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(darkDispatcher, "addDarkReceiver", darkReceiver);
            try {
                XposedHelpers.callMethod(darkDispatcher, "applyDark", darkReceiver);
            } catch (Throwable throwable) {
                logger.error("OS4 applyInitialDark", throwable);
            }
            logger.log("OS4 darkReceiver=registered dispatcher="
                    + darkDispatcher.getClass().getName());
        } catch (Throwable throwable) {
            darkDispatcher = null;
            darkReceiver = null;
            darkDispatcherClass = null;
            logger.error("OS4 registerDarkReceiver", throwable);
        }
    }

    private int resolveTint(Object areas, int fallbackTint) {
        View tintReference = statusBarClock != null ? statusBarClock : focusHost;
        if (tintReference == null || darkDispatcherClass == null) return fallbackTint;
        try {
            Object value = XposedHelpers.callStaticMethod(
                    darkDispatcherClass, "getTint", areas, tintReference, fallbackTint);
            return value instanceof Integer ? (Integer) value : fallbackTint;
        } catch (Throwable throwable) {
            logger.error("OS4 resolveDarkTint", throwable);
            return fallbackTint;
        }
    }

    private void unregisterDarkReceiver() {
        if (darkDispatcher == null || darkReceiver == null) return;
        try {
            XposedHelpers.callMethod(darkDispatcher, "removeDarkReceiver", darkReceiver);
        } catch (Throwable throwable) {
            logger.error("OS4 unregisterDarkReceiver", throwable);
        } finally {
            darkDispatcher = null;
            darkReceiver = null;
            darkDispatcherClass = null;
        }
    }

    private void updateTint(int tint, String source) {
        if (currentTint == tint) return;
        currentTint = tint;
        FocusHostView host = focusHost;
        if (host != null) {
            applyTint(host);
            host.updateDividerTint();
        }
        logger.log("OS4 tint updated source=" + source + " tint=0x"
                + Integer.toHexString(tint));
    }

    private void applyTint(View view) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(currentTint);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyTint(group.getChildAt(index));
            }
        }
    }

    private void setNotificationIconsHidden(boolean hidden) {
        notificationIconsHideRequested = hidden;
        if (hidden) {
            enforceNotificationIconsHidden("render");
        } else {
            restoreTrackedNotificationIcons();
        }
    }

    private void enforceNotificationIconsHidden(String source) {
        if (!notificationIconsHideRequested) return;
        View icons = resolveNotificationIcons();
        if (icons == null) {
            if (!notificationIconsMissingLogged) {
                notificationIconsMissingLogged = true;
                logger.log("OS4 notificationIcons=missing requestedHidden=true source=" + source);
            }
            return;
        }
        notificationIconsMissingLogged = false;
        boolean replaced = notificationIcons != icons;
        if (!notificationIconsHidden || replaced) {
            restoreTrackedNotificationIcons();
            notificationIcons = icons;
            notificationIconsOriginalVisibility = icons.getVisibility();
            notificationIconsOriginalAlpha = icons.getAlpha();
            notificationIconsHidden = true;
        }
        if (replaced || icons.getVisibility() != View.GONE || icons.getAlpha() != 0f) {
            int currentVisibility = icons.getVisibility();
            float currentAlpha = icons.getAlpha();
            icons.setAlpha(0f);
            icons.setVisibility(View.GONE);
            logger.log("OS4 notificationIcons=GONE id=" + icons.getId()
                    + " source=" + source + " replaced=" + replaced
                    + " currentVisibility=" + currentVisibility
                    + " currentAlpha=" + currentAlpha
                    + " originalVisibility=" + notificationIconsOriginalVisibility
                    + " originalAlpha=" + notificationIconsOriginalAlpha);
        }
    }

    private View resolveNotificationIcons() {
        ViewGroup root = statusBarRoot;
        if (root == null) return null;
        int id = root.getResources().getIdentifier(
                "notificationIcons", "id", context.getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private void restoreNotificationIcons() {
        notificationIconsHideRequested = false;
        notificationIconsMissingLogged = false;
        restoreTrackedNotificationIcons();
    }

    private void restoreTrackedNotificationIcons() {
        if (!notificationIconsHidden) return;
        View icons = notificationIcons;
        if (icons != null) {
            icons.setAlpha(notificationIconsOriginalAlpha);
            icons.setVisibility(notificationIconsOriginalVisibility);
        }
        logger.log("OS4 notificationIcons=restored id="
                + (icons == null ? 0 : icons.getId())
                + " visibility=" + notificationIconsOriginalVisibility
                + " alpha=" + notificationIconsOriginalAlpha);
        notificationIcons = null;
        notificationIconsHidden = false;
    }

    private void registerStatusBarPreDrawListener() {
        unregisterStatusBarPreDrawListener();
        ViewGroup root = statusBarRoot;
        if (root == null) return;
        try {
            statusBarPreDrawRoot = root;
            statusBarPreDrawListener = () -> {
                try {
                    enforceNotificationIconsHidden("preDraw");
                } catch (Throwable throwable) {
                    logger.error("OS4 enforceNotificationIconsGuard", throwable);
                }
                return true;
            };
            root.getViewTreeObserver().addOnPreDrawListener(statusBarPreDrawListener);
            logger.log("OS4 notificationIconsGuard=registered");
        } catch (Throwable throwable) {
            statusBarPreDrawRoot = null;
            statusBarPreDrawListener = null;
            logger.error("OS4 registerNotificationIconsGuard", throwable);
        }
    }

    private void unregisterStatusBarPreDrawListener() {
        View root = statusBarPreDrawRoot;
        ViewTreeObserver.OnPreDrawListener listener = statusBarPreDrawListener;
        if (root != null && listener != null) {
            try {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
            } catch (Throwable throwable) {
                logger.error("OS4 unregisterNotificationIconsGuard", throwable);
            }
        }
        statusBarPreDrawRoot = null;
        statusBarPreDrawListener = null;
    }

    private void hideOriginalChildren() {
        ViewGroup slot = primarySlot;
        FocusHostView host = focusHost;
        if (slot == null || host == null) return;
        for (int index = 0; index < slot.getChildCount(); index++) {
            View child = slot.getChildAt(index);
            if (child != host) child.setVisibility(View.GONE);
        }
        slot.setVisibility(View.VISIBLE);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final class FocusHostView extends FrameLayout {
        private ValueAnimator animator;
        private Runnable pendingAnimation;
        private View content;
        private View divider;
        private int contentInsetPx;
        private int maxWidthPx = Integer.MAX_VALUE;
        private boolean blockClicks = true;

        FocusHostView(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
        }

        void showContent(View nextContent, DisplayItem item, HookSettings settings) {
            clearContent();
            blockClicks = !settings.allowFocusClick;
            float density = getResources().getDisplayMetrics().density;
            maxWidthPx = settings.limitWidth
                    ? Math.max(1, Math.round(settings.widthDp * density)) : Integer.MAX_VALUE;
            if (settings.showFocusDivider) {
                int dividerWidth = Math.max(1, Math.round(density));
                int dividerHeight = Math.max(1, Math.round(12f * density));
                contentInsetPx = dividerWidth + Math.max(1, Math.round(6f * density));
                divider = new View(getContext());
                divider.setBackgroundColor(currentTint);
                divider.setAlpha(0.45f);
                LayoutParams dividerParams = new LayoutParams(
                        dividerWidth, dividerHeight, Gravity.CENTER_VERTICAL | Gravity.START);
                addView(divider, dividerParams);
            }
            content = nextContent;
            LayoutParams params = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL | Gravity.START);
            params.setMarginStart(contentInsetPx);
            addView(nextContent, params);
            applyTint(nextContent);
            if (settings.allowFocusClick && item.contentIntent != null
                    && !(nextContent instanceof ViewGroup)) {
                nextContent.setOnClickListener(view -> send(item.contentIntent, item.key));
            }
            requestLayout();
            long delay = Math.max(0L, Math.min(5000L, settings.marqueeDelayMs));
            pendingAnimation = () -> startScroll(settings.marqueeBounce);
            postDelayed(pendingAnimation, delay);
            logger.log("OS4 marquee scheduled key=" + item.key + " delayMs=" + delay
                    + " bounce=" + settings.marqueeBounce);
        }

        void clearContent() {
            if (pendingAnimation != null) {
                removeCallbacks(pendingAnimation);
                pendingAnimation = null;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (content != null) content.setTranslationX(0f);
            removeAllViews();
            content = null;
            divider = null;
            contentInsetPx = 0;
        }

        private void startScroll(boolean bounce) {
            pendingAnimation = null;
            View child = content;
            if (child == null || getVisibility() != View.VISIBLE) return;
            int availableWidth = Math.max(1, getWidth() - contentInsetPx);
            int distance = child.getMeasuredWidth() - availableWidth;
            if (distance <= 0 && child instanceof TextView) {
                TextView text = (TextView) child;
                distance = Math.round(text.getPaint().measureText(String.valueOf(text.getText())))
                        - availableWidth + text.getPaddingLeft() + text.getPaddingRight();
            }
            if (distance <= 0) {
                logger.log("OS4 marquee not needed contentWidth=" + child.getMeasuredWidth()
                        + " hostWidth=" + getWidth() + " maxWidthPx=" + maxWidthPx
                        + " contentInsetPx=" + contentInsetPx);
                return;
            }
            float direction = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f : -1f;
            animator = bounce
                    ? ValueAnimator.ofFloat(0f, direction * distance, 0f)
                    : ValueAnimator.ofFloat(0f, direction * distance);
            animator.setDuration(Math.max(2500L, distance * 35L));
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(value -> {
                if (content == child && getVisibility() == View.VISIBLE) {
                    child.setTranslationX((Float) value.getAnimatedValue());
                }
            });
            animator.start();
            logger.log("OS4 marquee started distance=" + distance + " bounce=" + bounce
                    + " contentWidth=" + child.getMeasuredWidth()
                    + " hostWidth=" + getWidth() + " maxWidthPx=" + maxWidthPx);
        }

        void updateDividerTint() {
            if (divider != null) divider.setBackgroundColor(currentTint);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child != content || contentInsetPx <= 0) {
                return super.drawChild(canvas, child, drawingTime);
            }
            int saveCount = canvas.save();
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                canvas.clipRect(0, 0, getWidth() - contentInsetPx, getHeight());
            } else {
                canvas.clipRect(contentInsetPx, 0, getWidth(), getHeight());
            }
            boolean drawn = super.drawChild(canvas, child, drawingTime);
            canvas.restoreToCount(saveCount);
            return drawn;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return blockClicks || super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return blockClicks || super.onTouchEvent(event);
        }

        @Override
        protected void onDetachedFromWindow() {
            clearContent();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int unlimitedWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            super.onMeasure(unlimitedWidth, heightMeasureSpec);
            int width = getMeasuredWidth();
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
            }
            width = Math.min(width, maxWidthPx);
            setMeasuredDimension(Math.max(1, width), getMeasuredHeight());
        }
    }

    private void send(PendingIntent intent, String key) {
        try {
            intent.send();
            logger.log("OS4 focus click sent key=" + key);
        } catch (PendingIntent.CanceledException exception) {
            logger.error("OS4 focus click canceled key=" + key, exception);
        }
    }
}
