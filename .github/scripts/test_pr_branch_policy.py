import copy
import os
import unittest
from unittest.mock import patch

from pr_branch_policy import INPUTS, MANIFESTS, PolicyError, check_pull_request, validate_snapshot


def snapshot(loader):
    forge = loader == "forge"
    version = "1.20.1" if forge else "1.21.1"
    next_patch = "1.20.2" if forge else "1.21.2"
    key = "forge_version" if forge else "neo_version"
    plugin = "net.minecraftforge.gradle" if forge else "net.neoforged.moddev"
    java = 17 if forge else 21
    required = "mandatory=true" if forge else 'type="required"'
    path = MANIFESTS[3] if forge else MANIFESTS[0]
    files = dict.fromkeys(INPUTS)
    files["gradle.properties"] = (
        f"minecraft_version={version}\n{key}=47.4.0\n"
        "mod_id=productivebeesgenesis\n"
    )
    files["build.gradle"] = (
        f"plugins {{\n    id '{plugin}' version '1'\n}}\n"
        f"java.toolchain.languageVersion = JavaLanguageVersion.of({java})\n"
    )
    files[path] = (
        'modLoader="javafml"\n'
        '[[mods]]\nmodId="${mod_id}"\n'
        f'[[dependencies.${{mod_id}}]]\nmodId="{loader}"\n{required}\n'
        f'[[dependencies.${{mod_id}}]]\nmodId="minecraft"\n'
        f'versionRange="[{version},{next_patch})"\n'
    )
    return files


class SnapshotPolicyTests(unittest.TestCase):
    def test_matching_platforms(self):
        for branch, loader in (
            ("main-neo/1.21.1", "neoforge"),
            ("bees-processing-network/1.21.1", "neoforge"),
            ("forge/1.20.1", "forge"),
        ):
            with self.subTest(branch=branch):
                self.assertIn(branch, validate_snapshot(branch, "fix/example", snapshot(loader)))

    def test_cross_platform_prs_fail_in_both_directions(self):
        for branch, loader in (("main-neo/1.21.1", "forge"), ("forge/1.20.1", "neoforge")):
            with self.subTest(branch=branch):
                with self.assertRaisesRegex(PolicyError, "requires Minecraft"):
                    validate_snapshot(branch, "fix/example", snapshot(loader))

    def test_archival_or_unknown_target_fails(self):
        for branch in ("Alpha", "main", "feature/new-version"):
            with self.subTest(branch=branch):
                with self.assertRaisesRegex(PolicyError, "Choose"):
                    validate_snapshot(branch, "fix/example", snapshot("neoforge"))

    def test_experimental_branch_cannot_merge_into_maintenance(self):
        for branch in ("bees-processing-network/1.21.1", "visual-lab/1.21.1", "Alpha"):
            with self.subTest(branch=branch):
                with self.assertRaisesRegex(PolicyError, "Experimental"):
                    validate_snapshot("main-neo/1.21.1", branch, snapshot("neoforge"))

    def test_incorrect_java_and_commented_plugin_fail(self):
        files = snapshot("forge")
        files["build.gradle"] = files["build.gradle"].replace("of(17)", "of(21)")
        with self.assertRaisesRegex(PolicyError, "Java 17"):
            validate_snapshot("forge/1.20.1", "fix/example", files)
        files = snapshot("forge")
        files["build.gradle"] = files["build.gradle"].replace("    id ", "    // id ")
        with self.assertRaisesRegex(PolicyError, "Gradle plugin"):
            validate_snapshot("forge/1.20.1", "fix/example", files)

    def test_mixed_manifest_and_wrong_loader_dependency_fail(self):
        files = snapshot("forge")
        files[MANIFESTS[0]] = snapshot("neoforge")[MANIFESTS[0]]
        with self.assertRaisesRegex(PolicyError, "exactly one"):
            validate_snapshot("forge/1.20.1", "fix/example", files)
        files = snapshot("forge")
        files[MANIFESTS[3]] = files[MANIFESTS[3]].replace('modId="forge"', 'modId="neoforge"')
        with self.assertRaisesRegex(PolicyError, "depend on forge"):
            validate_snapshot("forge/1.20.1", "fix/example", files)

    def test_broad_minecraft_range_and_duplicate_property_fail(self):
        files = snapshot("forge")
        files[MANIFESTS[3]] = files[MANIFESTS[3]].replace("[1.20.1,1.20.2)", "[1.20,)")
        with self.assertRaisesRegex(PolicyError, "restrict Minecraft"):
            validate_snapshot("forge/1.20.1", "fix/example", files)
        files = snapshot("forge")
        files["gradle.properties"] += "minecraft_version=1.21.1\n"
        with self.assertRaisesRegex(PolicyError, "Duplicate"):
            validate_snapshot("forge/1.20.1", "fix/example", files)


