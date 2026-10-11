package io.github.fakegeo.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class StepCounterModelTest {
    @Test public void untouchedNativeCountersArePassedThroughExactly() {
        StepCounterModel counter=new StepCounterModel();
        assertEquals(120,counter.nativeValue(120,false),0);assertEquals(123,counter.nativeValue(123,false),0);
    }
    @Test public void stopResumeAndMultipleListenersNeverRollBackOrDoubleCount() {
        StepCounterModel counter=new StepCounterModel();counter.nativeValue(120,false);
        assertEquals(120,counter.virtualValue(0),0);assertEquals(122,counter.virtualValue(2),0);
        assertEquals(122,counter.virtualValue(2),0);
        counter.nativeValue(121,true); // Native events are suppressed during simulation.
        assertEquals(122,counter.nativeValue(121,false),0);
        assertEquals(123,counter.nativeValue(122,false),0);
        assertEquals(123,counter.nativeValue(122,false),0);
        assertEquals(123,counter.virtualValue(2),0);assertEquals(124,counter.virtualValue(3),0);
    }
    @Test public void invalidNativeValuesDoNotCorruptTheSyntheticCounter() {
        StepCounterModel counter=new StepCounterModel();counter.nativeValue(15,false);counter.virtualValue(0);
        assertTrue(Float.isNaN(counter.nativeValue(Float.NaN,true)));
        assertEquals(16,counter.virtualValue(1),0);
    }
}
