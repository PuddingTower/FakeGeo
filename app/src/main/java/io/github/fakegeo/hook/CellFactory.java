package io.github.fakegeo.hook;

import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.gsm.GsmCellLocation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.environment.CellFingerprint;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replays captured LTE/NR identities. A real cell object of the same technology is cloned and
 * rewritten first, because hidden constructors vary across Android releases; synthesis is only a
 * fallback and never returns a half-built object that could panic a caller.
 */
public final class CellFactory {
    private static final int TYPE_LTE = 13, TYPE_NR = 20;
    private CellFactory() {}

    public static List<CellInfo> build(List<CellInfo> real, Profile profile) {
        List<CellInfo> result = new ArrayList<>();
        for (CellFingerprint fake : profile.cellPoints) {
            if (fake.ci <= 0) continue;
            CellInfo carrier = cloneMatching(real, fake.type);
            if (carrier == null) carrier = synthesize(fake);
            if (carrier == null) continue;
            if (apply(carrier, fake)) result.add(carrier);
        }
        return result;
    }

    /** Legacy getCellLocation() reads LAC/CID from a GsmCellLocation even on LTE networks. */
    public static GsmCellLocation legacyLocation(Profile profile) {
        CellFingerprint fake = firstLte(profile);
        if (fake == null) return null;
        try {
            GsmCellLocation location = new GsmCellLocation();
            boolean set = false;
            try { XposedHelpers.callMethod(location, "setLacAndCid", fake.tac, (int) fake.ci); set = true; }
            catch (Throwable hiddenApi) {
                boolean lac = set(location, "mLac", fake.tac);
                boolean cid = set(location, "mCid", (int) fake.ci);
                set = lac && cid;
            }
            set(location, "mPsc", fake.pci);
            return set && location.getLac() == fake.tac && location.getCid() == (int) fake.ci ? location : null;
        } catch (Throwable unavailable) {
            XposedBridge.log("EnvSim: legacy cell location unavailable: " + unavailable);
            return null;
        }
    }

    public static Bundle legacyBundle(Profile profile) {
        CellFingerprint fake = firstLte(profile);
        Bundle bundle = new Bundle();
        bundle.putInt("lac", fake == null ? -1 : fake.tac);
        bundle.putInt("cid", fake == null ? -1 : (int) fake.ci);
        bundle.putInt("psc", fake == null ? -1 : fake.pci);
        bundle.putInt("baseStationId", -1);
        bundle.putInt("baseStationLatitude", Integer.MAX_VALUE);
        bundle.putInt("baseStationLongitude", Integer.MAX_VALUE);
        bundle.putInt("systemId", -1);
        bundle.putInt("networkId", -1);
        return bundle;
    }

    private static CellFingerprint firstLte(Profile profile) {
        for (CellFingerprint fake : profile.cellPoints)
            if (fake.type.equals(CellFingerprint.LTE) && fake.ci > 0) return fake;
        return null;
    }

    private static CellInfo cloneMatching(List<CellInfo> real, String type) {
        if (real == null) return null;
        for (CellInfo candidate : real) {
            if (!matches(candidate, type)) continue;
            try {
                Parcel parcel = Parcel.obtain();
                try {
                    candidate.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    return CellInfo.CREATOR.createFromParcel(parcel);
                } finally { parcel.recycle(); }
            } catch (Throwable unavailable) { return null; }
        }
        return null;
    }

    private static boolean matches(CellInfo info, String type) {
        return type.equals(CellFingerprint.LTE) ? info instanceof CellInfoLte
                : type.equals(CellFingerprint.NR) && info instanceof CellInfoNr;
    }

