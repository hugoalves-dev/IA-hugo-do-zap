#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'Deploy: %s\n' "$1" >&2; exit 1; }
deploy_dir=$(printf '%s' "${VPS_DEPLOY_DIR_B64:?missing deploy dir}" | base64 -d)
image=$(printf '%s' "${IMAGE_REF_B64:?missing image digest}" | base64 -d)
expected=${COMPOSE_SHA256:?missing Compose SHA-256}
compose_encoded=${COMPOSE_CONTENT_B64:?missing Compose content}

# Reject invalid paths and image input before any Docker or filesystem mutation.
[[ "$deploy_dir" =~ ^/[A-Za-z0-9._/-]+$ ]] || fail "Unsafe deploy path"
[[ "$deploy_dir" != / && "$deploy_dir" != */ && "$deploy_dir" != *//* &&
   "$deploy_dir" != */../* && "$deploy_dir" != */./* &&
   "$deploy_dir" != */.. && "$deploy_dir" != */. ]] || fail "Unsafe deploy path"
[[ "$image" =~ ^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$ ]] || fail "Invalid immutable image"
[[ "$expected" =~ ^[a-f0-9]{64}$ ]] || fail "Invalid Compose SHA-256"

cd "$deploy_dir"
[[ -f .env && ! -L .env && -f compose.yaml && ! -L compose.yaml ]] || fail "Missing regular .env or compose.yaml"
[[ ! -L .watcher-deploy.lock ]] || fail "Deployment lock cannot be a symlink"
[[ -z "${COMPOSE_FILE:-}" && -z "${COMPOSE_PROJECT_NAME:-}" ]] || fail "Unsupported Compose environment overrides"
if grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_(FILE|PROJECT_NAME)[[:space:]]*[:=]' .env; then
  fail "COMPOSE_FILE/COMPOSE_PROJECT_NAME in .env are not supported"
fi
umask 077
exec 9>>.watcher-deploy.lock
flock -w 120 9 || fail "Another deployment is using the watcher"

# The previous installer left candidate/journal files after a failed bootstrap.
# Verify that these belong to this project, then archive them ONLY after a healthy deploy.
legacy=false
if [[ -e .watcher-bootstrap.journal || -L .watcher-bootstrap.journal ||
      -e .compose.override.yaml.candidate || -L .compose.override.yaml.candidate ]]; then
  [[ -f .watcher-bootstrap.journal && ! -L .watcher-bootstrap.journal &&
     -f .compose.override.yaml.candidate && ! -L .compose.override.yaml.candidate ]] ||
    fail "Incomplete legacy bootstrap; review files without deleting them"
  grep -Fxq 'version=1' .watcher-bootstrap.journal || fail "Unknown legacy bootstrap journal"
  grep -Eq '^phase=(bootstrapping|promoting|committed)$' .watcher-bootstrap.journal || fail "Unknown bootstrap phase"
  grep -Fxq "project_name=$(basename "$PWD")" .watcher-bootstrap.journal || fail "Bootstrap belongs to another Compose project"
  grep -Fxq '# Managed by backend deploy workflow; do not edit.' .compose.override.yaml.candidate ||
    fail "Unrecognized legacy candidate"
  grep -Eq '^    image: ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$' .compose.override.yaml.candidate ||
    fail "Legacy candidate has no immutable image"
  legacy=true
fi
if [[ -e .watcher-deploy.journal || -L .watcher-deploy.journal ]]; then
  [[ -f .watcher-deploy.journal && ! -L .watcher-deploy.journal ]] || fail "Unrecognized old deploy journal"
  grep -Fxq 'version=1' .watcher-deploy.journal || fail "Unrecognized old deploy journal"
  grep -Eq '^phase=(deploying|promoting|committed)$' .watcher-deploy.journal || fail "Unrecognized old deploy phase"
  legacy=true
fi

# Refuse to overwrite hand-maintained overrides or files modified outside this workflow.
old_override=false
if [[ -e compose.override.yaml || -L compose.override.yaml ]]; then
  [[ -f compose.override.yaml && ! -L compose.override.yaml ]] || fail "Invalid compose.override.yaml"
  [[ "$(wc -l < compose.override.yaml)" -eq 5 ]] || fail "Unmanaged compose.override.yaml"
  grep -Fxq '# Managed by backend deploy workflow; do not edit.' compose.override.yaml || fail "Unmanaged override"
  grep -Fxq 'services:' compose.override.yaml || fail "Unmanaged override"
  grep -Fxq '  watcher:' compose.override.yaml || fail "Unmanaged override"
  grep -Eq '^    image: ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$' compose.override.yaml || fail "Unmanaged override image"
  grep -Fxq '    pull_policy: never' compose.override.yaml || fail "Unmanaged override policy"
  old_override=true
