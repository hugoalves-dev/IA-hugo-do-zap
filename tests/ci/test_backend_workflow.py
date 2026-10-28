from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "backend.yml"


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
        self.assertIn("$COMPOSE_SHA256 $MODE", deploy)
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
        self.assertIn("StrictHostKeyChecking=yes", deploy)
        self.assertNotIn("ssh-keyscan", deploy)
        self.assertNotIn("StrictHostKeyChecking=no", deploy)

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
