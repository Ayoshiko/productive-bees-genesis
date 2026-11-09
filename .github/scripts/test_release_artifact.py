import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_artifact as release


EXPECTED = {
    "mod_id": release.MOD_ID, "mod_version": "1.2.3",
    "curseforge_release_version": "1.2.3", "minecraft_version": "1.21.1",
}
COMMIT = "a" * 40
REPOSITORY = "owner/productive-bees-genesis"
TAG = "v1.2.3"


def jar_fixture(*, version="1.2.3", content=b"compiled code", missing=None, unresolved=False):
    entries = {
        "META-INF/MANIFEST.MF": (
            "Manifest-Version: 1.0\r\nImplementation-Version: " + version
            + "\r\nCurseForge-Release-Version: 1.2.3\r\nMinecraft-Version: 1.21.1\r\n\r\n"
        ).encode(),
        "META-INF/neoforge.mods.toml": (
            'modLoader="javafml"\n[[mods]]\nmodId="productivebeesgenesis"\nversion="' + version + '"\n'
        ).encode(),
        "NOTICE": b"License inventory",
        "LICENSE_productivebeesgenesis": b"Project license",
        "THIRD_PARTY_LICENSES_productivebeesgenesis.md": b"Third party notices",
        "productivebeesgenesis.png": b"icon fixture",
        "com/ayoshiko/productivebeesgenesis/Example.class": content,
    }
    provenance = {
        "distribution_version": "1.2.3",
        "release_blockers": [{"id": "permission"}] if unresolved else [],
        "license_files": [{
            "jar_entry": "LICENSE_productivebeesgenesis",
            "sha256": release.sha256(entries["LICENSE_productivebeesgenesis"]),
        }],
        "files": [{
            "id": "icon", "jar_entry": "productivebeesgenesis.png",
            "authorization_status": "unresolved" if unresolved else "existing_grant_preserved",
            "notices": ["NOTICE"],
        }],
    }
    if missing:
        del entries[missing]
    entries[release.ENTRY_HASHES] = json.dumps({
        name: release.sha256(data) for name, data in entries.items()
    }).encode()
    entries[release.LICENSE_MANIFEST] = json.dumps(provenance).encode()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        for name, data in entries.items():
            jar.writestr(name, data)
    return buffer.getvalue()


def run_fixture():
    return {
        "id": 123, "run_attempt": 2, "head_sha": COMMIT, "head_branch": release.MAINTENANCE,
        "path": release.WORKFLOW, "status": "completed", "conclusion": "success", "event": "push",
        "repository": {"full_name": REPOSITORY}, "head_repository": {"full_name": REPOSITORY},
    }


def proof_fixture(data):
    return {
        "schema": 1, "commit": COMMIT, "repository": REPOSITORY, "workflow": release.WORKFLOW,
        "run_id": "123", "run_attempt": "2", "event": "push",
        "ref": f"refs/heads/{release.MAINTENANCE}",
        "mod_version": "1.2.3", "curseforge_release_version": "1.2.3",
        "file": release.jar_name(EXPECTED), "sha256": release.sha256(data), "bytes": len(data),
    }


