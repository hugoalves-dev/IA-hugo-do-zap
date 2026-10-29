#!/bin/sh
set -eu

: "${DEPLOY_SCRIPT_B64:?DEPLOY_SCRIPT_B64 is required.}"
script_file="$(mktemp "${TMPDIR:-/tmp}/watcher-deploy.XXXXXX")"

cleanup() {
  status=$?
  trap - EXIT
  rm -f -- "$script_file" || status=1
  exit "$status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

printf '%s' "$DEPLOY_SCRIPT_B64" | base64 -d > "$script_file"
bash "$script_file"
