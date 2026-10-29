import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/deploy-vps.sh"
OLD = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "1" * 64
NEW = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "2" * 64
MARKER = "# Managed by backend deploy workflow; do not edit."


def override(image):
    return f"{MARKER}\nservices:\n  watcher:\n    image: {image}\n    pull_policy: never\n"


FAKE_DOCKER = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
if [[ "$1" == inspect ]]; then
  printf '%s\n' "${FAKE_OLD_HEALTH:-healthy}"
  exit 0
fi
if [[ "$1" == pull ]]; then
  [[ "${FAKE_PULL_FAILURE:-false}" != true ]] || exit 81
  exit 0
fi
[[ "$1" == compose ]] || exit 82
args=" $* "
if [[ "$args" == *" ps -q watcher "* ]]; then
  printf 'fixture-watcher\n'
  exit 0
fi
if [[ "$args" == *" config --quiet "* ]]; then exit 0; fi
if [[ "$args" == *" up -d "* && "$args" == *" watcher "* ]]; then
  current="$(sed -n 's/^    image: //p' compose.override.yaml)"
  [[ "$current" != "${FAKE_FAIL_IMAGE:-}" ]] || exit 83
  exit 0
fi
exit 84
"""


class AutomaticDeployTest(unittest.TestCase):
    def setUp(self):
        if os.name == "nt":
            self.skipTest("POSIX deployment script tests")
        self.tmp = tempfile.TemporaryDirectory(prefix="watcher-ci-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.target = self.root / "app"
        self.target.mkdir()
        self.compose = self.target / "compose.yaml"
        self.compose.write_text("services:\n  watcher:\n    image: sample:latest\n", encoding="utf-8")
        self.env_file = self.target / ".env"
        self.env_file.write_text("COMPOSE_PROFILES=prod\nPRIVATE_TOKEN=placeholder\n", encoding="utf-8")
        self.previous_env = self.env_file.read_bytes()
        self.override = self.target / "compose.override.yaml"
        self.override.write_text(override(OLD), encoding="utf-8")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        binary = self.bin / "docker"
        binary.write_text(FAKE_DOCKER, encoding="utf-8")
        binary.chmod(0o755)
        self.commands = self.root / "commands.log"

    def run_deploy(self, image=NEW, compose_text=None, **extra):
        content = self.compose.read_bytes() if compose_text is None else compose_text.encode("utf-8")
        environ = os.environ.copy()
        environ.update({
            "PATH": str(self.bin) + os.pathsep + environ.get("PATH", ""),
            "FAKE_DOCKER_LOG": str(self.commands),
            "VPS_DEPLOY_DIR_B64": base64.b64encode(os.fsencode(self.target)).decode(),
            "IMAGE_REF_B64": base64.b64encode(image.encode()).decode(),
            "COMPOSE_CONTENT_B64": base64.b64encode(content).decode(),
            "COMPOSE_SHA256": hashlib.sha256(content).hexdigest(),
        })
        environ.update(extra)
        return subprocess.run(["bash", str(SCRIPT)], cwd=self.root, env=environ,
                              capture_output=True, text=True, timeout=25)

    def docker_commands(self):
        return self.commands.read_text().splitlines() if self.commands.exists() else []

    def add_legacy_bootstrap(self):
        self.override.unlink()
        (self.target / ".watcher-bootstrap.journal").write_text(
            f"version=1\nphase=bootstrapping\ncandidate_image={OLD}\n"
            "compose_sha256=" + "f" * 64 + "\nproject_name=app\n", encoding="utf-8")
        (self.target / ".compose.override.yaml.candidate").write_text(override(OLD), encoding="utf-8")

    def test_success_updates_only_watcher_by_digest_and_preserves_env(self):
        result = self.run_deploy()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(override(NEW), self.override.read_text())
        self.assertEqual(self.previous_env, self.env_file.read_bytes())
        self.assertEqual(hashlib.sha256(self.compose.read_bytes()).hexdigest(),
                         (self.target / ".watcher-deployed-compose.sha256").read_text().strip())
        cmds = self.docker_commands()
        self.assertTrue(any(cmd.startswith("pull " + NEW) for cmd in cmds), cmds)
        self.assertTrue(any("up -d --no-deps --pull never --wait --wait-timeout 240 watcher" in cmd for cmd in cmds), cmds)
        self.assertFalse(any(" evolution-api" in cmd or " down " in cmd or " prune " in cmd for cmd in cmds))

    def test_unhealthy_release_automatically_restores_last_healthy_image(self):
        result = self.run_deploy(FAKE_FAIL_IMAGE=NEW)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failed health check", result.stderr.lower())
        self.assertEqual(override(OLD), self.override.read_text())
        self.assertEqual(self.previous_env, self.env_file.read_bytes())
        self.assertFalse((self.target / ".watcher-deployed-compose.sha256").exists())
        self.assertEqual(2, sum(" up -d " in " " + cmd for cmd in self.docker_commands()))

    def test_legacy_bootstrap_recovery_archives_files_only_after_healthy(self):
        self.add_legacy_bootstrap()
        result = self.run_deploy()
        self.assertEqual(0, result.returncode, result.stderr)
        archives = list(self.target.glob(".watcher-legacy-recovered.*"))
        self.assertEqual(1, len(archives))
        self.assertTrue((archives[0] / ".watcher-bootstrap.journal").exists())
        self.assertTrue((archives[0] / ".compose.override.yaml.candidate").exists())
        self.assertFalse((self.target / ".watcher-bootstrap.journal").exists())
        self.assertEqual(override(NEW), self.override.read_text())

    def test_failed_bootstrap_preserves_legacy_evidence(self):
        self.add_legacy_bootstrap()
        result = self.run_deploy(FAKE_FAIL_IMAGE=NEW, FAKE_OLD_HEALTH="unhealthy")
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((self.target / ".watcher-bootstrap.journal").exists())
        self.assertTrue((self.target / ".compose.override.yaml.candidate").exists())
        self.assertFalse(self.override.exists())

    def test_unmanaged_override_fails_closed(self):
        self.override.write_text("services:\n  evolution-api:\n    image: unsafe:latest\n")
        result = self.run_deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unmanaged", result.stderr)
        self.assertEqual([], self.docker_commands())

    def test_compose_update_from_git_after_success_is_automatic(self):
        original_hash = hashlib.sha256(self.compose.read_bytes()).hexdigest()
        (self.target / ".watcher-deployed-compose.sha256").write_text(original_hash + "\n")
        updated = self.compose.read_text() + "  evolution-api:\n    image: sample:latest\n"
        result = self.run_deploy(compose_text=updated)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(updated, self.compose.read_text())
        self.assertEqual(hashlib.sha256(updated.encode()).hexdigest(),
                         (self.target / ".watcher-deployed-compose.sha256").read_text().strip())

    def test_local_compose_drift_blocks_overwrite(self):
        (self.target / ".watcher-deployed-compose.sha256").write_text("a" * 64 + "\n")
        original = self.compose.read_text()
        result = self.run_deploy(compose_text=original + "# new")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("outside the deploy workflow", result.stderr)
        self.assertEqual(original, self.compose.read_text())
        self.assertEqual([], self.docker_commands())

    def test_pull_failure_does_not_modify_running_deployment(self):
        result = self.run_deploy(FAKE_PULL_FAILURE="true")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(override(OLD), self.override.read_text())
        self.assertFalse(any(" up -d " in " " + c for c in self.docker_commands()))

    def test_initial_remote_compose_must_match_repo(self):
        self.override.unlink()
        result = self.run_deploy(compose_text=self.compose.read_text() + "# mismatch\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("First deploy requires", result.stderr)
        self.assertEqual([], self.docker_commands())

    def test_partial_legacy_state_is_not_deleted(self):
        (self.target / ".watcher-bootstrap.journal").write_text("garbage\n")
        result = self.run_deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Incomplete legacy bootstrap", result.stderr)
        self.assertTrue((self.target / ".watcher-bootstrap.journal").exists())
        self.assertEqual([], self.docker_commands())

    def test_bash_syntax_and_no_destructive_global_cleanup(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertEqual(0, subprocess.run(["bash", "-n", str(SCRIPT)]).returncode)
        for destructive in ("docker compose down", "docker image prune", "--remove-orphans",
                            "docker volume rm", "docker system prune"):
            self.assertNotIn(destructive, script)


if __name__ == "__main__":
    unittest.main()
