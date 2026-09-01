package com.hyperos3.focusrestore;

import org.json.JSONObject;

/** Package-private parser for HyperOS Dynamic Island payloads. */
final class IslandPayloadParser {
    private static final int MAX_PAYLOAD_CHARS = 256 * 1024;

    private IslandPayloadParser() {
    }

    static ParsedText parse(String payload, String generalSeparator, String sideSeparator) {
        if (payload == null || payload.length() > MAX_PAYLOAD_CHARS
                || payload.trim().length() == 0) return null;
        String general = separator(generalSeparator);
        String side = separator(sideSeparator);
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject v2 = root.optJSONObject("param_v2");
            if (v2 == null) v2 = root;

            if (root.optInt("protocol", 3) == 1
                    || "verifyCode".equals(root.optString("scene"))) {
                String legacy = joinTexts(root, general, "title", "desc1", "desc2");
                return empty(legacy) ? null
                        : new ParsedText(legacy, "protocol1:" + root.optString("scene", "legacy"));
            }

            JSONObject base = v2.optJSONObject("baseInfo");
            String result = joinTexts(base, general, "title", "subTitle", "specialTitle",
                    "extraTitle", "content", "subContent");
            String source = empty(result) ? null : "baseInfo";

            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("highlightInfo"), general,
                        "title", "content", "subContent");
                if (!empty(result)) source = "highlightInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("highlightInfoV3"), general,
                        "primaryText", "secondaryText", "highLightText", "label");
                if (!empty(result)) source = "highlightInfoV3";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("chatInfo"), general, "title", "content");
                if (!empty(result)) source = "chatInfo";
            }
            if (empty(result)) {
                JSONObject icon = v2.optJSONObject("iconTextInfo");
                result = joinCompact(firstText(icon, "title"), firstText(icon, "content"), general);
                result = joinCompact(result, firstText(icon, "subContent"), general);
                if (!empty(result)) source = "iconTextInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("animTextInfo"), general, "title", "content");
                if (!empty(result)) source = "animTextInfo";
            }
            if (empty(result)) {
                result = joinTexts(v2.optJSONObject("coverInfo"), general,
                        "title", "content", "subContent");
                if (!empty(result)) source = "coverInfo";
            }

            String hintText = joinTexts(v2.optJSONObject("hintInfo"), general,
                    "title", "subTitle", "content", "subContent");
            if (empty(result) && !empty(hintText)) source = "hintInfo";
            result = appendDistinctText(result, hintText, general);

            String multiProgress = progressText(v2.optJSONObject("multiProgressInfo"), general);
            if (empty(result) && !empty(multiProgress)) source = "multiProgressInfo";
            result = appendDistinctText(result, multiProgress, general);

            String progress = progressText(v2.optJSONObject("progressInfo"), general);
            if (empty(result) && !empty(progress)) source = "progressInfo";
            result = appendDistinctText(result, progress, general);

            String step = joinTexts(v2.optJSONObject("stepInfo"), general,
                    "title", "content", "subContent", "step");
            if (empty(result) && !empty(step)) source = "stepInfo";
            result = appendDistinctText(result, step, general);

            String islandText = findIslandText(v2.optJSONObject("param_island"), general, side);
            if (base != null && !empty(result)) {
                String islandTitle = findPrimaryIslandTitle(v2.optJSONObject("param_island"));
                if (!empty(islandTitle) && !result.startsWith(islandTitle)) {
                    result = joinText(islandTitle, result, general);
                }
            }
            if (empty(result) && !empty(islandText)) source = "param_island";
            result = appendDistinctText(result, islandText, general);

            String aodTitle = firstText(v2, "aodTitle");
            result = appendDistinctText(result, aodTitle, general);
            if (!empty(result)) return new ParsedText(result, source == null ? "composite" : source);

            result = clean(v2.optString("ticker", null));
            if (!empty(result)) return new ParsedText(result, "ticker");
            if (v2 != root) {
                result = clean(root.optString("ticker", null));
                if (!empty(result)) return new ParsedText(result, "custom.ticker");
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    static final class ParsedText {
        final String text;
        final String source;

        ParsedText(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    private static String separator(String value) {
        return value == null ? "" : value;
    }

    private static String joinTexts(JSONObject object, String sep, String... keys) {
        if (object == null) return null;
        String result = null;
        for (String key : keys) result = joinText(result, firstText(object, key), sep);
        return result;
    }

    private static String progressText(JSONObject object, String sep) {
        if (object == null) return null;
        String result = joinText(firstText(object, "title", "content", "label"), percentageText(object), sep);
        if (!empty(result)) return result;
        JSONObject nested = object.optJSONObject("progressInfo");
        return nested == null ? null : joinText(firstText(nested, "title", "content", "label"), percentageText(nested), sep);
    }

    private static String percentageText(JSONObject object) {
        if (object == null || !object.has("progress")) return null;
        Object value = object.opt("progress");
        if (value == null || value == JSONObject.NULL) return null;
        String text = clean(String.valueOf(value));
        return empty(text) ? null : (text.endsWith("%") ? text : text + "%");
    }

    private static String findIslandText(JSONObject island, String general, String side) {
        if (island == null) return null;
        String result = joinTexts(island, general, "title", "content", "frontTitle");
        JSONObject big = island.optJSONObject("bigIslandArea");
        JSONObject left = big == null ? null : big.optJSONObject("imageTextInfoLeft");
        JSONObject text = left == null ? null : firstObject(left, "textInfo", "miui.focus.paramtextInfo");
        String leftText = joinTexts(text, general, "frontTitle", "title", "content", "subContent");
        JSONObject right = big == null ? null : big.optJSONObject("imageTextInfoRight");
        text = right == null ? null : firstObject(right, "textInfo", "miui.focus.paramtextInfo");
        String rightText = joinTexts(text, general, "frontTitle", "title", "content", "subContent");
        result = appendDistinctText(result, appendDistinctText(leftText, rightText, side), general);
        result = appendDistinctText(result, progressText(big == null ? null : firstObject(big,
                "progressTextInfo", "fixedWidthDigitInfo", "sameWidthDigitInfo"), general), general);
        result = appendDistinctText(result, joinTexts(island.optJSONObject("smallIslandArea"), general,
                "title", "content", "subContent"), general);
        return result;
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
            Object raw = object.opt(key);
            if (raw == null || raw == JSONObject.NULL
                    || raw instanceof JSONObject || raw instanceof org.json.JSONArray) continue;
            String value = clean(String.valueOf(raw));
            if (!empty(value)) return value;
        }
        return null;
    }

    private static String joinCompact(String first, String second, String sep) {
        return joinText(first, second, sep);
    }

    private static String joinText(String first, String second, String sep) {
        first = clean(first);
        second = clean(second);
        if (empty(first)) return second;
        if (empty(second) || first.equals(second)) return first;
        return first + sep + second;
    }

    private static String appendDistinctText(String first, String second, String sep) {
        first = clean(first);
        second = clean(second);
        if (empty(second)) return first;
        if (empty(first) || first.equals(second) || first.contains(second)) return empty(first) ? second : first;
        if (second.contains(first)) return second;
        return first + sep + second;
    }

    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.length() == 0 || "Copy".equalsIgnoreCase(value)
                || "稍后提醒".equals(value)) return null;
        return value;
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }
}
