#!/usr/bin/env bash
set -euo pipefail

# Run from any directory. Secrets are read by Spring from the ignored root .env.
cd "$(dirname "$0")/.."
profile="${1:-mariadb}"
case "$profile" in
  local|mariadb) ;;
  *) echo 'Usage: bash scripts/seed-demo.sh [mariadb|local]' >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
  echo 'Usage: bash scripts/seed-demo.sh [mariadb|local]' >&2
  exit 2
fi
printf 'Starting backend with %s,full-demo (existing demo data is preserved).\n' "$profile"
printf 'Configure app.full-demo.password and bootstrap administrator in .env first.\n'
exec ./gradlew bootRun --args="--spring.profiles.active=$profile,full-demo"
