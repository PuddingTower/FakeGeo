package io.github.fakegeo.debug;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.os.Parcel;
import android.os.Parcelable;
import dalvik.system.PathClassLoader;
import io.github.fakegeo.hook.system.ReturnValues;
import java.util.Collections;

final class ReturnContractRegression {
    private ReturnContractRegression() {}
    static String run(Context context) throws ReflectiveOperationException {
        if (android.os.Build.VERSION.SDK_INT < 36) throw new IllegalStateException("This device regression targets the Android 16 Wi-Fi APEX");
        // App reflection cannot see this boot-class constructor (hiddenapi BLOCKED).
        // Load the device's identical APEX definitions in an isolated loader for this
        // disabled-environment regression; production uses the service's return Class.
        ClassLoader loader = new PathClassLoader("/apex/com.android.wifi/javalib/framework-wifi.jar", context.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.android.wifi.x.com.android.modules.utils.")) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        };
        Class<?> declared = Class.forName("com.android.wifi.x.com.android.modules.utils.ParceledListSlice", false, loader);
        ScanResult fake = new ScanResult(); fake.SSID = "FakeGeo-regression"; fake.BSSID = "02:00:00:00:00:10";
        Object slice = ReturnValues.listResult(declared, Collections.singletonList(fake));
        if (!ReturnValues.accepts(declared, slice)) throw new AssertionError("Replacement has the wrong binder type");
        Object list = declared.getMethod("getList").invoke(slice);
        if (!(list instanceof java.util.List) || ((java.util.List<?>) list).size() != 1)
            throw new AssertionError("Slice does not contain the synthetic result");
        if (ReturnValues.accepts(declared, Collections.singletonList(fake)))
            throw new AssertionError("Old ArrayList mismatch was not rejected");
        Parcel parcel = Parcel.obtain();
        try {
            ((Parcelable) slice).writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) declared.getField("CREATOR").get(null);
            Object decoded = creator.createFromParcel(parcel);
            Object decodedList = declared.getMethod("getList").invoke(decoded);
            if (!(decodedList instanceof java.util.List) || ((java.util.List<?>) decodedList).size() != 1
                    || !fake.BSSID.equals(((ScanResult) ((java.util.List<?>) decodedList).get(0)).BSSID))
                throw new AssertionError("Wi-Fi binder parcel round trip failed");
        } finally { parcel.recycle(); }
        return "WIFI_RETURN_CONTRACT_PASSED (isolated device APEX + parcel round trip): " + declared.getName();
    }
}
