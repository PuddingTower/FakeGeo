package io.github.fakegeo.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

public final class ServicePackages {
    public static final Set<String> AOSP_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.android.phone", "com.android.bluetooth", "com.android.wifi", "com.google.android.wifi")));
    /** ColorOS 16 / Qualcomm fusion stack. Absent packages simply never load the module. */
    public static final Set<String> VENDOR_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.oplus.location", "com.oplus.locationproxy", "com.oplus.nhs", "com.oplus.athena", "com.qualcomm.location")));
    public static final Set<String> NAMES = union(AOSP_NAMES, VENDOR_NAMES);
    /** Debug builds install beside the release id, so both may own the keep-alive exemption. */
    public static final Set<String> MODULE_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            io.github.fakegeo.BuildConfig.APPLICATION_ID, "io.github.fakegeo")));
    private ServicePackages() {}
    public static boolean isVendor(String name) { return VENDOR_NAMES.contains(name); }
    private static Set<String> union(Set<String> first, Set<String> second) {
        HashSet<String> all = new HashSet<>(first); all.addAll(second);
        return Collections.unmodifiableSet(all);
    }
}
