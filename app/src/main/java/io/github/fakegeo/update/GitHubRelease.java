package io.github.fakegeo.update;

import io.github.fakegeo.ui.AppInfo;
import java.net.URI;
import org.json.JSONObject;

public final class GitHubRelease {
    private static final String RELEASES_PATH = "/" + AppInfo.REPOSITORY_SLUG + "/releases/";
    public final String tag, notes, url;
    public final Version version;
    public GitHubRelease(JSONObject json) {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw new IllegalArgumentException("没有可用的正式 Release");
        tag = json.optString("tag_name", ""); version = new Version(tag);
        String description = json.optString("body", ""); notes = description.length() > 1800 ? description.substring(0, 1800) + "…" : description;
        String candidate = json.optString("html_url", "");
        try {
            URI link = URI.create(candidate);
            url = "https".equals(link.getScheme()) && "github.com".equals(link.getHost()) && link.getUserInfo() == null
                    && (link.getPort() == -1 || link.getPort() == 443) && link.getPath() != null
                    && link.getPath().startsWith(RELEASES_PATH) ? candidate : AppInfo.RELEASES;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Release 链接无效"); }
    }
    public boolean newerThan(String current) { return version.compareTo(new Version(current)) > 0; }
}
