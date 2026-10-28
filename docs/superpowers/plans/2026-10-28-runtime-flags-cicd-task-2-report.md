# Task 2: CI/CD report

## Delivery

- Added `.github/workflows/backend.yml` with separate Java 25 test/build, GHCR publish, and VPS deploy jobs.
- Pull requests and feature-branch pushes run validation only. A `main` push can publish an image but cannot deploy. Deployment requires a manual `workflow_dispatch` on `main` with the `deploy` input set to `true`.
- Published images receive `latest` and full commit-SHA tags. Deploy consumes the registry digest output from that same publish job and attaches GitHub build provenance to GHCR.
- Added a `WATCHER_IMAGE` Compose override. The VPS job checks for the existing `.env` and `compose.yaml`, then pulls and updates only `watcher`; it does not rewrite `.env`, remove other services, prune images, or remove volumes/orphans.
- Added focused workflow contract checks and documented repository/VPS configuration in `README.md`.

## Validation

- `python -m unittest discover -s tests/ci -p 'test_backend_workflow.py'`: 5 tests passed. The checks were first run before adding the workflow and failed because the three jobs were absent.
- Parsed the workflow with cached `gopkg.in/yaml.v3` using `GOPROXY=off`; the parser found three jobs and `workflow_dispatch`. No dependency was downloaded or added to the repository.
- Extracted the deployment `run` block and ran `bash -n` against it, including its embedded remote script: passed.
- `docker compose --env-file <synthetic-temp-file> -f compose.yaml config --quiet`: passed. Rendered configuration retained services `watcher`, `evolution-api`, `redis`, volume `evolution_instances`, networks `infra_network` and `evolution_backend`, and the expected watcher image fallback.
- `actionlint` is not installed, so its GitHub Actions expression diagnostics were unavailable. The focused contract checks cover job dependencies, event gates, deploy input, digest selection, SSH trust, and cleanup constraints.
- No Maven suite, image build/publish, VPS connection, deployment, health check, pairing, or real send was run.

## Configuration required before a deploy

Read-only GitHub queries found no configured repository Actions secrets, variables, or self-hosted runners. The workflow uses GitHub-hosted `ubuntu-latest` runners and the built-in `GITHUB_TOKEN` for publishing.

Configure these repository secrets:

- `SSH_KEY`
- `SSH_PORT`
- `VPS_IP`
- `VPS_USER`
- `VPS_KNOWN_HOSTS` — verified host-key line for the VPS address and port; the workflow enforces `StrictHostKeyChecking=yes`.

Configure repository variable `VPS_DEPLOY_DIR` as a non-root absolute path. That existing directory must contain the reviewed `compose.yaml` with the `WATCHER_IMAGE` override and the already-configured `.env`. The VPS needs Docker Compose v2, Docker permissions for `VPS_USER`, connectivity to `infra_network` and both PostgreSQL databases, and an existing GHCR package pull authorization if the image package is private.

## Repository state

Target checkout: branch `codex/evolution-poc`, remote `origin` at `hugoalves-dev/IA-hugo-do-zap`. The existing dirty `.superpowers/sdd/2026-10-26-03-ai-telegram-actions/progress.md`, `.idea/`, and `target/` were preserved. No push or live VPS operation was performed.
