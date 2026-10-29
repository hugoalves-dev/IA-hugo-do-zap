import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "deploy-vps.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "backend.yml"
OLD_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "1" * 64
NEW_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "2" * 64
NEXT_IMAGE = "ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + "3" * 64
MARKER = "# Managed by backend deploy workflow; do not edit."


def render_override(image):
    return f"{MARKER}\nservices:\n  watcher:\n    image: {image}\n    pull_policy: never\n"


FAKE_DOCKER = r'''#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == image && "$2" == inspect ]]; then
  printf 'image inspect %s\n' "$3" >> "$FAKE_DOCKER_LOG"
  [[ "${FAKE_LOCAL_MISSING_IMAGE:-}" != "$3" ]] || exit 94
  exit 0
fi
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
if [[ "$1" == ps ]]; then
  args="$*"
  if [[ "$args" == *"label=com.docker.compose.service=watcher"* || "$args" == *"name=watcher"* ]]; then
    printf '%s\n' "${FAKE_EXISTING_WATCHERS:-}"
  elif [[ "$args" == *"label=com.docker.compose.project="* ]]; then
    printf '%s\n' "${FAKE_PROJECT_CONTAINERS:-}"
  fi
  exit 0
fi
if [[ "$1" == volume && "$2" == ls ]]; then
  printf '%s\n' "${FAKE_PROJECT_VOLUMES:-}"
  exit 0
fi
if [[ "$1" == volume && "$2" == inspect ]]; then
  [[ "${FAKE_EXISTING_VOLUME:-}" == "$3" ]] || exit 96
  printf '%s\n' "$3"
  exit 0
fi
if [[ "$1" == network && "$2" == ls ]]; then
  printf '%s\n' "${FAKE_PROJECT_NETWORKS:-}"
  exit 0
fi
if [[ "$1" == network && "$2" == inspect ]]; then
  network_name="${@: -1}"
  if [[ "$network_name" == infra_network ]]; then
    printf '%s\n' infra_network
    exit 0
  fi
  [[ "${FAKE_EXISTING_PROJECT_NETWORK:-}" == "$network_name" ]] || exit 97
  printf '%s\n' "$network_name"
  exit 0
fi
if [[ "$1" == ps ]]; then
  args="$*"
  if [[ "$args" == *"label=com.docker.compose.service=watcher"* || "$args" == *"name=watcher"* ]]; then
    printf '%s\n' "${FAKE_EXISTING_WATCHERS:-}"
  elif [[ "$args" == *"label=com.docker.compose.project="* ]]; then
    printf '%s\n' "${FAKE_PROJECT_CONTAINERS:-}"
  fi
  exit 0
fi
if [[ "$1" == volume && "$2" == ls ]]; then
  printf '%s\n' "${FAKE_PROJECT_VOLUMES:-}"
  exit 0
fi
if [[ "$1" == volume && "$2" == inspect ]]; then
  [[ "${FAKE_EXISTING_VOLUME:-}" == "${@: -1}" ]] || exit 96
  printf '%s\n' "${@: -1}"
  exit 0
fi
if [[ "$1" == network && "$2" == ls ]]; then
  printf '%s\n' "${FAKE_PROJECT_NETWORKS:-}"
  exit 0
fi
if [[ "$1" == network && "$2" == inspect ]]; then
  network_name="${@: -1}"
  if [[ "$network_name" == infra_network || "$network_name" == "${FAKE_EXISTING_PROJECT_NETWORK:-}" ]]; then
    printf '%s\n' "$network_name"
    exit 0
  fi
  exit 97
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
image=""
if [[ -f "$compose_file" ]]; then
  image="$(sed -n 's/^[[:space:]]*image:[[:space:]]*//p' "$compose_file" | head -n 1)"
fi

case " $args " in
  *" up --help "*)
    printf '%s\n' '--wait --wait-timeout'
    exit 0
    ;;
  *" config --services "*)
    if [[ -n "${FAKE_SERVICES:-}" ]]; then
      printf '%s\n' "$FAKE_SERVICES"
    else
      printf 'watcher\nevolution-api\n'
    fi
    exit 0
    ;;
  *" config --environment "*)
    if [[ -n "${FAKE_CONFIG_ENV:-}" ]]; then
      printf '%s\n' "$FAKE_CONFIG_ENV"
    else
      printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.synthetic.invalid:5432/watcher\nSPRING_DATASOURCE_USERNAME=watcher_app\nSPRING_DATASOURCE_PASSWORD=synthetic-only\nEVOLUTION_DATABASE_URL=postgresql://evolution_app:synthetic-only@postgres.synthetic.invalid:5432/evolution\nWATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=false\nWATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED=false\nWATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED=false\n'
    fi
    exit 0
    ;;
  *" config --volumes "*)
    printf '%s\n' "${FAKE_VOLUMES:-evolution_instances}"
    exit 0
    ;;
  *" config --networks "*)
    printf 'infra_network\n'
    exit 0
    ;;
  *" config --networks "*)
    printf 'infra_network\n'
    exit 0
    ;;
  *" config --images watcher "*)
    printf '%s\n' "$image"
    exit 0
    ;;
  *" pull --policy always watcher "*)
    [[ "${FAKE_PULL_FAIL_IMAGE:-}" != "$image" ]] || exit 41
    exit 0
    ;;
  *" up "*" watcher "*)
    if [[ "${FAKE_REGISTRY_OFFLINE:-}" == true && "$image" == "${FAKE_REGISTRY_OLD_IMAGE:-}" && "$args" != *"--pull never"* ]]; then
      exit 45
    fi
    if [[ "${FAKE_BLOCK_UP_IMAGE:-}" == "$image" ]]; then
      : > "$FAKE_UP_STARTED"
      while [[ ! -f "$FAKE_UP_RELEASE" ]]; do sleep 0.02; done
    fi
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

FAKE_FLOCK = r'''#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n && "$2" == 9 ]] || exit 95
if ! mkdir "$FAKE_FLOCK_LOCK_DIR" 2>/dev/null; then
  exit 1