    private static boolean apply(CellInfo info, CellFingerprint fake) {
        try {
            boolean applied;
            if (info instanceof CellInfoLte) applied = applyLte((CellInfoLte) info, fake);
            else if (info instanceof CellInfoNr) applied = applyNr((CellInfoNr) info, fake);
            else applied = false;
            if (!applied) return false;
            set(info, "mRegistered", fake.registered);
            set(info, "mTimeStamp", SystemClock.elapsedRealtimeNanos());
            set(info, "mCellConnectionStatus", fake.registered ? 1 : 0);
            return true;
        } catch (Throwable unavailable) {
            XposedBridge.log("EnvSim: cell rewrite unavailable: " + unavailable);
            return false;
        }
    }

    private static boolean applyLte(CellInfoLte info, CellFingerprint fake) {
        CellIdentityLte identity = info.getCellIdentity();
        CellSignalStrengthLte signal = info.getCellSignalStrength();
        if (identity == null || signal == null) return false;
        int mcc = number(fake.mcc), mnc = number(fake.mnc);
        set(identity, "mMcc", mcc); set(identity, "mMnc", mnc);
        set(identity, "mMccStr", fake.mcc); set(identity, "mMncStr", fake.mnc);
        set(identity, "mTac", fake.tac);
        set(identity, "mCi", (int) fake.ci); set(identity, "mNci", fake.ci);
        set(identity, "mPci", fake.pci); set(identity, "mEarfcn", fake.earfcn);
        set(signal, "mRsrp", fake.signal); set(signal, "mRsrq", -10);
        set(signal, "mRssnr", 20); set(signal, "mCqi", 0); set(signal, "mCqiTableIndex", 0);
        set(signal, "mTimingAdvance", 0); set(signal, "mLevel", level(fake.signal));
        return identity.getTac() == fake.tac && identity.getPci() == fake.pci
                && identity.getCi() == (int) fake.ci && identity.getMcc() == mcc && identity.getMnc() == mnc;
    }

    private static boolean applyNr(CellInfoNr info, CellFingerprint fake) {
        CellIdentityNr identity = (CellIdentityNr) info.getCellIdentity();
        CellSignalStrengthNr signal = (CellSignalStrengthNr) info.getCellSignalStrength();
        if (identity == null || signal == null) return false;
        int mcc = number(fake.mcc), mnc = number(fake.mnc);
        set(identity, "mMcc", mcc); set(identity, "mMnc", mnc);
        set(identity, "mMccStr", fake.mcc); set(identity, "mMncStr", fake.mnc);
        set(identity, "mTac", fake.tac); set(identity, "mPci", fake.pci);
        set(identity, "mNci", fake.ci); set(identity, "mNrArfcn", fake.earfcn);
        set(identity, "mNrarfcn", fake.earfcn);
        set(signal, "mSsRsrp", fake.signal); set(signal, "mSsRsrq", -10); set(signal, "mSsSinr", 20);
        set(signal, "mCsiRsrp", fake.signal); set(signal, "mCsiRsrq", -10); set(signal, "mCsiSinr", 20);
        set(signal, "mCsiCqiTableIndex", 0); set(signal, "mCsiCqiReport", new ArrayList<Integer>());
        set(signal, "mTimingAdvanceMicros", 0); set(signal, "mLevel", level(fake.signal));
        return fake.mcc.equals(identity.getMccString()) && fake.mnc.equals(identity.getMncString())
                && identity.getTac() == fake.tac && identity.getPci() == fake.pci && identity.getNci() == fake.ci;
    }

