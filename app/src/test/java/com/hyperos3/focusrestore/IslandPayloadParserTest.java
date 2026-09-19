package com.hyperos3.focusrestore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IslandPayloadParserTest {
    @Test
    public void parsesWeatherAndKeepsHeavySnow() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"Weather\",\"content\":\"Heavy Snow\"}}}",
                "·", "·");
        assertEquals("Weather·Heavy Snow", value.text);
    }

    @Test
    public void parsesVerificationCodeInTitleContentOrder() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"protocol\":1,\"scene\":\"verifyCode\",\"title\":\"验证码\",\"desc1\":\"1234\",\"desc2\":\"Copy\"}",
                "·", "·");
        assertEquals("验证码·1234", value.text);
    }

    @Test
    public void usesSeparateSeparatorForIslandSides() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Left\"}},\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"Right\"}}}}}}}",
                "·", " | ");
        assertTrue(value.text.contains("Left | Right"));
    }

    @Test
    public void allowsEmptySeparators() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"A\",\"content\":\"B\"}}}",
                "", "");
        assertEquals("AB", value.text);
    }

    @Test
    public void mergesChatProgressAndIslandSummary() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"chatInfo\":{\"title\":\"Delivery\",\"content\":\"Arriving\"},\"progressInfo\":{\"progress\":70},\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Courier\"}},\"imageTextInfoRight\":{\"textInfo\":{\"title\":\"Nearby\"}}}}}}}",
                "·", " | ");
        assertEquals("Delivery·Arriving·70%·Courier | Nearby", value.text);
    }

    @Test
    public void doesNotDuplicateIslandTextAlreadyInMainTemplate() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\"Heavy Snow\",\"content\":\"Red Alert\"},\"param_island\":{\"bigIslandArea\":{\"imageTextInfoLeft\":{\"textInfo\":{\"title\":\"Heavy Snow\"}}}}}}}",
                "·", "·");
        assertEquals("Heavy Snow·Red Alert", value.text);
    }

    @Test
    public void capsExtractedTextLength() {
        IslandPayloadParser.ParsedText value = IslandPayloadParser.parse(
                "{\"param_v2\":{\"baseInfo\":{\"title\":\""
                        + repeat('x', InputLimits.MAX_OUTPUT_CHARS + 100) + "\"}}}",
                "·", "·");
        assertEquals(InputLimits.MAX_OUTPUT_CHARS, value.text.length());
    }

    @Test
    public void rejectsPayloadAboveUtf8ByteLimit() {
        String payload = "{\"title\":\""
                + repeat('\u4e2d', InputLimits.MAX_PAYLOAD_UTF8_BYTES / 3 + 1) + "\"}";
        assertNull(IslandPayloadParser.parse(payload, "·", "·"));
    }

    @Test
    public void extractsSmallIslandPictureBeforeOtherPictures() {
        String payload = "{\"param_v2\":{\"baseInfo\":{\"picFunction\":\"miui.focus.pic_app\"},"
                + "\"param_island\":{\"smallIslandArea\":{\"picInfo\":{"
                + "\"pic\":\"miui.focus.pic_weather\"}}}}}}";
        assertEquals("miui.focus.pic_weather",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void prefersDarkPictureAndFallsBackToLight() {
        String withDark = "{\"param_v2\":{\"param_island\":{\"bigIslandArea\":{"
                + "\"picFunction\":\"miui.focus.pic_pay\","
                + "\"picFunctionDark\":\"miui.focus.pic_pay_dark\"}}}}}";
        assertEquals("miui.focus.pic_pay_dark",
                IslandPayloadParser.findPictureReference(withDark, true));
        assertEquals("miui.focus.pic_pay",
                IslandPayloadParser.findPictureReference(withDark, false));
        assertEquals("miui.focus.pic_pay",
                IslandPayloadParser.findPictureReference(
                        "{\"pic\":\"miui.focus.pic_pay\"}", true));
    }

    @Test
    public void usesDeterministicPictureKeyPriority() {
        String payload = "{\"param_v2\":{\"baseInfo\":{"
                + "\"picFunction\":\"miui.focus.pic_function\","
                + "\"pic\":\"miui.focus.pic_primary\"}}}";
        assertEquals("miui.focus.pic_primary",
                IslandPayloadParser.findPictureReference(payload, false));
    }

    @Test
    public void acceptsAnimationSourceOnlyInsideAnimIconInfo() {
        String payload = "{\"param_v2\":{\"animTextInfo\":{\"animIconInfo\":{"
                + "\"src\":\"miui.focus.pic_animation\"}}}}";
        assertEquals("miui.focus.pic_animation",
                IslandPayloadParser.findPictureReference(payload, false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"src\":\"miui.focus.pic_background\"}", false));
    }

    @Test
    public void rejectsNonIconPictureReference() {
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"pic\":\"https://example.invalid/icon.png\"}", false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"picCover\":\"miui.focus.pic_album_art\"}", false));
        assertNull(IslandPayloadParser.findPictureReference(
                "{\"picBg\":\"miui.focus.pic_background\"}", false));
    }

    @Test
    public void rejectsInvalidJson() {
        assertNull(IslandPayloadParser.parse("{invalid", "·", "·"));
        assertNull(IslandPayloadParser.findPictureReference("{invalid", false));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
