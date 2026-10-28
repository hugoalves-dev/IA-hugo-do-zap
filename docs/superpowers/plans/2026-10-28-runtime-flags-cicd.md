# Runtime flags and CI/CD implementation plan

> Use superpowers:subagent-driven-development. Verify and push after each task.

**Goal:** Configure shadow and real WhatsApp delivery through `.env`, and provide separate CI, image-publish and VPS-deploy jobs modeled on JobApplyTracker.

**Architecture:** Existing safety gates and PostgreSQL outbox remain authoritative. Environment selects simulation and explicitly enables real production mode and transport; delivery ambiguity remains UNKNOWN. GitHub Actions validates Java, publishes an immutable image and offers a controlled SSH Compose deploy to the existing VPS.

**Spec:** `docs/superpower/spec/2026-10-16-archived-watcher-automation-design.md` and user request 2026-10-28.

## Constraints

- All real actions default disabled; no deploy, pairing or real send merely from opening a PR or pushing a feature branch.
- No secrets in repository, logs or chat. Do not assume HTTP 2xx confirms delivery. Keep UNKNOWN and no automatic resend.
- Preserve existing untracked files; targeted tests only; use Java 25 and existing PostgreSQL tests.
- Never remove unrelated volumes, images or containers. No global docker prune.
- Push commits to `origin/codex/evolution-poc` after each reviewed task.

### Task 1: Runtime modes

**Files:** `.env.example`, `compose.yaml`, `src/main/java/dev/hugo/watcher/notification/EvolutionActionTransportConfiguration.java`, related properties and focused tests, `README.md`.

- [ ] Add failing tests showing default OFF, shadow enabled/disabled, explicit real flags selecting the HTTP transport, missing credentials rejected, and ambiguous responses remaining UNKNOWN.
- [ ] Run the smallest focused tests and confirm RED.
- [ ] Wire explicit Spring environment variables via Compose and conditionally construct real transport only with explicit opt-in and valid config; document exact flags, interaction with consent, kill switch and delivery uncertainty.
- [ ] Run focused tests and `docker compose config --quiet` with synthetic nonsecret environment; compare results. Commit task files.

### Task 2: Separate CI/CD jobs

**Files:** `.github/workflows/backend.yml`, deployment Compose/docs as needed, focused validation.

- [ ] Verify current JobApplyTracker CI/CD shape, target branch/remotes and Docker build inputs; write focused checks for workflow job dependencies, PR no deploy, deploy only from explicitly selected main/ref, image SHA pinning, SSH host key verification, and no global prune.
- [ ] Run checks and confirm RED; implement test/build, GHCR publish and VPS deployment as separate dependent jobs using the repo's Java 25/Compose, artifact provenance and a configured deploy directory. Use existing secrets by name where applicable; never print values.
- [ ] Validate workflow syntax/expressions, Compose config with synthetic values, and focused checks. Commit task files.
- [ ] Report exact required GitHub variables/secrets and VPS preconditions. Do not claim a deployment unless execution and live health are observed.
