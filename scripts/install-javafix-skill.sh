#!/bin/sh
set -eu

REPOSITORY="krandalf75/javafix"
VERSION=""
LOCAL_PROJECT=""

usage() {
  cat <<'EOF'
Usage: install-javafix-skill.sh [--version VERSION] [--local [PROJECT]]

Install the JavaFix executable and OpenCode skill from a published GitHub
release. By default, the skill is installed globally for the current user.
--local installs the skill in PROJECT/.opencode/skills/javafix (default: cwd).
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || { echo "Missing value for --version" >&2; exit 2; }
      VERSION=$2
      shift 2
      ;;
    --local)
      if [ "$#" -ge 2 ] && [ "${2#--}" = "$2" ]; then
        LOCAL_PROJECT=$2
        shift 2
      else
        LOCAL_PROJECT=$PWD
        shift
      fi
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
if command -v shasum >/dev/null 2>&1; then
  hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
elif command -v sha256sum >/dev/null 2>&1; then
  hash_file() { sha256sum "$1" | awk '{print $1}'; }
else
  echo "shasum or sha256sum is required to verify release downloads" >&2
  exit 1
fi

if [ -z "$VERSION" ]; then
  LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$REPOSITORY/releases/latest" |
    sed -n 's/^[[:space:]]*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p')
  VERSION=${LATEST_TAG#v}
fi
case "$VERSION" in
  ""|*[!0-9A-Za-z._-]*)
    echo "Invalid release version: $VERSION" >&2
    exit 2
    ;;
esac

TAG="v$VERSION"
BASE_URL="https://github.com/$REPOSITORY/releases/download/$TAG"
SHARE_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/javafix"
SKILL_TARGET="$SHARE_DIR/skills/javafix-$VERSION"
if [ -n "$LOCAL_PROJECT" ]; then
  SKILL_LINK="$LOCAL_PROJECT/.opencode/skills/javafix"
else
  SKILL_LINK="${XDG_CONFIG_HOME:-$HOME/.config}/opencode/skills/javafix"
fi
if [ -e "$SKILL_LINK" ] && [ ! -L "$SKILL_LINK" ]; then
  echo "Refusing to replace existing non-symlink skill: $SKILL_LINK" >&2
  echo "Move it aside, then re-run this installer." >&2
  exit 1
fi

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/javafix-install.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

curl -fsSL "$BASE_URL/javafix-$VERSION.jar" -o "$TMP_DIR/javafix.jar"
curl -fsSL "$BASE_URL/javafix-skill.tar.gz" -o "$TMP_DIR/javafix-skill.tar.gz"
curl -fsSL "$BASE_URL/SHA256SUMS" -o "$TMP_DIR/SHA256SUMS"

verify_checksum() {
  asset=$1
  file=$2
  expected=$(awk -v name="$asset" '$2 == name { print $1 }' "$TMP_DIR/SHA256SUMS")
  [ -n "$expected" ] || { echo "No checksum found for $asset" >&2; exit 1; }
  actual=$(hash_file "$TMP_DIR/$file")
  [ "$actual" = "$expected" ] || { echo "Checksum verification failed: $asset" >&2; exit 1; }
}
verify_checksum "javafix-$VERSION.jar" javafix.jar
verify_checksum javafix-skill.tar.gz javafix-skill.tar.gz

mkdir -p "$SHARE_DIR" "$SKILL_TARGET"
cp "$TMP_DIR/javafix.jar" "$SHARE_DIR/javafix-$VERSION.jar"
ln -sfn "javafix-$VERSION.jar" "$SHARE_DIR/javafix.jar"

mkdir -p "$TMP_DIR/extracted"
tar -xzf "$TMP_DIR/javafix-skill.tar.gz" -C "$TMP_DIR/extracted" --strip-components=1 javafix
[ -f "$TMP_DIR/extracted/SKILL.md" ] || { echo "Release skill archive is invalid" >&2; exit 1; }
cp "$TMP_DIR/extracted/SKILL.md" "$SKILL_TARGET/SKILL.md"
touch "$SKILL_TARGET/.javafix-managed"

mkdir -p "$(dirname "$SKILL_LINK")"
ln -sfn "$SKILL_TARGET" "$SKILL_LINK"

cat <<EOF
JavaFix $VERSION installed.
JAR:   $SHARE_DIR/javafix.jar
Skill: $SKILL_LINK

Set JAVAFIX_JAR="$SHARE_DIR/javafix.jar" in the environment used to launch
OpenCode, or restart OpenCode so the installed skill is discovered.
EOF
