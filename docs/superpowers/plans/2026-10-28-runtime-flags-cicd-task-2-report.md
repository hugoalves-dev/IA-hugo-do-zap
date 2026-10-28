# Task 2: CI/CD report

## Delivery

- Added `.github/workflows/backend.yml` with separate Java 25 test/build, GHCR publish, and VPS deploy jobs.
- Pull requests and feature-branch pushes run validation only. A `main` push can publish an image but cannot deploy. Deployment requires a manual `workflow_dispatch` on `main` with the `deploy` input set to `true`.
- Published images receive `latest` and full commit-SHA tags. Deploy consumes the registry digest output from that same publish job and attaches GitHub build provenance to GHCR.
- Added an unauthenticated Spring liveness/readiness probe configuration; readiness includes PostgreSQL and details stay hidden. Docker health checks readiness.
- The deploy job checks out its source commit and sends `scripts/deploy-vps.sh` over SSH. The script fails closed unless the remote `compose.yaml` SHA-256 matches that commit and `compose.override.yaml` is the workflow-managed lock for the currently approved image digest.
- The script stages a candidate `compose.override.yaml`, validates service names and the `evolution_instances` volume, pulls the candidate digest, and updates only `watcher` with `--no-deps --wait --wait-timeout 180`. It inspects running and health state before atomically promoting the candidate override. On failed startup or unhealthy status, it restores the previous override and digest, restarts only `watcher`, and checks rollback health.
- The generated `compose.override.yaml` is auto-loaded by normal Compose commands and uses `pull_policy: always`, so the approved digest remains pinned without editing `.env` or falling back to the base build. A pre-existing user override is preserved and causes a safe failure.
- Added shell fake end-to-end checks for failure rollback, unhealthy rollback, repeated deploys, Compose drift, user overrides, `COMPOSE_FILE` bypass, service/volume drift, and readiness configuration. `README.md` documents first-deploy state and VPS requirements.

## Validation

- `python -B -m unittest discover -s tests/ci -p 'test_backend_workflow.py'`: 5 workflow contract tests passed.
- `python -B -m unittest discover -s tests/ci -p 'test_deploy_vps.py'`: 10 shell fake end-to-end tests passed. They include startup failure rollback, unhealthy rollback, repeated deploys, failed Compose SHA, unowned override, `COMPOSE_FILE` bypass, and service/volume drift; no real Docker daemon or VPS was used.
- The new shell tests were run before the remote script existed and failed on the missing contract; the same focused cases passed after implementation.
- Parsed the workflow with cached `gopkg.in/yaml.v3` using `GOPROXY=off`; the parser found three jobs and `workflow_dispatch`. No dependency was downloaded or added to the repository.
- `bash -n` passed for `scripts/deploy-vps.sh` and the GitHub deploy step.
- `docker compose --env-file <synthetic-temp-file> -f compose.yaml config --quiet`: passed. A temporary Compose project also confirmed the default `compose.override.yaml` lookup selects the pinned watcher digest without changing `.env`.
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

Configure repository variable `VPS_DEPLOY_DIR` as a non-root absolute path. That existing directory must contain the exact committed `compose.yaml`, the already-configured `.env`, and a `compose.override.yaml` in the workflow marker format, pinned to the currently healthy known-good digest. The first lock must be seeded by an operator from a verified existing digest. An unowned override is left untouched and blocks deployment. The VPS needs Bash, `sha256sum`, `base64`, Docker Compose with `up --wait --wait-timeout`, Docker permissions for `VPS_USER`, connectivity to `infra_network` and both PostgreSQL databases, and an existing GHCR package pull authorization if the image package is private.

## Repository state

Target checkout: branch `codex/evolution-poc`, remote `origin` at `hugoalves-dev/IA-hugo-do-zap`. The existing dirty `.superpowers/sdd/2026-10-26-03-ai-telegram-actions/progress.md`, `.idea/`, and `target/` were preserved. No push or live VPS operation was performed.