class FakeAPI:
    repository = "Ayoshiko/productive-bees-genesis"

    def __init__(self, loader="forge"):
        self.pr = {
            "base": {"ref": "forge/1.20.1", "repo": {"full_name": self.repository}},
            "head": {"ref": "fix/example", "sha": "a" * 40, "repo": {"full_name": "contributor/fork"}},
        }
        self.files = snapshot(loader)
        self.writes = []
        self.reads = 0
        self.change_during_read = False

    def request(self, path, method="GET", payload=None):
        if method == "GET":
            self.reads += 1
            result = copy.deepcopy(self.pr)
            if self.change_during_read and self.reads > 1:
                result["head"]["sha"] = "b" * 40
            return result
        self.writes.append((method, payload))
        return {"id": 123}

    def snapshot(self, repository, sha):
        return self.files


class CheckLifecycleTests(unittest.TestCase):
    @patch.dict(os.environ, {"GITHUB_RUN_ID": "1"})
    def test_check_is_bound_to_head_commit_and_target(self):
        api = FakeAPI()
        check_pull_request(api, 13, {})
        self.assertEqual(api.writes[0][1]["name"], "PR branch policy / forge/1.20.1")
        self.assertEqual(api.writes[0][1]["head_sha"], "a" * 40)
        self.assertEqual(api.writes[-1][1]["conclusion"], "success")

    @patch.dict(os.environ, {"GITHUB_RUN_ID": "1"})
    def test_policy_failure_publishes_failure(self):
        api = FakeAPI("neoforge")
        with self.assertRaises(PolicyError):
            check_pull_request(api, 13, {})
        self.assertEqual(api.writes[-1][1]["conclusion"], "failure")

    @patch.dict(os.environ, {"GITHUB_RUN_ID": "1"})
    def test_head_change_during_read_fails_old_check(self):
        api = FakeAPI()
        api.change_during_read = True
        with self.assertRaisesRegex(PolicyError, "changed during"):
            check_pull_request(api, 13, {})
        self.assertEqual(api.writes[-1][1]["conclusion"], "failure")

    def test_superseded_event_does_not_overwrite_current_check(self):
        api = FakeAPI()
        event = {"pull_request": copy.deepcopy(api.pr)}
        event["pull_request"]["base"]["ref"] = "main-neo/1.21.1"
        check_pull_request(api, 13, event)
        self.assertEqual(api.writes, [])


class ManualBranchTests(unittest.TestCase):
    @patch.dict(os.environ, {"GITHUB_RUN_ID": "1"})
    def test_branch_check_uses_current_branch_commit(self):
        from pr_branch_policy import check_branch
        from unittest.mock import Mock

        api = Mock(repository="Ayoshiko/productive-bees-genesis")
        api.request.return_value = {"commit": {"sha": "c" * 40}}
        api.snapshot.return_value = snapshot("forge")
        check_branch(api, "forge/1.20.1")
        payload = api.request.call_args.args[2]
        self.assertEqual(payload["head_sha"], "c" * 40)
        self.assertEqual(payload["name"], "PR branch policy / forge/1.20.1")
        self.assertEqual(payload["conclusion"], "success")

    def test_invalid_branch_does_not_publish_success(self):
        from pr_branch_policy import check_branch
        from unittest.mock import Mock

        api = Mock(repository="Ayoshiko/productive-bees-genesis")
        api.request.return_value = {"commit": {"sha": "c" * 40}}
        api.snapshot.return_value = snapshot("neoforge")
        with self.assertRaises(PolicyError):
            check_branch(api, "forge/1.20.1")
        self.assertEqual(api.request.call_count, 1)


if __name__ == "__main__":
    unittest.main()
