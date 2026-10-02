package io.github.fakegeo.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class Hooks {
    private Hooks() {}
    static void all(Class<?> type, String name, XC_MethodHook hook) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || Modifier.isAbstract(method.getModifiers())) continue;
            try { XposedBridge.hookMethod(method, hook); }
            catch (Throwable error) { XposedBridge.log("FakeGeo: skipped " + type.getName() + "." + name + ": " + error); }
        }
    }
}
