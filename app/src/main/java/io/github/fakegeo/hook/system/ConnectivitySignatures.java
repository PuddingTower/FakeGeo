package io.github.fakegeo.hook.system;

import android.net.NetworkCapabilities;
import java.lang.reflect.Method;

/** Explicit recipient positions, including callbacks with no application Binder identity. */
public final class ConnectivitySignatures {
    private ConnectivitySignatures() {}
    public static int recipientUidIndex(Method method) {
        Class<?>[] args = method.getParameterTypes();
        if (method.getReturnType() != NetworkCapabilities.class || args.length == 0 || args[0] != NetworkCapabilities.class) return -1;
        if (method.getName().equals("networkCapabilitiesRestrictedForCallerPermissions")
                && args.length == 3 && args[1] == int.class && args[2] == int.class) return 2;
        if (method.getName().equals("createWithLocationInfoSanitizedIfNecessaryWhenParceled")
                && (args.length == 5 || args.length == 6) && args[1] == boolean.class
                && args[2] == int.class && args[3] == int.class && args[4] == String.class
                && (args.length == 5 || args[5] == String.class)) return 3;
        if (method.getName().equals("maybeSanitizeLocationInfoForCaller")
                && args.length == 3 && args[1] == int.class && args[2] == String.class) return 1;
        return -1;
    }
}
