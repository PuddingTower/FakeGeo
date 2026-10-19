package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;

/** Deliberate profile changes invalidate provider caches; same-revision heartbeats do not. */
public final class ProviderCachePolicy {
    public static boolean clear(Profile previous,Profile next) {
        boolean before=active(previous),after=active(next);
        if(!before && !after) return false;
        if(previous!=null && previous.revision==next.revision && before==after) return false;
        return true;
    }
    private static boolean active(Profile p) { return p!=null && p.enabled && p.systemMode && p.location; }
    private ProviderCachePolicy() {}
}
