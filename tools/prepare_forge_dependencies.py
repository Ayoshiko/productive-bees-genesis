"""Restore verified Forge build inputs without committing third-party JARs."""
from pathlib import Path
import hashlib
import io
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / "libs"
EXPECTED = {
    "productivelib-1.20.1-0.0.4.jar": "e91c001b3589f505b5a71a533137bc672c59df68d515714fc4fac42c57bec8f0",
    "mixinextras-common-0.5.5.jar": "6aca5ce982d7924413c0a65fb8709735c09a59cc2354fc0430932549b5ca24ce",
    "mixinextras-forge-0.5.5.jar": "42564e02756fbdf78e1f1fd2115fe2d01fb42497ba3be2a3c261da2058122533",
}

def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "productive-bees-genesis-forge-build"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()

def valid(name):
    path = LIBS / name
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == EXPECTED[name]

def save(name, data):
    if hashlib.sha256(data).hexdigest() != EXPECTED[name]:
        raise ValueError("Dependency checksum mismatch: " + name)
    (LIBS / name).write_bytes(data)
    print("Prepared", name)

def main():
    LIBS.mkdir(exist_ok=True)
    name = "productivelib-1.20.1-0.0.4.jar"
    if not valid(name):
        version = json.loads(download("https://api.modrinth.com/v2/version/mywVOObd"))
        artifact = next((f for f in version["files"] if f.get("primary")), version["files"][0])
        data = download(artifact["url"])
        if hashlib.sha512(data).hexdigest() != artifact["hashes"]["sha512"]:
            raise ValueError("Productive Bees download checksum mismatch")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            save(name, archive.read("META-INF/jarjar/" + name))
    name = "mixinextras-common-0.5.5.jar"
    if not valid(name):
        save(name, download("https://repo.maven.apache.org/maven2/io/github/llamalad7/mixinextras-common/0.5.5/" + name))
    name = "mixinextras-forge-0.5.5.jar"
    if not valid(name):
        save(name, download("https://repo.maven.apache.org/maven2/io/github/llamalad7/mixinextras-forge/0.5.5/" + name))
    print("Forge compile inputs verified.")

if __name__ == "__main__":
    main()
