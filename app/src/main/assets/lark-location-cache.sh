#!/system/bin/sh
# Opt-in refresh of a target app's third-party positioning caches (AMap-style SDKs).
# Only paths matching the fixed whitelist are moved aside, so a login state and chat history stay.
# Main user and ColorOS clone users are both covered. Restore uses the same revision id.
set -eu
export PATH=/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:/system/bin:/system/xbin:$PATH
fg_base=/data/system/fakegeo
fg_action=${1-}
fg_revision=${2-}
fg_package=${3-}
case "$fg_revision" in ''|*[!0-9]*) echo INVALID_CACHE_REVISION; exit 1;; esac
[ "${#fg_revision}" -le 19 ] || { echo INVALID_CACHE_REVISION; exit 1; }
[ "${#fg_package}" -le 255 ] && printf '%s\n' "$fg_package" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' || { echo INVALID_CACHE_PACKAGE; exit 1; }
case "$fg_package" in io.github.fakegeo|io.github.fakegeo.dev|com.android.*|com.google.android.gms*) echo PROTECTED_CACHE_PACKAGE; exit 1;; esac
fg_backup="$fg_base/lark-location-cache-$fg_revision"
# Paths are relative to each profile directory; anything else is never touched.
fg_patterns='shared_prefs/amap_location*.xml shared_prefs/*amap*.xml files/amap files/amap_sdk files/AMapSdk cache/location* cache/amap*'
umask 077
[ "$(id -u)" = 0 ] || { echo ROOT_REQUIRED; exit 1; }
[ -d "$fg_base" ] && [ ! -L "$fg_base" ] || { echo UNSAFE_CACHE_ROOT; exit 1; }
[ ! -L "$fg_backup" ] || { echo UNSAFE_CACHE_PATH; exit 1; }
case "$fg_action" in
    backup|restore) :;;
    *) echo UNKNOWN_CACHE_ACTION; exit 1;;
esac

fg_users=''
for fg_dir in /data/user/*; do
    case "$fg_dir" in
        /data/user/[0-9]*) [ -L "$fg_dir" ] && { echo UNSAFE_CACHE_PATH; exit 1; }
            [ -d "$fg_dir" ] && fg_users="$fg_users ${fg_dir#/data/user/}";;
    esac
done
[ -n "$fg_users" ] || { echo NO_USERSPACE; exit 1; }
if [ "$fg_action" = restore ] && [ "${4-}" = rollback ] && [ ! -e "$fg_backup" ]; then echo CACHE_NOT_MOVED; exit 0; fi

fg_uid_for() {
    fg_records=$(cmd package list packages -U --user "$1" "$fg_package")
    fg_found=''
    while IFS= read -r fg_line; do
        case "$fg_line" in "package:$fg_package uid:"*) fg_found=${fg_line#"package:$fg_package uid:"};; esac
    done <<FG_LIST
$fg_records
FG_LIST
    printf '%s' "$fg_found"
}

if [ "$fg_action" = backup ]; then
    [ ! -e "$fg_backup" ] || { echo CACHE_BACKUP_ALREADY_EXISTS; exit 1; }
    fg_moved=0
    fg_scanned=0
    for fg_user in $fg_users; do
        fg_uid=$(fg_uid_for "$fg_user" || true)
        case "$fg_uid" in ''|*[!0-9]*) continue;; esac
        [ "$fg_uid" -ge 10000 ] && [ "$fg_uid" -lt 100000 ] || continue
        fg_app="/data/user/$fg_user/$fg_package"
        [ -d "$fg_app" ] && [ ! -L "$fg_app" ] || continue
        [ "$(stat -c %u "$fg_app")" = "$fg_uid" ] || { echo CACHE_OWNER_MISMATCH; exit 1; }
        fg_scanned=$((fg_scanned + 1))
        fg_needs_stop=1
        for fg_pattern in $fg_patterns; do
            for fg_path in "$fg_app"/$fg_pattern; do
                [ -e "$fg_path" ] || continue
                [ ! -L "$fg_path" ] || { echo CACHE_SYMLINK_REJECTED; exit 1; }
                fg_relative=${fg_path#"$fg_app"/}
                [ "$(stat -c %u "$fg_path")" = "$fg_uid" ] || { echo CACHE_OWNER_MISMATCH; exit 1; }
                if [ "$fg_moved" = 0 ]; then
                    mkdir -p "$fg_backup"
                    printf '%s\n' "$fg_package" > "$fg_backup/package"
                fi
                # Stop that user's process once, before its files move, so the SDK cannot rewrite them.
                if [ "$fg_needs_stop" = 1 ]; then
                    am force-stop --user "$fg_user" "$fg_package" || true
                    fg_needs_stop=0
                fi
                fg_target="$fg_backup/$fg_user/$fg_relative"
                mkdir -p "$(dirname "$fg_target")"
                mv "$fg_path" "$fg_target"
                fg_moved=$((fg_moved + 1))
            done
        done
    done
    if [ "$fg_moved" = 0 ]; then
        echo "VENDOR_LOCATION_CACHE_NOT_FOUND scanned=$fg_scanned"
        exit 0
    fi
    printf '%s\n' READY > "$fg_backup/state"
    echo "VENDOR_LOCATION_CACHE_BACKUP_READY files=$fg_moved"
else
    [ -d "$fg_backup" ] && [ ! -L "$fg_backup" ] || { echo CACHE_BACKUP_MISSING; exit 1; }
    [ -f "$fg_backup/package" ] && [ "$(cat "$fg_backup/package")" = "$fg_package" ] || { echo CACHE_BACKUP_PACKAGE_CHANGED; exit 1; }
    fg_state=$(cat "$fg_backup/state" 2>/dev/null || echo READY)
    if [ "$fg_state" = RESTORED ]; then echo VENDOR_LOCATION_CACHE_ALREADY_RESTORED; exit 0; fi
    [ "$fg_state" = READY ] || { echo CACHE_BACKUP_STATE_INVALID; exit 1; }
    fg_restored=0
    for fg_user in $fg_users; do
        fg_source="$fg_backup/$fg_user"
        [ -d "$fg_source" ] && [ ! -L "$fg_source" ] || continue
        fg_uid=$(fg_uid_for "$fg_user" || true)
        case "$fg_uid" in ''|*[!0-9]*) continue;; esac
        fg_app="/data/user/$fg_user/$fg_package"
        [ -d "$fg_app" ] || continue
        while IFS= read -r fg_file; do
            fg_relative=${fg_file#"$fg_source/"}
            [ ! -L "$fg_file" ] || { echo CACHE_SYMLINK_REJECTED; exit 1; }
            fg_destination="$fg_app/$fg_relative"
            [ ! -e "$fg_destination" ] || { echo CACHE_RESTORE_CONFLICT; exit 1; }
            mkdir -p "$(dirname "$fg_destination")"
            mv "$fg_file" "$fg_destination"
            chown "$fg_uid:$fg_uid" "$fg_destination"
            restorecon "$fg_destination" 2>/dev/null || true
            fg_restored=$((fg_restored + 1))
        done <<FG_FILES
$(find "$fg_source" -mindepth 1 -type f)
FG_FILES
        rm -rf "$fg_source"
    done
    printf '%s\n' RESTORED > "$fg_backup/state"
    echo "VENDOR_LOCATION_CACHE_RESTORED files=$fg_restored"
fi
