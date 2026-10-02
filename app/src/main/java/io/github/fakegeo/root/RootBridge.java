package io.github.fakegeo.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootBridge {
    private RootBridge() {}
    public static synchronized String execute(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
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
    public static void authorize() throws IOException, InterruptedException {
        if (!"0".equals(execute(RootCommands.authorization()))) throw new IOException("su 未获得 UID 0，请检查 KernelSU 的 Root 配置。");
    }
}
