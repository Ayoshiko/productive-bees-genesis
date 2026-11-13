"""Inspect release metadata and mirror the published NeoForge attachment."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import tomllib
from urllib.parse import quote
import zipfile


MOD_ID = "productivebeesgenesis"
MAINTENANCE = "main-neo/1.21.1"
WORKFLOW = ".github/workflows/build.yml"
PROOF = "build-proof.json"
LICENSE_MANIFEST = "META-INF/license-manifest.json"
ENTRY_HASHES = "META-INF/license-entry-hashes.json"


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def properties(text):
    result = {}
    for line in text.splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    require(result.get("mod_id") == MOD_ID, "Unexpected mod_id")
    require(result.get("minecraft_version") == "1.21.1", "Unexpected Minecraft version")
    for key in ("mod_version", "curseforge_release_version"):
        require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?",
                             result.get(key, "")), f"Invalid {key}")
    return result


def jar_name(expected):
    return f"{MOD_ID}-{expected['mod_version']}.jar"


def manifest_attributes(data):
    unfolded = data.decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    return dict(line.split(": ", 1) for line in unfolded.split("\n\n", 1)[0].splitlines()
                if ": " in line)


def verify_jar(data, filename, expected):
    require(filename == jar_name(expected), "Unexpected release filename")
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
        require(len(names) == len(set(names)), "Duplicate JAR entry")
        required = {
            "META-INF/MANIFEST.MF", "META-INF/neoforge.mods.toml", "NOTICE",
            f"LICENSE_{MOD_ID}", f"THIRD_PARTY_LICENSES_{MOD_ID}.md",
            "productivebeesgenesis.png", LICENSE_MANIFEST, ENTRY_HASHES,
        }
        require(required <= set(names), f"Missing required notices or metadata: {required - set(names)}")
        attributes = manifest_attributes(jar.read("META-INF/MANIFEST.MF"))
        require(attributes.get("Implementation-Version") == expected["mod_version"],
                "Manifest mod version mismatch")
        require(attributes.get("CurseForge-Release-Version") == expected["curseforge_release_version"],
                "Manifest CurseForge version mismatch")
        require(attributes.get("Minecraft-Version") == expected["minecraft_version"],
                "Manifest Minecraft version mismatch")
        metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        require(metadata.get("modLoader") == "javafml", "Unexpected mod loader")
        mods = [mod for mod in metadata.get("mods", []) if mod.get("modId") == MOD_ID]
        require(len(mods) == 1 and mods[0].get("version") == expected["mod_version"],
                "NeoForge mod version mismatch")
        provenance = json.loads(jar.read(LICENSE_MANIFEST))
        hashes = json.loads(jar.read(ENTRY_HASHES))
        require(provenance.get("distribution_version") == expected["mod_version"],
                "Provenance version mismatch")
        require(set(hashes) == set(names) - {LICENSE_MANIFEST, ENTRY_HASHES},
                "Final entry hash coverage mismatch")
        for name, digest in hashes.items():
            require(sha256(jar.read(name)) == digest, f"Final entry hash mismatch: {name}")
        require(provenance.get("license_files") and provenance.get("files"),
                "Empty license or provenance inventory")
        for notice in provenance["license_files"]:
            name = notice["jar_entry"]
            require(name in names and sha256(jar.read(name)) == notice["sha256"],
                    f"Missing or altered license text: {name}")
        mapped = set()
        for source in provenance["files"]:
            entries = source.get("jar_entries") or ([source["jar_entry"]] if source.get("jar_entry") else [])
            for name in entries:
                require(name in names and name not in mapped, f"Invalid provenance mapping: {name}")
                mapped.add(name)
            require(set(source.get("notices", [])) <= set(names), "Missing source notice")
        require(all(name in mapped for name in names if name.startswith("assets/") or name.endswith(".png")),
                "Unmapped bundled asset")
        unresolved = [entry["id"] for entry in provenance["files"]
                      if entry.get("authorization_status") == "unresolved"]
        blockers = provenance.get("release_blockers", [])
        return {
            "sha256": sha256(data), "bytes": len(data), "file": filename,
            "provenance_complete": not blockers and not unresolved,
            "unresolved_groups": len(blockers), "unresolved_records": len(unresolved),
        }



def english_changelog(body):
    lines = body.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if line.rstrip() == "## English":
            english = "".join(lines[index + 1:])
            require(english.strip(), "Release body must contain a non-empty ## English section")
            return english
    raise VerificationError("Release body must contain a non-empty ## English section")


def github_output(path, name, value):
    require(re.fullmatch(r"[a-z_]+", name), "Invalid output name")
    delimiter = "PBG_" + sha256(value.encode("utf-8"))
    while delimiter in value.splitlines():
        delimiter += "_"
    with Path(path).open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"{name}<<{delimiter}\n")
        output.write(value)
        if not value.endswith("\n"):
            output.write("\n")
        output.write(delimiter + "\n")


class Commands:
    def __init__(self, seconds=240):
        self.deadline = time.monotonic() + seconds

    def run(self, *args):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "Release verification deadline exceeded")
        completed = subprocess.run(args, check=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=min(45, remaining))
        return completed.stdout

    def api(self, endpoint, binary=False):
        arguments = ["gh", "api", endpoint, "--method", "GET"]
        if binary:
            arguments += ["--header", "Accept: application/octet-stream"]
        data = self.run(*arguments)
        return data if binary else json.loads(data)


def record(args):
    expected = properties(Path(args.properties).read_text(encoding="utf-8"))
    jar = Path(args.jar_dir) / jar_name(expected)
    data = jar.read_bytes()
    details = verify_jar(data, jar.name, expected)
    commit = Commands().run("git", "rev-parse", "HEAD").decode().strip()
    require(commit == os.environ["GITHUB_SHA"], "Build checkout does not match GITHUB_SHA")
    proof = {
        "schema": 1, "commit": commit, "repository": os.environ["GITHUB_REPOSITORY"],
        "workflow": WORKFLOW, "run_id": os.environ["GITHUB_RUN_ID"],
        "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"], "event": os.environ["GITHUB_EVENT_NAME"],
        "ref": os.environ["GITHUB_REF"], "mod_version": expected["mod_version"],
        "curseforge_release_version": expected["curseforge_release_version"], **details,
    }
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, destination / jar.name)
    (destination / PROOF).write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(proof))


def mirror(args):
    commands = Commands()
    repository = os.environ["GITHUB_REPOSITORY"]
    tag = os.environ["RELEASE_TAG"]
    require(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z.-]+)?", tag),
            "Invalid release tag")
    commit = commands.run("git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}").decode().strip()
    expected = properties(commands.run("git", "show", f"{commit}:gradle.properties").decode("utf-8"))
    require(expected["mod_version"] == tag[1:], "Tag does not match mod_version")
    endpoint = f"repos/{repository}"
    release = commands.api(f"{endpoint}/releases/tags/{quote(tag, safe='')}")
    require(release.get("tag_name") == tag and not release.get("draft"), "Release tag or publication mismatch")
    assets = [asset for asset in release["assets"] if asset["name"] == jar_name(expected)]
    require(len(assets) == 1, "Release must contain exactly one expected JAR")
    released_bytes = commands.api(f"{endpoint}/releases/assets/{assets[0]['id']}", binary=True)
    details = verify_jar(released_bytes, jar_name(expected), expected)
    version_type = os.environ.get("INPUT_VERSION_TYPE") or ("beta" if release["prerelease"] else "release")
    require(version_type in {"release", "beta", "alpha"}, "Invalid version type")
    body = os.environ.get("INPUT_CHANGELOG") or release.get("body") or ""
    english = english_changelog(body)
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=True)
    artifact = destination / jar_name(expected)
    artifact.write_bytes(released_bytes)
    result = {"commit": commit, "tag": tag, "release_id": release["id"], **details}
    (destination / "verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for name, value in {
        "version": expected["curseforge_release_version"], "version_type": version_type,
        "artifact": artifact.as_posix(), "changelog": english,
    }.items():
        github_output(os.environ["GITHUB_OUTPUT"], name, value)
    print(f"Prepared {jar_name(expected)} from release {tag}, SHA-256 {details['sha256']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    record_parser = subparsers.add_parser("record")
    record_parser.add_argument("--properties", default="gradle.properties")
    record_parser.add_argument("--jar-dir", default="build/libs")
    record_parser.add_argument("--output", default="build/ci/release")
    mirror_parser = subparsers.add_parser("mirror")
    mirror_parser.add_argument("--output", default="build/verified-release")
    inspect_parser = subparsers.add_parser("inspect")
    inspect_parser.add_argument("jar")
    inspect_parser.add_argument("--properties", default="gradle.properties")
    args = parser.parse_args()
    try:
        if args.command == "record":
            record(args)
        elif args.command == "mirror":
            mirror(args)
        else:
            expected = properties(Path(args.properties).read_text(encoding="utf-8"))
            jar = Path(args.jar)
            print(json.dumps(verify_jar(jar.read_bytes(), jar.name, expected)))
    except (VerificationError, OSError, KeyError, json.JSONDecodeError, tomllib.TOMLDecodeError,
            zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"Release verification failed: {error}\n")


if __name__ == "__main__":
    main()