fi

incoming=$(mktemp "$PWD/.watcher-compose.incoming.XXXXXX")
previous=$(mktemp "$PWD/.watcher-compose.previous.XXXXXX")
old_override_file=$(mktemp "$PWD/.watcher-override.previous.XXXXXX")
new_override=$(mktemp "$PWD/.watcher-override.new.XXXXXX")
trap 'rm -f -- "$incoming" "$previous" "$old_override_file" "$new_override"' EXIT
printf '%s' "$compose_encoded" | base64 -d > "$incoming" || fail "Invalid Compose payload"
[[ "$(sha256sum "$incoming" | cut -d ' ' -f1)" == "$expected" ]] || fail "Compose payload hash mismatch"
current=$(sha256sum compose.yaml | cut -d ' ' -f1)
if [[ -e .watcher-deployed-compose.sha256 || -L .watcher-deployed-compose.sha256 ]]; then
  [[ -f .watcher-deployed-compose.sha256 && ! -L .watcher-deployed-compose.sha256 ]] ||
    fail "Invalid Compose ownership marker"
  [[ "$(cat .watcher-deployed-compose.sha256)" == "$current" ]] ||
    fail "compose.yaml was modified outside the deploy workflow"
elif [[ "$current" != "$expected" ]]; then
  fail "First deploy requires the existing VPS compose.yaml to match Git before automatic updates"
fi
cp -p compose.yaml "$previous"
if [[ "$old_override" == true ]]; then cp -p compose.override.yaml "$old_override_file"; fi

# Rollback is attempted only when the previous container was actually healthy.
was_healthy=false
previous_id=$(docker compose --env-file .env ps -q watcher 2>/dev/null || true)
if [[ -n "$previous_id" &&
  "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$previous_id" 2>/dev/null || true)" == healthy ]]; then
  was_healthy=true
fi

printf '# Managed by backend deploy workflow; do not edit.\nservices:\n  watcher:\n    image: %s\n    pull_policy: never\n' "$image" > "$new_override"
# Pull first so a missing GHCR image cannot modify a running service.
docker pull "$image" || fail "Could not pull approved image"
if [[ "$current" != "$expected" ]]; then mv -f -- "$incoming" compose.yaml; fi
mv -f -- "$new_override" compose.override.yaml

if ! (docker compose --env-file .env config --quiet &&
      docker compose --env-file .env up -d --no-deps --pull never --wait --wait-timeout 240 watcher); then
  printf 'Deploy: watcher failed health check; restoring previous Compose configuration\n' >&2
  cp -p "$previous" compose.yaml
  if [[ "$old_override" == true ]]; then
    cp -p "$old_override_file" compose.override.yaml
  else
    rm -f -- compose.override.yaml
  fi
  if [[ "$was_healthy" == true ]]; then
    docker compose --env-file .env up -d --no-deps --pull never --wait --wait-timeout 240 watcher ||
      printf 'Deploy: rollback also failed; inspect watcher logs\n' >&2
  fi
  fail "Watcher did not become healthy; PostgreSQL, Evolution and volumes were untouched"
fi

# Successful application means a new push can always deploy without manual dispatch.
marker_tmp=$(mktemp "$PWD/.watcher-compose.marker.XXXXXX")
printf '%s\n' "$expected" > "$marker_tmp"
mv -f -- "$marker_tmp" .watcher-deployed-compose.sha256
if [[ "$legacy" == true ]]; then
  archive=$(mktemp -d "$PWD/.watcher-legacy-recovered.XXXXXX")
  for item in .watcher-bootstrap.journal .compose.override.yaml.candidate .watcher-deploy.journal; do
    if [[ -f "$item" && ! -L "$item" ]]; then mv -- "$item" "$archive/"; fi
  done
  echo "Deploy: archived previously failed bootstrap metadata at $archive"
fi
echo "Deploy: watcher is healthy, pinned to $image"
