package io.github.fakegeo.hook.system;

/** Keep radio/OS service callers' network state intact across all Android users. */
public final class CallerPolicy {
    private CallerPolicy() {}
    public static boolean application(int uid) { return uid >= 0 && uid % 100000 >= 10000; }
}
