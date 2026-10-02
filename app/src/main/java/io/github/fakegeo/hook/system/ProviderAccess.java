package io.github.fakegeo.hook.system;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** AOSP 10/11 inner providers and 12+ standalone managers have different lock/user APIs. */
final class ProviderAccess {
    private ProviderAccess() {}
    static Object field(Object value, String name) throws ReflectiveOperationException {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
            catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(value.getClass().getName() + "." + name);
    }
    private static Object call(Object value, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { Method method = type.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(value, args); }
            catch (NoSuchMethodException absent) {}
        }
        throw new NoSuchMethodException(value.getClass().getName() + "." + name);
    }
    static String name(Object provider) throws ReflectiveOperationException {
        try { return (String) call(provider, "getName", new Class<?>[0]); }
        catch (NoSuchMethodException absent) { return (String) field(provider, "mName"); }
    }
    static Object lock(Object provider) throws ReflectiveOperationException {
        for (String field : new String[]{"mMultiplexerLock", "mLock"}) {
            try { Object lock = field(provider, field); if (lock != null) return lock; }
            catch (NoSuchFieldException absent) {}
        }
        return field(field(provider, "this$0"), "mLock");
    }
    static boolean enabled(Object provider) throws ReflectiveOperationException {
        try {
            if (!Boolean.TRUE.equals(call(provider, "hasProvider", new Class<?>[0]))) return false;
        } catch (NoSuchMethodException legacy) {
            return Boolean.TRUE.equals(call(provider, "isUseableLocked", new Class<?>[0]));
        }
        int user;
        try { user = ((Number) call(field(provider, "mUserHelper"), "getCurrentUserId", new Class<?>[0])).intValue(); }
        catch (NoSuchFieldException old) {
            Object helper = field(field(provider, "this$0"), "mUserInfoHelper");
            int[] users = (int[]) call(helper, "getCurrentUserIds", new Class<?>[0]);
            if (users.length == 0) return false; user = users[0];
        }
        return Boolean.TRUE.equals(call(provider, "isEnabled", new Class<?>[]{int.class}, user));
    }
    static int reportPriority(Method method) {
        String type = method.getParameterTypes()[0].getName();
        return type.equals("android.location.LocationResult") ? 0 : type.equals("android.location.Location") ? 1 : 2;
    }
}
