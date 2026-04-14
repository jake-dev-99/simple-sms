#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRY_RUN=true

NAME=$(grep '^name:' "$ROOT/pubspec.yaml" | head -1 | awk '{print $2}')
VERSION=$(grep '^version:' "$ROOT/pubspec.yaml" | head -1 | awk '{print $2}')

for arg in "$@"; do
  case "$arg" in
    --live) DRY_RUN=false ;;
    --help|-h)
      echo "Usage: tool/publish.sh [--live]"
      echo ""
      echo "  --live    Actually publish (default is dry-run)"
      echo ""
      echo "Package: $NAME $VERSION"
      exit 0
      ;;
  esac
done

cd "$ROOT"

echo "══════════════════════════════════════════"
echo "📦 $NAME $VERSION"
echo "   $ROOT"
echo "══════════════════════════════════════════"
echo ""

# ── Pre-flight checks ──────────────────────────

echo "▸ Checking for uncommitted changes..."
if ! git diff --quiet HEAD 2>/dev/null; then
  echo "❌ Uncommitted changes detected. Commit or stash before publishing."
  exit 1
fi
echo "  Clean."
echo ""

echo "▸ Resolving dependencies..."
flutter pub get --no-example > /dev/null 2>&1 || flutter pub get > /dev/null 2>&1
echo "  Done."
echo ""

echo "▸ Running analyzer..."
if ! dart analyze lib/; then
  echo "❌ Analysis failed. Fix issues before publishing."
  exit 1
fi
echo ""

echo "▸ Running tests..."
if ! flutter test; then
  echo "❌ Tests failed. Fix before publishing."
  exit 1
fi
echo ""

# ── Publish ─────────────────────────────────────

if $DRY_RUN; then
  echo "══════════════════════════════════════════"
  echo "▸ Dry run..."
  echo ""
  flutter pub publish --dry-run
  echo ""
  echo "══════════════════════════════════════════"
  echo "Dry run complete. Run with --live to publish for real."
else
  echo "══════════════════════════════════════════"
  echo "▸ Publishing $NAME $VERSION to pub.dev..."
  echo ""
  if flutter pub publish --force; then
    echo ""
    echo "══════════════════════════════════════════"
    echo "✅ $NAME $VERSION published successfully."
    echo ""
    echo "   https://pub.dev/packages/$NAME"
  else
    echo ""
    echo "══════════════════════════════════════════"
    echo "❌ Publish failed."
    exit 1
  fi
fi
