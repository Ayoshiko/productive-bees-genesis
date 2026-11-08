#!/usr/bin/env python3
"""Prepare exactly the development JARs pinned in one manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

MAX_SECONDS = 240.0
REQUEST_SECONDS = 25.0
MAX_JAR_BYTES = 256 * 1024 * 1024
CHUNK_BYTES = 64 * 1024


class DependencyError(RuntimeError):
    pass


class Deadline:
    def __init__(self, seconds: float, clock=time.monotonic):
        if not 0 < seconds <= MAX_SECONDS:
            raise DependencyError("Deadline must be greater than zero and at most 240 seconds")
        self.clock = clock
        self.end = clock() + seconds

    def remaining(self) -> float:
        remaining = self.end - self.clock()
        if remaining <= 0:
            raise DependencyError("Development dependency preparation deadline exceeded")
        return remaining


def target_path(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if "\\" in relative or ":" in relative or parts.is_absolute() or ".." in parts.parts or not parts.parts:
        raise DependencyError(f"Invalid dependency path: {relative}")
    if parts.parts[0] != "libs" or parts.suffix != ".jar":
        raise DependencyError(f"Dependency must be a JAR inside libs: {relative}")
    result = root.joinpath(*parts.parts)
    for path in [result, *result.parents]:
        if path == root:
            break
        if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
            raise DependencyError(f"Refusing linked dependency path: {relative}")
    return result


def read_manifest(path: Path, root: Path) -> list[dict]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise DependencyError(f"Cannot read dependency manifest: {error}") from error
    if not isinstance(manifest, dict) or manifest.get("schema") != 1 or not isinstance(manifest.get("entries"), list):
        raise DependencyError("Unsupported development dependency manifest")
    entries = manifest["entries"]
    names = set()
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise DependencyError("Invalid dependency entry")
        name = entry["path"]
        target_path(root, name)
        if name in names:
            raise DependencyError(f"Duplicate dependency path: {name}")
        names.add(name)
        if not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
            raise DependencyError(f"Invalid SHA-256: {name}")
        if "official_sha512" in entry and not re.fullmatch(r"[0-9a-f]{128}", entry["official_sha512"]):
            raise DependencyError(f"Invalid official SHA-512: {name}")
        if "url" in entry:
            if "archive" in entry or "entry" in entry:
                raise DependencyError(f"Ambiguous dependency source: {name}")
            if not isinstance(entry["url"], str) or not entry["url"].startswith("https://"):
                raise DependencyError(f"Dependency URL must use HTTPS: {name}")
        else:
            if not isinstance(entry.get("archive"), str) or not isinstance(entry.get("entry"), str):
                raise DependencyError(f"Missing dependency source: {name}")
            member = PurePosixPath(entry["entry"])
            if member.is_absolute() or ".." in member.parts or "\\" in entry["entry"]:
                raise DependencyError(f"Invalid nested JAR entry: {name}")
    by_name = {entry["path"]: entry for entry in entries}
    visited = set()

    def visit(name: str, active: set[str]):
        if name in active:
            raise DependencyError(f"Cyclic dependency archive: {name}")
        if name in visited:
            return
        entry = by_name[name]
        if "archive" in entry:
            if entry["archive"] not in by_name:
                raise DependencyError(f"Archive is not pinned in the manifest: {entry['archive']}")
            visit(entry["archive"], active | {name})
        visited.add(name)

    for name in by_name:
        visit(name, set())
    return entries


def verify_file(path: Path, entry: dict, deadline: Deadline):
    if not path.is_file() or path.stat().st_nlink != 1:
        raise DependencyError(f"Refusing non-regular or linked JAR: {entry['path']}")
    digests = {"sha256": hashlib.sha256()}
    if "official_sha512" in entry:
        digests["official_sha512"] = hashlib.sha512()
    with path.open("rb") as stream:
        while True:
            deadline.remaining()
            chunk = stream.read(CHUNK_BYTES)
            if not chunk:
                break
            for digest in digests.values():
                digest.update(chunk)
    for name, digest in digests.items():
        if digest.hexdigest() != entry[name]:
            raise DependencyError(
                f"Digest mismatch for {entry['path']}: {name} expected {entry[name]}, "
                f"actual {digest.hexdigest()}; existing file was not overwritten"
            )


def stage_stream(root: Path, stream, deadline: Deadline) -> Path:
    staging = root / "build" / "dependency-downloads"
    staging.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix="jar-", suffix=".part", dir=staging, delete=False) as output:
        path = Path(output.name)
        total = 0
        reader = getattr(stream, "read1", stream.read)
        while True:
            deadline.remaining()
            data = reader(CHUNK_BYTES)
            if not data:
                break
            total += len(data)
            if total > MAX_JAR_BYTES:
                raise DependencyError(f"JAR exceeds download budget; retained {path}")
            output.write(data)
    return path


def fetch_entry(root: Path, entry: dict, deadline: Deadline) -> Path:
    request = urllib.request.Request(entry["url"], headers={
        "User-Agent": "ProductiveBeesGenesis-development-dependencies/1.0"
    })
    try:
        # One request per source; the CLI supervisor also bounds DNS and slow streams.
        request_deadline = Deadline(min(REQUEST_SECONDS, deadline.remaining()))
        with urllib.request.urlopen(request, timeout=request_deadline.remaining()) as response:
            return stage_stream(root, response, request_deadline)
    except (OSError, ValueError) as error:
        raise DependencyError(f"Cannot download {entry['path']}: {error}") from error


def extract_entry(root: Path, entry: dict, deadline: Deadline) -> Path:
    archive = target_path(root, entry["archive"])
    try:
        with zipfile.ZipFile(archive) as jar:
            members = [info for info in jar.infolist() if info.filename == entry["entry"]]
            if len(members) != 1 or members[0].is_dir() or members[0].file_size > MAX_JAR_BYTES:
                raise DependencyError(f"Expected exactly one nested JAR entry: {entry['entry']}")
            with jar.open(members[0]) as stream:
                return stage_stream(root, stream, deadline)
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise DependencyError(f"Cannot extract {entry['path']}: {error}") from error


def prepare(root: Path, entries: list[dict], *, check: bool, deadline: Deadline) -> list[dict]:
    root = root.resolve()
    present = set()
    for entry in entries:
        deadline.remaining()
        path = target_path(root, entry["path"])
        if path.exists():
            verify_file(path, entry, deadline)
            present.add(entry["path"])
    missing = [entry["path"] for entry in entries if entry["path"] not in present]
    if check and missing:
        raise DependencyError("Missing pinned development JARs: " + ", ".join(missing))
    by_name = {entry["path"]: entry for entry in entries}
    results = []

    def ensure(name: str):
        if name in present:
            return
        entry = by_name[name]
        if "archive" in entry:
            ensure(entry["archive"])
            staged = extract_entry(root, entry, deadline)
        else:
            staged = fetch_entry(root, entry, deadline)
        try:
            verify_file(staged, entry, deadline)
        except DependencyError as error:
            raise DependencyError(f"{error}; failed download retained at {staged}") from error
        target = target_path(root, name)
        target.parent.mkdir(parents=True, exist_ok=True)
        deadline.remaining()
        try:
            # Exclusive creation also rejects a file that appeared after preflight.
            with staged.open("rb") as source, target.open("xb") as destination:
                while True:
                    deadline.remaining()
                    chunk = source.read(CHUNK_BYTES)
                    if not chunk:
                        break
                    destination.write(chunk)
        except FileExistsError as error:
            raise DependencyError(f"Dependency appeared during preparation; not overwritten: {name}") from error
        verify_file(target, entry, deadline)
        present.add(name)
        results.append({"path": name, "status": "prepared", "sha256": entry["sha256"],
                        "staged": str(staged)})

    if not check:
        for entry in entries:
            ensure(entry["path"])
    results.extend({"path": entry["path"], "status": "verified", "sha256": entry["sha256"]}
                   for entry in entries if not any(row["path"] == entry["path"] for row in results))
    return results


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--manifest", type=Path, default=Path("gradle/development-dependencies.json"))
    parser.add_argument("--check", action="store_true", help="Verify existing inputs without network or writes")
    parser.add_argument("--deadline", type=float, default=MAX_SECONDS)
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if not 0 < args.deadline <= MAX_SECONDS:
        parser.error("--deadline must be greater than zero and at most 240")
    if not args.worker:
        command = [sys.executable, str(Path(__file__).resolve()), "--worker", "--root", str(args.root),
                   "--manifest", str(args.manifest), "--deadline", str(args.deadline)]
        if args.check:
            command.append("--check")
        try:
            return subprocess.run(command, timeout=args.deadline, check=False).returncode
        except subprocess.TimeoutExpired:
            print("Development dependency preparation exceeded its hard deadline; worker terminated", file=sys.stderr)
            return 1
    try:
        deadline = Deadline(args.deadline)
        root = args.root.resolve()
        manifest = args.manifest if args.manifest.is_absolute() else root / args.manifest
        entries = read_manifest(manifest, root)
        results = prepare(root, entries, check=args.check, deadline=deadline)
        print(json.dumps({"status": "verified" if args.check else "prepared", "entries": results}, indent=2))
        return 0
    except (DependencyError, OSError, zipfile.BadZipFile) as error:
        print(f"Development dependency preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
