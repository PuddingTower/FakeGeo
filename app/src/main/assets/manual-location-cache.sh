#!/system/bin/sh
# Opt-in root cache refresh for a validated user-selected application. Fixed location DB whitelist.
set -eu
fg_base=/data/system/fakegeo
fg_revision=${2-}
fg_package=${3-}
case "$fg_revision" in ''|*[!0-9]*) echo INVALID_CACHE_REVISION; exit 1;; esac
[ "${#fg_revision}" -le 19 ] || { echo INVALID_CACHE_REVISION; exit 1; }
[ "${#fg_package}" -le 255 ] && printf '%s\n' "$fg_package" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' || { echo INVALID_CACHE_PACKAGE; exit 1; }
case "$fg_package" in io.github.fakegeo|com.android.*|com.google.android.gms*) echo PROTECTED_CACHE_PACKAGE; exit 1;; esac
fg_app="/data/user/0/$fg_package"
fg_db="$fg_app"
fg_backup="$fg_base/manual-location-cache-$fg_revision"
[ -d "$fg_base" ] && [ ! -L "$fg_base" ] || { echo UNSAFE_CACHE_ROOT; exit 1; }
fg_names='databases/hmdb databases/hmdb-journal databases/hmdb-wal databases/hmdb-shm databases/alsn20170807.db databases/alsn20170807.db-journal databases/alsn20170807.db-wal databases/alsn20170807.db-shm files/ofld/ofl_location.db files/ofld/ofl_location.db-journal files/ofld/ofl_location.db-wal files/ofld/ofl_location.db-shm'
umask 077
[ "$(id -u)" = 0 ] || { echo ROOT_REQUIRED; exit 1; }
for fg_parent in /data/user /data/user/0 "$fg_app" "$fg_db" "$fg_backup"; do
    [ ! -L "$fg_parent" ] || { echo UNSAFE_CACHE_PATH; exit 1; }
done
if [ "${1-}" = restore ] && [ "${4-}" = rollback ] && [ ! -e "$fg_backup" ]; then echo CACHE_NOT_MOVED; exit 0; fi
# Package query may return substring matches; accept only the exact validated package.
fg_records=$(cmd package list packages -U --user 0 "$fg_package")
fg_uid=''
while IFS= read -r fg_line; do
    case "$fg_line" in "package:$fg_package uid:"*) fg_uid=${fg_line#"package:$fg_package uid:"};; esac
done <<FG_PACKAGES
$fg_records
FG_PACKAGES
if [ -z "$fg_uid" ] && [ "${1-}" = backup ]; then echo LOCATION_CACHE_NOT_FOUND; exit 0; fi
case "$fg_uid" in ''|*[!0-9]*) echo TARGET_UID_UNAVAILABLE; exit 1;; esac
[ "$fg_uid" -ge 10000 ] && [ "$fg_uid" -lt 100000 ] || { echo TARGET_UID_NOT_APPLICATION; exit 1; }
fg_system=$(cmd package list packages -s --user 0 "$fg_package")
while IFS= read -r fg_line; do
    [ "$fg_line" != "package:$fg_package" ] || { echo SYSTEM_CACHE_TARGET_REJECTED; exit 1; }
done <<FG_SYSTEM_PACKAGES
$fg_system
FG_SYSTEM_PACKAGES
if [ ! -d "$fg_db" ] && [ "${1-}" = backup ]; then echo LOCATION_CACHE_NOT_FOUND; exit 0; fi
[ -d "$fg_db" ] || { echo CACHE_DIRECTORY_ABSENT; exit 1; }
[ "$(stat -c %u "$fg_app")" = "$fg_uid" ] && [ "$(stat -c %u "$fg_db")" = "$fg_uid" ] || { echo CACHE_DIRECTORY_OWNER_MISMATCH; exit 1; }

fg_validate() {
    for fg_parent in databases files files/ofld; do
        [ ! -L "$1/$fg_parent" ] || { echo CACHE_PARENT_SYMLINK_REJECTED; return 1; }
    done
    for fg_name in $fg_names; do
        fg_file="$1/$fg_name"
        [ ! -L "$fg_file" ] || { echo CACHE_SYMLINK_REJECTED; return 1; }
        if [ -e "$fg_file" ]; then
            [ -f "$fg_file" ] && [ "$(stat -c %u "$fg_file")" = "$fg_uid" ] || { echo CACHE_OWNER_MISMATCH; return 1; }
        fi
    done
}

