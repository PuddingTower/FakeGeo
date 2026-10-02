"""Execute the Android shell diagnostic against fake firewall tools; never touch a real firewall."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
BASH = sys.argv[1] if len(sys.argv) > 1 else "bash"
MOCK = r'''#!/bin/bash
family=$(basename "$0")
base="$FG_MOCK_STATE/$family"
mkdir -p "$base"
[ "$1" != -w ] || shift 2
operation=$1; chain=$2
echo "$family $*" >> "$FG_MOCK_STATE/commands"
if [ "$FG_FAIL" = "$family:$operation" ]; then echo MOCK_INSTALL_FAILURE >&2; exit 1; fi
case "$operation" in
    -N) mkdir "$base/$chain" 2>/dev/null;;
    -A) [ -d "$base/$chain" ] && printf '%s\n' "$*" > "$base/$chain/rule";;
    -I) [ "$chain" = OUTPUT ] && touch "$base/jump";;
    -C) [ "$chain" = OUTPUT ] && [ -f "$base/jump" ];;
    -D) [ "$chain" = OUTPUT ] && rm -f "$base/jump";;
    -L) [ -d "$base/$chain" ] && echo '1 100 REJECT all owner UID match 10444';;
    -F) case "$chain" in FGEO_DIAG4|FGEO_DIAG6) rm -f "$base/$chain/rule";; *) echo FORBIDDEN_SHARED_FLUSH >&2; exit 99;; esac;;
    -X) rmdir "$base/$chain";;
    *) echo UNKNOWN_FIREWALL_OPERATION >&2; exit 99;;
esac
'''


def script(path, text):
    path.write_text(text, encoding="utf-8", newline="\n")
    path.chmod(0o755)


def bash_path(path):
    value = path.resolve().as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


def run_case(case, fail="", foreign=False, interrupt=False, invalid_uid=False, recovery=False):
    with tempfile.TemporaryDirectory(prefix="netiso-", dir=ROOT / ".gradle") as temp:
        stage = Path(temp)
        assert stage.resolve().is_relative_to(ROOT.resolve())
        tools, state, data = stage / "bin", stage / "state", stage / "data"
        for directory in (tools, state, data):
            directory.mkdir()
        for family in ("iptables", "ip6tables"):
            (state / family / "OEM_KEEP").mkdir(parents=True)
            (state / family / "OEM_KEEP" / "rule").write_text("UNRELATED_RULE", encoding="utf-8")
            script(tools / family, MOCK)
        script(tools / "id", "#!/bin/bash\necho 0\n")
        uid = "1000" if invalid_uid else "10444"
        script(tools / "cmd", f"#!/bin/bash\necho 'package:com.wisedu.cpdaily uid:{uid}'\n")
        script(tools / "sleep", "#!/bin/bash\nif [ \"$1\" = 60 ]; then "
               + ("exec /usr/bin/sleep 60" if interrupt else "exit 0") + "; else exec /usr/bin/sleep \"$@\"; fi\n")
        source = (ROOT / "app/src/debug/assets/network-isolation.sh").read_text(encoding="utf-8")
        source = source.replace("fg_dir=/data/system/fakegeo", "fg_dir='" + bash_path(data) + "'")
        runner = data / "network-isolation.sh"
        script(runner, source)
        if foreign:
            (state / "iptables/FGEO_DIAG4").mkdir()
            (state / "iptables/FGEO_DIAG4/foreign").write_text("KEEP", encoding="utf-8")
        env = dict(os.environ, FG_MOCK_STATE=bash_path(state), FG_FAIL=fail,
                   FG_MOCK_BIN=bash_path(tools), FG_RUNNER=bash_path(runner),
                   PATH=str(tools) + os.pathsep + os.environ.get("PATH", ""))
        command = [BASH, "-c", 'export PATH="$FG_MOCK_BIN:$PATH"; exec /bin/bash "$FG_RUNNER" "$@"', "network-test"]
        if interrupt:
            process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    log = data / "network-isolation.log"
                    if log.exists() and "NETWORK_ISOLATION_ACTIVE" in log.read_text():
                        break
                    if process.poll() is not None:
                        raise AssertionError("timer exited before ACTIVE")
                    time.sleep(0.05)
                else:
                    raise AssertionError("timer did not activate")
                assert (state / "iptables/jump").exists() and (state / "ip6tables/jump").exists()
                stopped = subprocess.run(command + ["stop"], env=env, capture_output=True, text=True, timeout=15)
                assert stopped.returncode == 0, stopped.stdout + stopped.stderr
                process.communicate(timeout=10)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()
        else:
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            if recovery:
                assert "NETWORK_ISOLATION_RESTORE_FAILED" in (data / "network-isolation.log").read_text()
                assert (state / "iptables/jump").exists() and (data / "network-isolation.lock").exists()
                recovered = subprocess.run(command + ["stop"], env=dict(env, FG_FAIL=""), capture_output=True, text=True, timeout=15)
                assert recovered.returncode == 0, recovered.stdout + recovered.stderr
                assert (data / "network-isolation.log").read_text().endswith("NETWORK_ISOLATION_RESTORED\n")
            else:
                assert (result.returncode == 0) == (not fail and not foreign and not invalid_uid), result.stdout + result.stderr
        assert not (state / "iptables/jump").exists(), "IPv4 isolation jump remained"
        assert not (state / "ip6tables/jump").exists(), "IPv6 isolation jump remained"
        assert not (data / "network-isolation.lock").exists(), "lease lock remained"
        for family in ("iptables", "ip6tables"):
            assert (state / family / "OEM_KEEP/rule").read_text() == "UNRELATED_RULE"
        if foreign:
            assert (state / "iptables/FGEO_DIAG4/foreign").read_text() == "KEEP"
        calls = (state / "commands").read_text() if (state / "commands").exists() else ""
        for line in calls.splitlines():
            if " -A " in line:
                assert "--uid-owner 10444 -j REJECT" in line, "non-targeted block"
            assert " -F OUTPUT" not in line
        print(json.dumps({"case": case, "passed": True}))


if __name__ == "__main__":
    run_case("timer_expiry_restores_both_families")
    run_case("explicit_stop_interrupts_timer", interrupt=True)
    run_case("ipv6_install_failure_rolls_back_ipv4", fail="ip6tables:-I")
    run_case("foreign_chain_is_preserved", foreign=True)
    run_case("system_uid_is_rejected", invalid_uid=True)
    run_case("cleanup_failure_is_reported_and_stop_recovers", fail="iptables:-D", recovery=True)
