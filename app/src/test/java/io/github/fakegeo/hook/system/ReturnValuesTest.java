package io.github.fakegeo.hook.system;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReturnValuesTest {
    public static class ParceledListSlice {
        final List<?> list;
        public ParceledListSlice(List<?> list) { this.list = list; }
    }
    public static class BrokenWrapper {
        public static class ParceledListSlice { public ParceledListSlice() {} }
    }
    @Test public void oldBinderRetainsItsTypedArrayContract() {
        String[] original = {"device"};
        Object result = ReturnValues.emptyDevices(original);
        assertEquals(String[].class, result.getClass()); assertEquals(0, ((String[]) result).length);
        assertEquals(1, original.length);
    }
    @Test public void receiverBasedBinderGetsAListRatherThanAnArray() {
        List<String> original = Collections.singletonList("device");
        Object result = ReturnValues.emptyDevices(original);
        assertTrue(result instanceof List); assertTrue(((List<?>) result).isEmpty());
        assertEquals(1, original.size()); assertNull(ReturnValues.emptyDevices(null));
    }
    @Test public void wifiListsAndRelocatedParcelSlicesKeepTheirDeclaredReturnType() throws Exception {
        List<String> values = Collections.singletonList("virtual AP");
        Object oldApi = ReturnValues.listResult(List.class, values);
        Object relocated = ReturnValues.listResult(ParceledListSlice.class, values);
        assertTrue(ReturnValues.accepts(List.class, oldApi));
        assertTrue(ReturnValues.accepts(ParceledListSlice.class, relocated));
        assertEquals(values, ((ParceledListSlice) relocated).list);
        assertFalse(ReturnValues.accepts(ParceledListSlice.class, oldApi));
    }
    @Test public void unsupportedWrappersFailBeforePassingAValueToLsposed() {
        assertThrows(IllegalArgumentException.class, () -> ReturnValues.listResult(String.class, Collections.emptyList()));
        assertThrows(NoSuchMethodException.class, () -> ReturnValues.listResult(BrokenWrapper.ParceledListSlice.class, Collections.emptyList()));
    }
    @Test public void typedArraysAndPrimitiveResultsAreValidated() throws Exception {
        Object array = ReturnValues.listResult(String[].class, Collections.singletonList("AP"));
        assertArrayEquals(new String[]{"AP"}, (String[]) array);
        assertFalse(ReturnValues.accepts(String[].class, Collections.emptyList()));
        assertTrue(ReturnValues.accepts(boolean.class, true));
        assertFalse(ReturnValues.accepts(boolean.class, 1));
        assertFalse(ReturnValues.accepts(int.class, 1L));
        assertFalse(ReturnValues.accepts(int.class, null));
        assertTrue(ReturnValues.accepts(List.class, null));
    }
}
