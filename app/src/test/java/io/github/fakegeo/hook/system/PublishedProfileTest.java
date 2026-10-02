package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublishedProfileTest {
    @Test public void invalidRefreshDoesNotResumeRealLocation() throws Exception {
        PublishedProfile state=new PublishedProfile();
        state.update(new JSONObject().put("revision",10).put("enabled",true).toString());
        Profile accepted=state.get();
        assertThrows(Exception.class,()->state.update("not json"));
        assertSame(accepted,state.get()); assertTrue(state.get().enabled);
        assertThrows(IllegalArgumentException.class,()->state.update(new JSONObject().put("revision",11).put("latitude",91).toString()));
        assertSame(accepted,state.get());
    }
    @Test public void validDisableEndsReplacementAndOldRevisionCannotReenableIt() throws Exception {
        PublishedProfile state=new PublishedProfile();
        state.update(new JSONObject().put("revision",10).put("enabled",true).toString());
        state.update(new JSONObject().put("revision",11).put("enabled",false).toString());
        assertFalse(state.get().enabled);
        assertThrows(IllegalArgumentException.class,()->state.update(new JSONObject().put("revision",10).put("enabled",true).toString()));
        assertFalse(state.get().enabled); assertEquals(11,state.get().revision);
    }
    @Test public void startupWithInvalidPublicationRemainsDisabled() {
        PublishedProfile state=new PublishedProfile();
        assertThrows(Exception.class,()->state.update("{"));
        assertFalse(state.get().enabled); assertEquals(0,state.get().revision);
    }
}
