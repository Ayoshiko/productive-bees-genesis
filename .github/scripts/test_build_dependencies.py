"""Focused, offline behavior checks for development dependency preparation."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name("build_dependencies.py")
spec = importlib.util.spec_from_file_location("build_dependencies", SCRIPT)
deps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deps)


class DependencyPreparationTest(unittest.TestCase):
    def setUp(self):
        evidence = SCRIPT.resolve().parents[2] / "build/audit-repairs-20261108/RP10/dependencies-tests"
        evidence.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="case-", dir=evidence))
        (self.root / "libs").mkdir()

    def entry(self, name, content, **source):
        return {"path": "libs/" + name, "sha256": hashlib.sha256(content).hexdigest(),
                **(source or {"url": "https://example.invalid/" + name})}

    def test_existing_mismatch_refuses_before_network_and_preserves_bytes(self):
        existing = self.root / "libs/existing.jar"
        existing.write_bytes(b"user file")
        entries = [self.entry("missing.jar", b"missing"), self.entry("existing.jar", b"expected")]
        with patch.object(deps.urllib.request, "urlopen") as request:
            with self.assertRaisesRegex(deps.DependencyError, "not overwritten"):
                deps.prepare(self.root, entries, check=False, deadline=deps.Deadline(5))
            request.assert_not_called()
        self.assertEqual(b"user file", existing.read_bytes())
        self.assertFalse((self.root / "libs/missing.jar").exists())

    def test_verified_download_and_exact_nested_member(self):
        nested = b"nested jar"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr("META-INF/jarjar/productivelib.jar", nested)
            jar.writestr("wrong/productivelib.jar", b"decoy")
        outer = archive.getvalue()
        entries = [self.entry("outer.jar", outer),
                   self.entry("nested.jar", nested, archive="libs/outer.jar",
                              entry="META-INF/jarjar/productivelib.jar")]
        with patch.object(deps.urllib.request, "urlopen", return_value=io.BytesIO(outer)) as request:
            rows = deps.prepare(self.root, entries, check=False, deadline=deps.Deadline(5))
            self.assertEqual(1, request.call_count)
        self.assertEqual(nested, (self.root / "libs/nested.jar").read_bytes())
        self.assertEqual(2, len(rows))
        with patch.object(deps.urllib.request, "urlopen") as request:
            deps.prepare(self.root, entries, check=True, deadline=deps.Deadline(5))
            request.assert_not_called()

    def test_bad_download_is_not_published(self):
        with patch.object(deps.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt")):
            with self.assertRaisesRegex(deps.DependencyError, "retained"):
                deps.prepare(self.root, [self.entry("bad.jar", b"expected")],
                             check=False, deadline=deps.Deadline(5))
        self.assertFalse((self.root / "libs/bad.jar").exists())
        self.assertEqual(1, len(list((self.root / "build/dependency-downloads").glob("*.part"))))

    def test_nested_suffix_is_not_accepted(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr("wrong/productivelib.jar", b"decoy")
        outer = archive.getvalue()
        (self.root / "libs/outer.jar").write_bytes(outer)
        entries = [self.entry("outer.jar", outer),
                   self.entry("nested.jar", b"decoy", archive="libs/outer.jar",
                              entry="META-INF/jarjar/productivelib.jar")]
        with self.assertRaisesRegex(deps.DependencyError, "exactly one"):
            deps.prepare(self.root, entries, check=False, deadline=deps.Deadline(5))
        self.assertFalse((self.root / "libs/nested.jar").exists())

    def test_deadline_checks_stream_and_supervisor_terminates_worker(self):
        ticks = iter([0, 0.5, 2])
        with self.assertRaisesRegex(deps.DependencyError, "deadline"):
            deps.stage_stream(self.root, io.BytesIO(b"x"), deps.Deadline(1, lambda: next(ticks)))
        with patch.object(deps.subprocess, "run", side_effect=subprocess.TimeoutExpired("worker", 1)) as run:
            self.assertEqual(1, deps.main(["--root", str(self.root), "--check", "--deadline", "1"]))
            self.assertEqual(1, run.call_args.kwargs["timeout"])

    def test_manifest_rejects_unpinned_archive_and_path_escape(self):
        manifest = self.root / "manifest.json"
        bad = self.entry("nested.jar", b"x", archive="libs/not-pinned.jar",
                         entry="META-INF/jarjar/lib.jar")
        manifest.write_text(json.dumps({"schema": 1, "entries": [bad]}), encoding="utf-8")
        with self.assertRaisesRegex(deps.DependencyError, "not pinned"):
            deps.read_manifest(manifest, self.root)
        with self.assertRaisesRegex(deps.DependencyError, "Invalid dependency path"):
            deps.target_path(self.root, "libs/../outside.jar")


if __name__ == "__main__":
    unittest.main()
