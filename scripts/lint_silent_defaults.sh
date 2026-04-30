#!/usr/bin/env bash
# lint_silent_defaults.sh — CI gate for silent-fallback anti-patterns
#
# Detects new introductions of `?? EnumName.value` in Dart parser code
# (fromRaw, fromJson, fromMap factories). These patterns silently mask
# unrecognized input by substituting a fixed enum default, which hides
# data corruption downstream (see: HEIC MIME fallback PR #73,
# MmsMessageType off-by-N PR #63, objectType→contacts PR #146).
#
# Legitimate uses of `?? Enum.value` (API parameter defaults, DI, clock
# injection) can be whitelisted with `// ignore: silent_default` on the
# same line.
#
# Usage:
#   ./scripts/lint_silent_defaults.sh          # check lib/
#   ./scripts/lint_silent_defaults.sh src/foo/  # check a subdirectory
#
# Exit codes:
#   0 — no violations found
#   1 — violations found (prints file:line for each)

set -euo pipefail

TARGET="${1:-lib/}"
VIOLATIONS=0

# Pattern: `?? SomeEnum.someValue` — a null-coalesce to a specific enum
# member. We match PascalCase.camelCase to catch enum references while
# skipping `?? ''`, `?? 0`, `?? false`, `?? const []`, etc.
#
# The grep is intentionally broad; the ignore-comment filter narrows it.
PATTERN='\?\?\s*[A-Z][a-zA-Z0-9]*\.[a-z][a-zA-Z0-9]*'

while IFS= read -r -d '' file; do
  while IFS=: read -r lineno line; do
    # Skip lines with the explicit opt-out comment
    if echo "$line" | grep -q '// ignore: silent_default'; then
      continue
    fi
    echo "$file:$lineno: $line"
    VIOLATIONS=$((VIOLATIONS + 1))
  done < <(grep -n -E "$PATTERN" "$file" || true)
done < <(find "$TARGET" -name '*.dart' \
  -not -name '*.g.dart' \
  -not -path '*/build/*' \
  -not -path '*/.dart_tool/*' \
  -not -path '*/test/*' \
  -print0)

if [ "$VIOLATIONS" -gt 0 ]; then
  echo ""
  echo "Found $VIOLATIONS potential silent enum default(s)."
  echo "If a use is intentional (API parameter default, DI, etc.),"
  echo "add '// ignore: silent_default' to the line."
  exit 1
fi

echo "No silent enum defaults found."
exit 0
