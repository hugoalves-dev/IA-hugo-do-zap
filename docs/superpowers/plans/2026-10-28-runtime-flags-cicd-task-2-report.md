# Task 2: CI/CD report

## Delivery

- Added `.github/workflows/backend.yml` with separate Java 25 test/build, GHCR publish, and VPS deploy jobs.
- Pull requests and feature-branch pushes run validation only. A `main` push can publish an image but cannot deploy. Deployment requires a manual `workflow_dispatch` on `main` with the `deploy` input set to `true`.
- Published images receive `latest` and full commit-SHA tags. Deploy consumes the registry digest output from that same publish job and attaches GitHub build provenance to GHCR.
- Added an unauthenticated Spring liveness/readiness probe configuration; readiness includes PostgreSQL and details stay hidden. Docker health checks readiness.
- The deploy job checks out its source commit and sends `scripts/deploy-vps.sh` over SSH. The script fails closed unless the remote `compose.yaml` SHA-256 matches that commit and `compose.override.yaml` is the workflow-managed lock for the currently approved image digest.
- The script stages a candidate `compose.override.yaml`, validates service names and the `evolution_instances` volume, pulls the candidate digest, and updates only `watcher` with `--no-deps --wait --wait-timeout 180`. It inspects running and health state before atomically promoting the candidate override. On failed startup or unhealthy status, it restores the previous override and digest, restarts only `watcher`, and checks rollback health.
- The generated `compose.override.yaml` is auto-loaded by normal Compose commands and uses `pull_policy: never`, so commands use the locally cached approved digest without editing `.env` or falling back to the base build. New candidate pulls happen explicitly before deployment. A pre-existing user override is preserved and causes a safe failure.
- The deploy script locks `.watcher-deploy.lock` with `flock`, checks that the previous digest is local before changing the service, and runs rollback with `--pull never`. Candidate pull is explicit; if GHCR becomes unavailable after pull, rollback still uses the cached prior digest.
- A non-secret atomic journal records phase, both digests, and the Compose SHA. A later invocation recovers interrupted deployment state under the same lock, verifies the prior digest rollback, clears the journal, and exits with an operator instruction. Mismatched or malformed journal state fails closed.
- Added shell fake end-to-end checks for failure rollback, unhealthy rollback, cached rollback during registry outage, repeated deploys, concurrent deploy exclusion, interrupted-promotion recovery, Compose drift, user overrides, `COMPOSE_FILE` bypass, service/volume drift, and readiness configuration. The workflow runs these focused Python checks in its test job. `README.md` documents first-deploy state and VPS requirements.

## Validation

- `python -B -m unittest discover -s tests/ci -p 'test_backend_workflow.py'`: 5 workflow contract tests passed.
- `python -B -m unittest discover -s tests/ci -p 'test_deploy_vps.py'`: 14 shell fake end-to-end tests passed. They cover startup/health failures, registry outage rollback from cache, concurrent processes, interrupted promotion, Compose drift, unowned overrides, `COMPOSE_FILE` bypass, and service/volume drift; no real Docker daemon or VPS was used.
- The new local-image, offline-rollback, concurrency, and restart cases first failed against the previous deploy script. All passed after implementation.
- This Windows test host has no system `flock`, so the shell test used its file-lock shim for concurrent process checks. The deploy script calls system `flock` on the VPS.
- Parsed the workflow with cached `gopkg.in/yaml.v3` using `GOPROXY=off`; the parser found three jobs and `workflow_dispatch`. No dependency was downloaded or added to the repository.
- `bash -n` passed for `scripts/deploy-vps.sh`, the GitHub deploy step, and the test fake shell fixtures.
- `docker compose --env-file <synthetic-temp-file> config --quiet`: passed in a temporary project. Default discovery loaded the pinned override and retained readiness settings, services, volume, networks, and `.env` bytes.
- `actionlint` is not installed, so its GitHub Actions expression diagnostics were unavailable. Focused checks cover event gates, digest selection, SSH trust, rollback, health, Compose compatibility, and cleanup constraints.
- No Maven suite, image build/publish, VPS connection, deployment, real health observation, pairing, or real send was run.

## Configuration required before a deploy

Read-only GitHub queries found no configured repository Actions secrets, variables, or self-hosted runners. The workflow uses GitHub-hosted `ubuntu-latest` runners and the built-in `GITHUB_TOKEN` for publishing.

Configure these repository secrets:

- `SSH_KEY`
- `SSH_PORT`
- `VPS_IP`
- `VPS_USER`
- `VPS_KNOWN_HOSTS` — verified host-key line for the VPS address and port; the workflow enforces `StrictHostKeyChecking=yes`.

Configure repository variable `VPS_DEPLOY_DIR` as a non-root absolute path. That existing directory must contain the exact committed `compose.yaml`, the already-configured `.env`, and a `compose.override.yaml` in the workflow marker format, pinned to the currently healthy known-good digest. The first lock must be seeded by an operator after verifying the digest and confirming its image exists in Docker's local cache. An unowned override is left untouched and blocks deployment. The VPS needs Bash, `flock`, `sha256sum`, `base64`, Docker Compose with `up --wait --wait-timeout`, Docker permissions for `VPS_USER`, connectivity to `infra_network` and both PostgreSQL databases, and an existing GHCR package pull authorization if the image package is private.

## Repository state

Target checkout: branch `codex/evolution-poc`, remote `origin` at `hugoalves-dev/IA-hugo-do-zap`. The existing dirty `.superpowers/sdd/2026-10-26-03-ai-telegram-actions/progress.md`, `.idea/`, and `target/` were preserved. No push or live VPS operation was performed.
