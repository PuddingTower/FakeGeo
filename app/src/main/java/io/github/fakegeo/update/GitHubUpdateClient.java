package io.github.fakegeo.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class GitHubUpdateClient {
    public static final String ENDPOINT = "https://api.github.com/repos/myxuebi/FakeGeo/releases/latest";
    public static GitHubRelease latest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        connection.setRequestProperty("User-Agent", "FakeGeo-UpdateChecker");
        try { return read(connection); }
        catch (java.net.UnknownHostException | java.net.ConnectException | javax.net.ssl.SSLException failure) {
            throw new IOException("无法连接 GitHub，请检查网络后重试。", failure);
        } catch (java.net.SocketTimeoutException failure) {
            throw new IOException("GitHub 连接超时，请稍后重试。", failure);
        } finally { connection.disconnect(); }
    }
    static GitHubRelease read(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code == 404) throw new IOException("未找到公开的正式 Release，请确认仓库已公开并发布版本。");
        if (code == 403 || code == 429) throw new IOException("GitHub 请求受限，请稍后再试。");
        if (code != 200) throw new IOException("GitHub 返回 HTTP " + code + "，请稍后重试。");
        try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = stream.read(chunk)) != -1) {
                if (bytes.size() + count > 1024 * 1024) throw new IOException("Release 信息过大，无法检查更新。");
                bytes.write(chunk, 0, count);
            }
            try { return new GitHubRelease(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()))); }
            catch (org.json.JSONException malformed) { throw new IOException("GitHub Release 信息格式无效，请稍后重试。", malformed); }
        }
    }
    private GitHubUpdateClient() {}
}
