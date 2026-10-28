#!/bin/sh
set -eu

MARKETING_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

python3 "$MARKETING_ROOT/scripts/build_site.py"

wrangler pages deploy "$MARKETING_ROOT/public" --project-name spotted

if command -v indexnow >/dev/null 2>&1; then
  indexnow submit-sitemap whereday.com --recent-days 7 --confirm
else
  printf '%s\n' 'warning: indexnow CLI not found; production deployed without URL notification' >&2
fi
