package io.github.fakegeo.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeliveryGateTest {
    @Test public void cancelInvalidatesAlreadyQueuedCallback() {
        DeliveryGate gate = new DeliveryGate(100, Long.MAX_VALUE, 5);
        assertTrue(gate.queue(100)); gate.cancel();
        assertFalse(gate.mayDeliver(101)); assertFalse(gate.queue(102));
    }
    @Test public void oneShotCompletesExactlyOnce() {
        DeliveryGate gate = new DeliveryGate(100, Long.MAX_VALUE, 1);
        assertTrue(gate.queue(100)); assertFalse(gate.queue(100)); assertTrue(gate.mayDeliver(100));
        gate.complete(true); assertTrue(gate.finished(101)); assertFalse(gate.queue(101));
    }
    @Test public void expirationInvalidatesDeliveryWaitingOnAnExecutor() {
        DeliveryGate gate = new DeliveryGate(100, 20, 5);
        assertTrue(gate.queue(110)); assertTrue(gate.mayDeliver(119)); assertFalse(gate.mayDeliver(120));
    }
    @Test public void unsuccessfulDeliveryDoesNotConsumeLimit() {
        DeliveryGate gate = new DeliveryGate(100, 1000, 1);
        assertTrue(gate.queue(100)); gate.complete(false); assertTrue(gate.queue(101));
        gate.complete(true); assertFalse(gate.queue(102));
    }
    @Test public void saturatesDeadlineWithoutOverflow() {
        DeliveryGate gate = new DeliveryGate(Long.MAX_VALUE - 100, 200, 1);
        assertTrue(gate.queue(Long.MAX_VALUE - 50)); assertTrue(gate.mayDeliver(Long.MAX_VALUE - 1));
        assertTrue(gate.finished(Long.MAX_VALUE));
    }
}
