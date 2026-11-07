#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: $0 <version>   e.g. $0 0.1.1" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Working tree is not clean. Commit or stash changes first." >&2
    exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "Tag v$VERSION already exists." >&2
    exit 1
fi

mvn -B versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
mvn -B package

git add pom.xml
git commit -m "Release v$VERSION"
git tag "v$VERSION"

BRANCH="$(git branch --show-current)"
git push origin "$BRANCH"
git push origin "v$VERSION"

echo
echo "Pushed v$VERSION."
echo "CI: https://github.com/leocb/Yamaha-LS9-Network-Protocol-Library/actions"
echo "This tag triggers CI, Maven Central, JitPack, and the GitHub release."