if [ "${1-}" = backup ]; then
    [ ! -e "$fg_backup" ] || { echo CACHE_BACKUP_ALREADY_EXISTS; exit 1; }
    fg_validate "$fg_db"
    for fg_parent in databases files files/ofld; do
        if [ -e "$fg_db/$fg_parent" ]; then
            [ -d "$fg_db/$fg_parent" ] && [ "$(stat -c %u "$fg_db/$fg_parent")" = "$fg_uid" ] || { echo CACHE_DIRECTORY_OWNER_MISMATCH; exit 1; }
        fi
    done
    fg_present=0
    for fg_name in $fg_names; do [ ! -f "$fg_db/$fg_name" ] || fg_present=1; done
    [ "$fg_present" = 1 ] || { echo LOCATION_CACHE_NOT_FOUND; exit 0; }
    am force-stop --user 0 "$fg_package"
    # Recheck after all package processes are stopped, before moving any files.
    fg_validate "$fg_db"
    mkdir "$fg_backup"
    chmod 700 "$fg_backup"
    printf '%s\n' "$fg_uid" > "$fg_backup/uid"
    printf '%s\n' "$fg_package" > "$fg_backup/package"
    fg_committed=0
    fg_undo_backup() {
        [ "$fg_committed" = 0 ] || return 0
        fg_undo_failed=0
        for fg_name in $fg_names; do
            if [ -f "$fg_backup/$fg_name" ]; then
                mv "$fg_backup/$fg_name" "$fg_db/$fg_name" || fg_undo_failed=1
            fi
        done
        if [ "$fg_undo_failed" = 0 ]; then rm -f "$fg_backup/uid" "$fg_backup/package" "$fg_backup/state" "$fg_backup/original.sha256"; for fg_parent in files/ofld files databases; do
            [ ! -d "$fg_backup/$fg_parent" ] || rmdir "$fg_backup/$fg_parent"
        done
        rmdir "$fg_backup";
        else echo CACHE_BACKUP_ROLLBACK_FAILED; fi
    }
    trap 'fg_undo_backup' EXIT
    trap 'exit 1' HUP INT TERM
    : > "$fg_backup/original.sha256"
    for fg_name in $fg_names; do
        if [ -f "$fg_db/$fg_name" ]; then sha256sum "$fg_db/$fg_name" >> "$fg_backup/original.sha256"; fi
    done
    fg_count=0
    for fg_name in $fg_names; do
        if [ -f "$fg_db/$fg_name" ]; then
            mkdir -p "$fg_backup/${fg_name%/*}"
            mv "$fg_db/$fg_name" "$fg_backup/$fg_name"
            fg_count=$((fg_count + 1))
        fi
    done
    printf '%s\n' READY > "$fg_backup/state"
    fg_committed=1
    echo "LOCATION_CACHE_BACKUP_READY uid=$fg_uid files=$fg_count"
elif [ "${1-}" = restore ]; then
    [ -d "$fg_backup" ] && [ ! -L "$fg_backup/uid" ] && [ ! -L "$fg_backup/state" ] || { echo CACHE_BACKUP_MISSING; exit 1; }
    [ -f "$fg_backup/package" ] && [ ! -L "$fg_backup/package" ] && [ "$(cat "$fg_backup/package")" = "$fg_package" ] || { echo CACHE_BACKUP_PACKAGE_CHANGED; exit 1; }
    [ "$(cat "$fg_backup/uid")" = "$fg_uid" ] || { echo CACHE_BACKUP_UID_CHANGED; exit 1; }
    fg_state=$(cat "$fg_backup/state")
    case "$fg_state" in
        RESTORED) echo LOCATION_CACHE_ALREADY_RESTORED; exit 0;;
        READY|COLLECTING_GENERATED|RESTORING_ORIGINALS) :;;
        *) echo CACHE_BACKUP_STATE_INVALID; exit 1;;
    esac
    fg_validate "$fg_db"
    fg_validate "$fg_backup"
    for fg_name in $fg_names; do
        if [ -f "$fg_backup/$fg_name" ]; then
            fg_parent="$fg_db/${fg_name%/*}"
            [ -d "$fg_parent" ] && [ "$(stat -c %u "$fg_parent")" = "$fg_uid" ] || { echo CACHE_RESTORE_PARENT_MISSING_OR_CHANGED; exit 1; }
        fi
    done
    am force-stop --user 0 "$fg_package"
    fg_validate "$fg_db"
    [ ! -L "$fg_backup/generated" ] || { echo UNSAFE_GENERATED_PATH; exit 1; }
    mkdir -p "$fg_backup/generated"
    # Collect every new sidecar before restoring originals. A test WAL cannot accompany an old DB.
    if [ "$fg_state" != RESTORING_ORIGINALS ]; then
        printf '%s\n' COLLECTING_GENERATED > "$fg_backup/state"
        for fg_name in $fg_names; do
            if [ -f "$fg_db/$fg_name" ]; then
                [ ! -e "$fg_backup/generated/$fg_name" ] || { echo CACHE_RESTORE_CONFLICT; exit 1; }
                mkdir -p "$fg_backup/generated/${fg_name%/*}"
                mv "$fg_db/$fg_name" "$fg_backup/generated/$fg_name"
            fi
        done
        printf '%s\n' RESTORING_ORIGINALS > "$fg_backup/state"
    fi
    for fg_name in $fg_names; do
        if [ -f "$fg_backup/$fg_name" ]; then
            [ ! -e "$fg_db/$fg_name" ] || { echo CACHE_RESTORE_CONFLICT; exit 1; }
            mv "$fg_backup/$fg_name" "$fg_db/$fg_name"
        fi
    done
    [ -f "$fg_backup/original.sha256" ] && [ ! -L "$fg_backup/original.sha256" ] || { echo CACHE_CHECKSUM_MANIFEST_MISSING; exit 1; }
    if [ -s "$fg_backup/original.sha256" ]; then sha256sum -c "$fg_backup/original.sha256" || { echo CACHE_RESTORE_CHECKSUM_FAILED; exit 1; }; fi
    printf '%s\n' RESTORED > "$fg_backup/state"
    echo CACHE_ORIGINAL_CHECKSUMS_VERIFIED
    echo LOCATION_CACHE_ORIGINALS_RESTORED
else
    echo UNKNOWN_CACHE_ACTION; exit 1
fi
