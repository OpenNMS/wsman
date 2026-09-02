#!/usr/bin/env bash
set -e

if [ -z "$1" ]; then
  echo "Usage: $0 <release-version>"
  exit 1
fi

RELEASE_VERSION="$1"

# Compute next snapshot version (simple patch bump)
IFS='.' read -r major minor patch <<< "$RELEASE_VERSION"
NEXT_SNAPSHOT_VERSION="${major}.${minor}.$((patch + 1))-SNAPSHOT"

echo "Releasing version: $RELEASE_VERSION"
echo "Next snapshot version: $NEXT_SNAPSHOT_VERSION"
echo

# 1. Set release version
echo "Setting release version to $RELEASE_VERSION..."
mvn versions:set -DnewVersion="$RELEASE_VERSION"
mvn versions:commit

# 2. Commit + tag
echo "Committing release and tagging v$RELEASE_VERSION..."
git commit -am "Release $RELEASE_VERSION"
git tag "v$RELEASE_VERSION"

# 3. Publish to Maven Central (Central Publishing)
echo "Publishing version $RELEASE_VERSION to Maven Central..."
mvn -Prelease deploy

# 4. Push commits + tags
echo "Pushing commits and tags to remote..."
git push
git push --tags

# 5. Bump to next snapshot
echo "Bumping to next snapshot version $NEXT_SNAPSHOT_VERSION..."
mvn versions:set -DnewVersion="$NEXT_SNAPSHOT_VERSION"
mvn versions:commit

git commit -am "$RELEASE_VERSION -> $NEXT_SNAPSHOT_VERSION"
git push
echo
echo "Release $RELEASE_VERSION completed successfully."
echo "Now on snapshot version: $NEXT_SNAPSHOT_VERSION"
