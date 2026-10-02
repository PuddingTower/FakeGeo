package io.github.fakegeo.hook.system;

import android.net.NetworkCapabilities;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectivitySignaturesTest {
    private static class Aosp29 {
        NetworkCapabilities networkCapabilitiesRestrictedForCallerPermissions(NetworkCapabilities nc, int pid, int uid) { return nc; }
    }
    private static class Mainline36 {
        NetworkCapabilities createWithLocationInfoSanitizedIfNecessaryWhenParceled(NetworkCapabilities nc, boolean include, int pid, int uid, String pkg, String tag) { return nc; }
    }
    private static class UnknownVendor {
        NetworkCapabilities networkCapabilitiesRestrictedForCallerPermissions(NetworkCapabilities nc, int uid, int pid, String reason) { return nc; }
        Object maybeSanitizeLocationInfoForCaller(NetworkCapabilities nc, int uid, String pkg) { return nc; }
    }
    @Test public void binderIdentityIsNotUsedForAsynchronousRecipientSelection() throws Exception {
        Method older = Aosp29.class.getDeclaredMethods()[0], current = Mainline36.class.getDeclaredMethods()[0];
        Object[] oldArgs = {null, 4500, 10316}, newArgs = {null, true, 4500, 110316, "test.app", null};
        assertTrue(CallerPolicy.application((Integer) oldArgs[ConnectivitySignatures.recipientUidIndex(older)]));
        assertTrue(CallerPolicy.application((Integer) newArgs[ConnectivitySignatures.recipientUidIndex(current)]));
        newArgs[3] = 1000;
        assertFalse(CallerPolicy.application((Integer) newArgs[ConnectivitySignatures.recipientUidIndex(current)]));
        assertFalse(CallerPolicy.application(4500));
    }
    @Test public void unknownSignaturesAndWrongReturnTypesCannotSelectAnArbitraryIntegerAsUid() {
        for (Method method : UnknownVendor.class.getDeclaredMethods())
            assertEquals(-1, ConnectivitySignatures.recipientUidIndex(method));
    }
}