    /** Last resort when the app has no real cell of this technology (for example a Wi-Fi-only tablet). */
    private static CellInfo synthesize(CellFingerprint fake) {
        try {
            if (fake.type.equals(CellFingerprint.LTE)) {
                CellInfoLte info = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                CellIdentityLte identity = identityLte(fake);
                CellSignalStrengthLte signal = (CellSignalStrengthLte) XposedHelpers.newInstance(CellSignalStrengthLte.class);
                setDefaultLte(identity);
                XposedHelpers.setObjectField(info, "mCellIdentityLte", identity);
                XposedHelpers.setObjectField(info, "mCellSignalStrengthLte", signal);
                return apply(info, fake) ? info : null;
            }
            if (fake.type.equals(CellFingerprint.NR)) {
                CellInfoNr info = (CellInfoNr) XposedHelpers.newInstance(CellInfoNr.class);
                CellIdentityNr identity = identityNr(fake);
                CellSignalStrengthNr signal = (CellSignalStrengthNr) XposedHelpers.newInstance(CellSignalStrengthNr.class);
                setDefaultNr(identity);
                XposedHelpers.setObjectField(info, "mCellIdentityNr", identity);
                XposedHelpers.setObjectField(info, "mCellSignalStrengthNr", signal);
                return apply(info, fake) ? info : null;
            }
        } catch (Throwable unavailable) {
            XposedBridge.log("EnvSim: cell synthesis unavailable: " + unavailable);
        }
        return null;
    }

    private static CellIdentityLte identityLte(CellFingerprint fake) {
        int mcc = number(fake.mcc), mnc = number(fake.mnc), ci = (int) fake.ci;
        Object[][] variants = {
                {mcc, mnc, ci, fake.pci, fake.tac, fake.earfcn},
                {mcc, mnc, ci, fake.pci, fake.tac},
                {mcc, mnc, ci, fake.pci, fake.tac, fake.earfcn, 0}
        };
        for (Object[] values : variants) {
            try { return (CellIdentityLte) XposedHelpers.newInstance(CellIdentityLte.class, values); }
            catch (Throwable mismatch) { /* Try the next shape. */ }
        }
        CellIdentityLte identity = (CellIdentityLte) XposedHelpers.newInstance(CellIdentityLte.class);
        set(identity, "mMcc", mcc); set(identity, "mMnc", mnc);
        set(identity, "mMccStr", fake.mcc); set(identity, "mMncStr", fake.mnc);
        set(identity, "mType", TYPE_LTE);
        return identity;
    }

    private static CellIdentityNr identityNr(CellFingerprint fake) {
        int mcc = number(fake.mcc), mnc = number(fake.mnc), pci = fake.pci, tac = fake.tac, arfcn = fake.earfcn;
        Object[][] variants = {
                {pci, tac, arfcn, fake.ci, mcc, mnc},
                {pci, tac, arfcn, fake.ci, new int[0], fake.mcc, fake.mnc},
                {pci, tac, arfcn, fake.ci, mcc, mnc, fake.mcc, fake.mnc}
        };
        for (Object[] values : variants) {
            try { return (CellIdentityNr) XposedHelpers.newInstance(CellIdentityNr.class, values); }
            catch (Throwable mismatch) { /* Try the next shape. */ }
        }
        CellIdentityNr identity = (CellIdentityNr) XposedHelpers.newInstance(CellIdentityNr.class);
        set(identity, "mMcc", mcc); set(identity, "mMnc", mnc);
        set(identity, "mMccStr", fake.mcc); set(identity, "mMncStr", fake.mnc);
        set(identity, "mType", TYPE_NR);
        return identity;
    }

    private static void setDefaultLte(CellIdentityLte identity) {
        set(identity, "mBands", new int[0]);
        set(identity, "mAdditionalPlmns", new HashSet<String>());
        set(identity, "mBandwidth", 0);
        set(identity, "mNci", 0L);
        set(identity, "mCsgInfo", null);
    }

    private static void setDefaultNr(CellIdentityNr identity) {
        set(identity, "mBands", new int[0]);
        set(identity, "mAdditionalPlmns", new HashSet<String>());
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static int level(int rsrp) {
        if (rsrp >= -85) return 4;
        if (rsrp >= -95) return 3;
        if (rsrp >= -105) return 2;
        if (rsrp >= -115) return 1;
        return 0;
    }

    private static boolean set(Object target, String name, Object value) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException absent) { /* Walk up to the base class. */ }
            catch (Throwable unavailable) { return false; }
        }
        return false;
    }
}
