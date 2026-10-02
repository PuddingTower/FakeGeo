package io.github.fakegeo.root;

import io.github.fakegeo.config.ModuleVersion;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeGateTest {
    @Test public void oldSystemCodeCannotBeEnabledAfterAnApkUpdate() throws Exception {
        JSONObject legacy = new JSONObject().put("reportedAt", 100000);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(legacy, 100000));
        legacy.put("moduleVersion", "0.2.1");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(legacy, 100000));
    }
    @Test public void onlyAFreshReportFromTheCurrentModuleCanEnableSystemMode() throws Exception {
        JSONObject current = new JSONObject().put("moduleVersion", ModuleVersion.CURRENT).put("reportedAt", 100000);
        RuntimeGate.requireCurrent(current, 100000);
        RuntimeGate.requireCurrent(current, 115000);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(current, 115001));
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(current, 99999));
    }
    @Test public void systemBaselineRequiresTheGnssImplementationVersion() throws Exception {
        JSONObject baseline = new JSONObject().put("moduleVersion", ModuleVersion.SYSTEM_BASELINE).put("reportedAt", 100000);
        RuntimeGate.requireCurrent(baseline, 100001);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(baseline, 115001));
        baseline.put("moduleVersion", "unknown");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(baseline, 100001));
        baseline.put("moduleVersion", "0.2.2");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(baseline, 100001));
        baseline.put("moduleVersion", "0.2.3");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(baseline, 100001));
        baseline.put("moduleVersion", "0.2.4");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(baseline, 100001));
    }
    @Test public void configurationReadFailurePreventsNewEnableEvenWithFreshCode() throws Exception {
        JSONObject status=new JSONObject().put("moduleVersion",ModuleVersion.CURRENT).put("reportedAt",100000)
                .put("configError","unavailable");
        assertThrows(IllegalStateException.class,()->RuntimeGate.requireCurrent(status,100001));
        status.put("configError",""); RuntimeGate.requireCurrent(status,100001);
    }
    @Test public void wifiModeRequiresRecipientProtectionAndRejectsThePreviousBaseline() throws Exception {
        JSONObject old = new JSONObject().put("moduleVersion", "0.2.5").put("reportedAt", 100000);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(old, 100001));
        old.put("moduleVersion", "0.3.0");
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireCurrent(old, 100001));
        JSONObject current = new JSONObject().put("moduleVersion", ModuleVersion.CURRENT).put("reportedAt", 100000);
        RuntimeGate.requireCurrent(current, 100001);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireWifiConnectivity(current));
        current.put("wifiConnectivityProtected", false);
        assertThrows(IllegalStateException.class, () -> RuntimeGate.requireWifiConnectivity(current));
        current.put("wifiConnectivityProtected", true); RuntimeGate.requireWifiConnectivity(current);
    }
    @Test public void motionNeedsNewSystemCodeRatherThanThePreviousUiOnlyVersion() throws Exception {
        for(String version:new String[]{"0.3.1","0.3.4"}) {
            JSONObject status=new JSONObject().put("moduleVersion",version).put("reportedAt",100000);
            assertThrows(IllegalStateException.class,()->RuntimeGate.requireCurrent(status,100001));
        }
    }
    @Test public void realPositionRequiresTheExplicitCaptureCapability() throws Exception {
        JSONObject report=new JSONObject();
        assertThrows(IllegalStateException.class,()->RuntimeGate.requireRealGps(report));
        report.put("realGpsCaptureSupported",true);RuntimeGate.requireRealGps(report);
    }
    @Test public void joystickRequiresTheCacheContinuityFixEvenOnAnAcceptedBaseline() throws Exception {
        JSONObject report=new JSONObject().put("moduleVersion",ModuleVersion.SYSTEM_BASELINE).put("reportedAt",100000);
        RuntimeGate.requireCurrent(report,100001);
        assertThrows(IllegalStateException.class,()->RuntimeGate.requireMotionContinuity(report));
        report.put("motionCacheContinuitySupported",true);RuntimeGate.requireMotionContinuity(report);
    }
}
