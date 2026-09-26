#!/bin/bash
set -euo pipefail

# Usage: ./scripts/release.sh <build-number> [version]
# Example: ./scripts/release.sh 13 1.0.9
#
# Stamps versionCode/versionName into app/build.gradle.kts and commits
# "Release <version> (<build>)", so dev builds and Settings show the same
# version as the Play build (iOS /release stamps the pbxproj the same way).
# CI also sets both values from the tag.

GRADLE=app/build.gradle.kts
BUILD="${1:?Usage: ./scripts/release.sh <build-number> [version]}"
VERSION="${2:-$(grep 'versionName' "$GRADLE" | head -1 | sed 's/.*"\(.*\)".*/\1/')}"
TAG="v${VERSION}-${BUILD}"
CURRENT_BUILD=$(grep 'versionCode' "$GRADLE" | head -1 | sed 's/[^0-9]//g')

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "main" ]; then
  echo "Error: must be on main branch (currently on $BRANCH)"
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Error: uncommitted changes exist"
  exit 1
fi

if ! [[ "$BUILD" =~ ^[0-9]+$ ]]; then
  echo "Error: build number must be a whole number (got $BUILD)"
  exit 1
fi

# Play rejects a versionCode at or below one it already has.
if [ "$BUILD" -le "$CURRENT_BUILD" ]; then
  echo "Error: build $BUILD must be above the current versionCode $CURRENT_BUILD"
  exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Error: tag $TAG already exists"
  exit 1
fi

echo "Creating release: $TAG (version $VERSION, build $BUILD)"
echo ""

sed -i.bak \
  -e "s/versionCode = [0-9]*/versionCode = $BUILD/" \
  -e "s/versionName = \"[0-9.]*\"/versionName = \"$VERSION\"/" \
  "$GRADLE"
rm "$GRADLE.bak"
git commit -q -m "Release $VERSION ($BUILD)" -- "$GRADLE"

git tag "$TAG"
# Atomic: never a tag on origin whose stamp commit is not on main.
git push --atomic origin main "$TAG"

echo ""
echo "Tag $TAG pushed — CI will build and upload to Google Play"
