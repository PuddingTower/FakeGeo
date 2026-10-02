package io.github.fakegeo.hook.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderAccessTest {
    private static class LegacyService {
        final Object mLock = new Object();
        final class LocationProvider {
            final String mName = "gps";
            boolean usable = true;
            public boolean isUseableLocked() { return usable; }
        }
    }
    private static class Users11 { public int[] getCurrentUserIds() { return new int[]{10, 11}; } }
    private static class Service11 {
        final Object mLock = new Object(); final Users11 mUserInfoHelper = new Users11();
        final class Manager {
            int checkedUser = -1;
            public String getName() { return "network"; }
            public boolean hasProvider() { return true; }
            public boolean isEnabled(int user) { checkedUser = user; return user == 10; }
        }
    }
    private static class UsersModern { public int getCurrentUserId() { return 12; } }
    private static class Base { final Object mMultiplexerLock = new Object(); }
    private static class Modern extends Base {
        final UsersModern mUserHelper = new UsersModern(); boolean present = true;
        public boolean hasProvider() { return present; }
        public boolean isEnabled(int user) { return user == 12; }
    }
    private static class Reports {
        public void onReportLocation(android.location.Location location) {}
        public void onReportLocation(java.util.List<android.location.Location> batch) {}
    }
    @Test public void legacyUsesOuterLockAndUsability() throws Exception {
        LegacyService service = new LegacyService(); LegacyService.LocationProvider provider = service.new LocationProvider();
        assertSame(service.mLock, ProviderAccess.lock(provider)); assertEquals("gps", ProviderAccess.name(provider));
        assertTrue(ProviderAccess.enabled(provider)); provider.usable = false; assertFalse(ProviderAccess.enabled(provider));
    }
    @Test public void android11ChecksAnActualUserRatherThanUserCurrentSentinel() throws Exception {
        Service11 service = new Service11(); Service11.Manager manager = service.new Manager();
        assertSame(service.mLock, ProviderAccess.lock(manager)); assertTrue(ProviderAccess.enabled(manager));
        assertEquals(10, manager.checkedUser);
    }
    @Test public void modernResolvesInheritedMultiplexerLockAndCurrentUser() throws Exception {
        Modern manager = new Modern(); assertSame(manager.mMultiplexerLock, ProviderAccess.lock(manager));
        assertTrue(ProviderAccess.enabled(manager)); manager.present = false; assertFalse(ProviderAccess.enabled(manager));
    }
    @Test public void heartbeatPrefersRegularFixOverGnssBatchOverload() throws Exception {
        assertTrue(ProviderAccess.reportPriority(Reports.class.getDeclaredMethod("onReportLocation", android.location.Location.class))
                < ProviderAccess.reportPriority(Reports.class.getDeclaredMethod("onReportLocation", java.util.List.class)));
    }
}
