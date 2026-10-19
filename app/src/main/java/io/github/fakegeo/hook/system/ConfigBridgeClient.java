package io.github.fakegeo.hook.system;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import de.robv.android.xposed.XposedHelpers;

/** Client half of {@link ConfigBinderBridge}; null means "ask the ContentProvider instead". */
public final class ConfigBridgeClient {
    private ConfigBridgeClient() {}
    public static String read(Context context, int kind) {
        if (context == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder binder = locationBinder(context);
            if (binder == null) return null;
            data.writeInt(ConfigBinderBridge.MAGIC);
            data.writeInt(kind);
            // An older system runtime does not know the code, so the transaction simply fails.
            if (!binder.transact(ConfigBinderBridge.TRANSACTION, data, reply, 0)) return null;
            reply.setDataPosition(0);
            if (reply.readInt() != 1) return null;
            return reply.readString();
        } catch (Throwable unavailable) { return null; }
        finally { data.recycle(); reply.recycle(); }
    }
    private static IBinder locationBinder(Context context) {
        Object manager = context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        try { return (IBinder) XposedHelpers.getObjectField(manager, "mService"); }
        catch (Throwable renamed) { /* fall through to the service lookup */ }
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            return (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "location");
        } catch (Throwable unavailable) { return null; }
    }
}
