from pathlib import Path
import base64
import os
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend.yml"
BASH_PATH = shutil.which("bash") or "bash"
SH_PATH = shutil.which("sh") or "sh"
if os.name == "nt":
    git_bash = Path("C:/Program Files/Git/bin/bash.exe")
    if not git_bash.exists():
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git" / "bin" / "bash.exe"
    if git_bash.exists():
        BASH_PATH = str(git_bash)
        SH_PATH = str(git_bash.with_name("sh.exe"))


class BackendWorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8") if WORKFLOW_PATH.exists() else ""

    def job(self, name):
        match = re.search(
            rf"(?ms)^  {re.escape(name)}:\s*\n(.*?)(?=^  [a-z0-9_-]+:\s*\n|\Z)",
            self.workflow,
        )
        self.assertIsNotNone(match, f"missing job {name}")
        return match.group(1)

    def test_jobs_run_as_test_publish_deploy_chain(self):
        build = self.job("test-and-build")
        self.assertIn("runs-on: ubuntu-latest", build)
        self.assertIn("python3 -B -m unittest discover -s tests/ci -p 'test*.py'", build)
        self.assertIn("mvn -B -ntp verify", build)
        self.assertIn("needs: test-and-build", self.job("publish-image"))
        self.assertIn("needs: publish-image", self.job("deploy-vps"))

    def test_pull_requests_never_publish_or_deploy(self):
        publish = self.job("publish-image")
        deploy = self.job("deploy-vps")
        self.assertIn("github.event_name == 'push'", publish)
        self.assertIn("github.event_name == 'workflow_dispatch'", publish)
        self.assertIn("github.ref == 'refs/heads/main'", publish)
        self.assertNotIn("pull_request", publish)
        self.assertIn("github.event_name == 'workflow_dispatch'", deploy)
        self.assertIn("github.ref == 'refs/heads/main'", deploy)
        self.assertIn("inputs.deploy == true", deploy)
        self.assertNotIn("github.event_name == 'push'", deploy)
        self.assertRegex(
            self.workflow,
            r"(?ms)^  workflow_dispatch:\s*\n\s+inputs:\s*\n\s+deploy:\s*\n"
            r"\s+description:.*\n\s+required: true\n\s+type: boolean\n\s+default: false",
        )

    def test_bootstrap_is_a_separate_explicit_manual_mode(self):
        deploy = self.job("deploy-vps")
        self.assertIn("inputs.bootstrap == true", deploy)
        self.assertIn("inputs.deploy == true", deploy)
        self.assertIn("BOOTSTRAP", deploy)
        self.assertIn('if [[ "$DEPLOY" == true && "$BOOTSTRAP" == true ]]', deploy)
        self.assertIn("COMPOSE_SHA256: ${{ env.COMPOSE_SHA256 }}", deploy)
        self.assertIn("DEPLOY_MODE: ${{ env.DEPLOY_MODE }}", deploy)
        self.assertRegex(
            self.workflow,
            r"(?ms)^      bootstrap:\s*\n\s+description:.*\n\s+required: true\n\s+type: boolean\n\s+default: false",
        )

    def test_publish_attests_image_and_deploy_uses_digest(self):
        publish = self.job("publish-image")
        deploy = self.job("deploy-vps")
        self.assertIn("type=sha,format=long,prefix=sha-", publish)
        self.assertIn("outputs.digest", publish)
        self.assertIn("actions/attest@v4", publish)
        self.assertIn("create-storage-record: false", publish)
        self.assertIn("needs.publish-image.outputs.digest", deploy)
        self.assertRegex(deploy, r"IMAGE_REF:\s*.*@\$\{\{\s*needs\.publish-image\.outputs\.digest\s*\}\}")

    def test_ssh_requires_pinned_host_keys_and_strict_checking(self):
        deploy = self.job("deploy-vps")
        for name in ("SSH_KEY", "SSH_PORT", "VPS_IP", "VPS_USER", "VPS_KNOWN_HOSTS"):
            self.assertIn(f"secrets.{name}", deploy)
        self.assertRegex(deploy, r"uses: appleboy/ssh-action@[0-9a-f]{40}")
        self.assertIn("fingerprint:", deploy)
        self.assertIn("DRONE_SSH_VERSION: 1.8.1", deploy)
        self.assertIn("script_path: scripts/deploy-vps-action.sh", deploy)
        action = deploy.split("uses: appleboy/ssh-action", maxsplit=1)[1]
        self.assertNotIn("VPS_KNOWN_HOSTS", action)
        self.assertNotIn("debug: true", action)
        self.assertIn("!/^#/ && NF >= 3", deploy)
        self.assertIn("VPS_KNOWN_HOSTS entries marked with @ are not supported", deploy)
        marker_rejection = "awk '!/^#/ && $1 ~ /^@/ { marked = 1 } END { exit !marked }'"
        self.assertIn(marker_rejection, deploy)
        self.assertLess(deploy.index(marker_rejection), deploy.index("mapfile -t host_keys"))
        self.assertNotIn("ssh-keyscan", deploy)
        self.assertNotIn("StrictHostKeyChecking=no", deploy)
        self.assertNotIn("StrictHostKeyChecking=yes", deploy)

    def test_ssh_action_pins_drone_ssh_through_environment(self):
        deploy = self.job("deploy-vps")
        action = deploy.split("uses: appleboy/ssh-action", maxsplit=1)[1]
        self.assertIn("DRONE_SSH_VERSION: 1.8.1", action)
        self.assertNotRegex(action, r"(?m)^\s+version:")
        self.assertIn("DEPLOY_SCRIPT_B64: ${{ env.DEPLOY_SCRIPT_B64 }}", action)
        self.assertIn("DEPLOY_SCRIPT_B64,VPS_DEPLOY_DIR_B64", action)

    def test_action_wrapper_runs_bash_and_propagates_its_failure_from_sh(self):
        wrapper = ROOT / "scripts" / "deploy-vps-action.sh"
        self.assertTrue(wrapper.exists(), "missing POSIX wrapper for explicit Bash execution")
        wrapper_text = wrapper.read_text(encoding="utf-8")
        self.assertTrue(wrapper_text.startswith("#!/bin/sh\nset -eu\n"))
        self.assertIn('printf \'%s\' "$DEPLOY_SCRIPT_B64" | base64 -d > "$script_file"', wrapper_text)
        self.assertIn('bash "$script_file"', wrapper_text)
        environment = os.environ.copy()
        environment["DEPLOY_SCRIPT_B64"] = base64.b64encode(
            b'test -n "$BASH_VERSION" || exit 98\nexit 37\n'
        ).decode("ascii")
        completed = subprocess.run(
            [SH_PATH, str(wrapper)],
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )
        self.assertEqual(completed.returncode, 37, completed.stderr)

    def test_deploy_script_uses_action_environment_and_fails_fast(self):
        script = (ROOT / "scripts" / "deploy-vps.sh").read_text(encoding="utf-8")
        self.assertTrue(script.startswith("#!/usr/bin/env bash\nset -e\nset -euo pipefail\n"))
        self.assertIn('deploy_dir_b64="${VPS_DEPLOY_DIR_B64:?VPS_DEPLOY_DIR_B64 is required.}"', script)
        self.assertIn('image_ref_b64="${IMAGE_REF_B64:?IMAGE_REF_B64 is required.}"', script)
        self.assertIn('expected_compose_sha256="${COMPOSE_SHA256:?COMPOSE_SHA256 is required.}"', script)
        self.assertIn('mode="${DEPLOY_MODE:?DEPLOY_MODE is required.}"', script)

        completed = subprocess.run(
            [BASH_PATH, "-s"],
            input=script,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertNotIn("docker", completed.stderr)

    def test_deploy_script_rejects_path_shell_metacharacters_before_remote_work(self):
        environment = os.environ.copy()
        environment.update(
            {
                "VPS_DEPLOY_DIR_B64": base64.b64encode(b"/docker/app;touch-INJECTION-MUST-NOT-RUN").decode("ascii"),
                "IMAGE_REF_B64": base64.b64encode(
                    b"ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + b"a" * 64
                ).decode("ascii"),
                "COMPOSE_SHA256": "b" * 64,
                "DEPLOY_MODE": "deploy",
            }
        )
        completed = subprocess.run(
            [BASH_PATH, "-s"],
            input=(ROOT / "scripts" / "deploy-vps.sh").read_text(encoding="utf-8"),
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("safe, normalized absolute path", completed.stderr)
        self.assertNotIn("docker", completed.stderr)

    def test_deploy_uses_base64_for_untrusted_directory_and_digest_values(self):
        deploy = self.job("deploy-vps")
        script = (ROOT / "scripts" / "deploy-vps.sh").read_text(encoding="utf-8")
        self.assertIn("base64 --wrap=0", deploy)
        self.assertIn('deploy_script_b64="$(base64 --wrap=0 scripts/deploy-vps.sh)"', deploy)
        self.assertIn("envs: DEPLOY_SCRIPT_B64,VPS_DEPLOY_DIR_B64,IMAGE_REF_B64,COMPOSE_SHA256,DEPLOY_MODE", deploy)
        self.assertIn("base64 -d", script)
        self.assertIn("/docker/ia-hugo-do-zap", deploy)
        self.assertIn("VPS_DEPLOY_DIR", deploy)
        self.assertIn("..", script)

    def test_deploy_only_updates_watcher_without_global_cleanup(self):
        deploy = self.job("deploy-vps")
        script = (ROOT / "scripts" / "deploy-vps.sh").read_text(encoding="utf-8") if (ROOT / "scripts" / "deploy-vps.sh").exists() else ""
        self.assertIn("VPS_DEPLOY_DIR", deploy)
        self.assertIn("scripts/deploy-vps.sh", deploy)
        self.assertIn("docker compose", script)
        self.assertIn("[[ -f .env ]]", script)
        for destructive in ("docker image prune", "--remove-orphans", "docker compose down", "docker volume rm"):
            self.assertNotIn(destructive, deploy + script)


if __name__ == "__main__":
    unittest.main()
