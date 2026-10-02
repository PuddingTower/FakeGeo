package io.github.fakegeo.update;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric release precedence, including v tags and semantic prerelease identifiers. */
public final class Version implements Comparable<Version> {
    private static final Pattern FORMAT = Pattern.compile("[vV]?([0-9]+(?:\\.[0-9]+){0,3})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?");
    private final String[] numbers, prerelease;
    public Version(String value) {
        if (value == null || value.length() > 128) throw new IllegalArgumentException("无法识别版本号");
        Matcher match = FORMAT.matcher(value.trim());
        if (!match.matches()) throw new IllegalArgumentException("无法识别版本号：" + value);
        numbers = match.group(1).split("\\."); prerelease = match.group(2) == null ? null : match.group(2).split("\\.");
    }
    @Override public int compareTo(Version other) {
        for (int i = 0; i < Math.max(numbers.length, other.numbers.length); i++) {
            BigInteger left = new BigInteger(i < numbers.length ? numbers[i] : "0"), right = new BigInteger(i < other.numbers.length ? other.numbers[i] : "0");
            int order = left.compareTo(right); if (order != 0) return order;
        }
        if (prerelease == null || other.prerelease == null) return prerelease == other.prerelease ? 0 : prerelease == null ? 1 : -1;
        for (int i = 0; i < Math.min(prerelease.length, other.prerelease.length); i++) {
            String a = prerelease[i], b = other.prerelease[i]; boolean numericA = a.matches("[0-9]+"), numericB = b.matches("[0-9]+");
            int order = numericA && numericB ? new BigInteger(a).compareTo(new BigInteger(b)) : numericA != numericB ? (numericA ? -1 : 1) : a.compareTo(b);
            if (order != 0) return order;
        }
        return Integer.compare(prerelease.length, other.prerelease.length);
    }
}
