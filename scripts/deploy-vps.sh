#!/usr/bin/env bash
set -e
set -euo pipefail

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

deploy_dir_b64="${VPS_DEPLOY_DIR_B64:?VPS_DEPLOY_DIR_B64 is required.}"
image_ref_b64="${IMAGE_REF_B64:?IMAGE_REF_B64 is required.}"
expected_compose_sha256="${COMPOSE_SHA256:?COMPOSE_SHA256 is required.}"
mode="${DEPLOY_MODE:?DEPLOY_MODE is required.}"

deploy_dir="$(printf '%s' "$deploy_dir_b64" | base64 -d)"
image_ref="$(printf '%s' "$image_ref_b64" | base64 -d)"
image_pattern='^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$'

if [[ ! "$deploy_dir" =~ ^/[A-Za-z0-9._/-]+$ || "$deploy_dir" == / || "$deploy_dir" == */ || "$deploy_dir" == *//* || "$deploy_dir" == */../* || "$deploy_dir" == */./* || "$deploy_dir" == */.. || "$deploy_dir" == */. ]]; then
  die "VPS_DEPLOY_DIR must be a safe, normalized absolute path."
fi
if [[ ! "$image_ref" =~ $image_pattern ]]; then
  die "IMAGE_REF must be a GHCR image pinned by SHA-256 digest."
fi
if [[ ! "$expected_compose_sha256" =~ ^[a-f0-9]{64}$ ]]; then
  die "COMPOSE_SHA256 must be a full SHA-256 value."
fi
if [[ "$mode" != deploy && "$mode" != bootstrap ]]; then
  die "MODE must be deploy or bootstrap."
fi

cd "$deploy_dir"
[[ -f compose.yaml ]] || die "compose.yaml is missing."
[[ -f .env ]] || die ".env is missing."
if [[ "$mode" == bootstrap ]]; then
  [[ ! -L compose.yaml && ! -L .env ]] || die "Bootstrap requires regular Compose and .env files inside VPS_DEPLOY_DIR."
  if [[ -L .watcher-deploy.lock || ( -e .watcher-deploy.lock && -s .watcher-deploy.lock ) ]]; then
    die "Bootstrap refuses a non-empty deployment lock file."
  fi
fi

exec 9>>"$PWD/.watcher-deploy.lock"
flock -n 9 || die "Another VPS deployment holds the exclusive lock."

if [[ -n "${COMPOSE_FILE:-}" ]] || grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_FILE[[:space:]]*[:=]' .env; then
  die "COMPOSE_FILE overrides are unsupported because they can bypass the managed image lock."
fi
if [[ -n "${COMPOSE_PROJECT_NAME:-}" ]] || grep -Eq '^[[:space:]]*(export[[:space:]]+)?COMPOSE_PROJECT_NAME[[:space:]]*[:=]' .env; then
  die "COMPOSE_PROJECT_NAME overrides are unsupported; the deploy directory defines the isolated project."
fi

compose_hash_output="$(sha256sum compose.yaml)"
actual_compose_sha256="${compose_hash_output%% *}"
if [[ "$actual_compose_sha256" != "$expected_compose_sha256" ]]; then
  die "Remote compose.yaml does not match the workflow commit."
fi

override_file=compose.override.yaml
candidate_file=.compose.override.yaml.candidate
journal_file=.watcher-deploy.journal
bootstrap_journal_file=.watcher-bootstrap.journal
previous_image_ref=""
bootstrap_project_name=""
journal_temp=""
override_temp=""
candidate_started=false
transaction_active=false
deployment_committed=false
bootstrap_candidate_started=false
bootstrap_transaction_active=false
bootstrap_committed=false

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

write_bootstrap_journal() {
  local phase="$1"
  local candidate="$2"

  journal_temp="$(mktemp "$PWD/.watcher-bootstrap.journal.next.XXXXXX")" || return 1
  if ! printf 'version=1\nphase=%s\ncandidate_image=%s\ncompose_sha256=%s\nproject_name=%s\n' \
    "$phase" "$candidate" "$expected_compose_sha256" "$bootstrap_project_name" > "$journal_temp"; then
    return 1
  fi
  chmod 0644 "$journal_temp" || return 1
  if ! mv -f -- "$journal_temp" "$bootstrap_journal_file"; then
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
  if [[ "$mode" == bootstrap ]]; then
    env -u WATCHER_IMAGE docker compose --env-file .env -p "$bootstrap_project_name" "$@"
  else
    env -u WATCHER_IMAGE docker compose --env-file .env "$@"
  fi
}

compose_candidate() {
  if [[ "$mode" == bootstrap ]]; then
    env -u WATCHER_IMAGE docker compose --env-file .env -p "$bootstrap_project_name" -f compose.yaml -f "$candidate_file" "$@"
  else
    env -u WATCHER_IMAGE docker compose --env-file .env -f compose.yaml -f "$candidate_file" "$@"
  fi
}

compose_bootstrap_base() {
  env -u WATCHER_IMAGE docker compose --env-file .env -p "$bootstrap_project_name" -f compose.yaml "$@"
}

require_compose_wait() {
  local help_output
  help_output="$(docker compose up --help)" || return 1
  [[ "$help_output" == *--wait* && "$help_output" == *--wait-timeout* ]]
}

validate_model() {
  local mode="$1"
  local expected_image="$2"
  local services volumes networks images=""

  if [[ "$mode" == default ]]; then
    services="$(compose_default config --services | LC_ALL=C sort)" || return 1
    volumes="$(compose_default config --volumes)" || return 1
    networks="$(compose_default config --networks | LC_ALL=C sort)" || return 1
    [[ -z "$expected_image" ]] || images="$(compose_default config --images watcher)" || return 1
  elif [[ "$mode" == candidate || "$mode" == bootstrap-candidate ]]; then
    services="$(compose_candidate config --services | LC_ALL=C sort)" || return 1
    volumes="$(compose_candidate config --volumes)" || return 1
    networks="$(compose_candidate config --networks | LC_ALL=C sort)" || return 1
    images="$(compose_candidate config --images watcher)" || return 1
  else
    services="$(compose_bootstrap_base config --services | LC_ALL=C sort)" || return 1
    volumes="$(compose_bootstrap_base config --volumes)" || return 1
    networks="$(compose_bootstrap_base config --networks | LC_ALL=C sort)" || return 1
  fi

  [[ "$services" == $'evolution-api\nwatcher' ]] || return 1
  grep -Fxq 'evolution_instances' <<< "$volumes" || return 1
  [[ "$networks" == infra_network ]] || return 1
  [[ -z "$expected_image" || "$images" == "$expected_image" ]]
}

container_is_healthy() {
  local mode="$1"
  local container_id running health

  if [[ "$mode" == candidate || "$mode" == bootstrap-candidate ]]; then
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

bootstrap_config_value() {
  local name="$1"
  local line
  line="$(grep -F "$name=" <<< "$bootstrap_config_environment" | tail -n 1)" || return 1
  [[ -n "$line" ]] || return 1
  printf '%s' "${line#*=}"
}

bootstrap_normalize_authority() {
  local authority="$1"
  local host port remainder host_address

  [[ -n "$authority" && "$authority" != *@* ]] || return 1
  if [[ "$authority" == \[*\]:* ]]; then
    remainder="${authority#\[}"
    host="[${remainder%%]*}]"
    port="${authority#*]:}"
  elif [[ "$authority" == \[*\] ]]; then
    host="$authority"
    port=5432
  elif [[ "$authority" == *:* ]]; then
    [[ "$authority" != *:*:* ]] || return 1
    host="${authority%:*}"
    port="${authority##*:}"
  else
    host="$authority"
    port=5432
  fi

  [[ -n "$host" && "$port" =~ ^[0-9]{1,5}$ ]] || return 1
  port=$((10#$port))
  (( port >= 1 && port <= 65535 )) || return 1
  if [[ "$host" == \[*\] ]]; then
    host_address="${host#\[}"
    host_address="${host_address%\]}"
    [[ "$host_address" =~ ^[A-Fa-f0-9:.]+$ ]] || return 1
    host="[$host_address]"
  else
    [[ "$host" =~ ^[A-Za-z0-9._-]+$ ]] || return 1
  fi
  host="${host,,}"
  host="${host%.}"
  [[ -n "$host" ]] || return 1
  bootstrap_normalized_authority="$host:$port"
}

validate_bootstrap_environment() {
  local name value spring_url spring_user spring_password evolution_url evolution_user evolution_userinfo evolution_password
  local spring_rest spring_authority spring_database evolution_rest evolution_authority_database evolution_authority evolution_database
  local spring_endpoint evolution_endpoint

  bootstrap_config_environment="$(compose_bootstrap_base config --environment 2>/dev/null)" || return 1
  for name in WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED; do
    value="$(bootstrap_config_value "$name")" || return 1
    [[ "$value" == false ]] || return 1
  done

  spring_url="$(bootstrap_config_value SPRING_DATASOURCE_URL)" || return 1
  spring_user="$(bootstrap_config_value SPRING_DATASOURCE_USERNAME)" || return 1
  spring_password="$(bootstrap_config_value SPRING_DATASOURCE_PASSWORD)" || return 1
  evolution_url="$(bootstrap_config_value EVOLUTION_DATABASE_URL)" || return 1
  [[ "$spring_url" == jdbc:postgresql://*/* && "$evolution_url" == postgresql://*@*/* ]] || return 1
  [[ "$spring_url" != *\?* && "$spring_url" != *\#* && "$evolution_url" != *\?* && "$evolution_url" != *\#* ]] || return 1

  spring_rest="${spring_url#jdbc:postgresql://}"
  spring_authority="${spring_rest%%/*}"
  spring_database="${spring_rest#*/}"
  [[ "$spring_database" != */* ]] || return 1
  evolution_rest="${evolution_url#postgresql://}"
  evolution_userinfo="${evolution_rest%%@*}"
  [[ "$evolution_userinfo" == *:* ]] || return 1
  evolution_user="${evolution_userinfo%%:*}"
  evolution_password="${evolution_userinfo#*:}"
  evolution_authority_database="${evolution_rest#*@}"
  evolution_authority="${evolution_authority_database%%/*}"
  evolution_database="${evolution_authority_database#*/}"
  [[ "$evolution_database" != */* ]] || return 1
  [[ -n "$spring_database" && -n "$spring_user" && -n "$spring_password" ]] || return 1
  [[ -n "$evolution_database" && -n "$evolution_user" && -n "$evolution_password" ]] || return 1
  [[ "$spring_database" =~ ^[A-Za-z0-9_.-]+$ && "$evolution_database" =~ ^[A-Za-z0-9_.-]+$ ]] || return 1
  [[ "$spring_user" =~ ^[A-Za-z0-9_.-]+$ && "$evolution_user" =~ ^[A-Za-z0-9_.-]+$ ]] || return 1
  bootstrap_normalize_authority "$spring_authority" || return 1
  spring_endpoint="$bootstrap_normalized_authority"
  bootstrap_normalize_authority "$evolution_authority" || return 1
  evolution_endpoint="$bootstrap_normalized_authority"
  [[ "$spring_endpoint" == "$evolution_endpoint" ]] || return 1
  [[ "${spring_database,,}" != "${evolution_database,,}" ]] || return 1
  [[ "${spring_user,,}" != "${evolution_user,,}" ]]
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

bootstrap_deploy() {
  local project_containers project_volumes project_networks existing_watchers
  local expected_volume network_name
  local config_environment

  bootstrap_project_name="${PWD##*/}"
  [[ "$PWD" == "$(pwd -P)" ]] || die "Bootstrap refuses a symlinked deployment directory."
  [[ "$bootstrap_project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || \
    die "Bootstrap requires a lowercase isolated Compose project directory name."
  if grep -Eq '^name:[[:space:]]*' compose.yaml; then
    die "Bootstrap requires the Compose project name to come from VPS_DEPLOY_DIR."
  fi

  if [[ -e "$override_file" || -L "$override_file" || -e "$journal_file" || -L "$journal_file" || \
        -e "$bootstrap_journal_file" || -L "$bootstrap_journal_file" || -e "$candidate_file" || -L "$candidate_file" ]]; then
    die "Bootstrap requires no existing Compose override, journal, or candidate file."
  fi

  project_containers="$(docker ps --all --quiet --filter "label=com.docker.compose.project=$bootstrap_project_name")" || \
    die "Cannot verify existing Docker containers for the bootstrap project."
  [[ -z "$project_containers" ]] || die "Bootstrap refuses an existing Docker Compose project."
  existing_watchers="$(docker ps --all --quiet --filter 'label=com.docker.compose.service=watcher')" || \
    die "Cannot verify existing watcher containers."
  [[ -z "$existing_watchers" ]] || die "Bootstrap refuses to replace an existing watcher container."
  existing_watchers="$(docker ps --all --quiet --filter 'name=watcher')" || \
    die "Cannot verify existing watcher-named containers."
  [[ -z "$existing_watchers" ]] || die "Bootstrap refuses to replace an existing watcher-named container."

  project_volumes="$(docker volume ls --quiet --filter "label=com.docker.compose.project=$bootstrap_project_name")" || \
    die "Cannot verify existing Docker volumes for the bootstrap project."
  [[ -z "$project_volumes" ]] || die "Bootstrap refuses to use an existing project volume."
  expected_volume="${bootstrap_project_name}_evolution_instances"
  if docker volume inspect "$expected_volume" >/dev/null 2>&1; then
    die "Bootstrap refuses an existing evolution_instances volume."
  fi

  project_networks="$(docker network ls --quiet --filter "label=com.docker.compose.project=$bootstrap_project_name")" || \
    die "Cannot verify existing Docker networks for the bootstrap project."
  [[ -z "$project_networks" ]] || die "Bootstrap refuses to use an existing project network."
  network_name="$(docker network inspect --format '{{.Name}}' infra_network)" || \
    die "The pre-existing infra_network is required for bootstrap."
  [[ "$network_name" == infra_network ]] || die "The required external infra_network could not be verified."

  validate_model bootstrap-base "" || die "Bootstrap Compose services, networks, or volumes failed validation."
  validate_bootstrap_environment || \
    die "Bootstrap requires real-send flags disabled and distinct PostgreSQL database identities and roles."
  require_compose_wait || die "Docker Compose must support up --wait and --wait-timeout."

  bootstrap_cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$bootstrap_transaction_active" == true && "$bootstrap_committed" != true ]]; then
      if [[ "$bootstrap_candidate_started" == true ]]; then
        printf '%s\n' "First bootstrap failed after watcher start; journal and candidate are retained for operator recovery." >&2
        status=1
      else
        rm -f -- "$bootstrap_journal_file" "$candidate_file"
      fi
    fi
    if [[ -n "$journal_temp" ]]; then rm -f -- "$journal_temp"; fi
    if [[ -n "$override_temp" ]]; then rm -f -- "$override_temp"; fi
    exit "$status"
  }
  trap bootstrap_cleanup EXIT

  bootstrap_transaction_active=true
  write_bootstrap_journal bootstrapping "$image_ref" || \
    die "Could not write the bootstrap journal; no service was started."
  write_override "$image_ref" "$candidate_file" || \
    die "Could not stage the first image lock; no service was started."
  validate_model bootstrap-candidate "$image_ref" || \
    die "Bootstrap candidate failed service, volume, or digest validation."
  validate_bootstrap_environment || \
    die "Bootstrap runtime environment no longer meets the isolation checks."

  compose_candidate pull --policy always watcher
  image_is_local "$image_ref" || die "Bootstrap candidate image is not available in the local Docker cache."
  bootstrap_candidate_started=true
  compose_candidate up -d --no-build --no-deps --pull never --wait --wait-timeout 180 watcher
  container_is_healthy bootstrap-candidate || die "Bootstrap watcher did not become healthy."

  write_bootstrap_journal promoting "$image_ref" || \
    die "Bootstrap health passed but promotion journal could not be recorded; candidate state is retained."
  mv -f -- "$candidate_file" "$override_file"
  candidate_file=""
  validate_model default "$image_ref" || die "Promoted bootstrap Compose model does not resolve to its digest."
  container_is_healthy default || die "Promoted bootstrap watcher failed its health check."
  write_bootstrap_journal committed "$image_ref" || \
    die "Bootstrap is healthy but commit journal update failed; operator recovery is required."
  rm -f -- "$bootstrap_journal_file"
  bootstrap_transaction_active=false
  bootstrap_committed=true
  printf '%s\n' "First watcher installation is healthy and pinned by digest."
}

if [[ "$mode" == bootstrap ]]; then
  if [[ -L "$bootstrap_journal_file" || -e "$bootstrap_journal_file" ]]; then
    die "A prior bootstrap journal requires operator recovery; no Docker resources were removed."
  fi
  if [[ -L "$journal_file" || -e "$journal_file" ]]; then
    die "A normal deployment journal exists; bootstrap is not allowed."
  fi
  if [[ -e "$override_file" || -L "$override_file" ]]; then
    die "Bootstrap refuses an existing Compose override."
  fi
  bootstrap_deploy
  exit 0
fi

if [[ -L "$bootstrap_journal_file" || -e "$bootstrap_journal_file" ]]; then
  die "A bootstrap journal requires operator recovery before normal deployment."
fi

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
