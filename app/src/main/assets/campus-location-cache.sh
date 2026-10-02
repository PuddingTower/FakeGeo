#!/system/bin/sh
# Opt-in root cache refresh. Fixed package and database whitelist; backups are retained per revision.
set -eu
fg_db=/data/user/0/com.wisedu.cpdaily/databases
fg_base=/data/system/fakegeo
fg_revision=${2-}
case "$fg_revision" in ''|*[!0-9]*) echo INVALID_CACHE_REVISION; exit 1;; esac
[ "${#fg_revision}" -le 19 ] || { echo INVALID_CACHE_REVISION; exit 1; }
fg_backup="$fg_base/campus-cache-$fg_revision"
[ -d "$fg_base" ] && [ ! -L "$fg_base" ] || { echo UNSAFE_CACHE_ROOT; exit 1; }
fg_names='hmdb hmdb-journal hmdb-wal hmdb-shm alsn20170807.db alsn20170807.db-journal alsn20170807.db-wal alsn20170807.db-shm'
umask 077
[ "$(id -u)" = 0 ] || { echo ROOT_REQUIRED; exit 1; }
for fg_parent in /data/user /data/user/0 /data/user/0/com.wisedu.cpdaily "$fg_db" "$fg_backup"; do
    [ ! -L "$fg_parent" ] || { echo UNSAFE_CACHE_PATH; exit 1; }
done
fg_uid=$(cmd package list packages -U --user 0 com.wisedu.cpdaily |
    sed -n 's/^package:com\.wisedu\.cpdaily uid:\([0-9][0-9]*\)$/\1/p')
if [ -z "$fg_uid" ] && [ "${1-}" = backup ]; then echo CACHE_TARGET_ABSENT; exit 0; fi
if [ ! -d "$fg_db" ] && [ "${1-}" = backup ]; then echo CACHE_DIRECTORY_ABSENT; exit 0; fi
[ -d "$fg_db" ] || { echo CACHE_DIRECTORY_ABSENT; exit 1; }
case "$fg_uid" in ''|*[!0-9]*) echo TARGET_UID_UNAVAILABLE; exit 1;; esac
[ "$fg_uid" -ge 10000 ] && [ "$fg_uid" -lt 100000 ] || { echo TARGET_UID_NOT_APPLICATION; exit 1; }

fg_validate() {
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
    am force-stop --user 0 com.wisedu.cpdaily
    # Recheck after all package processes are stopped, before moving any files.
    fg_validate "$fg_db"
    mkdir "$fg_backup"
    chmod 700 "$fg_backup"
    printf '%s\n' "$fg_uid" > "$fg_backup/uid"
    fg_committed=0
    fg_undo_backup() {
        [ "$fg_committed" = 0 ] || return 0
        fg_undo_failed=0
        for fg_name in $fg_names; do
            if [ -f "$fg_backup/$fg_name" ]; then
                mv "$fg_backup/$fg_name" "$fg_db/$fg_name" || fg_undo_failed=1
            fi
        done
        if [ "$fg_undo_failed" = 0 ]; then rm -f "$fg_backup/uid" "$fg_backup/state" "$fg_backup/original.sha256"; rmdir "$fg_backup";
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
            mv "$fg_db/$fg_name" "$fg_backup/$fg_name"
            fg_count=$((fg_count + 1))
        fi
    done
    printf '%s\n' READY > "$fg_backup/state"
    fg_committed=1
    echo "LOCATION_CACHE_BACKUP_READY uid=$fg_uid files=$fg_count"
elif [ "${1-}" = restore ]; then
    if [ ! -d "$fg_backup" ] && [ "${3-}" = rollback ]; then echo CACHE_NOT_MOVED; exit 0; fi
    [ -d "$fg_backup" ] && [ ! -L "$fg_backup/uid" ] && [ ! -L "$fg_backup/state" ] || { echo CACHE_BACKUP_MISSING; exit 1; }
    [ "$(cat "$fg_backup/uid")" = "$fg_uid" ] || { echo CACHE_BACKUP_UID_CHANGED; exit 1; }
    fg_state=$(cat "$fg_backup/state")
    case "$fg_state" in
        RESTORED) echo LOCATION_CACHE_ALREADY_RESTORED; exit 0;;
        READY|COLLECTING_GENERATED|RESTORING_ORIGINALS) :;;
        *) echo CACHE_BACKUP_STATE_INVALID; exit 1;;
    esac
    fg_validate "$fg_db"
    fg_validate "$fg_backup"
    am force-stop --user 0 com.wisedu.cpdaily
    fg_validate "$fg_db"
    [ ! -L "$fg_backup/generated" ] || { echo UNSAFE_GENERATED_PATH; exit 1; }
    mkdir -p "$fg_backup/generated"
    # Collect every new sidecar before restoring originals. A test WAL cannot accompany an old DB.
    if [ "$fg_state" != RESTORING_ORIGINALS ]; then
        printf '%s\n' COLLECTING_GENERATED > "$fg_backup/state"
        for fg_name in $fg_names; do
            if [ -f "$fg_db/$fg_name" ]; then
                [ ! -e "$fg_backup/generated/$fg_name" ] || { echo CACHE_RESTORE_CONFLICT; exit 1; }
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