fi
parent_pid="$PPID"
(
  while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.02; done
  rmdir "$FAKE_FLOCK_LOCK_DIR" 2>/dev/null || true
) >/dev/null 2>&1 </dev/null &
exit 0
'''

FAKE_BASH_ENV = r'''mv() {
  local destination="${@: -1}"
  printf 'mv %s\n' "$*" >> "$FAKE_MV_LOG"
  if [[ "${FAKE_BLOCK_PROMOTION:-}" == true && "$destination" == compose.override.yaml ]]; then
    : > "$FAKE_PROMOTION_STARTED"
    while [[ ! -f "$FAKE_PROMOTION_RELEASE" ]]; do sleep 0.02; done
  fi
  if [[ "${FAKE_CRASH_PROMOTION:-}" == true && "$destination" == compose.override.yaml ]]; then
    : > "$FAKE_PROMOTION_STARTED"
    kill -9 "$$"
  fi
  if [[ "${FAKE_CRASH_AFTER_PROMOTION:-}" == true && "$destination" == compose.override.yaml ]]; then
    /usr/bin/mv "$@"
    : > "$FAKE_PROMOTION_FINISHED"
    kill -9 "$$"
  fi
  /usr/bin/mv "$@"
}
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
            "volumes:\n"
            "  evolution_instances:\n",
            encoding="utf-8",
        )
        (self.deploy_dir / ".env").write_text("COMPOSE_PROFILES=prod\nSYNTHETIC_SETTING=keep-me\n", encoding="utf-8")
        self.override_path = self.deploy_dir / "compose.override.yaml"
        self.override_path.write_text(render_override(OLD_IMAGE), encoding="utf-8")
        self.env_path = self.deploy_dir / ".env"
        self.original_env = self.env_path.read_bytes()
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.docker_log = self.root / "docker.log"
        self.active_image = self.root / "active-image"
        self.mv_log = self.root / "mv.log"
        self.flock_dir = self.root / ".fake-flock"
        self.active_image.write_text(OLD_IMAGE + "\n", encoding="ascii")
        self._write_executable("docker", FAKE_DOCKER)
        if shutil.which("flock") is None:
            self._write_executable("flock", FAKE_FLOCK)
        self.bash_env = self.root / "bash-env.sh"
        self.bash_env.write_text(FAKE_BASH_ENV, encoding="utf-8", newline="\n")
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
        drive, tail = os.path.splitdrive(str(path))
        if len(drive) == 2 and drive[1] == ":":
            return "/" + drive[0].lower() + tail.replace("\\", "/")
        return str(path).replace("\\", "/")

    def _deploy_args(self):
        return [self.bash, str(SCRIPT)]

    def _deploy_env(self, image=None, expected_sha=None, mode="deploy", **extra_env):
        env = os.environ.copy()
        env.update(
            {
                "PATH": str(self.bin_dir) + os.pathsep + env.get("PATH", ""),
                "FAKE_DOCKER_LOG": self._bash_path(self.docker_log),
                "FAKE_ACTIVE_IMAGE": self._bash_path(self.active_image),
                "FAKE_MV_LOG": self._bash_path(self.mv_log),
                "FAKE_FLOCK_LOCK_DIR": self._bash_path(self.flock_dir),
                "FAKE_COMPOSE_SHA": self._compose_sha(),
                "BASH_ENV": self._bash_path(self.bash_env),
            }
        )
        if image is not None:
            env.update(
                {
                    "VPS_DEPLOY_DIR_B64": base64.b64encode(
                        os.fsencode(self._bash_path(self.deploy_dir))
                    ).decode("ascii"),
                    "IMAGE_REF_B64": base64.b64encode(image.encode("ascii")).decode("ascii"),
                    "COMPOSE_SHA256": expected_sha or self._compose_sha(),
                    "DEPLOY_MODE": mode,
                }
            )
        env.update(extra_env)
        for name in ("FAKE_UP_STARTED", "FAKE_UP_RELEASE", "FAKE_PROMOTION_STARTED", "FAKE_PROMOTION_RELEASE", "FAKE_PROMOTION_FINISHED"):
            if name in env:
                env[name] = self._bash_path(Path(env[name]))
        return env

    def test_deploy_script_receives_the_action_environment_contract(self):
        env = self._deploy_env(NEW_IMAGE)

        self.assertEqual([self.bash, str(SCRIPT)], self._deploy_args())
        self.assertEqual(
            base64.b64encode(os.fsencode(self._bash_path(self.deploy_dir))).decode("ascii"),
            env["VPS_DEPLOY_DIR_B64"],
        )
        self.assertEqual(base64.b64encode(NEW_IMAGE.encode("ascii")).decode("ascii"), env["IMAGE_REF_B64"])
        self.assertEqual(self._compose_sha(), env["COMPOSE_SHA256"])
        self.assertEqual("deploy", env["DEPLOY_MODE"])

    def _wait_for_path(self, path, exists, timeout=10, process=None):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if path.exists() == exists:
                return
            if process is not None and process.poll() is not None:
                self.fail(f"Process exited early ({process.returncode}); mv={self.mv_log.read_text(errors='ignore') if self.mv_log.exists() else '<none>'}; docker={self.docker_log.read_text(errors='ignore') if self.docker_log.exists() else '<none>'}")
            time.sleep(0.02)
        self.fail(f"Timed out waiting for {path} exists={exists}; mv={self.mv_log.read_text(errors='ignore') if self.mv_log.exists() else '<none>'}; docker={self.docker_log.read_text(errors='ignore') if self.docker_log.exists() else '<none>'}")

    def _wait_for_flock_release(self):
        self._wait_for_path(self.flock_dir, False)

    def _run_deploy(self, image, expected_sha=None, mode="deploy", wait_for_lock_release=True, **extra_env):
        result = subprocess.run(
            self._deploy_args(),
            cwd=self.root,
            env=self._deploy_env(image, expected_sha, mode, **extra_env),
            text=True,
            capture_output=True,
            timeout=30,
        )
        if wait_for_lock_release:
            self._wait_for_flock_release()
        return result

    def _spawn_deploy(self, image, mode="deploy", **extra_env):
        return subprocess.Popen(
            self._deploy_args(),
            cwd=self.root,
            env=self._deploy_env(image, mode=mode, **extra_env),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def _commands(self):
        if not self.docker_log.exists():
            return []
        return self.docker_log.read_text(encoding="utf-8").splitlines()

    def _up_commands(self):
        return [command for command in self._commands() if " up " in command and "--help" not in command]

    def _temporary_override_files(self):
        return list(self.deploy_dir.glob(".compose.override.yaml.*"))

    def _reset_bootstrap_state(self):
        self._wait_for_flock_release()
        for path in (
            self.override_path,
            self.deploy_dir / ".watcher-deploy.journal",
            self.deploy_dir / ".watcher-bootstrap.journal",
            self.deploy_dir / ".compose.override.yaml.candidate",
            self.deploy_dir / ".watcher-deploy.lock",
        ):
            path.unlink(missing_ok=True)
        self.docker_log.unlink(missing_ok=True)
        self.active_image.write_text(OLD_IMAGE + "\n", encoding="ascii")

    def _config_env(self, spring_url=None, spring_user="watcher_app", evolution_url=None, evolution_enabled="false"):
        return "\n".join(
            (
                f"SPRING_DATASOURCE_URL={spring_url or 'jdbc:postgresql://postgres.synthetic.invalid:5432/watcher'}",
                f"SPRING_DATASOURCE_USERNAME={spring_user}",
                "SPRING_DATASOURCE_PASSWORD=synthetic-only",
                f"EVOLUTION_DATABASE_URL={evolution_url or 'postgresql://evolution_app:synthetic-only@postgres.synthetic.invalid:5432/evolution'}",
                "WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED=false",
                "WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED=false",
                f"WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED={evolution_enabled}",
            )
        )

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

    def test_previous_digest_must_be_cached_before_candidate_pull(self):
        result = self._run_deploy(NEW_IMAGE, FAKE_LOCAL_MISSING_IMAGE=OLD_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("previous", result.stderr.lower())
        self.assertFalse(any(" pull --policy always watcher" in command for command in self._commands()))
        self.assertEqual([], self._up_commands())
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))

    def test_rollback_uses_cached_digest_while_registry_is_offline(self):
        result = self._run_deploy(
            NEW_IMAGE,
            FAKE_UP_FAIL_IMAGE=NEW_IMAGE,
            FAKE_REGISTRY_OFFLINE="true",
            FAKE_REGISTRY_OLD_IMAGE=OLD_IMAGE,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(OLD_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        up_commands = self._up_commands()
        self.assertEqual(2, len(up_commands), up_commands)
        self.assertTrue(all("--pull never" in command for command in up_commands))

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
        self.assertEqual(2, sum(" pull --policy always watcher" in command for command in commands))
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
        for variable in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME"):
            with self.subTest(variable=variable):
                self._reset_bootstrap_state()
                self.override_path.write_text(render_override(OLD_IMAGE), encoding="utf-8")
                self.env_path.write_text(f"{variable}=alternate\nSYNTHETIC_SETTING=keep-me\n", encoding="utf-8")
                env_before = self.env_path.read_bytes()

                result = self._run_deploy(NEW_IMAGE)

                self.assertNotEqual(0, result.returncode)
                self.assertIn(variable, result.stderr)
                self.assertEqual([], self._commands())
                self.assertEqual(env_before, self.env_path.read_bytes())
                self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))

    def test_service_or_volume_drift_fails_before_up(self):
        cases = (
            {"FAKE_SERVICES": "watcher\nevolution-api\nunrelated"},
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

    def test_second_process_cannot_enter_while_deploy_lock_is_held(self):
        promotion_started = self.root / "promotion-started"
        release_promotion = self.root / "release-promotion"
        process = self._spawn_deploy(
            NEW_IMAGE,
            FAKE_BLOCK_PROMOTION="true",
            FAKE_PROMOTION_STARTED=str(promotion_started),
            FAKE_PROMOTION_RELEASE=str(release_promotion),
        )
        try:
            self._wait_for_path(promotion_started, True, timeout=20, process=process)
            journal = self.deploy_dir / ".watcher-deploy.journal"
            self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
            commands_before = self._commands()

            second = self._run_deploy(NEXT_IMAGE, wait_for_lock_release=False)

            self.assertNotEqual(0, second.returncode)
            self.assertIn("lock", second.stderr.lower())
            self.assertEqual(commands_before, self._commands())
            self.assertTrue(journal.is_file())
            self.assertIn("phase=promoting", journal.read_text(encoding="utf-8"))
        finally:
            if process.poll() is None:
                process.kill()
            release_promotion.touch()
            process.communicate(timeout=15)
            self._wait_for_flock_release()

    def test_restart_during_promotion_recovers_previous_digest(self):
        promotion_started = self.root / "promotion-started"
        process = self._spawn_deploy(
            NEW_IMAGE,
            FAKE_CRASH_PROMOTION="true",
            FAKE_PROMOTION_STARTED=str(promotion_started),
        )
        self._wait_for_path(promotion_started, True, timeout=20, process=process)
        stdout, stderr = process.communicate(timeout=15)
        self.assertNotEqual(0, process.returncode, stdout + stderr)
        self._wait_for_flock_release()
        journal = self.deploy_dir / ".watcher-deploy.journal"
        self.assertTrue(journal.is_file())
        self.assertIn("phase=promoting", journal.read_text(encoding="utf-8"))
        self.assertEqual(NEW_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))

        result = self._run_deploy(NEW_IMAGE)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("interrupted", result.stderr.lower())
        self.assertEqual(render_override(OLD_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(OLD_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertFalse((self.deploy_dir / ".watcher-deploy.journal").exists())
        self.assertEqual([], self._temporary_override_files())
        self.assertEqual(self.original_env, self.env_path.read_bytes())
        self.assertIn("evolution_instances", self.compose_path.read_text(encoding="utf-8"))

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

    def test_bootstrap_creates_first_digest_only_after_health(self):
        self.override_path.unlink()

        result = self._run_deploy(NEW_IMAGE, mode="bootstrap")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(render_override(NEW_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(NEW_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertFalse((self.deploy_dir / ".watcher-bootstrap.journal").exists())
        self.assertFalse((self.deploy_dir / ".compose.override.yaml.candidate").exists())
        self.assertEqual(self.original_env, self.env_path.read_bytes())
        up_commands = self._up_commands()
        self.assertEqual(1, len(up_commands), up_commands)
        self.assertIn("-p app", up_commands[0])
        self.assertIn("--no-deps", up_commands[0])
        self.assertIn("--pull never", up_commands[0])
        self.assertTrue(up_commands[0].endswith(" watcher"))
        forbidden = ("compose down", "prune", "--remove-orphans", "volume rm", "up .* redis", "up .* evolution-api")
        for value in forbidden:
            self.assertFalse(any(value in command for command in self._commands()), self._commands())

    def test_bootstrap_refuses_existing_override_journals_candidate_and_project_resources(self):
        cases = (
            ("user override", "override", render_override(OLD_IMAGE), {}),
            ("normal journal", ".watcher-deploy.journal", "existing", {}),
            ("bootstrap journal", ".watcher-bootstrap.journal", "existing", {}),
            ("candidate", ".compose.override.yaml.candidate", render_override(NEW_IMAGE), {}),
            ("project container", None, None, {"FAKE_PROJECT_CONTAINERS": "container-id"}),
            ("existing watcher", None, None, {"FAKE_EXISTING_WATCHERS": "container-id"}),
            ("project volume", None, None, {"FAKE_PROJECT_VOLUMES": "app_evolution_instances"}),
            ("project network", None, None, {"FAKE_PROJECT_NETWORKS": "app_unexpected"}),
            ("named volume", None, None, {"FAKE_EXISTING_VOLUME": "app_evolution_instances"}),
        )
        for label, path_name, content, env in cases:
            with self.subTest(label=label):
                self._reset_bootstrap_state()
                if path_name == "override":
                    path = self.override_path
                elif path_name:
                    path = self.deploy_dir / path_name
                else:
                    path = None
                if path is not None:
                    path.write_text(content, encoding="utf-8")
                result = self._run_deploy(NEW_IMAGE, mode="bootstrap", **env)
                self.assertNotEqual(0, result.returncode, label)
                self.assertEqual([], self._up_commands(), self._commands())
                self.assertEqual(self.original_env, self.env_path.read_bytes())
                candidate_file = self.deploy_dir / ".compose.override.yaml.candidate"
                if path_name == ".compose.override.yaml.candidate":
                    self.assertEqual(content, candidate_file.read_text(encoding="utf-8"))
                else:
                    self.assertFalse(candidate_file.exists())
                self.assertEqual(content, path.read_text(encoding="utf-8") if path is not None else None)

    def test_bootstrap_rejects_real_action_flags_or_ambiguous_database_identity(self):
        cases = (
            self._config_env(evolution_enabled="true"),
            self._config_env(
                spring_url="jdbc:postgresql://postgres.synthetic.invalid:5432/shared",
                evolution_url="postgresql://evolution_app:synthetic-only@postgres.synthetic.invalid:5432/shared",
            ),
            self._config_env(
                spring_url="jdbc:postgresql://postgres.synthetic.invalid:5432/shared",
                evolution_url="postgresql://evolution_app:synthetic-only@postgres.synthetic.invalid/shared",
            ),
            self._config_env(
                spring_user="shared_role",
                evolution_url="postgresql://shared_role:synthetic-only@postgres.synthetic.invalid:5432/evolution",
            ),
            self._config_env(
                spring_url="jdbc:postgresql://postgres.synthetic.invalid:5432/watcher?user=evolution_app&password=synthetic-only",
            ),
            self._config_env(
                spring_url="jdbc:postgresql://postgres-primary.synthetic.invalid:5432/watcher",
                evolution_url="postgresql://evolution_app:synthetic-only@postgres-alias.synthetic.invalid:5432/evolution",
            ),
            self._config_env(
                evolution_url="postgresql://evolution_app:synthetic-only@postgres.synthetic.invalid:5432/%77atcher",
            ),
            self._config_env(
                spring_user="evolution_app",
                evolution_url="postgresql://%65volution_app:synthetic-only@postgres.synthetic.invalid:5432/evolution",
            ),
            self._config_env(
                spring_url="jdbc:postgresql://postgres%2esynthetic.invalid:5432/watcher",
                evolution_url="postgresql://evolution_app:synthetic-only@postgres%2esynthetic.invalid:5432/evolution",
            ),
            self._config_env(
                spring_url="jdbc:postgresql://pöstgres.synthetic.invalid:5432/watcher",
                evolution_url="postgresql://evolution_app:synthetic-only@pöstgres.synthetic.invalid:5432/evolution",
            ),
            self._config_env(spring_url="jdbc:postgresql://postgres.synthetic.invalid:5432/wátcher"),
            self._config_env(spring_user="wátcher_app"),
        )
        for config in cases:
            with self.subTest(config=config.splitlines()[-1]):
                self._reset_bootstrap_state()
                result = self._run_deploy(NEW_IMAGE, mode="bootstrap", FAKE_CONFIG_ENV=config)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("synthetic-only", result.stdout + result.stderr)
                self.assertEqual([], self._up_commands(), self._commands())
                self.assertFalse((self.deploy_dir / ".compose.override.yaml").exists())
                self.assertEqual(self.original_env, self.env_path.read_bytes())

    def test_bootstrap_pull_failure_can_retry_without_partial_state(self):
        self.override_path.unlink()
        first = self._run_deploy(NEW_IMAGE, mode="bootstrap", FAKE_PULL_FAIL_IMAGE=NEW_IMAGE)

        self.assertNotEqual(0, first.returncode)
        self.assertEqual([], self._up_commands())
        self.assertFalse((self.deploy_dir / ".watcher-bootstrap.journal").exists())
        self.assertFalse((self.deploy_dir / ".compose.override.yaml.candidate").exists())
        self.assertFalse(self.override_path.exists())
        self.assertEqual(self.original_env, self.env_path.read_bytes())

        retry = self._run_deploy(NEW_IMAGE, mode="bootstrap")

        self.assertEqual(0, retry.returncode, retry.stderr)
        self.assertEqual(render_override(NEW_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(self.original_env, self.env_path.read_bytes())

    def test_failed_bootstrap_retains_journal_and_blocks_automatic_retry(self):
        self.override_path.unlink()
        failed = self._run_deploy(NEW_IMAGE, mode="bootstrap", FAKE_UNHEALTHY_IMAGE=NEW_IMAGE)

        self.assertNotEqual(0, failed.returncode)
        journal = self.deploy_dir / ".watcher-bootstrap.journal"
        self.assertTrue(journal.is_file())
        self.assertIn(NEW_IMAGE, journal.read_text(encoding="utf-8"))
        self.assertNotIn("synthetic-only", journal.read_text(encoding="utf-8"))
        self.assertTrue((self.deploy_dir / ".compose.override.yaml.candidate").is_file())
        self.assertFalse(self.override_path.exists())
        self.assertEqual(self.original_env, self.env_path.read_bytes())
        self.assertFalse(any(value in command for command in self._commands() for value in ("down", "prune", "volume rm")))

        before = self._commands()
        retry = self._run_deploy(NEW_IMAGE, mode="bootstrap")
        normal_deploy = self._run_deploy(NEW_IMAGE, mode="deploy")

        self.assertNotEqual(0, retry.returncode)
        self.assertNotEqual(0, normal_deploy.returncode)
        self.assertIn("journal", retry.stderr.lower() + normal_deploy.stderr.lower())
        self.assertEqual(before, self._commands())
        self.assertTrue(journal.is_file())
        self.assertEqual(self.original_env, self.env_path.read_bytes())

    def test_promoted_bootstrap_lock_has_safe_manual_recovery_without_data_changes(self):
        self.override_path.unlink()
        promotion_finished = self.root / "promotion-finished"
        process = self._spawn_deploy(
            NEW_IMAGE,
            mode="bootstrap",
            FAKE_CRASH_AFTER_PROMOTION="true",
            FAKE_PROMOTION_FINISHED=str(promotion_finished),
        )
        self._wait_for_path(promotion_finished, True, timeout=20, process=process)
        stdout, stderr = process.communicate(timeout=15)
        self.assertNotEqual(0, process.returncode, stdout + stderr)
        self._wait_for_flock_release()

        journal = self.deploy_dir / ".watcher-bootstrap.journal"
        candidate = self.deploy_dir / ".compose.override.yaml.candidate"
        self.assertTrue(journal.is_file())
        self.assertIn("phase=promoting", journal.read_text(encoding="utf-8"))
        self.assertFalse(candidate.exists())
        self.assertEqual(render_override(NEW_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(NEW_IMAGE + "\n", self.active_image.read_text(encoding="ascii"))
        self.assertEqual(self.original_env, self.env_path.read_bytes())

        before_retry = self._commands()
        blocked = self._run_deploy(NEW_IMAGE, mode="bootstrap")
        self.assertNotEqual(0, blocked.returncode)
        self.assertIn("journal", blocked.stderr.lower())
        self.assertEqual(before_retry, self._commands())

        manual_recovery = r'''set -euo pipefail
exec 9>>.watcher-deploy.lock
flock -n 9
test ! -e .compose.override.yaml.candidate
grep -Eq '^phase=(promoting|committed)$' .watcher-bootstrap.journal
candidate_image="$(sed -n 's/^candidate_image=//p' .watcher-bootstrap.journal)"
current_image="$(docker compose --env-file .env -p "$(basename "$PWD")" config --images watcher)"
test "$current_image" = "$candidate_image"
container_id="$(docker compose --env-file .env -p "$(basename "$PWD")" ps -q watcher)"
test -n "$container_id"
test "$(docker inspect --format '{{.State.Running}}' "$container_id")" = true
test "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")" = healthy
rm -f -- .watcher-bootstrap.journal
'''
        manual_env = self._deploy_env()
        recovery = subprocess.run(
            [self.bash, "-c", manual_recovery],
            cwd=self.deploy_dir,
            env=manual_env,
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(0, recovery.returncode, recovery.stderr)
        self._wait_for_flock_release()
        self.assertFalse(journal.exists())
        self.assertEqual(render_override(NEW_IMAGE), self.override_path.read_text(encoding="utf-8"))
        self.assertEqual(self.original_env, self.env_path.read_bytes())
        self.assertIn("evolution_instances", self.compose_path.read_text(encoding="utf-8"))
        self.assertFalse(any("down" in command or "volume rm" in command or "prune" in command for command in self._commands()))
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn(".compose.override.yaml.candidate", readme)
        self.assertIn(".watcher-bootstrap.journal", readme)
        self.assertIn(manual_recovery, readme)


if __name__ == "__main__":
    unittest.main()
