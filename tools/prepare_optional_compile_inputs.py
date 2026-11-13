"""Stage pinned optional mod JARs; never bundle them in Genesis."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def stage(entry, data):
    artifact, version = entry["artifact"], entry["version"]
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise ValueError("Dependency checksum mismatch: " + artifact)
    target = ROOT / ".cached-maven/local" / artifact / version
    target.mkdir(parents=True, exist_ok=True)
    (target / f"{artifact}-{version}.jar").write_bytes(data)
    (target / f"{artifact}-{version}.pom").write_text(
        f'<project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>\n',
        encoding="utf-8")
    print("Prepared", artifact)

def prepare(directories=(), download_missing=False):
    inputs = json.loads((ROOT / "tools/forge_optional_compile_inputs.json").read_text("utf-8"))
    remaining = {entry["sha256"]: entry for entry in inputs}
    for directory in directories:
        for jar in directory.glob("*.jar"):
            data = jar.read_bytes()
            entry = remaining.get(hashlib.sha256(data).hexdigest())
            if entry is not None:
                stage(entry, data)
                del remaining[entry["sha256"]]
    for entry in list(remaining.values()):
        artifact, version = entry["artifact"], entry["version"]
        target = ROOT / ".cached-maven/local" / artifact / version / f"{artifact}-{version}.jar"
        if target.is_file():
            data = target.read_bytes()
            if hashlib.sha256(data).hexdigest() == entry["sha256"]:
                stage(entry, data)
                del remaining[entry["sha256"]]
                continue
        if download_missing:
            request = urllib.request.Request(entry["url"], headers={"User-Agent": "productive-bees-genesis-forge-build"})
            with urllib.request.urlopen(request, timeout=90) as response:
                stage(entry, response.read())
            del remaining[entry["sha256"]]
    if remaining:
        raise ValueError("Missing pinned inputs: " + ", ".join(entry["artifact"] for entry in remaining.values()))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mods", type=Path, nargs="+", help="Directories containing the original optional mod JARs")
    args = parser.parse_args()
    prepare(args.mods)

if __name__ == "__main__":
    main()
