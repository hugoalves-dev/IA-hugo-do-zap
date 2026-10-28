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

if [[ "$deploy_dir" != /* || "$deploy_dir" == / ]]; then
  die "VPS_DEPLOY_DIR must be a non-root absolute path."
fi
if [[ ! "$image_ref" =~ ^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$ ]]; then
  die "IMAGE_REF must be a GHCR image pinned by SHA-256 digest."
fi
if [[ ! "$expected_compose_sha256" =~ ^[a-f0-9]{64}$ ]]; then
  die "COMPOSE_SHA256 must be a full SHA-256 value."
fi

cd "$deploy_dir"
[[ -f compose.yaml ]] || die "compose.yaml is missing."
[[ -f .env ]] || die ".env is missing."
if [[ -n "${COMPOSE_FILE:-}" ]] || grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_FILE[[:space:]]*[:=]' .env; then
  die "COMPOSE_FILE overrides are unsupported because they can bypass the managed image lock."
fi

compose_hash_output="$(sha256sum compose.yaml)"
actual_compose_sha256="${compose_hash_output%% *}"
if [[ "$actual_compose_sha256" != "$expected_compose_sha256" ]]; then
  die "Remote compose.yaml does not match the workflow commit."
fi

override_file=compose.override.yaml
if [[ ! -f "$override_file" || -L "$override_file" ]]; then
  die "A workflow-managed compose.override.yaml with the previous digest is required."
fi

mapfile -t override_lines < "$override_file"
for line_index in "${!override_lines[@]}"; do
  override_lines[$line_index]="${override_lines[$line_index]%$'\r'}"
done
if (( ${#override_lines[@]} != 5 )) || \
  [[ "${override_lines[0]}" != "# Managed by backend deploy workflow; do not edit." ]] || \
  [[ "${override_lines[1]}" != "services:" ]] || \
  [[ "${override_lines[2]}" != "  watcher:" ]] || \
  [[ "${override_lines[3]}" != "    image: "* ]] || \
  [[ "${override_lines[4]}" != "    pull_policy: always" ]]; then
  die "Existing compose.override.yaml is not owned by this workflow; it was preserved."
fi

previous_image_ref="${override_lines[3]#    image: }"
image_name="${image_ref%@sha256:*}"
if [[ ! "$previous_image_ref" =~ ^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$ ]] || \
  [[ "${previous_image_ref%@sha256:*}" != "$image_name" ]]; then
  die "Existing Compose override does not contain a previous digest for this image."
fi

compose_default() {
  env -u WATCHER_IMAGE docker compose --env-file .env "$@"
}

compose_candidate() {
  env -u WATCHER_IMAGE docker compose --env-file .env -f compose.yaml -f "$candidate_file" "$@"
}

validate_model() {
  local mode="$1"
  local expected_image="$2"
  local services volumes images

  if [[ "$mode" == default ]]; then
    services="$(compose_default config --services | LC_ALL=C sort)"
    volumes="$(compose_default config --volumes)"
    images="$(compose_default config --images watcher)"
  else
    services="$(compose_candidate config --services | LC_ALL=C sort)"
    volumes="$(compose_candidate config --volumes)"
    images="$(compose_candidate config --images watcher)"
  fi

  if [[ "$services" != $'evolution-api\nredis\nwatcher' ]]; then
    die "Compose service names differ from the reviewed deployment model."
  fi
  if ! grep -Fxq 'evolution_instances' <<< "$volumes"; then
    die "Compose no longer declares the evolution_instances volume."
  fi
  if [[ "$images" != "$expected_image" ]]; then
    die "Compose watcher image does not match the expected digest."
  fi
}

compose_supports_wait="$(docker compose up --help)"
if [[ "$compose_supports_wait" != *--wait* || "$compose_supports_wait" != *--wait-timeout* ]]; then
  die "Docker Compose must support up --wait and --wait-timeout."
fi

validate_model default "$previous_image_ref"

candidate_file=""
backup_file=""
candidate_started=false
deployment_committed=false

container_is_healthy() {
  local mode="$1"
  local container_id running health

  if [[ "$mode" == candidate ]]; then
    container_id="$(compose_candidate ps -q watcher)"
  else
    container_id="$(compose_default ps -q watcher)"
  fi
  [[ -n "$container_id" ]] || { printf '%s\n' "watcher container was not found" >&2; return 1; }

  running="$(docker inspect --format '{{.State.Running}}' "$container_id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")"
  if [[ "$running" != true || "$health" != healthy ]]; then
    printf 'watcher is not ready (running=%s, health=%s)\n' "$running" "$health" >&2
    return 1
  fi
}

cleanup() {
  local status=$?
  local restored=false
  local override_restored=false
  trap - EXIT

  if [[ "$candidate_started" == true && "$deployment_committed" != true ]]; then
    printf '%s\n' "Deployment failed; restoring the previous image digest." >&2
    if [[ -n "${backup_file:-}" && -f "$backup_file" ]]; then
      if mv -f -- "$backup_file" "$override_file"; then
        backup_file=""
        override_restored=true
      else
        printf '%s\n' "Could not restore the previous Compose override." >&2
      fi
    fi
    if [[ "$override_restored" != true ]]; then
      printf '%s\n' "Rollback stopped because the previous Compose override is unavailable." >&2
    elif ! compose_default up -d --no-build --no-deps --wait --wait-timeout 180 watcher; then
      printf '%s\n' "Rollback command failed; inspect watcher before another deployment." >&2
    elif container_is_healthy default; then
      restored=true
      printf '%s\n' "Previous image is healthy again." >&2
    else
      printf '%s\n' "Rollback did not restore a healthy watcher." >&2
    fi
    if [[ "$restored" != true ]]; then
      status=1
    fi
  fi

  if [[ -n "${candidate_file:-}" ]]; then
    rm -f -- "$candidate_file"
  fi
  if [[ -n "${backup_file:-}" ]]; then
    rm -f -- "$backup_file"
  fi
  exit "$status"
}
trap cleanup EXIT

candidate_file="$(mktemp "$PWD/.compose.override.yaml.next.XXXXXX")"
backup_file="$(mktemp "$PWD/.compose.override.yaml.previous.XXXXXX")"
cp -p -- "$override_file" "$backup_file"
printf '# Managed by backend deploy workflow; do not edit.\nservices:\n  watcher:\n    image: %s\n    pull_policy: always\n' "$image_ref" > "$candidate_file"
chmod 0644 "$candidate_file"

validate_model candidate "$image_ref"
compose_candidate pull watcher
candidate_started=true
compose_candidate up -d --no-build --no-deps --wait --wait-timeout 180 watcher
container_is_healthy candidate

mv -f -- "$candidate_file" "$override_file"
candidate_file=""
deployment_committed=true
printf '%s\n' "Deployment healthy; default Compose configuration now pins the approved digest."
