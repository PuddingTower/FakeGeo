package io.github.fakegeo.config;

/** Published configuration for system services; draft configuration for listed app-compatibility targets. */
public final class ProfileAccess {
    public static Profile snapshot(boolean ownUid, String[] packages, Profile draft, Profile published) {
        boolean authorized=ownUid,service=false;
        if(packages!=null) for(String name:packages) {
            service|=ServicePackages.NAMES.contains(name);
            authorized|=draft.targets.contains(name)||ServicePackages.NAMES.contains(name);
        }
        return authorized ? (service ? published : draft) : Profile.defaults();
    }
    private ProfileAccess() {}
}
