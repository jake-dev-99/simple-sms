#!/usr/bin/env bash
# UNFY-123: enforce the first-party Kotlin MMS exit contract before release.
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_root="${1:-$repository_root/android/src/main}"
[[ -d "$source_root" ]] || { echo "Missing Android source directory: $source_root" >&2; exit 2; }

remnants="$(find "$source_root" -type f \( -name '*.java' -o -name 'mms_config.xml' -o -name 'apns.xml' \) -print)"
if [[ -n "$remnants" ]]; then
    echo "MMS migration contains obsolete source or carrier resources:" >&2
    echo "$remnants" >&2
    exit 1
fi

if matches="$(grep -R -n -E 'com[.]klinker[.]|com[.]android[.]mms|com[.]google[.]android[.]mms' "$source_root")"; then
    echo "MMS migration contains obsolete package references:" >&2
    echo "$matches" >&2
    exit 1
else
    search_status=$?
    [[ "$search_status" -eq 1 ]] || exit "$search_status"
fi

echo "First-party MMS source and carrier-resource checks passed."
