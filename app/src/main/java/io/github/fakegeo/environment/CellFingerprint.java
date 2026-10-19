package io.github.fakegeo.environment;

import org.json.JSONObject;
import java.util.Locale;

/** Captured serving/neighbour cell identity. Only LTE and NR are replayed; others remain hidden. */
public final class CellFingerprint {
    public static final String LTE = "lte", NR = "nr", GSM = "gsm", WCDMA = "wcdma",
            TDSCDMA = "tdscdma", CDMA = "cdma";
    public final String type, mcc, mnc;
    public final int tac, pci, earfcn, signal;
    public final long ci;
    public final boolean registered;

    public CellFingerprint(String type, String mcc, String mnc, int tac, long ci, int pci, int earfcn,
                           int signal, boolean registered) {
        this.type = type(type);
        this.mcc = digits(mcc, 3, 3, "MCC");
        this.mnc = digits(mnc, 2, 3, "MNC");
        if (tac < 0 || tac > 65535) throw new IllegalArgumentException("TAC/LAC 超出范围");
        if (ci < 0) throw new IllegalArgumentException("小区标识超出范围");
        if (pci < 0 || pci > 1007) throw new IllegalArgumentException("PCI/PSC 超出范围");
        if (earfcn < 0 || earfcn > 3279165) throw new IllegalArgumentException("频点超出范围");
        this.tac = tac;
        this.ci = ci;
        this.pci = pci;
        this.earfcn = earfcn;
        this.signal = signal < -140 ? -140 : Math.min(signal, -40);
        this.registered = registered;
    }

    public String key() { return type + ":" + mcc + mnc + ":" + tac + ":" + ci; }

    public String label() {
        String technology = type.equals(NR) ? "5G NR" : type.equals(LTE) ? "4G LTE" : type.toUpperCase(Locale.ROOT);
        return technology + " · " + mcc + "-" + mnc + " · TAC " + tac + " · CI " + ci;
    }

    public JSONObject json() {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type).put("mcc", mcc).put("mnc", mnc).put("tac", tac).put("ci", ci)
                    .put("pci", pci).put("earfcn", earfcn).put("signal", signal).put("registered", registered);
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return json;
    }

    public static CellFingerprint parse(JSONObject json) {
        if (json == null) return null;
        String type = json.optString("type", "");
        if (type.isEmpty()) return null;
        return new CellFingerprint(type, json.optString("mcc", ""), json.optString("mnc", ""),
                json.optInt("tac", -1), json.optLong("ci", -1), json.optInt("pci", -1),
                json.optInt("earfcn", -1), json.optInt("signal", -90), json.optBoolean("registered", false));
    }

    private static String type(String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!result.equals(LTE) && !result.equals(NR) && !result.equals(GSM) && !result.equals(WCDMA)
                && !result.equals(TDSCDMA) && !result.equals(CDMA))
            throw new IllegalArgumentException("未知的小区类型");
        return result;
    }

    private static String digits(String value, int min, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("[0-9]{" + min + "," + max + "}"))
            throw new IllegalArgumentException(label + " 格式无效");
        return result;
    }
}
