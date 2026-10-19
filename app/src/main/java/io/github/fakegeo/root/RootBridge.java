package io.github.fakegeo.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootBridge {
    /** KernelSU and APatch keep su outside the zygote PATH, so a bare "su" is only the first guess. */
    private static final String[] CANDIDATES = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su"};
    private static volatile String working;
    private RootBridge() {}
    public static synchronized String execute(String command) throws IOException, InterruptedException {
        Process process = start(command);
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new IOException("Root 请求超时，请在 KernelSU 管理器授权 FakeGeo 后重试。");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while (bytes.size() < 32768 && (count = process.getInputStream().read(buffer, 0, Math.min(buffer.length, 32768 - bytes.size()))) > 0)
                bytes.write(buffer, 0, count);
            String output = bytes.toString(StandardCharsets.UTF_8.name()).trim();
            if (process.exitValue() != 0) throw new IOException("Root 操作失败：" + output);
            return output;
        } finally { process.destroy(); }
    }
    private static Process start(String command) throws IOException {
        String preferred = working;
        IOException last = null;
        for (int index = 0; index < CANDIDATES.length; index++) {
            String candidate = preferred != null && index == 0 ? preferred : CANDIDATES[index];
            if (candidate == null) continue;
            try {
                Process process = new ProcessBuilder(candidate, "-c", command).redirectErrorStream(true).start();
                working = candidate;
                return process;
            } catch (IOException missing) { last = missing; }
        }
        throw last == null ? new IOException("未找到 su 可执行文件") : last;
    }
    public static void authorize() throws IOException, InterruptedException {
        if (!"0".equals(execute(RootCommands.authorization()))) throw new IOException("su 未获得 UID 0，请检查 KernelSU 的 Root 配置。");
    }
}
