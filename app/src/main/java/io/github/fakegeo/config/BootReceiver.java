package io.github.fakegeo.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit URI grants expire at reboot; restore them before services poll the provider. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try { ConfigStore.restoreGrants(context); }
        catch (RuntimeException failure) { android.util.Log.e("FakeGeo", "Restoring service URI grants failed", failure); }
    }
}
