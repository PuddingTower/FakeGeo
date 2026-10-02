package io.github.fakegeo.motion;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded private control history; deliberately contains no coordinates or radio identities. */
final class MotionDiagnostics {
    static synchronized void record(Context context,String event,double amount,double kmh,long revision,String detail) {
        try {
            android.content.SharedPreferences prefs=context.createDeviceProtectedStorageContext().getSharedPreferences("joystick_diagnostics",Context.MODE_PRIVATE);
            JSONArray previous="opened".equals(event)?new JSONArray():new JSONArray(prefs.getString("events","[]"));
            JSONArray next=new JSONArray();
            for(int i=Math.max(0,previous.length()-23);i<previous.length();i++) next.put(previous.get(i));
            JSONObject value=new JSONObject().put("event",event).put("at",System.currentTimeMillis())
                    .put("amount",amount).put("targetKmh",kmh).put("revision",revision);
            if(detail!=null) value.put("detail",detail.substring(0,Math.min(280,detail.length())));
            next.put(value);prefs.edit().putString("events",next.toString()).apply();
        } catch(Exception ignored) { /* Diagnostics never interrupt movement or its stop path. */ }
    }
    private MotionDiagnostics() {}
}
