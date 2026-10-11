package io.github.fakegeo.config;

/** Published sensor configuration uses its own allowlist, even when location is system-wide. */
public final class ProfileAccess {
    public static Profile snapshot(boolean sensors, boolean ownUid, String[] packages, Profile draft, Profile published) {
        boolean authorized=ownUid,service=false;
        if(packages!=null) for(String name:packages) {
            service|=ServicePackages.NAMES.contains(name);
            authorized|=sensors ? draft.sensorTargets.contains(name) && published.sensorTargets.contains(name)
                    : draft.targets.contains(name) || ServicePackages.NAMES.contains(name);
        }
        return authorized ? (sensors || service ? published : draft) : Profile.defaults();
    }
    private ProfileAccess() {}
}
