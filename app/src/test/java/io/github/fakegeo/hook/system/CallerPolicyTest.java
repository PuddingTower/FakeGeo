package io.github.fakegeo.hook.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallerPolicyTest {
    @Test public void osAndRadioCallersRemainInternalInEveryUser() {
        for (int user : new int[]{0, 1, 10}) for (int app : new int[]{0, 1000, 1001, 1002, 2000, 9999})
            assertFalse(CallerPolicy.application(user * 100000 + app));
        assertFalse(CallerPolicy.application(-1));
    }
    @Test public void applicationAndIsolatedCallersAreClassifiedAcrossUsers() {
        for (int user : new int[]{0, 1, 10}) for (int app : new int[]{10000, 10379, 19999, 99000})
            assertTrue(CallerPolicy.application(user * 100000 + app));
    }
}
