package com.hyperos3.focusrestore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared bounds for untrusted notification payloads and persisted text settings. */
final class InputLimits {
    static final int MAX_PAYLOAD_CHARS = 256 * 1024;
    static final int MAX_PAYLOAD_UTF8_BYTES = 512 * 1024;
    static final int MAX_OUTPUT_CHARS = 4096;
    static final int MAX_SEPARATOR_CHARS = 32;
    static final int MAX_FORCE_PACKAGES = 256;
    static final int MAX_PACKAGE_NAME_CHARS = 255;

    private InputLimits() {
    }

    static boolean isPayloadAllowed(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_PAYLOAD_CHARS) {
            return false;
        }
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_PAYLOAD_UTF8_BYTES) return false;
        }
        return true;
    }

    static String limitOutput(String value) {
        return truncate(value, MAX_OUTPUT_CHARS);
    }

    static String limitSeparator(String value) {
        return truncate(value, MAX_SEPARATOR_CHARS);
    }

    static Set<String> sanitizePackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) return Collections.emptySet();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : packages) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.length() == 0 || trimmed.length() > MAX_PACKAGE_NAME_CHARS) continue;
            result.add(trimmed);
            if (result.size() >= MAX_FORCE_PACKAGES) break;
        }
        return result.isEmpty() ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(result);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
