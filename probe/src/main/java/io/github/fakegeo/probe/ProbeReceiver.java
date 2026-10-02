package io.github.fakegeo.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Parcel;

public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Location location = intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
        String value = "PendingIntent: " + (location == null ? "无位置" : location.getLatitude() + ", " + location.getLongitude());
        // Record actual parcel fields as well as getters in the target process.
        if (location != null) {
            Parcel parcel = Parcel.obtain();
            try {
                location.writeToParcel(parcel, 0); parcel.setDataPosition(0);
                Location copy = Location.CREATOR.createFromParcel(parcel); value += " · parcel=" + copy;
            } finally { parcel.recycle(); }
        }
        context.getSharedPreferences("probe", Context.MODE_PRIVATE).edit().putString("pending", value).apply();
    }
}
