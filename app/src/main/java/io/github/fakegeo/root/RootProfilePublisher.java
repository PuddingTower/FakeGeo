package io.github.fakegeo.root;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import java.util.Base64;
import org.json.JSONObject;

/** Shared configuration publication; cache clearing is a separate explicit action. */
public final class RootProfilePublisher {
    private static final String SCRIPT = RootCommands.DIRECTORY + "/location-cache.sh";
    private static final String LEGACY_SCRIPT = RootCommands.DIRECTORY + "/campus-location-cache.sh";
    private static final String LAST = RootCommands.DIRECTORY + "/location-cache-last";
    private static final String LEGACY_LAST = RootCommands.DIRECTORY + "/campus-cache-last";
    private RootProfilePublisher() {}

    public static String publishUser(Context context, Profile requested) throws Exception {
        synchronized (ConfigStore.class) {
            JSONObject json=requested.json();
            io.github.fakegeo.motion.MotionModel.clear(json);
            json.put("revision",Math.max(System.currentTimeMillis(),Math.max(ConfigStore.published(context).revision,ConfigStore.load(context).revision)+1));
            return publish(context,new Profile(json));
        }
    }

    public static String publish(Context context, Profile next) throws Exception {
        synchronized (ConfigStore.class) {
            if (next.enabled && next.systemMode) {
                String raw = RootBridge.execute(RootCommands.status());
                if (!raw.startsWith("{")) throw new IllegalStateException("系统 Hook 尚未就绪，请检查系统作用域。");
                JSONObject report = new JSONObject(raw);
                RuntimeGate.requireCurrent(report, System.currentTimeMillis());
                if (next.wifi) RuntimeGate.requireWifiConnectivity(report);
            }
            String command = RootCommands.publish(next.json().toString(), next.revision);
            String result = RootBridge.execute(command);
            ConfigStore.save(context, next);
            ConfigStore.markPublished(context, next);
            return result;
        }
    }

    public static String restoreLatest(Context context) throws Exception {
        synchronized (ConfigStore.class) {
            JSONObject report = new JSONObject(RootBridge.execute(RootCommands.status()));
            RuntimeGate.requireCurrent(report, System.currentTimeMillis());
            if (report.optBoolean("enabled") || ConfigStore.published(context).enabled)
                throw new IllegalStateException("请先停止虚拟环境并确认系统已禁用，再恢复缓存。");
            return RootBridge.execute(namespace(install(context)
                    + installAsset(context, "campus-location-cache.sh", LEGACY_SCRIPT)
                    + "if [ -f '" + LAST + "' ] && [ ! -L '" + LAST + "' ]; then\n"
                    + "fg_last=$(sed -n '1p' '" + LAST + "')\nfg_package=$(sed -n '2p' '" + LAST + "')\n"
                    + "/system/bin/sh '" + SCRIPT + "' restore \"$fg_last\" \"$fg_package\"\n"
                    + "elif [ -f '" + LEGACY_LAST + "' ] && [ ! -L '" + LEGACY_LAST + "' ]; then\n"
                    + "fg_last=$(cat '" + LEGACY_LAST + "')\n/system/bin/sh '" + LEGACY_SCRIPT + "' restore \"$fg_last\"\n"
                    + "else echo CACHE_BACKUP_MISSING; exit 1; fi\n"));
        }
    }

    static void validateTarget(Context context, String target) throws Exception {
        if (android.os.Process.myUid() / 100000 != 0)
            throw new IllegalStateException("定位缓存刷新目前仅支持主用户");
        io.github.fakegeo.config.Validation.cachePackage(target);
        try {
            android.content.pm.ApplicationInfo app = context.getPackageManager().getApplicationInfo(target, 0);
            if ((app.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                throw new IllegalArgumentException("不能刷新系统应用的定位缓存");
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            // Removed apps are a no-op in the root script, never a different substring-matching package.
        }
    }
    private static String install(Context context) throws Exception {
        return installAsset(context, "location-cache.sh", SCRIPT);
    }
    static String installAsset(Context context, String asset, String path) throws Exception {
        byte[] bytes;
        try (java.io.InputStream stream = context.getAssets().open(asset)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[2048]; int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            bytes = buffer.toByteArray();
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return "set -eu\numask 077\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "[ -d '" + RootCommands.DIRECTORY + "' ] && [ ! -L '" + RootCommands.DIRECTORY + "' ] || exit 1\n"
                + "[ ! -L '" + path + "' ] && [ ! -L '" + LAST + "' ] && [ ! -L '" + LAST + ".new' ] || exit 1\n"
                + "printf '%s' '" + encoded + "' | base64 -d > '" + path + "'\nchmod 600 '" + path + "'\n";
    }
    static String namespace(String body) {
        return "nsenter -t 1 -m -- /system/bin/sh -c '" + body.replace("'", "'\"'\"'") + "'\n";
    }
}
