"""Stage pinned optional mod JARs from a local mods directory; never bundle them."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mods", type=Path, nargs="+", help="Directories containing the original optional mod JARs")
    args = parser.parse_args()
    inputs = json.loads((ROOT / "tools/forge_optional_compile_inputs.json").read_text("utf-8"))
    remaining = {entry["sha256"]: entry for entry in inputs}
    for directory in args.mods:
        for jar in directory.glob("*.jar"):
            entry = remaining.get(hashlib.sha256(jar.read_bytes()).hexdigest())
            if entry is None:
                continue
            artifact, version = entry["artifact"], entry["version"]
            target = ROOT / ".cached-maven/local" / artifact / version
            target.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(jar, target / f"{artifact}-{version}.jar")
            (target / f"{artifact}-{version}.pom").write_text(
                f'<project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>\n', encoding="utf-8")
            del remaining[entry["sha256"]]
            print("Prepared", artifact)
    if remaining:
        parser.error("Missing pinned inputs: " + ", ".join(entry["artifact"] for entry in remaining.values()))

if __name__ == "__main__":
    main()
