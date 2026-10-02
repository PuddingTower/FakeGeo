package io.github.fakegeo.root;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class RootCommandsTest {
    @Test public void payloadCannotBecomeShellCode() {
        String hostile = "{\"ssid\":\"';$(touch /bad);`id`\\n\"}";
        String command = RootCommands.publish(hostile);
        assertFalse(command.contains(hostile)); assertFalse(command.contains("touch /bad"));
        String encoded = Base64.getEncoder().encodeToString(hostile.getBytes(StandardCharsets.UTF_8));
        assertTrue(command.contains("'" + encoded + "'"));
    }
    @Test public void publishesAtomicallyWithRestrictedOwnershipAndContext() {
        String command = RootCommands.publish("{}");
        assertTrue(command.contains("chown 1000:1000")); assertTrue(command.contains("chmod 600"));
        assertTrue(command.contains("system_data_file")); assertTrue(command.contains("mv -f"));
        assertFalse(command.contains("setenforce")); assertFalse(command.contains("chmod 777"));
    }
}