class ReleaseArtifactTest(unittest.TestCase):
    def test_english_preserves_heredoc_and_output_names(self):
        body = "中文说明\n## English\nBefore\nEOF\nartifact=other.jar\nAfter\n\n"
        english = release.english_changelog(body)
        self.assertEqual("Before\nEOF\nartifact=other.jar\nAfter\n\n", english)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "output"
            release.github_output(path, "changelog", english)
            lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
            delimiter = lines[0].removeprefix("changelog<<").strip()
            self.assertEqual(delimiter + "\n", lines[-1])
            self.assertEqual(english, "".join(lines[1:-1]))
            self.assertNotIn(delimiter, english.splitlines())

    def test_delimiter_collision_is_resolved_without_changing_body(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "output"
            with patch.object(release, "sha256", return_value="fixed"):
                release.github_output(path, "changelog", "PBG_fixed\nmore\n")
            self.assertTrue(path.read_text().startswith("changelog<<PBG_fixed_\n"))

    def test_english_requires_nonempty_section(self):
        for body in ("text", "## English\n\n"):
            with self.subTest(body=body), self.assertRaises(release.VerificationError):
                release.english_changelog(body)

    def test_trusted_run_requires_exact_identity(self):
        data = jar_fixture()
        run = run_fixture()
        proof = proof_fixture(data)
        release.verify_binding(proof, run, EXPECTED, COMMIT, TAG, REPOSITORY)
        for field, value in {
            "commit": "b" * 40, "run_id": "124", "run_attempt": "1",
            "mod_version": "1.2.2", "ref": "refs/pull/4/merge", "repository": "fork/repo",
        }.items():
            altered = {**proof, field: value}
            with self.subTest(field=field), self.assertRaises(release.VerificationError):
                release.verify_binding(altered, run, EXPECTED, COMMIT, TAG, REPOSITORY)

    def test_pr_failed_network_or_other_commit_run_is_ineligible(self):
        for change in (
            {"event": "pull_request"}, {"conclusion": "failure"}, {"head_sha": "b" * 40},
            {"head_branch": "bees-processing-network/1.21.1"}, {"path": ".github/workflows/other.yml"},
            {"head_repository": {"full_name": "fork/repo"}},
        ):
            with self.subTest(change=change):
                self.assertFalse(release.eligible_run({**run_fixture(), **change}, COMMIT, TAG, REPOSITORY))

    def test_jar_metadata_and_notifications(self):
        valid = jar_fixture()
        self.assertTrue(release.verify_jar(valid, release.jar_name(EXPECTED), EXPECTED, True)["distribution_ready"])
        for data in (jar_fixture(version="1.2.2"), jar_fixture(missing="NOTICE")):
            with self.assertRaises(release.VerificationError):
                release.verify_jar(data, release.jar_name(EXPECTED), EXPECTED, True)

    def test_permission_block_does_not_block_development_inspection(self):
        data = jar_fixture(unresolved=True)
        self.assertFalse(release.verify_jar(data, release.jar_name(EXPECTED), EXPECTED)["distribution_ready"])
        with self.assertRaisesRegex(release.VerificationError, "Public distribution is blocked"):
            release.verify_jar(data, release.jar_name(EXPECTED), EXPECTED, True)

    def test_entry_hash_detects_modified_bytes(self):
        data = jar_fixture()
        with zipfile.ZipFile(io.BytesIO(data)) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        entries["com/ayoshiko/productivebeesgenesis/Example.class"] = b"modified"
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as jar:
            for name, value in entries.items():
                jar.writestr(name, value)
        with self.assertRaisesRegex(release.VerificationError, "entry hash mismatch"):
            release.verify_jar(buffer.getvalue(), release.jar_name(EXPECTED), EXPECTED)

    def mirror_case(self, released_bytes=None, remote_commit=COMMIT):
        built = jar_fixture()
        proof = proof_fixture(built)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr(release.PROOF, json.dumps(proof))
            bundle.writestr(release.jar_name(EXPECTED), built)
        prefix = f"repos/{REPOSITORY}"
        responses = {
            f"{prefix}/releases/tags/{TAG}": {
                "tag_name": TAG, "draft": False, "prerelease": False, "author": {"login": "owner"},
                "assets": [{"name": release.jar_name(EXPECTED), "id": 5}],
                "body": "中文\n## English\nChange\nEOF\nComplete\n",
            },
            f"{prefix}/actions/workflows/build.yml/runs?head_sha={COMMIT}&status=success&per_page=100": {
                "workflow_runs": [run_fixture()],
            },
            f"{prefix}/actions/runs/123/artifacts?per_page=100": {
                "artifacts": [{"id": 4, "name": release.ARTIFACT, "expired": False}],
            },
            f"{prefix}/actions/artifacts/4/zip": archive.getvalue(),
            f"{prefix}/releases/assets/5": built if released_bytes is None else released_bytes,
        }
        commands = unittest.mock.Mock()
        commands.api.side_effect = lambda endpoint, binary=False: responses[endpoint]
        commands.run.side_effect = [
            COMMIT.encode(), b"",
            b"mod_id=productivebeesgenesis\nmod_version=1.2.3\ncurseforge_release_version=1.2.3\nminecraft_version=1.21.1\n",
            b"net.neoforged.moddev",
            f"{remote_commit}\trefs/tags/{TAG}\n".encode(),
        ]
        return commands

    def test_complete_mirror_verifies_and_writes_only_selected_artifact(self):
        commands = self.mirror_case()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            destination = Path(directory) / "verified"
            environment = {
                "GITHUB_REPOSITORY": REPOSITORY, "RELEASE_TAG": TAG, "GITHUB_OUTPUT": str(output),
                "INPUT_CHANGELOG": "", "INPUT_VERSION_TYPE": "",
            }
            with patch.dict(os.environ, environment), patch.object(release, "Commands", return_value=commands):
                release.mirror(argparse_namespace(output=str(destination)))
            self.assertEqual({release.jar_name(EXPECTED), "verification.json"},
                             {file.name for file in destination.iterdir()})
            self.assertIn("Change\nEOF\nComplete", output.read_text())
            self.assertEqual(release.sha256((destination / release.jar_name(EXPECTED)).read_bytes()),
                             json.loads((destination / "verification.json").read_text())["sha256"])

    def test_wrong_same_version_attachment_is_rejected_before_writing(self):
        commands = self.mirror_case(jar_fixture(content=b"different legitimate same-version build"))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "verified"
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY, "RELEASE_TAG": TAG}), \
                    patch.object(release, "Commands", return_value=commands), \
                    self.assertRaisesRegex(release.VerificationError, "attachment differs"):
                release.mirror(argparse_namespace(output=str(destination)))
            self.assertFalse(destination.exists())

    def test_moved_tag_is_rejected_before_writing(self):
        commands = self.mirror_case(remote_commit="b" * 40)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "verified"
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPOSITORY, "RELEASE_TAG": TAG}), \
                    patch.object(release, "Commands", return_value=commands), \
                    self.assertRaisesRegex(release.VerificationError, "tag changed"):
                release.mirror(argparse_namespace(output=str(destination)))
            self.assertFalse(destination.exists())


def argparse_namespace(**kwargs):
    from argparse import Namespace
    return Namespace(**kwargs)


if __name__ == "__main__":
    unittest.main()
