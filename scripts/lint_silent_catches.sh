#!/usr/bin/env bash
# lint_silent_catches.sh — CI gate for silent-exception anti-patterns
#
# Detects `catch` blocks that swallow the exception by returning null /
# empty list / false without rethrowing or recording to AppLogger.error.
# This is the same anti-pattern that hid the thread_id SQL bug across
# thousands of full-sync runs (PR-C / #76).
#
# Pattern: a catch block whose ENTIRE body is one of
#   - return null;
#   - return [];
#   - return const [];
#   - return false;
#   - return '';
# optionally preceded by debugPrint/print/log calls and comments.
#
# A catch that calls AppLogger.error / recordError / rethrow / throw
# is exempt by construction (the regex below excludes them).
#
# Whitelist: `// ignore: silent_catch` anywhere within the catch body.
#
# Usage:
#   ./scripts/lint_silent_catches.sh           # check lib/
#   ./scripts/lint_silent_catches.sh src/foo/  # check a subdirectory

set -euo pipefail

TARGET="${1:-lib/}"

# Use perl for proper multi-line balanced-brace matching.
violations=$(find "$TARGET" -name '*.dart' \
  -not -name '*.g.dart' \
  -not -path '*/build/*' \
  -not -path '*/.dart_tool/*' \
  -not -path '*/test/*' \
  -print0 \
  | xargs -0 perl -0777 -ne '
    # Match `catch (...) { BODY }` where BODY is purely silent.
    # Using non-greedy match; bail on nested braces by limiting body
    # to lines without `{` characters (good enough for typical Dart).
    while (m{
      \bcatch\s*\([^)]*\)\s*\{   # catch (...) {
      ((?:[^{}]|\n)*?)            # BODY (no nested braces)
      \}                          # }
    }gx) {
      my $body = $1;
      my $offset = $-[0];
      my $line_no = (substr($_, 0, $offset) =~ tr/\n/\n/) + 1;

      # Skip whitelisted catches.
      next if $body =~ m{//\s*ignore:\s*silent_catch};

      # Skip catches that do real work.
      next if $body =~ m{(?:AppLogger\.(?:error|warn)|recordError|rethrow|^\s*throw\s)}m;

      # Strip comments and blank lines.
      my $stripped = $body;
      $stripped =~ s{//[^\n]*}{}g;
      $stripped =~ s{/\*.*?\*/}{}gs;

      # Strip allowed soft-statements: debugPrint(...) / print(...) / log(...)
      # — possibly multi-line. Be liberal: any statement that starts
      # with one of those names through the next semicolon.
      $stripped =~ s{\b(?:debugPrint|print|log)\s*\([^;]*\)\s*;}{}gs;

      # Whats left should be ONLY a silent return + whitespace.
      $stripped =~ s{^\s+|\s+$}{}gs;

      if ($stripped =~ m{^return\s+(?:null|\[\s*\]|const\s+(?:<[^>]+>\s*)?\[\s*\]|false|true|0|"")\s*;$}) {
        print "$ARGV:$line_no: silent catch body\n";
      }
    }
  ' 2>/dev/null)

if [ -n "$violations" ]; then
  echo "$violations"
  count=$(echo "$violations" | wc -l | tr -d ' ')
  echo ""
  echo "Found $count silent catch body(ies)."
  echo "Catch blocks that swallow exceptions by returning null/[]/false"
  echo "without rethrowing or recording to AppLogger.error hide real bugs"
  echo "from every caller. If a use is intentionally best-effort (legacy"
  echo "cleanup, optional-feature degradation), add"
  echo "  // ignore: silent_catch"
  echo "anywhere within the catch body."
  exit 1
fi

echo "No silent catch bodies found."
exit 0
