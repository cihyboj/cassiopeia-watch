#!/bin/bash
# Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.

# release.sh - Prepares a release PR for Cassiopeia Watch

set -euo pipefail

if [ "$#" -ne 1 ] || ! [[ "$1" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: ./release.sh <major>.<minor> (e.g. 41.0)"
    exit 1
fi

# Refuse to prepare a release with uncommitted changes.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Working tree is not clean. Commit or stash changes first."
    exit 1
fi

if ! command -v gh &> /dev/null; then
    echo "❌ GitHub CLI (gh) is required to open the release PR."
    exit 1
fi

VERSION_NAME=$1
MAJOR=${VERSION_NAME%%.*}
MINOR=${VERSION_NAME##*.}
# Bound input before Bash arithmetic so overflow cannot bypass the monotonic check.
if [ "${#MAJOR}" -gt 9 ] || [ "${#MINOR}" -gt 9 ]; then
    echo "❌ Version components are too large for an Android versionCode."
    exit 1
fi
VERSION_CODE=$((10#$MAJOR * 1000 + 10#$MINOR))
if [ "$VERSION_CODE" -gt 2100000000 ]; then
    echo "❌ versionCode $VERSION_CODE exceeds Android's supported maximum."
    exit 1
fi

# A real read verifies gh access; gh auth status can be misleading here.
gh repo view --json nameWithOwner > /dev/null
if [ ! -s RELEASE_NOTES_NEXT.md ]; then
    echo "❌ RELEASE_NOTES_NEXT.md must contain the release notes."
    exit 1
fi

# Start directly from the fetched main tip, even if main is checked out elsewhere.
git fetch origin main
PREVIOUS_CODE=$(git show origin/main:app/build.gradle | sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p')
if ! [[ "$PREVIOUS_CODE" =~ ^[0-9]+$ ]]; then
    echo "❌ Could not read versionCode from origin/main."
    exit 1
fi
if [ "$VERSION_CODE" -le "$PREVIOUS_CODE" ]; then
    echo "❌ New versionCode $VERSION_CODE is not greater than current $PREVIOUS_CODE."
    echo "   (Updaters reject downgrades. Pick a higher version.)"
    exit 1
fi

# Check everything that can be checked before creating the branch, so a failed
# precondition leaves nothing to clean up.
if [ -z "$(git show origin/main:RELEASE_NOTES_NEXT.md 2>/dev/null)" ]; then
    echo "❌ RELEASE_NOTES_NEXT.md must contain the release notes on origin/main."
    exit 1
fi
RELEASE_BRANCH="release/v$VERSION_NAME"
if git show-ref --verify --quiet "refs/heads/$RELEASE_BRANCH" ||
    [ -n "$(git ls-remote --heads origin "$RELEASE_BRANCH")" ]; then
    echo "❌ Branch $RELEASE_BRANCH already exists locally or on origin."
    echo "   A previous run stopped partway. Either finish it by hand (push the branch,"
    echo "   open the PR) or delete it and start again:"
    echo "     git branch -D $RELEASE_BRANCH; git push origin --delete $RELEASE_BRANCH"
    exit 1
fi
START_REF=$(git symbolic-ref --quiet --short HEAD || git rev-parse HEAD)
git switch --no-track -c "$RELEASE_BRANCH" origin/main

# From here on a failure leaves the release branch checked out; say how to recover.
on_failure() {
    echo "❌ Release preparation stopped on branch $RELEASE_BRANCH."
    echo "   To start over: git restore --staged --worktree . && git switch $START_REF && git branch -D $RELEASE_BRANCH"
    echo "   If the branch was already pushed: git push origin --delete $RELEASE_BRANCH"
}
trap on_failure ERR

echo "🚀 Preparing release for version $VERSION_NAME (Code: $VERSION_CODE)..."
echo "🧪 Running tests and lint..."
./gradlew test lint

echo "📝 Updating app/build.gradle..."
sed -i.bak "s/versionCode = .*/versionCode = $VERSION_CODE/" app/build.gradle
sed -i.bak "s/versionName = .*/versionName = \"$VERSION_NAME\"/" app/build.gradle
rm -f app/build.gradle.bak

# Fastlane Android changelogs are named after versionCode.
CHANGELOG_PATH="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
echo "📂 Creating changelog at $CHANGELOG_PATH..."
mkdir -p "$(dirname "$CHANGELOG_PATH")"
cp RELEASE_NOTES_NEXT.md "$CHANGELOG_PATH"

echo "💾 Committing release preparation..."
git add app/build.gradle "$CHANGELOG_PATH"
git commit -m "Prepare release v$VERSION_NAME"

echo "📤 Pushing release branch and opening PR..."
git push --set-upstream origin "$RELEASE_BRANCH"
gh pr create --base main --head "$RELEASE_BRANCH" \
    --title "Prepare release v$VERSION_NAME" --body-file RELEASE_NOTES_NEXT.md

trap - ERR
echo "✅ Release PR prepared. After merging, verify main's versionName is $VERSION_NAME, then run:"
printf 'git fetch origin main && git tag -a "v%s" origin/main -m "Release v%s" && git push origin "refs/tags/v%s"\n' \
    "$VERSION_NAME" "$VERSION_NAME" "$VERSION_NAME"
