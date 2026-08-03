#!/usr/bin/env bash

set -Eeuo pipefail

maintenance_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  printf '%s\n' \
    "usage: life-agent-maintenance backup [options]" \
    "       life-agent-maintenance restore [options]"
}

case "${1:-}" in
  backup)
    shift
    exec "$maintenance_root/bin/backup.sh" "$@"
    ;;
  restore)
    shift
    exec "$maintenance_root/bin/restore.sh" "$@"
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
