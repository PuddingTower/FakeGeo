package io.github.fakegeo.config;

import io.github.fakegeo.BuildConfig;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Validation {
    private Validation() {}
    public static double number(String text, double min, double max, String label) {
        final double value;
        try { value = Double.parseDouble(text.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + "必须为数字"); }
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(label + "范围为 " + min + " 到 " + max);
        return value;
    }
    public static String mac(String text) {
        String result = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (!result.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
            throw new IllegalArgumentException("MAC 地址格式应为 02:00:00:00:00:10");
        if ((Integer.parseInt(result.substring(0, 2), 16) & 1) != 0)
            throw new IllegalArgumentException("MAC 地址不能是组播地址");
        return result;
    }
    /** Blank means "feature not configured", which is different from an invalid address. */
    public static String optionalMac(String text) { return text == null || text.trim().isEmpty() ? "" : mac(text); }
    public static String hex(String text, int maxBytes, String label) {
        String value = text == null ? "" : text.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return "";
        if (!value.matches("[0-9A-F]+") || value.length() % 2 != 0 || value.length() / 2 > maxBytes)
            throw new IllegalArgumentException(label + "需要不超过 " + maxBytes + " 字节的十六进制文本");
        return value;
    }
    public static Set<String> packages(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String item : text.split("[\\s,;，；]+")) {
            if (item.isEmpty()) continue;
            if (!item.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("无效包名：" + item);
            // Refuses the release id as well, so a debug build cannot hook an installed release module.
            if (item.equals(BuildConfig.APPLICATION_ID) || item.equals("io.github.fakegeo") || item.startsWith("com.android.")
                    || item.startsWith("com.google.android.gms") || item.equals("android"))
                throw new IllegalArgumentException("不支持把系统组件或模块自身作为目标：" + item);
            result.add(item);
        }
        return result;
    }
    public static String cachePackage(String text) {
        if (text.isEmpty()) return text;
        if (text.length() > 255 || !text.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("无效缓存应用包名");
        packages(text);
        return text;
    }
}
