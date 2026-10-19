"""Exercise cache quarantine/restore on synthetic files inside the workspace only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASH = sys.argv[1] if len(sys.argv) > 1 else "bash"
MANUAL = len(sys.argv) > 2 and sys.argv[2] == "manual"
GENERIC = len(sys.argv) > 2 and sys.argv[2] in ("generic", "manual")
# The campus-run cache script was removed with the campus feature; production mode is retired.
PRODUCTION = False
PACKAGE = "org.example.selected" if GENERIC else "com.wisedu.cpdaily"
PREFIX = "manual-location-cache-" if MANUAL else "location-cache-" if GENERIC else "campus-cache-"
REVISION = "1790849051000"


def bash_path(path):
    value = path.resolve().as_posix()
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


def write(path, text):
    path.write_text(text, encoding="utf-8", newline="\n")
    path.chmod(0o755)


def case(name, mode):
    with tempfile.TemporaryDirectory(prefix="loccache-", dir=ROOT / ".gradle") as temp:
        stage = Path(temp)
        assert stage.resolve().is_relative_to(ROOT.resolve())
        tools, database, backup = stage / "bin", stage / "databases", stage / (PREFIX + REVISION if PRODUCTION else "backup")
        if GENERIC: database = stage / "apps" / PACKAGE / "databases"
        tools.mkdir(); database.mkdir(parents=True)
        backed = backup / "databases" if MANUAL else backup
        if MANUAL:
            baidu = database.parent / "files/ofld"
            baidu.mkdir(parents=True)
            (baidu / "ofl_location.db").write_bytes(b"ORIGINAL_BAIDU_OFFLINE")
            (baidu / "ofl_location.db-journal").write_bytes(b"ORIGINAL_BAIDU_JOURNAL")
            (baidu / "ofl_statistics.db").write_bytes(b"UNRELATED_BAIDU_STATISTICS")
        if GENERIC:
            other = stage / "apps/org.example.unselected/databases"
            other.mkdir(parents=True)
            (other / "hmdb").write_bytes(b"UNSELECTED_LOCATION_CACHE")
        original = {"hmdb": b"ORIGINAL_HISTORY", "hmdb-journal": b"ORIGINAL_JOURNAL", "alsn20170807.db": b"ORIGINAL_LAST_FIX"}
        if mode == "baidu_only": original = {}
        for file, data in original.items():
            (database / file).write_bytes(data)
        (database / "login.db").write_bytes(b"UNRELATED_ACCOUNT_FIXTURE")
        write(tools / "id", "#!/bin/bash\necho 0\n")
        write(tools / "cmd", "#!/bin/bash\necho 'package:com.wisedu.cpdaily uid:10444'\n")
        if GENERIC:
            write(tools / "cmd", "#!/bin/bash\nif [[ \" $* \" == *' -s '* ]]; then [ \"$FG_SYSTEM\" != 1 ] || echo \"package:$FG_PACKAGE\"; exit 0; fi\necho \"package:$FG_PACKAGE uid:10444\"\necho \"package:$FG_PACKAGE.extra uid:10555\"\n")
        write(tools / "stat", "#!/bin/bash\nif [ \"$1\" = -c ] && [ \"$2\" = %u ]; then echo 10444; else exec /usr/bin/stat \"$@\"; fi\n")
        write(tools / "am", "#!/bin/bash\necho \"$*\" >> \"$FG_STAGE/stops\"\n")
        write(tools / "mv", "#!/bin/bash\nif [ -n \"$FG_FAIL_SOURCE\" ] && [ \"$1\" = \"$FG_FAIL_SOURCE\" ] && [ ! -e \"$FG_STAGE/failed_once\" ]; then touch \"$FG_STAGE/failed_once\"; exit 1; fi\nexec /usr/bin/mv \"$@\"\n")
        source = (ROOT / ("app/src/main/assets/manual-location-cache.sh" if MANUAL else "app/src/main/assets/location-cache.sh" if GENERIC else "app/src/main/assets/campus-location-cache.sh" if PRODUCTION else "app/src/debug/assets/location-cache-isolation.sh")).read_text(encoding="utf-8")
        source = source.replace("fg_db=/data/user/0/com.wisedu.cpdaily/databases", "fg_db='" + bash_path(database) + "'")
        source = source.replace("fg_backup=/data/system/fakegeo/campus-location-cache-backup", "fg_backup='" + bash_path(backup) + "'")
        if PRODUCTION:
            source = source.replace("fg_base=/data/system/fakegeo", "fg_base='" + bash_path(stage) + "'")
        if GENERIC:
            source = source.replace('fg_app="/data/user/0/$fg_package"', 'fg_app="$FG_APP_ROOT/$fg_package"')
        runner = stage / "location-cache-isolation.sh"
        write(runner, source)
        env = dict(os.environ, FG_STAGE=bash_path(stage), FG_MOCK_BIN=bash_path(tools), FG_RUNNER=bash_path(runner), FG_PACKAGE=PACKAGE, FG_APP_ROOT=bash_path(stage / "apps"))
        command = [BASH, "-c", 'export PATH="$FG_MOCK_BIN:$PATH"; exec /bin/bash "$FG_RUNNER" "$@"', "cache-test"]

        def run(action, success=True, fail_source=""):
            result = subprocess.run(command + [action] + ([REVISION, PACKAGE] if GENERIC else [REVISION] if PRODUCTION else []), env=dict(env, FG_FAIL_SOURCE=fail_source), capture_output=True, text=True, timeout=10)
            assert (result.returncode == 0) == success, result.stdout + result.stderr
            return result

        if mode == "missing":
            for file in original: (database / file).unlink()
            if MANUAL:
                (baidu / "ofl_location.db").unlink(); (baidu / "ofl_location.db-journal").unlink()
            result = run("backup")
            if GENERIC:
                assert "LOCATION_CACHE_NOT_FOUND" in result.stdout
                assert not backup.exists()
                assert not (stage / "stops").exists()
                print(json.dumps({"case": name, "passed": True}), flush=True)
                return
            run("restore")
        elif mode == "unsafe":
            # A non-file candidate must reject the operation before stopping the target.
            (database / "hmdb").unlink()
            (database / "hmdb").mkdir()
            run("backup", False)
            assert not backup.exists()
            assert not (stage / "stops").exists()
            print(json.dumps({"case": name, "passed": True}), flush=True)
            return
        elif mode == "distinct_revisions":
            run("backup")
            (database / "hmdb").write_bytes(b"SECOND_LOCATION_CACHE")
            second = subprocess.run(command + ["backup", "1790849051001"] + ([PACKAGE] if GENERIC else []), env=dict(env, FG_FAIL_SOURCE=""), capture_output=True, text=True, timeout=10)
            assert second.returncode == 0, second.stdout + second.stderr
            assert (backed / "hmdb").read_bytes() == original["hmdb"]
            assert (stage / (PREFIX + ("1790849051001/databases/hmdb" if MANUAL else "1790849051001/hmdb"))).read_bytes() == b"SECOND_LOCATION_CACHE"
        elif mode == "invalid_revision":
            for value in ["../outside", "1;echo INJECTED", "0" * 20, ""]:
                result = subprocess.run(command + ["backup", value] + ([PACKAGE] if GENERIC else []), env=env, capture_output=True, text=True, timeout=10)
                assert result.returncode != 0, result.stdout + result.stderr
            for file, data in original.items(): assert (database / file).read_bytes() == data
            assert not backup.exists()
            assert not (stage / "stops").exists()
            print(json.dumps({"case": name, "passed": True}), flush=True)
            return
        elif mode in ("invalid_package", "system_package"):
            if mode == "invalid_package":
                for value in ["../other", "org.example.a;id", "org.example.$(id)", "com.android.phone", "io.github.fakegeo", "com.google.android.gms"]:
                    result = subprocess.run(command + ["backup", REVISION, value], env=env, capture_output=True, text=True, timeout=10)
                    assert result.returncode != 0, result.stdout + result.stderr
            else:
                result = subprocess.run(command + ["backup", REVISION, PACKAGE], env=dict(env, FG_SYSTEM="1"), capture_output=True, text=True, timeout=10)
                assert result.returncode != 0 and "SYSTEM_CACHE_TARGET_REJECTED" in result.stdout
            for file, data in original.items(): assert (database / file).read_bytes() == data
            assert not backup.exists() and not (stage / "stops").exists()
            print(json.dumps({"case": name, "passed": True}), flush=True)
            return
        elif mode == "rollback":
            run("backup", False, bash_path(database / "alsn20170807.db"))
            for file, data in original.items(): assert (database / file).read_bytes() == data
            assert not backup.exists()
        else:
            run("backup")
            for file, data in original.items():
                assert not (database / file).exists()
                assert (backed / file).read_bytes() == data
            if mode == "double_backup":
                run("backup", False)
            elif mode == "package_change":
                (backup / "package").write_text("org.example.different\n")
                run("restore", False)
                assert not (database / "hmdb").exists()
                (backup / "package").write_text(PACKAGE + "\n")
            elif mode == "uid_change":
                (backup / "uid").write_text("10445\n")
                run("restore", False)
                assert not (database / "hmdb").exists()
                (backup / "uid").write_text("10444\n")
            # Newly created WAL/SHM did not exist before the test. Neither may accompany the old DB.
            (database / "hmdb").write_bytes(b"TEST_HISTORY")
            (database / "hmdb-wal").write_bytes(b"TEST_WAL")
            (database / "alsn20170807.db-shm").write_bytes(b"TEST_SHM")
            if MANUAL:
                assert not (baidu / "ofl_location.db").exists()
                (baidu / "ofl_location.db").write_bytes(b"NEW_BAIDU_CACHE")
                (baidu / "ofl_location.db-wal").write_bytes(b"NEW_BAIDU_WAL")
            if mode == "restore_retry":
                run("restore", False, bash_path(backed / "hmdb"))
                assert (backup / "state").read_text().strip() == "RESTORING_ORIGINALS"
            run("restore")
            for file, data in original.items(): assert (database / file).read_bytes() == data
            assert not (database / "hmdb-wal").exists()
            assert not (database / "alsn20170807.db-shm").exists()
            assert (backup / ("generated/databases/hmdb-wal" if MANUAL else "generated/hmdb-wal")).read_bytes() == b"TEST_WAL"
            assert (backup / ("generated/databases/alsn20170807.db-shm" if MANUAL else "generated/alsn20170807.db-shm")).read_bytes() == b"TEST_SHM"
            assert (backup / "state").read_text().strip() == "RESTORED"
            if MANUAL:
                assert (baidu / "ofl_location.db").read_bytes() == b"ORIGINAL_BAIDU_OFFLINE"
                assert not (baidu / "ofl_location.db-wal").exists()
                assert (backup / "generated/files/ofld/ofl_location.db-wal").read_bytes() == b"NEW_BAIDU_WAL"
            if mode == "repeat_restore":
                run("restore")
                assert (database / "hmdb").read_bytes() == original["hmdb"]
        assert (database / "login.db").read_bytes() == b"UNRELATED_ACCOUNT_FIXTURE"
        assert "force-stop --user 0 " + PACKAGE in (stage / "stops").read_text()
        if MANUAL: assert (baidu / "ofl_statistics.db").read_bytes() == b"UNRELATED_BAIDU_STATISTICS"
        if GENERIC: assert (other / "hmdb").read_bytes() == b"UNSELECTED_LOCATION_CACHE"
        print(json.dumps({"case": name, "passed": True}), flush=True)


if __name__ == "__main__":
    case("roundtrip_and_new_sqlite_sidecar_isolation", "roundtrip")
    case("partial_backup_failure_restores_originals", "rollback")
    case("existing_backup_cannot_be_overwritten", "double_backup")
    case("changed_target_uid_is_rejected", "uid_change")
    case("partial_restore_can_be_retried", "restore_retry")
    case("repeat_restore_does_not_replace_originals_again", "repeat_restore")

    if PRODUCTION:
        case("empty_cache_does_not_block_first_activation", "missing")
        case("non_file_cache_candidate_rejected_before_target_stop", "unsafe")
        case("new_revision_preserves_earlier_backup", "distinct_revisions")
        case("invalid_revision_cannot_escape_backup_directory", "invalid_revision")

    if GENERIC:
        case("package_in_backup_must_match_restore_target", "package_change")
        case("invalid_and_protected_packages_cannot_be_touched", "invalid_package")
        case("system_app_with_application_uid_is_rejected", "system_package")

    if MANUAL:
        case("baidu_only_app_can_be_cleared_and_restored", "baidu_only")
