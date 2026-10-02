"""Check/export public source using .gitignore, without modifying the project's Git state.

Requires Python 3 and Git. Reports file names/line numbers only, never secret values.
This is a pattern check, not proof that arbitrary new files contain no personal data.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "service credential": re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b"
    ),
    "private signing key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "literal secret": re.compile(
        r"(?i)\b(?:api[_-]?key|amap[_-]?key|baidu[_-]?key|storePassword|keyPassword|password|"
        r"access[_-]?token|client[_-]?secret)\b\s*(?:=|:)\s*[\"'][A-Za-z0-9_+/=-]{16,}[\"']"
    ),
    "local absolute Windows path": re.compile(r"(?i)\b[A-Z]:[\\/]"),
    "local user home": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
}


def public_files():
    # An empty temporary index applies current ignore rules even if this workspace
    # later becomes a Git repository with previously tracked private files.
    with tempfile.TemporaryDirectory(prefix="fakegeo-source-") as directory:
        env = os.environ.copy()
        for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"):
            env.pop(key, None)
        subprocess.run(["git", "init", "--quiet", directory], env=env, check=True, capture_output=True)
        git = ["git", "-c", "core.excludesFile=" , "--git-dir=" + str(Path(directory) / ".git"),
               "--work-tree=" + str(ROOT)]
        result = subprocess.run(git + ["ls-files", "--others", "--exclude-standard", "-z"],
                                env=env, cwd=ROOT, check=True, capture_output=True)
        names = sorted(os.fsdecode(name) for name in result.stdout.split(b"\0") if name)
        # Check important privacy boundaries even when the corresponding files
        # do not exist. Real files under these paths must never enter the archive.
        samples = ["local.properties", ".gradle/phone-profile.private.json", ".idea/workspace.xml",
                   "app/build/outputs/apk/debug/app-debug.apk", "debug.keystore", ".env",
                   "diagnostics/profile.json", "device-data/shared_prefs/profile.xml", "cache.db-wal"]
        ignored = subprocess.run(git + ["check-ignore", "--no-index", "--stdin", "-z"],
                                 input=b"\0".join(s.encode() for s in samples)+b"\0",
                                 env=env, cwd=ROOT, check=True, capture_output=True)
        if set(ignored.stdout.decode().split("\0")) - {""} != set(samples):
            raise RuntimeError("Required private-file ignore rules are missing")
    return names


def check(names):
    issues = []
    for name in names:
        path = ROOT / name
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != ROOT):
            issues.append((name, 0, "symlink"))
            continue
        path.resolve().relative_to(ROOT)
        if name == "gradle/wrapper/gradle-wrapper.jar":
            continue  # Required Gradle Wrapper binary; no generated APK/JAR outputs.
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError:
            issues.append((name, 0, "unexpected binary file"))
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    issues.append((name, line_no, label))
    if issues:
        for name, line, label in issues:
            print(f"REVIEW {name}:{line}: {label}")
        raise SystemExit("Source export stopped; review the reported files.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check only; do not create an archive")
    args = parser.parse_args()
    names = public_files()
    check(names)
    print(f"Checked {len(names)} public files; required private paths are excluded.")
    if args.check:
        return
    target = ROOT / "build" / "public" / "FakeGeo-source.zip"
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(ROOT / name, "FakeGeo/" + name)
    print("Created " + target.relative_to(ROOT).as_posix())
    print("SHA256 " + hashlib.sha256(target.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
