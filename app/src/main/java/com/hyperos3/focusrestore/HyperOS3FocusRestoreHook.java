package com.hyperos3.focusrestore;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.database.Cursor;
import org.json.JSONObject;
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

    // OS3 rejects the legacy miui.focus.rv when used as contentRemoteViews.
    private static final boolean FALLBACK_MAIN_RV_FOR_STATUS_BAR = false;
    // Let HyperOS own the prompt lifecycle; forcing true leaves stale icons after clicks.
    private static final boolean FORCE_SHOULD_SHOW = false;

    private ClassLoader classLoader;
    private XSharedPreferences settings;
    private static final int DEFAULT_WIDTH_DP = 160;
    // FocusedTextView.startMarqueeLocal() copies this value into TextView.
    // -1 keeps long lyrics moving instead of stopping after one pass.
    private static final int MARQUEE_REPEAT_LIMIT = -1;
    private int marqueeDelayMs = SettingsProvider.DEFAULT_MARQUEE_DELAY_MS;
    private boolean compatRetry;
    private boolean limitWidth;
    private int widthDp = 160;
    private boolean islandCompat;
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!islandCompat) return;
                            FocusData data = inspectExpanded(param.args[0]);
                            IslandText islandText = extractIslandContent(data);
                            if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
                                try {
                                    XposedHelpers.setBooleanField(param.args[0], "mIsFocusNotification", true);
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
                            IslandText islandText = islandCompat ? extractIslandContent(data) : null;
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
                            scheduleNativeMarquee(param.thisObject);
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
                        islandCompat = cursor.getColumnCount() > 4 && cursor.getInt(4) != 0;
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
        return "limit=" + limitWidth + " widthDp=" + widthDp + " delayMs=" + marqueeDelayMs
                + " compatRetry=" + compatRetry + " islandCompat=" + islandCompat;
    }

    // Restored 0.7 behavior: constrain the text and its content slot, not the outer prompt.
    private void applyTextWidth(Object promptView) {
        try {
            Object value = XposedHelpers.getObjectField(promptView, "mContentText");
            if (!(value instanceof TextView)) return;
            TextView textView = (TextView) value;
            if (!limitWidth) return;
            float density = textView.getResources().getDisplayMetrics().density;
            int widthPx = Math.round(widthDp * density);
            textView.setMaxWidth(widthPx);
            ViewGroup.LayoutParams params = textView.getLayoutParams();
            if (params != null) {
                params.width = widthPx;
                textView.setLayoutParams(params);
            }
            textView.requestLayout();
            log("applied 0.7 manual focus text width=" + widthDp + "dp px=" + widthPx);
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
            log("hooked updateFocusedParentParams for 0.4 width behavior");
        } catch (Throwable t) {
            error("hookFocusedParentParams", t);
        }
    }

    private void applyParentWidth(Object fragment) {
        try {
            reloadSettings();
            Object value = XposedHelpers.getObjectField(fragment, "mFocusedNotifParent");
            if (!(value instanceof View)) return;
            View parent = (View) value;
            ViewGroup.LayoutParams params = parent.getLayoutParams();
            if (params == null) return;
            if (limitWidth) {
                int widthPx = Math.round(widthDp
                        * parent.getResources().getDisplayMetrics().density);
                if (params.width != widthPx) {
                    params.width = widthPx;
                    parent.setLayoutParams(params);
                    log("applied 0.4 manual focus parent width=" + widthDp
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
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController",
                    classLoader);
            Class<?> bean = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.FocusedNotifPromptController$FocusedNotifBean",
                    classLoader);

            XposedHelpers.findAndHookMethod(controller, "shouldShow", bean, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (islandCompat) patchBean(param.args[0], "before shouldShow");
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

    private boolean hasConvertibleIslandContent(FocusData data) {
        if (!islandCompat || data == null || !data.hasIslandParam) return false;
        IslandText text = extractIslandContent(data);
        return text != null && !TextUtils.isEmpty(text.text);
    }

    private void patchBean(Object bean, String stage) {
        FocusData data = inspectBean(bean);
        if (data == null) {
            log(stage + " bean=null");
            return;
        }


        IslandText islandText = islandCompat ? extractIslandContent(data) : null;
        if (islandText != null && !TextUtils.isEmpty(islandText.text)) {
            try {
                // When island conversion is enabled, the ordinary notification title
                // (for example "Template 1: Weather") is only a fallback. Replace it
                // with the selected template's actual body so the focus view receives
                // Heavy Snow, Verification Code, progress text, and similar content.
                String current = stringValue(getField(bean, "content"));
                XposedHelpers.setObjectField(bean, "content", islandText.text);
                data.content = islandText.text;
                data.isFocus = true;
                log(stage + " applied island focus source=" + islandText.source
                        + " replaced=" + !TextUtils.isEmpty(current));
            } catch (Throwable t) {
                error(stage + " applyIslandContent", t);
            }
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

    private IslandText extractIslandContent(FocusData data) {
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
                return TextUtils.isEmpty(legacy) ? null : new IslandText(legacy, "protocol1:" + root.optString("scene", "legacy"));
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
                if (!TextUtils.isEmpty(result)) source = "hintInfo";
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
            return TextUtils.isEmpty(result) ? null : new IslandText(result, source);
        } catch (Throwable t) {
            log("island param parse failed");
            return null;
        }
    }

    private static String joinTexts(JSONObject object, String ignoredSource, String... keys) {
        if (object == null) return null;
        String result = null;
        for (String key : keys) result = joinText(result, firstText(object, key));
        return result;
    }

    private static String progressText(JSONObject object) {
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

    private static String findIslandText(JSONObject island) {
        if (island == null) return null;
        String result = joinTexts(island, "param_island", "title", "content", "frontTitle");
        if (!TextUtils.isEmpty(result)) return result;
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject info = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = info == null ? null : firstObject(info, "textInfo", "miui.focus.paramtextInfo");
        result = joinTexts(text, "imageTextInfoLeft", "frontTitle", "title", "content", "subContent");
        if (!TextUtils.isEmpty(result)) return result;
        JSONObject right = big == null ? null : big.optJSONObject("imageTextInfoRight");
        text = right == null ? null : firstObject(right, "textInfo", "miui.focus.paramtextInfo");
        result = joinTexts(text, "imageTextInfoRight", "title", "content", "subContent");
        if (!TextUtils.isEmpty(result)) return result;
        result = progressText(big == null ? null : firstObject(big, "progressTextInfo", "fixedWidthDigitInfo", "sameWidthDigitInfo"));
        if (!TextUtils.isEmpty(result)) return result;
        JSONObject small = island.optJSONObject("smallIslandArea");
        return joinTexts(small, "smallIslandArea", "title", "content", "subContent");
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

    private static String joinCompact(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + "·" + second;
    }

    private static String joinText(String first, String second) {
        first = cleanText(first);
        second = cleanText(second);
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second) || first.equals(second)) return first;
        return first + " · " + second;
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
            data.hasExplicitFocusData = explicitFocus || data.hasMainRv || data.hasBarRv
                    || !TextUtils.isEmpty(data.ticker);
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
        boolean hasMainRv;
        boolean hasBarRv;
        boolean hasIslandParam;
        boolean hasExplicitFocusData;
        String islandParam;
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
                    + " islandParam=" + hasIslandParam
                    + " contentRv=" + (contentRv != null)
                    + " contentNightRv=" + (contentNightRv != null);
        }
    }
}
