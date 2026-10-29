from pathlib import Path
import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
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

    def test_job_level_environment_does_not_reference_env_context(self):
        deploy = self.job("deploy-vps")
        job_environment = re.search(r"(?ms)^    env:\s*\n(.*?)(?=^    steps:\s*$)", deploy).group(1)
        self.assertNotIn("IMAGE_REF:", job_environment)
        validation_step = re.search(
            r"(?ms)^      - name: Validate SSH settings.*?^        env:\s*\n(.*?)(?=^        run:)",
            deploy,
        ).group(1)
        self.assertIn("IMAGE_REF: ${{ env.IMAGE_NAME }}@${{ needs.publish-image.outputs.digest }}", validation_step)

    def test_ssh_uses_only_private_key_authentication_without_host_fingerprint(self):
        deploy = self.job("deploy-vps")
        for name in ("SSH_KEY", "SSH_PORT", "VPS_IP", "VPS_USER"):
            self.assertIn(f"secrets.{name}", deploy)
        self.assertNotIn("VPS_KNOWN_HOSTS", deploy)
        self.assertRegex(deploy, r"uses: appleboy/ssh-action@[0-9a-f]{40}")
        action = deploy.split("uses: appleboy/ssh-action", maxsplit=1)[1]
        self.assertIn("key: ${{ secrets.SSH_KEY }}", action)
        self.assertIn("DRONE_SSH_VERSION: 1.8.1", action)
        self.assertIn("script_path: scripts/deploy-vps-action.sh", action)
        self.assertNotRegex(action, r"(?m)^\s*(?:password|fingerprint):")
        self.assertNotIn("debug: true", action)
        self.assertNotIn("ssh-keyscan", deploy)

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

    def test_compose_default_and_local_postgres_profiles_are_isolated_and_documented(self):
        compose = ROOT / "compose.yaml"
        env_values = {
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres.synthetic.invalid:5432/watcher",
            "SPRING_DATASOURCE_USERNAME": "watcher_app",
            "SPRING_DATASOURCE_PASSWORD": "synthetic-watcher-password",
            "EVOLUTION_DATABASE_URL": "postgresql://evolution_app:synthetic-evolution-password@postgres.synthetic.invalid:5432/evolution",
            "WATCHER_TELEGRAM_BOT_TOKEN": "synthetic-token",
            "WATCHER_TELEGRAM_CHAT_ID": "123456789",
            "WATCHER_EVOLUTION_WEBHOOK_TOKEN": "synthetic-webhook-token",
            "EVOLUTION_API_KEY": "synthetic-evolution-api-key",
            "EVOLUTION_IMAGE": "evoapicloud/evolution-api:v2.3.7@sha256:1bd8afc4a6cf48822e6cf02469aeae7bd35a12a6b616eacd1291926307f4d339",
            "COMPOSE_PROFILES": "prod",
            "WATCHER_AI_ENABLED": "true",
            "WATCHER_AI_GEMINIAPIKEY": "synthetic-gemini-key",
            "WATCHER_AI_GEMINIMODEL": "synthetic-gemini-model",
            "WATCHER_AI_OPENROUTERAPIKEY": "synthetic-openrouter-key",
            "WATCHER_AI_OPENROUTERMODEL": "synthetic/openrouter-model",
            "WATCHER_AI_ATTEMPTHOLDMICROS": "250000",
            "WATCHER_AI_TIMEOUT": "15s",
            "LOCAL_POSTGRES_IMAGE": "postgres:17.11-alpine3.23@sha256:01ea903aa65deb58eb49d6e09d859ecff63a6bb7d61feca55f2abad71d08f72a",
            "LOCAL_WATCHER_DB_NAME": "synthetic_watcher",
            "LOCAL_WATCHER_DB_USER": "synthetic_watcher_role",
            "LOCAL_WATCHER_DB_PASSWORD": "synthetic-only-watcher-password",
            "LOCAL_EVOLUTION_DB_NAME": "synthetic_evolution",
            "LOCAL_EVOLUTION_DB_USER": "synthetic_evolution_role",
            "LOCAL_EVOLUTION_DB_PASSWORD": "synthetic-only-evolution-password",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            env_file = Path(temp_dir) / ".env"
            env_file.write_text("".join(f"{key}={value}\n" for key, value in env_values.items()), encoding="utf-8")

            def config(*profiles):
                command = ["docker", "compose", "--env-file", str(env_file), "-f", str(compose)]
                for profile in profiles:
                    command.extend(("--profile", profile))
                command.extend(("config", "--format", "json"))
                result = subprocess.run(command, capture_output=True, text=True, check=False)
                self.assertEqual(0, result.returncode, result.stderr)
                return json.loads(result.stdout)

            default = config()
            self.assertEqual({"watcher", "evolution-api"}, set(default["services"]))
            self.assertEqual("evoapicloud/evolution-api:v2.3.7@sha256:1bd8afc4a6cf48822e6cf02469aeae7bd35a12a6b616eacd1291926307f4d339", default["services"]["evolution-api"]["image"])
            self.assertEqual("false", default["services"]["evolution-api"]["environment"]["CACHE_REDIS_ENABLED"])
            self.assertEqual("true", default["services"]["evolution-api"]["environment"]["CACHE_LOCAL_ENABLED"])
            self.assertNotIn("depends_on", default["services"]["evolution-api"])
            self.assertEqual("ghcr.io/hugoalves-dev/ia-hugo-do-zap:latest", default["services"]["watcher"]["image"])
            self.assertEqual("127.0.0.1", default["services"]["evolution-api"]["ports"][0]["host_ip"])
            self.assertIn("postgres.synthetic.invalid", default["services"]["watcher"]["environment"]["SPRING_DATASOURCE_URL"])
            watcher_env = default["services"]["watcher"]["environment"]
            for name in ("WATCHER_AI_ENABLED", "WATCHER_AI_GEMINIAPIKEY", "WATCHER_AI_GEMINIMODEL",
                         "WATCHER_AI_OPENROUTERAPIKEY", "WATCHER_AI_OPENROUTERMODEL",
                         "WATCHER_AI_ATTEMPTHOLDMICROS", "WATCHER_AI_TIMEOUT"):
                self.assertEqual(env_values[name], watcher_env[name])

            local = config("local-db")
            self.assertEqual({"postgres-local-watcher", "postgres-local-evolution"}, set(local["services"]) - set(default["services"]))
            watcher_db = local["services"]["postgres-local-watcher"]
            evolution_db = local["services"]["postgres-local-evolution"]
            self.assertEqual("postgres:17.11-alpine3.23@sha256:01ea903aa65deb58eb49d6e09d859ecff63a6bb7d61feca55f2abad71d08f72a", watcher_db["image"])
            self.assertEqual(watcher_db["image"], evolution_db["image"])
            self.assertTrue(watcher_db["healthcheck"])
            self.assertTrue(evolution_db["healthcheck"])
            self.assertNotIn("ports", watcher_db)
            self.assertNotIn("ports", evolution_db)
            self.assertNotEqual(watcher_db["environment"]["POSTGRES_DB"], evolution_db["environment"]["POSTGRES_DB"])
            self.assertNotEqual(watcher_db["environment"]["POSTGRES_USER"], evolution_db["environment"]["POSTGRES_USER"])
            self.assertNotEqual(watcher_db["volumes"][0], evolution_db["volumes"][0])

        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        for required_text in (
            "TypeScript legado",
            "opção de rollback até o cutover ser validado",
            "--profile local-db",
            "POST /instance/create",
            "GET /instance/connect/watcher-evolution",
            "WhatsApp → Aparelhos conectados",
            "localhost:8080",
            "Nunca publique, envie em chat ou registre captura do QR Code",
            "WATCHER_AI_GEMINIAPIKEY",
            "WATCHER_AI_OPENROUTERAPIKEY",
            "WATCHER_AI_ENABLED",
            "ai_data_consents",
            "As migrações deixam o gate de classificação desligado, não criam consentimentos e não inserem limites",
            "só é chamado após uma falha técnica do Gemini",
            "Não há atualmente comando ou tela suportados para ligar `ai_classification_enabled`",
        ):
            self.assertIn(required_text, readme)


if __name__ == "__main__":
    unittest.main()
