package io.github.fakegeo.root;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Explicit cache actions; never publish or change the virtual environment. */
public final class LocationCacheManager {
    private static final String SCRIPT = RootCommands.DIRECTORY + "/manual-location-cache.sh";
    private static final String LAST = RootCommands.DIRECTORY + "/manual-cache-last";
    private static final AtomicLong lastId = new AtomicLong();
    private LocationCacheManager() {}
    public static String clear(Context context, String target) throws Exception {
        synchronized (ConfigStore.class) {
            if (target.isEmpty()) throw new IllegalArgumentException("请先选择应用");
            RootProfilePublisher.validateTarget(context, target);
            RootBridge.execute(RootCommands.status()); // Prepare the private directory after explicit Root action.
            long id = lastId.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
            String body = RootProfilePublisher.installAsset(context, "manual-location-cache.sh", SCRIPT)
                    + "[ ! -L '" + LAST + "' ] && [ ! -L '" + LAST + ".new' ] || exit 1\n"
                    + "/system/bin/sh '" + SCRIPT + "' backup " + id + " '" + target + "'\n"
                    + "fg_cache_done=0\ntrap 'if [ \"$fg_cache_done\" = 0 ]; then /system/bin/sh " + SCRIPT + " restore " + id + " " + target + " rollback; fi' EXIT\n"
                    + "trap 'exit 1' HUP INT TERM\n"
                    + "if [ -d '" + RootCommands.DIRECTORY + "/manual-location-cache-" + id + "' ]; then\n"
                    + "printf '%s\\n%s\\n' '" + id + "' '" + target + "' > '" + LAST + ".new'\n"
                    + "mv '" + LAST + ".new' '" + LAST + "'\nfi\nfg_cache_done=1\n";
            return RootBridge.execute(RootProfilePublisher.namespace(body));
        }
    }
    public static String restoreLatest(Context context) throws Exception {
        synchronized (ConfigStore.class) {
            JSONObject report = new JSONObject(RootBridge.execute(RootCommands.status()));
            RuntimeGate.requireCurrent(report, System.currentTimeMillis());
            if (report.optBoolean("enabled") || ConfigStore.published(context).enabled)
                throw new IllegalStateException("请先停止虚拟环境并确认系统已禁用，再恢复缓存。");
            String result = RootBridge.execute(RootProfilePublisher.namespace(
                    RootProfilePublisher.installAsset(context, "manual-location-cache.sh", SCRIPT)
                    + "[ ! -L '" + LAST + "' ] || exit 1\n"
                    + "if [ -f '" + LAST + "' ]; then\n"
                    + "fg_id=$(sed -n '1p' '" + LAST + "')\nfg_package=$(sed -n '2p' '" + LAST + "')\n"
                    + "/system/bin/sh '" + SCRIPT + "' restore \"$fg_id\" \"$fg_package\"\n"
                    + "else echo NO_MANUAL_BACKUP; fi\n"));
            return result.equals("NO_MANUAL_BACKUP") ? RootProfilePublisher.restoreLatest(context) : result;
        }
    }
}
