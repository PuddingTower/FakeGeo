package io.github.fakegeo.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

public final class ServicePackages {
    public static final Set<String> NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.android.phone", "com.android.bluetooth", "com.android.wifi", "com.google.android.wifi")));
    private ServicePackages() {}
}
