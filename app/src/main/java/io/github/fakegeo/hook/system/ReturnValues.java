package io.github.fakegeo.hook.system;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/** Bluetooth binder helpers changed from arrays to Lists with ResultReceiver-based AIDL. */
public final class ReturnValues {
    private ReturnValues() {}
    public static Object emptyDevices(Object value) {
        if (value instanceof List) return new ArrayList<>();
        if (value != null && value.getClass().isArray())
            return Array.newInstance(value.getClass().getComponentType(), 0);
        return value;
    }
    public static Object listResult(Class<?> declaredType, List<?> values) throws ReflectiveOperationException {
        ArrayList<?> copy = new ArrayList<>(values);
        if (declaredType.isInstance(copy)) return copy;
        if (declaredType.isArray()) {
            Object result = Array.newInstance(declaredType.getComponentType(), copy.size());
            for (int i = 0; i < copy.size(); i++) Array.set(result, i, copy.get(i));
            return result;
        }
        // APEX jars can relocate com.android.modules.utils under vendor-specific prefixes.
        if (declaredType.getSimpleName().equals("ParceledListSlice")) {
            java.lang.reflect.Constructor<?> constructor = declaredType.getDeclaredConstructor(List.class);
            constructor.setAccessible(true); return constructor.newInstance(copy);
        }
        throw new IllegalArgumentException("Unsupported list return type: " + declaredType.getName());
    }
    public static boolean accepts(Class<?> declaredType, Object result) {
        if (!declaredType.isPrimitive()) return result == null || declaredType.isInstance(result);
        if (declaredType == void.class) return result == null;
        return (declaredType == boolean.class && result instanceof Boolean)
                || (declaredType == int.class && result instanceof Integer)
                || (declaredType == long.class && result instanceof Long)
                || (declaredType == float.class && result instanceof Float)
                || (declaredType == double.class && result instanceof Double)
                || (declaredType == byte.class && result instanceof Byte)
                || (declaredType == short.class && result instanceof Short)
                || (declaredType == char.class && result instanceof Character);
    }
}
