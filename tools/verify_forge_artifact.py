"""Verify the reobfuscated Forge distribution before CI uploads it."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(path):
    properties = dict(
        line.split("=", 1)
        for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        required = {
            "META-INF/mods.toml",
            "META-INF/MANIFEST.MF",
            "META-INF/jarjar/metadata.json",
            "productivebeesgenesis.mixins.json",
            "productivebeesgenesis.refmap.json",
            "productivebeesgenesis.png",
            "pack.mcmeta",
            "LICENSE_productivebeesgenesis",
            "THIRD_PARTY_LICENSES_productivebeesgenesis.md",
        }
        missing = sorted(required - names)
        if missing:
            raise ValueError("Missing distribution files: " + ", ".join(missing))
        unexpected = sorted(
            name for name in names
            if name.startswith(("com/jdte/", "src/compatApi/", "port-disabled/"))
            or name == "META-INF/neoforge.mods.toml"
        )
        if unexpected:
            raise ValueError("Unexpected distribution files: " + ", ".join(unexpected))
        metadata = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8"))
        mod = next(entry for entry in metadata["mods"] if entry["modId"] == properties["mod_id"])
        if mod["version"] != properties["mod_version"]:
            raise ValueError("Forge metadata version does not match gradle.properties")
        dependencies = {entry["modId"]: entry for entry in metadata["dependencies"][properties["mod_id"]]}
        if "forge" not in dependencies or "neoforge" in dependencies:
            raise ValueError("Distribution does not declare the Forge loader")
        if dependencies["minecraft"]["versionRange"] != "[1.20.1,1.20.2)":
            raise ValueError("Distribution does not target Minecraft 1.20.1")
        if json.loads(archive.read("pack.mcmeta"))["pack"]["pack_format"] != 15:
            raise ValueError("Unexpected resource pack format for Minecraft 1.20.1")
        mixins = json.loads(archive.read("productivebeesgenesis.mixins.json"))
        if mixins["compatibilityLevel"] != "JAVA_17":
            raise ValueError("Mixin compatibility level is not Java 17")
        if not json.loads(archive.read(mixins["refmap"])).get("mappings"):
            raise ValueError("Mixin refmap has no mappings")
        nested = json.loads(archive.read("META-INF/jarjar/metadata.json"))["jars"]
        if not any(entry["identifier"]["artifact"] == "mixinextras-forge" and entry["path"] in names for entry in nested):
            raise ValueError("MixinExtras Forge is not bundled")
        classes = [name for name in names if name.endswith(".class")]
        if not classes:
            raise ValueError("Distribution contains no compiled classes")
        for name in classes:
            bytecode = archive.read(name)
            if bytecode[:4] != b"\xca\xfe\xba\xbe" or struct.unpack(">H", bytecode[6:8])[0] != 61:
                raise ValueError("Class is not Java 17 bytecode: " + name)
        for source, bundled in (
            ("LICENSE", "LICENSE_productivebeesgenesis"),
            ("THIRD_PARTY_LICENSES.md", "THIRD_PARTY_LICENSES_productivebeesgenesis.md"),
        ):
            if archive.read(bundled) != (ROOT / source).read_bytes():
                raise ValueError("Bundled license differs from source: " + source)
    print(json.dumps({
        "artifact": str(path.resolve()),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "classes": len(classes),
        "minecraft": properties["minecraft_version"],
        "version": properties["mod_version"],
        "status": "passed",
    }, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, nargs="?")
    args = parser.parse_args()
    path = args.artifact
    if path is None:
        candidates = list((ROOT / "build" / "libs").glob("*-all.jar"))
        if len(candidates) != 1:
            raise ValueError("Expected exactly one *-all.jar in build/libs")
        path = candidates[0]
    verify(path)


if __name__ == "__main__":
    main()
