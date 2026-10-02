package io.github.fakegeo.update;

import io.github.fakegeo.ui.AppInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubUpdateTest {
    @Test public void versionsCompareNumericallyAndSupportReleaseTags() {
        assertTrue(new Version("v0.3.10").compareTo(new Version("0.3.9")) > 0);
        assertEquals(0, new Version("1.2").compareTo(new Version("1.2.0+build.2")));
        assertTrue(new Version("1.0.0").compareTo(new Version("1.0.0-rc.10")) > 0);
        assertTrue(new Version("1.0.0-rc.10").compareTo(new Version("1.0.0-rc.2")) > 0);
        assertTrue(new Version("2.0.0").compareTo(new Version("10.0.0")) < 0);
        assertThrows(IllegalArgumentException.class, () -> new Version("release-current"));
    }
    @Test public void onlyNewerStableReleasesPromptAndLinksStayInThisRepository() throws Exception {
        GitHubRelease release = new GitHubRelease(new JSONObject().put("tag_name", "v0.3.10").put("html_url", AppInfo.RELEASES + "/tag/v0.3.10"));
        assertTrue(release.newerThan("0.3.4")); assertFalse(release.newerThan("0.3.10")); assertFalse(release.newerThan("0.4.0"));
        assertThrows(IllegalArgumentException.class, () -> new GitHubRelease(new JSONObject().put("tag_name", "v1.0.0").put("prerelease", true)));
        GitHubRelease foreign = new GitHubRelease(new JSONObject().put("tag_name", "v1.0.0").put("html_url", "https://github.com.evil.example/update"));
        assertEquals(AppInfo.RELEASES, foreign.url);
    }
    @Test public void missingReleaseAndRateLimitAreErrorsNotUpToDate() throws Exception {
        IOException missing = assertThrows(IOException.class, () -> GitHubUpdateClient.read(new Response(404, "{}")));
        assertTrue(missing.getMessage().contains("公开"));
        assertThrows(IOException.class, () -> GitHubUpdateClient.read(new Response(403, "{}")));
        assertThrows(IOException.class, () -> GitHubUpdateClient.read(new Response(429, "{}")));
        assertThrows(IOException.class, () -> GitHubUpdateClient.read(new Response(500, "{}")));
    }
    @Test public void malformedAndOversizedResponsesCannotReportSuccessfulUpdateCheck() throws Exception {
        assertThrows(Exception.class, () -> GitHubUpdateClient.read(new Response(200, "not json")));
        assertThrows(IllegalArgumentException.class, () -> GitHubUpdateClient.read(new Response(200, "{}")));
        assertThrows(IOException.class, () -> GitHubUpdateClient.read(new Response(200, "x".repeat(1024 * 1024 + 1))));
        assertEquals("v0.3.5", GitHubUpdateClient.read(new Response(200, "{\"tag_name\":\"v0.3.5\"}")).tag);
    }
    private static final class Response extends HttpURLConnection {
        private final int code; private final byte[] data;
        Response(int code, String body) throws Exception { super(new URL("https://api.github.com/")); this.code = code; data = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        @Override public int getResponseCode() { return code; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
