"""Read PR metadata as data; never check out or execute a contributor's code."""

import base64
import datetime
import json
import os
from pathlib import Path
import re
import sys
import tomllib
import urllib.error
import urllib.parse
import urllib.request

CONTEXT = "PR branch policy"
ROUTES = {
    "main-neo/1.21.1": ("1.21.1", "neoforge", 21),
    "bees-processing-network/1.21.1": ("1.21.1", "neoforge", 21),
    "forge/1.20.1": ("1.20.1", "forge", 17),
}
MANIFESTS = (
    "src/main/templates/META-INF/neoforge.mods.toml",
    "src/main/resources/META-INF/neoforge.mods.toml",
    "src/main/templates/META-INF/mods.toml",
    "src/main/resources/META-INF/mods.toml",
)
INPUTS = ("gradle.properties", "build.gradle", *MANIFESTS)


class PolicyError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PolicyError(message)


def properties(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        require(bool(separator), "gradle.properties must use explicit key=value entries.")
        key = key.strip()
        require(key not in result, "Duplicate Gradle property: " + key)
        result[key] = value.strip()
    return result


def validate_snapshot(base_ref, head_ref, files):
    require(base_ref in ROUTES,
            "Choose main-neo/1.21.1, forge/1.20.1, or bees-processing-network/1.21.1; Alpha is archival.")
    if base_ref == "main-neo/1.21.1":
        require(not head_ref.startswith(("bees-processing-network/", "visual-lab/", "Alpha")),
                "Experimental branches must not be merged directly into main-neo/1.21.1. Submit a focused maintenance change.")
    minecraft, loader, java = ROUTES[base_ref]
    require(files.get("gradle.properties") is not None, "Missing gradle.properties.")
    require(files.get("build.gradle") is not None, "Missing build.gradle.")
    props = properties(files["gradle.properties"])
    require(props.get("minecraft_version") == minecraft,
            f"Target {base_ref} requires Minecraft {minecraft}. Retarget the PR to the matching version branch.")
    require(props.get("mod_id") == "productivebeesgenesis", "Unexpected mod_id.")
    version_key = "neo_version" if loader == "neoforge" else "forge_version"
    opposite_key = "forge_version" if loader == "neoforge" else "neo_version"
    require(bool(props.get(version_key)) and opposite_key not in props,
            f"Target {base_ref} requires {version_key}, without {opposite_key}.")
    build = re.sub(r"/\*.*?\*/", "", files["build.gradle"], flags=re.S)
    build = re.sub(r"(?m)^\s*//.*$", "", build)
    plugin = "net.neoforged.moddev" if loader == "neoforge" else "net.minecraftforge.gradle"
    other_plugin = "net.minecraftforge.gradle" if loader == "neoforge" else "net.neoforged.moddev"
    plugin_pattern = r"""(?m)^\s*id\s*(?:\(\s*)?['"]%s['"]"""
    require(re.search(plugin_pattern % re.escape(plugin), build) is not None,
            f"Target {base_ref} requires the {plugin} Gradle plugin.")
    require(re.search(plugin_pattern % re.escape(other_plugin), build) is None,
            "The opposite loader's Gradle plugin must not be present.")
    java_pattern = r"(?m)^\s*java\.toolchain\.languageVersion\s*=\s*JavaLanguageVersion\.of\(\s*(\d+)\s*\)"
    versions = re.findall(java_pattern, build)
    require(versions == [str(java)], f"Target {base_ref} requires an explicit Java {java} toolchain.")

    present = [path for path in MANIFESTS if files.get(path) is not None]
    expected_name = "neoforge.mods.toml" if loader == "neoforge" else "mods.toml"
    require(len(present) == 1 and present[0].rsplit("/", 1)[1] == expected_name,
            f"Keep exactly one {expected_name} manifest and remove opposite-loader metadata.")
    def expand(match):
        key = match.group(1)
        require(key in props, "Missing manifest property: " + key)
        return props[key]

    expanded = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", expand, files[present[0]])
    metadata = tomllib.loads(expanded)
    require(metadata.get("modLoader") == "javafml", "Unexpected mod loader in manifest.")
    mods = metadata.get("mods", [])
    require(any(mod.get("modId") == "productivebeesgenesis" for mod in mods),
            "The manifest must declare productivebeesgenesis.")
    dependencies = metadata.get("dependencies", {}).get("productivebeesgenesis", [])
    loader_dependencies = [dep for dep in dependencies if dep.get("modId") in ("forge", "neoforge")]
    require(len(loader_dependencies) == 1 and loader_dependencies[0].get("modId") == loader,
            f"The manifest must depend on {loader}, without the opposite loader.")
    loader_dependency = loader_dependencies[0]
    require(loader_dependency.get("type") == "required" if loader == "neoforge"
            else loader_dependency.get("mandatory") is True,
            "The loader dependency must be required.")
    minecraft_dependencies = [dep for dep in dependencies if dep.get("modId") == "minecraft"]
    next_patch = "1.21.2" if minecraft == "1.21.1" else "1.20.2"
    allowed_ranges = {f"[{minecraft}]", f"[{minecraft},{next_patch})"}
    require(len(minecraft_dependencies) == 1
            and minecraft_dependencies[0].get("versionRange") in allowed_ranges,
            f"The manifest must restrict Minecraft to {minecraft}.")
    return f"{base_ref}: Minecraft {minecraft}, {loader}, Java {java}."


class GitHub:
    def __init__(self):
        self.token = os.environ["GH_TOKEN"]
        self.repository = os.environ["GITHUB_REPOSITORY"]
        require(self.repository == "Ayoshiko/productive-bees-genesis", "Unexpected policy repository.")

    def request(self, path, method="GET", payload=None, missing_ok=False):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            "https://api.github.com" + path, data=data, method=method,
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json",
                     "Content-Type": "application/json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            # Do not expose request headers or response bodies containing unrelated data.
            raise PolicyError(f"GitHub API {method} failed with HTTP {error.code}.") from None

    def snapshot(self, repository, sha):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is not None,
                "Invalid source repository.")
        require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Invalid source commit.")
        result = {}
        for path in INPUTS:
            item = self.request(f"/repos/{repository}/contents/{path}?ref={sha}", missing_ok=True)
            if item is None:
                result[path] = None
                continue
            require(item.get("type") == "file" and not item.get("target")
                    and not item.get("submodule_git_url") and item.get("encoding") == "base64",
                    "Policy inputs must be ordinary UTF-8 files: " + path)
            require(item.get("size", 0) <= 262144, "Policy input exceeds 256 KiB: " + path)
            encoded = "".join(item["content"].splitlines())
            content = base64.b64decode(encoded, validate=True).decode("utf-8")
            require(not content.startswith("\ufeff") and "\ufffd" not in content,
                    "Invalid text encoding in " + path)
            result[path] = content
        return result


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def check_pull_request(api, number, event):
    prefix = f"/repos/{api.repository}"
    pr = api.request(f"{prefix}/pulls/{number}")
    sha = pr["head"]["sha"]
    base_ref = pr["base"]["ref"]
    head_ref = pr["head"]["ref"]
    original = event.get("pull_request")
    if original and (original["head"]["sha"] != sha or original["base"]["ref"] != base_ref):
        print("Superseded PR event; the latest event will validate the current target.")
        return
    check = api.request(f"{prefix}/check-runs", "POST", {
        "name": f"{CONTEXT} / {base_ref}", "head_sha": sha,
        "status": "in_progress", "started_at": utc_now(),
        "external_id": f"pr-{number}-{os.environ['GITHUB_RUN_ID']}",
        "details_url": f"https://github.com/{api.repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    })
    conclusion = "failure"
    message = "Policy validation did not complete."
    try:
        require(pr["base"]["repo"]["full_name"] == api.repository, "Unexpected base repository.")
        require(pr["head"].get("repo") is not None, "The source repository is unavailable.")
        files = api.snapshot(pr["head"]["repo"]["full_name"], sha)
        message = validate_snapshot(base_ref, head_ref, files)
        latest = api.request(f"{prefix}/pulls/{number}")
        require(latest["head"]["sha"] == sha and latest["base"]["ref"] == base_ref,
                "The PR changed during validation. Wait for the latest policy run.")
        conclusion = "success"
    except Exception as error:
        message = f"{type(error).__name__}: {error}"
        raise
    finally:
        api.request(f"{prefix}/check-runs/{check['id']}", "PATCH", {
            "status": "completed", "conclusion": conclusion, "completed_at": utc_now(),
            "output": {"title": CONTEXT, "summary": message},
        })
        print(json.dumps({"pull_request": number, "head_sha": sha,
                          "base": base_ref, "conclusion": conclusion, "message": message}))


def check_branch(api, branch):
    require(branch in ROUTES, "Manual branch validation only supports registered maintenance branches.")
    prefix = f"/repos/{api.repository}"
    state = api.request(f"{prefix}/branches/{urllib.parse.quote(branch, safe='')}")
    sha = state["commit"]["sha"]
    files = api.snapshot(api.repository, sha)
    message = validate_snapshot(branch, branch, files)
    api.request(f"{prefix}/check-runs", "POST", {
        "name": f"{CONTEXT} / {branch}", "head_sha": sha,
        "status": "completed", "conclusion": "success", "completed_at": utc_now(),
        "details_url": f"https://github.com/{api.repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
        "output": {"title": CONTEXT, "summary": message},
    })
    print(json.dumps({"branch": branch, "head_sha": sha, "message": message}))


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    value = event.get("number") or event.get("inputs", {}).get("pull_request_number")
    api = GitHub()
    if value:
        require(re.fullmatch(r"[1-9][0-9]*", str(value)) is not None, "Invalid pull request number.")
        check_pull_request(api, int(value), event)
    else:
        require(os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch", "Missing pull request number.")
        check_branch(api, event.get("inputs", {}).get("branch", "main-neo/1.21.1"))


if __name__ == "__main__":
    main()
