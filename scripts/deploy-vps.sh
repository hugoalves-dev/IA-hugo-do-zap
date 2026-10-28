#!/usr/bin/env bash
set -euo pipefail

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

if (( $# != 3 )); then
  die "Expected encoded deploy directory, image digest, and Compose SHA-256."
fi

deploy_dir="$(printf '%s' "$1" | base64 -d)"
image_ref="$(printf '%s' "$2" | base64 -d)"
expected_compose_sha256="$3"
image_pattern='^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$'

if [[ "$deploy_dir" != /* || "$deploy_dir" == / ]]; then
  die "VPS_DEPLOY_DIR must be a non-root absolute path."
fi
if [[ ! "$image_ref" =~ $image_pattern ]]; then
  die "IMAGE_REF must be a GHCR image pinned by SHA-256 digest."
fi
if [[ ! "$expected_compose_sha256" =~ ^[a-f0-9]{64}$ ]]; then
  die "COMPOSE_SHA256 must be a full SHA-256 value."
fi

cd "$deploy_dir"
[[ -f compose.yaml ]] || die "compose.yaml is missing."
[[ -f .env ]] || die ".env is missing."

exec 9>>"$PWD/.watcher-deploy.lock"
flock -n 9 || die "Another VPS deployment holds the exclusive lock."

if [[ -n "${COMPOSE_FILE:-}" ]] || grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_FILE[[:space:]]*[:=]' .env; then
  die "COMPOSE_FILE overrides are unsupported because they can bypass the managed image lock."
fi

compose_hash_output="$(sha256sum compose.yaml)"
actual_compose_sha256="${compose_hash_output%% *}"
if [[ "$actual_compose_sha256" != "$expected_compose_sha256" ]]; then
  die "Remote compose.yaml does not match the workflow commit."
fi

override_file=compose.override.yaml
candidate_file=.compose.override.yaml.candidate
journal_file=.watcher-deploy.journal
previous_image_ref=""
journal_temp=""
override_temp=""
candidate_started=false
transaction_active=false
deployment_committed=false

render_override() {
  printf '# Managed by backend deploy workflow; do not edit.\nservices:\n  watcher:\n    image: %s\n    pull_policy: never\n' "$1"
}

read_managed_override() {
  local file="$1"
  local line_index
  local -a lines

  [[ -f "$file" && ! -L "$file" ]] || return 1
  mapfile -t lines < "$file"
  for line_index in "${!lines[@]}"; do
    lines[$line_index]="${lines[$line_index]%$'\r'}"
  done
  (( ${#lines[@]} == 5 )) || return 1
  [[ "${lines[0]}" == "# Managed by backend deploy workflow; do not edit." ]] || return 1
  [[ "${lines[1]}" == "services:" ]] || return 1
  [[ "${lines[2]}" == "  watcher:" ]] || return 1
  [[ "${lines[3]}" == "    image: "* ]] || return 1
  [[ "${lines[4]}" == "    pull_policy: never" ]] || return 1

  local image="${lines[3]#    image: }"
  [[ "$image" =~ $image_pattern ]] || return 1
  printf '%s\n' "$image"
}

write_override() {
  local image="$1"
  local destination="$2"

  override_temp="$(mktemp "$PWD/.compose.override.yaml.next.XXXXXX")" || return 1
  if ! render_override "$image" > "$override_temp"; then
    return 1
  fi
  chmod 0644 "$override_temp" || return 1
  if ! mv -f -- "$override_temp" "$destination"; then
    return 1
  fi
  override_temp=""
}

write_journal() {
  local phase="$1"
  local previous="$2"
  local candidate="$3"

  journal_temp="$(mktemp "$PWD/.watcher-deploy.journal.next.XXXXXX")" || return 1
  if ! printf 'version=1\nphase=%s\nprevious_image=%s\ncandidate_image=%s\ncompose_sha256=%s\n' \
    "$phase" "$previous" "$candidate" "$expected_compose_sha256" > "$journal_temp"; then
    return 1
  fi
  chmod 0644 "$journal_temp" || return 1
  if ! mv -f -- "$journal_temp" "$journal_file"; then
    return 1
  fi
  journal_temp=""
}

read_journal() {
  local line_index
  local -a lines

  [[ -f "$journal_file" && ! -L "$journal_file" ]] || return 1
  mapfile -t lines < "$journal_file"
  for line_index in "${!lines[@]}"; do
    lines[$line_index]="${lines[$line_index]%$'\r'}"
  done
  (( ${#lines[@]} == 5 )) || return 1
  [[ "${lines[0]}" == "version=1" ]] || return 1

  journal_phase="${lines[1]#phase=}"
  [[ "${lines[1]}" == "phase=$journal_phase" ]] || return 1
  [[ "$journal_phase" == deploying || "$journal_phase" == promoting || "$journal_phase" == committed ]] || return 1

  journal_previous="${lines[2]#previous_image=}"
  [[ "${lines[2]}" == "previous_image=$journal_previous" && "$journal_previous" =~ $image_pattern ]] || return 1
  journal_candidate="${lines[3]#candidate_image=}"
  [[ "${lines[3]}" == "candidate_image=$journal_candidate" && "$journal_candidate" =~ $image_pattern ]] || return 1
  journal_compose_sha256="${lines[4]#compose_sha256=}"
  [[ "${lines[4]}" == "compose_sha256=$journal_compose_sha256" && "$journal_compose_sha256" =~ ^[a-f0-9]{64}$ ]] || return 1

  [[ "${journal_previous%@sha256:*}" == "${image_ref%@sha256:*}" ]] || return 1
  [[ "${journal_candidate%@sha256:*}" == "${image_ref%@sha256:*}" ]] || return 1
}

compose_default() {
  env -u WATCHER_IMAGE docker compose --env-file .env "$@"
}

compose_candidate() {
  env -u WATCHER_IMAGE docker compose --env-file .env -f compose.yaml -f "$candidate_file" "$@"
}

require_compose_wait() {
  local help_output
  help_output="$(docker compose up --help)" || return 1
  [[ "$help_output" == *--wait* && "$help_output" == *--wait-timeout* ]]
}

validate_model() {
  local mode="$1"
  local expected_image="$2"
  local services volumes images

  if [[ "$mode" == default ]]; then
    services="$(compose_default config --services | LC_ALL=C sort)" || return 1
    volumes="$(compose_default config --volumes)" || return 1
    images="$(compose_default config --images watcher)" || return 1
  else
    services="$(compose_candidate config --services | LC_ALL=C sort)" || return 1
    volumes="$(compose_candidate config --volumes)" || return 1
    images="$(compose_candidate config --images watcher)" || return 1
  fi

  [[ "$services" == $'evolution-api\nredis\nwatcher' ]] || return 1
  grep -Fxq 'evolution_instances' <<< "$volumes" || return 1
  [[ "$images" == "$expected_image" ]]
}

container_is_healthy() {
  local mode="$1"
  local container_id running health

  if [[ "$mode" == candidate ]]; then
    container_id="$(compose_candidate ps -q watcher)" || return 1
  else
    container_id="$(compose_default ps -q watcher)" || return 1
  fi
  [[ -n "$container_id" ]] || { printf '%s\n' "watcher container was not found" >&2; return 1; }

  running="$(docker inspect --format '{{.State.Running}}' "$container_id")" || return 1
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")" || return 1
  if [[ "$running" != true || "$health" != healthy ]]; then
    printf 'watcher is not ready (running=%s, health=%s)\n' "$running" "$health" >&2
    return 1
  fi
}

image_is_local() {
  docker image inspect "$1" >/dev/null 2>&1
}

remove_journal_candidates() {
  local candidate_image="$1"
  if [[ -e "$candidate_file" || -L "$candidate_file" ]]; then
    local staged_image
    staged_image="$(read_managed_override "$candidate_file")" || return 1
    [[ "$staged_image" == "$candidate_image" ]] || return 1
    rm -f -- "$candidate_file" || return 1
  fi
  rm -f -- "$journal_file"
}

reconcile_interrupted_deployment() {
  local current_image

  if ! read_journal; then
    die "Deployment journal is malformed; inspect it and Compose state manually before retrying."
  fi
  if [[ "$journal_compose_sha256" != "$expected_compose_sha256" ]]; then
    die "Interrupted deployment belongs to a different Compose commit; reconcile it manually before retrying."
  fi
  current_image="$(read_managed_override "$override_file")" || \
    die "Interrupted deployment has an unowned Compose override; no files or services were changed."
  if [[ "$current_image" != "$journal_previous" && "$current_image" != "$journal_candidate" ]]; then
    die "Interrupted deployment state differs from its journal; reconcile Compose manually before retrying."
  fi
  if [[ -e "$candidate_file" || -L "$candidate_file" ]]; then
    local staged_image
    staged_image="$(read_managed_override "$candidate_file")" || \
      die "Interrupted candidate override is not workflow-owned; inspect it manually."
    [[ "$staged_image" == "$journal_candidate" ]] || \
      die "Interrupted candidate override differs from its journal; inspect it manually."
  fi
  require_compose_wait || \
    die "Docker Compose must support up --wait and --wait-timeout for journal recovery."

  if [[ "$journal_phase" == committed && "$current_image" == "$journal_candidate" ]] && \
    image_is_local "$journal_candidate" && validate_model default "$journal_candidate" && container_is_healthy default; then
    remove_journal_candidates "$journal_candidate" || \
      die "Committed deployment is healthy but journal cleanup failed; inspect the hidden candidate state."
    die "Interrupted deployment was already committed and healthy; verify it, then rerun this workflow if another deploy is needed."
  fi

  image_is_local "$journal_previous" || \
    die "Previous image digest is not cached; restore that image locally before journal recovery."
  write_override "$journal_previous" "$override_file" || \
    die "Could not restore previous image lock; journal retained for manual recovery."
  validate_model default "$journal_previous" || \
    die "Previous Compose model is invalid; journal retained for manual recovery."
  if ! compose_default up -d --no-build --no-deps --pull never --wait --wait-timeout 180 watcher; then
    die "Could not restore the previous digest without registry access; journal retained."
  fi
  container_is_healthy default || \
    die "Previous digest rollback is not healthy; journal retained for manual recovery."
  remove_journal_candidates "$journal_candidate" || \
    die "Previous digest is healthy but interrupted state cleanup failed."
  die "Interrupted deployment was reconciled to the previous healthy digest; inspect the service and rerun the workflow."
}

if [[ -L "$journal_file" ]]; then
  die "Deployment journal is a symlink; inspect it manually before retrying."
fi
if [[ -e "$journal_file" ]]; then
  reconcile_interrupted_deployment
fi

if [[ -e "$candidate_file" || -L "$candidate_file" ]]; then
  die "An orphan candidate override exists without a journal; inspect it manually before retrying."
fi
if [[ ! -f "$override_file" || -L "$override_file" ]]; then
  die "A workflow-managed compose.override.yaml with the previous digest is required."
fi

previous_image_ref="$(read_managed_override "$override_file")" || \
  die "Existing compose.override.yaml is not workflow-owned; it was preserved."
if [[ "${previous_image_ref%@sha256:*}" != "${image_ref%@sha256:*}" ]]; then
  die "Existing Compose override does not contain a previous digest for this image."
fi
image_is_local "$previous_image_ref" || \
  die "Previous image digest is not cached locally; refusing deploy without registry-independent rollback."
require_compose_wait || die "Docker Compose must support up --wait and --wait-timeout."
validate_model default "$previous_image_ref" || \
  die "Compose service, volume, or pinned image model differs from the reviewed deployment."

cleanup() {
  local status=$?
  local restored=false
  trap - EXIT

  if [[ "$transaction_active" == true && "$deployment_committed" != true ]]; then
    if [[ "$candidate_started" == true ]]; then
      printf '%s\n' "Deployment failed; restoring the previous cached image digest." >&2
      if image_is_local "$previous_image_ref" && \
        write_override "$previous_image_ref" "$override_file" && \
        validate_model default "$previous_image_ref" && \
        compose_default up -d --no-build --no-deps --pull never --wait --wait-timeout 180 watcher && \
        container_is_healthy default; then
        restored=true
        rm -f -- "$journal_file"
        printf '%s\n' "Previous image is healthy again." >&2
      else
        printf '%s\n' "Rollback failed; journal retained for fail-closed recovery." >&2
        status=1
      fi
    else
      rm -f -- "$journal_file"
    fi
    if [[ "$restored" == true || "$candidate_started" != true ]]; then
      if [[ -e "$candidate_file" || -L "$candidate_file" ]]; then
        rm -f -- "$candidate_file"
      fi
    fi
  fi

  if [[ -n "$journal_temp" ]]; then rm -f -- "$journal_temp"; fi
  if [[ -n "$override_temp" ]]; then rm -f -- "$override_temp"; fi
  exit "$status"
}
trap cleanup EXIT

transaction_active=true
write_journal deploying "$previous_image_ref" "$image_ref" || \
  die "Could not persist deployment journal; no service update was attempted."
write_override "$image_ref" "$candidate_file" || \
  die "Could not stage the candidate override; no service update was attempted."
validate_model candidate "$image_ref" || \
  die "Candidate Compose model failed service, volume, or digest checks."
compose_candidate pull --policy always watcher
image_is_local "$image_ref" || \
  die "Candidate digest was not cached after pull; no service update was attempted."

candidate_started=true
compose_candidate up -d --no-build --no-deps --pull never --wait --wait-timeout 180 watcher
container_is_healthy candidate || die "Candidate watcher did not become healthy."

write_journal promoting "$previous_image_ref" "$image_ref" || \
  die "Could not persist promotion phase; the previous digest will be restored."
mv -f -- "$candidate_file" "$override_file"
candidate_file=""
validate_model default "$image_ref" || \
  die "Promoted default Compose model does not resolve to the candidate digest."
container_is_healthy default || die "Promoted watcher failed its health check."
write_journal committed "$previous_image_ref" "$image_ref" || \
  die "Could not persist commit phase; the previous digest will be restored."
rm -f -- "$journal_file"
transaction_active=false
deployment_committed=true
printf '%s\n' "Deployment healthy; default Compose configuration now pins the approved digest."
