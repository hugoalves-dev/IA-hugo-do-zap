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
WORKFLOW = (ROOT / ".github/workflows/backend.yml").read_text(encoding="utf-8")
SCRIPT = (ROOT / "scripts/deploy-vps.sh").read_text(encoding="utf-8")
BASH = shutil.which("bash") or "bash"


class BackendWorkflowContractTest(unittest.TestCase):
    def job(self, name):
        match = re.search(rf"(?ms)^  {re.escape(name)}:\s*\n(.*?)(?=^  [a-z0-9_-]+:\s*\n|\Z)", WORKFLOW)
        self.assertIsNotNone(match, name)
        return match.group(1)

    def test_push_main_deploys_automatically_after_tests_and_publish(self):
        self.assertRegex(WORKFLOW, r"(?s)on:\n  push:\n    branches: \[main\]")
        self.assertIn("mvn -B -ntp verify", self.job("test-and-build"))
        self.assertIn("needs: test-and-build", self.job("publish-image"))
        deploy = self.job("deploy-vps")
        self.assertIn("needs: publish-image", deploy)
        self.assertIn("github.event_name == 'push'", deploy)
        self.assertIn("github.ref == 'refs/heads/main'", deploy)
        self.assertIn("watcher-vps-deploy", deploy)
        self.assertNotIn("inputs.deploy", deploy)
        self.assertNotIn("inputs.bootstrap", deploy)
        self.assertNotIn("DEPLOY_MODE", WORKFLOW)

    def test_pull_requests_only_test(self):
        self.assertRegex(WORKFLOW, r"(?s)  pull_request:\n    branches: \[main\]")
        for job in ("publish-image", "deploy-vps"):
            self.assertIn("github.ref == 'refs/heads/main'", self.job(job))
            self.assertNotIn("github.event_name == 'pull_request'", self.job(job))

    def test_deploy_is_pinned_to_ghcr_digest_and_uses_ssh_key(self):
        deploy = self.job("deploy-vps")
        self.assertIn("needs.publish-image.outputs.digest", deploy)
        self.assertIn("COMPOSE_CONTENT_B64", deploy)
        self.assertIn("COMPOSE_SHA256", deploy)
        self.assertIn("scripts/deploy-vps-action.sh", deploy)
        for secret in ("SSH_KEY", "SSH_PORT", "VPS_IP", "VPS_USER"):
            self.assertIn("secrets." + secret, deploy)
        self.assertIn("pull_policy: never", SCRIPT)
        self.assertIn('docker pull "$image"', SCRIPT)

    def test_deploy_only_touches_watcher_and_preserves_state(self):
        self.assertIn("up -d --no-deps --pull never --wait", SCRIPT)
        self.assertIn("watcher", SCRIPT)
        self.assertIn("was_healthy", SCRIPT)
        self.assertIn("rollback", SCRIPT)
        self.assertIn("legacy", SCRIPT)
        self.assertIn(".watcher-bootstrap.journal", SCRIPT)
        self.assertIn(".watcher-deployed-compose.sha256", SCRIPT)
        self.assertIn("flock", SCRIPT)
        for bad in ("docker compose down", "docker volume rm", "--remove-orphans", "docker image prune", "docker system prune"):
            self.assertNotIn(bad, SCRIPT + WORKFLOW)

    def test_compose_passes_ai_controls_on_recreation(self):
        names = {
            "WATCHER_AI_CLASSIFICATION_ENABLED": "true",
            "WATCHER_AI_DISABLE_LOCAL_BUDGET": "true",
            "WATCHER_AI_REQUIRE_CHAT_CONSENT": "false",
            "WATCHER_AUTOMATION_KILL_SWITCH_ENABLED": "false",
            "WATCHER_AUTOMATION_INTELLIGENT_ALERTS_ENABLED": "false",
            "WATCHER_AUTOMATION_AUTO_REPLY_ENABLED": "false",
            "WATCHER_SIMULATION_ENABLED": "true",
        }
        env = os.environ.copy()
        env.update(names)
        result = subprocess.run(["docker", "compose", "--env-file", str(ROOT / ".env.example"),
                                 "config", "--format", "json"], cwd=ROOT, env=env,
                                text=True, capture_output=True, check=True)
        watcher = json.loads(result.stdout)["services"]["watcher"]["environment"]
        for name, value in names.items():
            self.assertEqual(value, str(watcher[name]).lower(), name)
        env["WATCHER_AI_CLASSIFICATION_ENABLED"] = "false"
        second = subprocess.run(["docker", "compose", "--env-file", str(ROOT / ".env.example"),
                                 "config", "--format", "json"], cwd=ROOT, env=env,
                                text=True, capture_output=True, check=True)
        self.assertEqual("false", str(json.loads(second.stdout)["services"]["watcher"]["environment"]
                                      ["WATCHER_AI_CLASSIFICATION_ENABLED"]).lower())

    def test_shell_syntax_and_missing_arguments(self):
        self.assertEqual(0, subprocess.run([BASH, "-n"], input=SCRIPT, text=True, capture_output=True).returncode)
        result = subprocess.run([BASH, "-s"], input=SCRIPT, text=True, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("docker", result.stderr)

    def test_rejects_injected_directory_before_docker(self):
        env = os.environ.copy()
        env.update({
            "VPS_DEPLOY_DIR_B64": base64.b64encode(b"/docker/app;echo-PWNED").decode(),
            "IMAGE_REF_B64": base64.b64encode(b"ghcr.io/hugoalves-dev/ia-hugo-do-zap@sha256:" + b"a"*64).decode(),
            "COMPOSE_CONTENT_B64": base64.b64encode(b"services: {}\n").decode(),
            "COMPOSE_SHA256": "b"*64,
        })
        result = subprocess.run([BASH, "-s"], input=SCRIPT, text=True, env=env, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsafe deploy path", result.stderr)
        self.assertNotIn("docker", result.stderr)

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
            "WATCHER_AI_CLASSIFICATION_ENABLED",
            "WATCHER_AI_DISABLE_LOCAL_BUDGET",
            "WATCHER_AI_REQUIRE_CHAT_CONSENT",
            "/emergencia",
        ):
            self.assertIn(required_text, readme)


if __name__ == "__main__":
    unittest.main()
