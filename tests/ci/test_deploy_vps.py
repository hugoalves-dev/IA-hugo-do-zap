import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "deploy-vps.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "backend.yml"
OLD_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "1" * 64
NEW_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "2" * 64
NEXT_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "3" * 64
MARKER = "# Managed by backend deploy workflow; do not edit."


def render_override(image):
    return f"{MARKER}\nservices:\n  watcher:\n    image: {image}\n    pull_policy: always\n"


FAKE_DOCKER = r'''#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == inspect ]]; then
  format="$3"
  if [[ "$format" == *".State.Running"* ]]; then
    printf 'true\n'
  elif [[ "$format" == *".State.Health.Status"* ]]; then
    active="$(cat "$FAKE_ACTIVE_IMAGE")"
    if [[ "$active" == "${FAKE_UNHEALTHY_IMAGE:-}" ]]; then
      printf 'unhealthy\n'
    else
      printf 'healthy\n'
    fi
  else
    exit 93
  fi
  exit 0
fi
[[ "$1" == compose ]] || exit 90
shift
args="$*"
printf 'compose %s\n' "$args" >> "$FAKE_DOCKER_LOG"

compose_file="$PWD/compose.override.yaml"
while (($#)); do
  if [[ "$1" == -f ]]; then
    compose_file="$2"
    shift 2
  else
    shift
  fi
done
image="$(sed -n 's/^[[:space:]]*image:[[:space:]]*//p' "$compose_file" | head -n 1)"

case " $args " in
  *" up --help "*)
    printf '%s\n' '--wait --wait-timeout'
    exit 0
    ;;
  *" config --services "*)
    if [[ -n "${FAKE_SERVICES:-}" ]]; then
      printf '%s\n' "$FAKE_SERVICES"
    else
      printf 'watcher\nevolution-api\nredis\n'
    fi
    exit 0
    ;;
  *" config --volumes "*)
    printf '%s\n' "${FAKE_VOLUMES:-evolution_instances}"
    exit 0
    ;;
  *" config --images watcher "*)
    printf '%s\n' "$image"
    exit 0
    ;;
  *" pull watcher "*)
    [[ "${FAKE_PULL_FAIL_IMAGE:-}" != "$image" ]] || exit 41
    exit 0
    ;;
  *" up "*" watcher "*)
    printf '%s\n' "$image" > "$FAKE_ACTIVE_IMAGE"
    [[ "${FAKE_UP_FAIL_IMAGE:-}" != "$image" ]] || exit 42
    exit 0
    ;;
  *" ps -q watcher "*)
    printf 'fixture-container-id\n'
    exit 0
    ;;
esac
exit 91
'''

class DeployScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="watcher-deploy-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.deploy_dir = self.root / "app"
        self.deploy_dir.mkdir()
        self.compose_path = self.deploy_dir / "compose.yaml"
        self.compose_path.write_text(
            "services:\n"
            "  watcher:\n"
            "    image: ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest\n"
            "    healthcheck:\n"
            "      test: [CMD, wget, -q, -O, /dev/null, http://127.0.0.1:8080/actuator/health/readiness]\n"
            "  evolution-api:\n"
            "    image: synthetic.invalid/evolution:v1\n"
            "  redis:\n"
            "    image: synthetic.invalid/redis:v1\n"
            "volumes:\n"
            "  evolution_instances:\n",
            encoding="utf-8",
        )
        (self.deploy_dir / ".env").write_text("SYNTHETIC_SETTING=keep-me\n", encoding="utf-8")
        self.override_path = self.deploy_dir / "compose.override.yaml"
        self.override_path.write_text(render_override(OLD_IMAGE), encoding="utf-8")
        self.env_path = self.deploy_dir / ".env"
        self.original_env = self.env_path.read_bytes()
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.docker_log = self.root / "docker.log"
        self.active_image = self.root / "active-image"
        self.active_image.write_text(OLD_IMAGE + "\n", encoding="ascii")
        self._write_executable("docker", FAKE_DOCKER)
        self._write_executable(
            "sha256sum",
            "#!/usr/bin/env bash\n"
            "printf '%s  %s\\n' \"$FAKE_COMPOSE_SHA\" \"$1\"\n",
        )
        self.bash = os.environ.get("BASH_EXE") or shutil.which("bash")
        self.assertTrue(SCRIPT.is_file(), "scripts/deploy-vps.sh must exist")
        self.assertTrue(self.bash, "bash must be available to run deployment contract tests")

    def _write_executable(self, name, contents):
        path = self.bin_dir / name
        path.write_text(contents, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def _compose_sha(self):
        return hashlib.sha256(self.compose_path.read_bytes()).hexdigest()

    def _bash_path(self, path):
        if os.name != "nt":
            return str(path)
        result = subprocess.run(
            [self.bash, "-c", 'cygpath -u "$1"', "bash", str(path)],
            text=True,
            capture_output=True,
            check=True,
            timeout=10,
        )
        return result.stdout.strip()

    def _run_deploy(self, image, expected_sha=None, **extra_env):
        env = os.environ.copy()
        env.update(
            {
                "PATH": str(self.bin_dir) + os.pathsep + env.get("PATH", ""),
                "FAKE_DOCKER_LOG": self._bash_path(self.docker_log),
                "FAKE_ACTIVE_IMAGE": self._bash_path(self.active_image),
                "FAKE_COMPOSE_SHA": self._compose_sha(),
            }
        )
        env.update(extra_env)
        return subprocess.run(
            [
                self.bash,
                str(SCRIPT),
                base64.b64encode(os.fsencode(self._bash_path(self.deploy_dir))).decode("ascii"),
                base64.b64encode(image.encode("ascii")).decode("ascii"),
                expected_sha or self._compose_sha(),
            ],
            cwd=self.root,
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def _commands(self):
        if not self.docker_log.exists():
            return []
        return self.docker_log.read_text(encoding="utf-8").splitlines()

    def _up_commands(self):
        return [command for command in self._commands() if " up " in command and "--help" not in command]

    def _temporary_override_files(self):
        return list(self.deploy_dir.glob(".compose.override.yaml.*"))

    def test_success_persists_digest_for_plain_compose_and_waits_for_health(self):
        result = self._run_deploy(NEW_IMAGE)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(render_override(NEW_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(NEW_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertEqual(self.original_env, self.env_path.read_bytes())
        self.assertEqual([], self._temporary_override_files())
        commands = self._commands()
        self.assertTrue(any("config --images watcher" in command for command in commands))
        up_commands = [command for command in commands if " up " in command and "--help" not in command]
        self.assertEqual(1, len(up_commands))
        self.assertIn("--wait-timeout 180", up_commands[0])
        self.assertIn("--no-deps", up_commands[0])
        self.assertTrue(up_commands[0].endswith(" watcher"))
        for forbidden in (" down ", "prune", "--remove-orphans", "volume rm"):
            self.assertFalse(any(forbidden in command for command in commands), commands)

    def test_failed_candidate_start_rolls_back_to_previous_digest(self):
        result = self._run_deploy(NEW_IMAGE, FAKE_UP_FAIL_IMAGE=NEW_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(OLD_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertEqual([], self._temporary_override_files())
        up_commands = self._up_commands()
        self.assertEqual(2, len(up_commands), up_commands)
        self.assertIn("--no-deps", up_commands[-1])
        self.assertTrue(up_commands[-1].endswith(" watcher"))

    def test_unhealthy_candidate_rolls_back_and_returns_failure(self):
        result = self._run_deploy(NEW_IMAGE, FAKE_UNHEALTHY_IMAGE=NEW_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unhealthy", result.stderr.lower())
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(OLD_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertEqual([], self._temporary_override_files())
        self.assertEqual(2, len(self._up_commands()))

    def test_reexecution_uses_current_digest_and_updates_only_watcher(self):
        first = self._run_deploy(NEW_IMAGE)
        second = self._run_deploy(NEXT_IMAGE)

        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(render_override(NEXT_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(NEXT_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        commands = self._commands()
        self.assertEqual(2, sum(" pull watcher" in command for command in commands))
        self.assertEqual(2, len(self._up_commands()))
        self.assertFalse(any("--remove-orphans" in command or "prune" in command for command in commands))

    def test_compose_mismatch_fails_before_docker_or_state_changes(self):
        result = self._run_deploy(NEW_IMAGE, "f" * 64)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("compose.yaml", result.stderr.lower())
        self.assertEqual([], self._commands())
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))

    def test_existing_user_override_is_preserved_and_rejected(self):
        user_override = "services:\n  redis:\n    command: redis-server\n"
        self.override_path.write_text(user_override, encoding="utf-8")

        result = self._run_deploy(NEW_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("override", result.stderr.lower())
        self.assertEqual(user_override, self.override_path.read_text(encoding="utf-8"))
        self.assertEqual([], self._commands())

    def test_compose_file_override_is_rejected_without_touching_env(self):
        self.env_path.write_text("COMPOSE_FILE=alternate.yaml\nSYNTHETIC_SETTING=keep-me\n", encoding="utf-8")
        env_before = self.env_path.read_bytes()

        result = self._run_deploy(NEW_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("COMPOSE_FILE", result.stderr)
        self.assertEqual([], self._commands())
        self.assertEqual(env_before, self.env_path.read_bytes())
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))

    def test_service_or_volume_drift_fails_before_up(self):
        cases = (
            {"FAKE_SERVICES": "watcher\nevolution-api\nredis\nunrelated"},
            {"FAKE_VOLUMES": "unexpected_volume"},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                self.docker_log.unlink(missing_ok=True)
                result = self._run_deploy(NEW_IMAGE, **overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self._up_commands())
                self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
                self.assertEqual(self.original_env, self.env_path.read_bytes())

    def test_readiness_healthcheck_is_enabled_and_has_no_details(self):
        compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: \"true\"", compose)
        self.assertIn("MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: readinessState,db", compose)
        self.assertIn("/actuator/health/readiness", compose)
        self.assertIn("/actuator/health/readiness", dockerfile)
        self.assertIn("MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS: never", compose)

    def test_workflow_checks_out_compose_hash_and_sends_tested_remote_script(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("actions/checkout@v4", workflow[workflow.index("  deploy-vps:"):])
        self.assertIn("sha256sum compose.yaml", workflow)
        self.assertIn("scripts/deploy-vps.sh", workflow)
        self.assertIn("COMPOSE_SHA256", workflow)
        self.assertNotIn("<<'REMOTE'", workflow)


if __name__ == "__main__":
    unittest.main()
