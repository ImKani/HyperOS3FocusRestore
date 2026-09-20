package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

public class InputLimitsTest {
    @Test
    public void payloadChecksCharactersAndUtf8Bytes() {
        assertTrue(InputLimits.isPayloadAllowed("{\"title\":\"ok\"}"));
        assertFalse(InputLimits.isPayloadAllowed(null));
        assertFalse(InputLimits.isPayloadAllowed(""));
        assertFalse(InputLimits.isPayloadAllowed(repeat('a', InputLimits.MAX_PAYLOAD_CHARS + 1)));
        assertFalse(InputLimits.isPayloadAllowed(repeat('\u4e2d',
                InputLimits.MAX_PAYLOAD_UTF8_BYTES / 3 + 1)));
    }

    @Test
    public void outputAndSeparatorRemainUnicodeSafe() {
        String output = repeat('x', InputLimits.MAX_OUTPUT_CHARS - 1) + "\ud83d\ude00";
        assertEquals(InputLimits.MAX_OUTPUT_CHARS - 1,
                InputLimits.limitOutput(output).length());
        String separator = repeat('s', InputLimits.MAX_SEPARATOR_CHARS + 10);
        assertEquals(InputLimits.MAX_SEPARATOR_CHARS,
                InputLimits.limitSeparator(separator).length());
    }

    @Test
    public void packagesAreTrimmedDeduplicatedAndBounded() {
        Set<String> packages = new LinkedHashSet<>();
        packages.add(" com.example.one ");
        packages.add("com.example.one");
        packages.add("");
        packages.add(repeat('p', InputLimits.MAX_PACKAGE_NAME_CHARS + 1));
        for (int index = 0; index < InputLimits.MAX_FORCE_PACKAGES + 20; index++) {
            packages.add("com.example.app" + index);
        }
        Set<String> sanitized = InputLimits.sanitizePackages(packages);
        assertTrue(sanitized.contains("com.example.app0"));
        assertFalse(sanitized.contains(""));
        assertEquals(InputLimits.MAX_FORCE_PACKAGES, sanitized.size());
    }

    @Test
    public void moreThanLegacy256PackagesRemainAvailable() {
        Set<String> packages = new LinkedHashSet<>();
        for (int index = 0; index < 300; index++) {
            packages.add("com.example.installed" + index);
        }
        Set<String> sanitized = InputLimits.sanitizePackages(packages);
        assertEquals(300, sanitized.size());
        assertTrue(sanitized.contains("com.example.installed299"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
