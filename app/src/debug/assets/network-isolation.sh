#!/system/bin/sh
# One-minute diagnostic only. Never flush shared tables or change another application's rules.
fg_dir=/data/system/fakegeo
fg_lock="$fg_dir/network-isolation.lock"
fg_log="$fg_dir/network-isolation.log"
fg_chain4=FGEO_DIAG4
fg_chain6=FGEO_DIAG6
fg_owned4=0
fg_owned6=0
fg_sleep_pid=
umask 077
[ "$(id -u)" = 0 ] || { echo ROOT_REQUIRED; exit 1; }
[ -d "$fg_dir" ] && [ ! -L "$fg_dir" ] && [ ! -L "$fg_lock" ] && [ ! -L "$fg_log" ] || { echo UNSAFE_PATH; exit 1; }
fg_ip4=$(command -v iptables) || { echo IPV4_TOOL_MISSING; exit 1; }
fg_ip6=$(command -v ip6tables) || { echo IPV6_TOOL_MISSING; exit 1; }

fg_remove() {
    fg_tool=$1
    fg_chain=$2
    for fg_attempt in 1 2 3; do
        if ! "$fg_tool" -w 1 -C OUTPUT -j "$fg_chain" 2>/dev/null; then break; fi
        "$fg_tool" -w 1 -D OUTPUT -j "$fg_chain" 2>/dev/null || :
    done
    if "$fg_tool" -w 1 -C OUTPUT -j "$fg_chain" 2>/dev/null; then return 1; fi
    if "$fg_tool" -w 1 -L "$fg_chain" >/dev/null 2>&1; then
        "$fg_tool" -w 1 -F "$fg_chain" && "$fg_tool" -w 1 -X "$fg_chain" || return 1
    fi
}
fg_cleanup() {
    trap '' HUP INT TERM
    trap - EXIT
    [ -z "$fg_sleep_pid" ] || kill "$fg_sleep_pid" 2>/dev/null || :
    fg_failed=0
    if [ "$fg_owned4" = 1 ]; then
        "$fg_ip4" -w 1 -L "$fg_chain4" -n -v -x >> "$fg_log" 2>/dev/null || :
        fg_remove "$fg_ip4" "$fg_chain4" || fg_failed=1
    fi
    if [ "$fg_owned6" = 1 ]; then
        "$fg_ip6" -w 1 -L "$fg_chain6" -n -v -x >> "$fg_log" 2>/dev/null || :
        fg_remove "$fg_ip6" "$fg_chain6" || fg_failed=1
    fi
    if [ "$fg_failed" = 0 ]; then
        rm -f "$fg_lock/pid" "$fg_lock/uid" "$fg_lock/v4" "$fg_lock/v6"
        rmdir "$fg_lock" || fg_failed=1
    fi
    if [ "$fg_failed" = 0 ]; then echo NETWORK_ISOLATION_RESTORED >> "$fg_log";
    else echo NETWORK_ISOLATION_RESTORE_FAILED >> "$fg_log"; fi
    return "$fg_failed"
}

if [ "${1-}" = stop ]; then
    [ -d "$fg_lock" ] || { echo NETWORK_ISOLATION_NOT_RUNNING; exit 0; }
    fg_pid=$(cat "$fg_lock/pid" 2>/dev/null)
    case "$fg_pid" in ''|*[!0-9]*) fg_pid=;; esac
    if [ -n "$fg_pid" ] && [ -r "/proc/$fg_pid/cmdline" ] &&
            tr '\000' ' ' < "/proc/$fg_pid/cmdline" | grep -F "$fg_dir/network-isolation.sh" >/dev/null; then
        kill -TERM "$fg_pid" || exit 1
        for fg_attempt in 1 2 3 4 5 6 7 8; do
            [ -d "$fg_lock" ] || { echo NETWORK_ISOLATION_STOPPED; exit 0; }
            sleep 1
        done
        echo NETWORK_ISOLATION_STOP_PENDING; exit 1
    fi
    # Recover only chains marked as created by this diagnostic, even if the timer died.
    [ ! -f "$fg_lock/v4" ] || fg_owned4=1
    [ ! -f "$fg_lock/v6" ] || fg_owned6=1
    fg_cleanup
    exit $?
fi
[ -z "${1-}" ] || { echo UNKNOWN_ACTION; exit 1; }

fg_uid=$(cmd package list packages -U --user 0 com.wisedu.cpdaily |
    sed -n 's/^package:com\.wisedu\.cpdaily uid:\([0-9][0-9]*\)$/\1/p')
case "$fg_uid" in ''|*[!0-9]*) echo TARGET_UID_UNAVAILABLE; exit 1;; esac
[ "$fg_uid" -ge 10000 ] && [ "$fg_uid" -lt 100000 ] || { echo TARGET_UID_NOT_APPLICATION; exit 1; }
mkdir "$fg_lock" || { echo NETWORK_ISOLATION_BUSY; exit 1; }
printf '%s\n' "$$" > "$fg_lock/pid"
printf '%s\n' "$fg_uid" > "$fg_lock/uid"
trap 'fg_cleanup' EXIT
trap 'exit 1' HUP INT TERM
# Existing foreign chains cause an abort; cleanup does not claim or remove them.
if "$fg_ip4" -w 1 -L "$fg_chain4" >/dev/null 2>&1 || "$fg_ip6" -w 1 -L "$fg_chain6" >/dev/null 2>&1; then
    echo NETWORK_ISOLATION_CHAIN_BUSY >> "$fg_log"; exit 1
fi
"$fg_ip4" -w 1 -N "$fg_chain4" || exit 1
fg_owned4=1
touch "$fg_lock/v4" || exit 1
"$fg_ip6" -w 1 -N "$fg_chain6" || exit 1
fg_owned6=1
touch "$fg_lock/v6" || exit 1
"$fg_ip4" -w 1 -A "$fg_chain4" -m owner --uid-owner "$fg_uid" -j REJECT || exit 1
"$fg_ip6" -w 1 -A "$fg_chain6" -m owner --uid-owner "$fg_uid" -j REJECT || exit 1
"$fg_ip4" -w 1 -I OUTPUT 1 -j "$fg_chain4" || exit 1
"$fg_ip6" -w 1 -I OUTPUT 1 -j "$fg_chain6" || exit 1
echo "NETWORK_ISOLATION_ACTIVE uid=$fg_uid seconds=60" >> "$fg_log"
# wait is interruptible, unlike a foreground sleep: explicit Stop promptly runs the EXIT cleanup.
sleep 60 &
fg_sleep_pid=$!
wait "$fg_sleep_pid"
