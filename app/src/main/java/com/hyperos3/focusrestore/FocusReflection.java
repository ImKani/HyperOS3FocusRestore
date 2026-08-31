package com.hyperos3.focusrestore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/** Small compatibility layer for optional private SystemUI APIs. */
final class FocusReflection {
    private FocusReflection() {
    }

    static Class<?> findClass(ClassLoader loader, String name) {
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Class<?> findClass(String name, ClassLoader loader) {
        return findClass(loader, name);
    }

    static Class<?> findFirstClass(ClassLoader loader, String... names) {
        for (String name : names) {
            Class<?> value = findClass(loader, name);
            if (value != null) return value;
        }
        return null;
    }

    static boolean hasMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) return false;
        try {
            owner.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (Throwable ignored) {
            try {
                owner.getMethod(name, parameterTypes);
                return true;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    static boolean hasField(Class<?> owner, String name) {
        if (owner == null) return false;
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String capability(Class<?> owner, String name, Class<?>... parameterTypes) {
        boolean available = parameterTypes.length == 0 ? hasAnyMethod(owner, name)
                : hasMethod(owner, name, parameterTypes);
        return name + "=" + (available ? "available" : "missing");
    }

    private static boolean hasAnyMethod(Class<?> owner, String name) {
        if (owner == null) return false;
        for (Method method : owner.getDeclaredMethods()) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }
}
