package io.github.fakegeo.config;

import io.github.fakegeo.BuildConfig;

public final class ModuleVersion {
    public static final String CURRENT = BuildConfig.VERSION_NAME;
    /** Bumped only when system_server hook code changes; app-only releases keep the old baseline. */
    public static final String SYSTEM_BASELINE = "0.5.0";
    private ModuleVersion() {}
}
