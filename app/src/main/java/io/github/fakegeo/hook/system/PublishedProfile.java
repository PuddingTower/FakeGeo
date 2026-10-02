package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;

/** A failed read must not silently turn a published virtual environment into a real one. */
final class PublishedProfile {
    private volatile Profile value = Profile.defaults();
    Profile get() { return value; }
    void update(String json) throws Exception {
        Profile next = Profile.parse(json);
        if (next.revision < value.revision) throw new IllegalArgumentException("系统配置版本回退");
        value = next;
    }
}
