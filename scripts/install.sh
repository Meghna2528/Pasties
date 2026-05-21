#!/usr/bin/env bash
set -euo pipefail

APP_NAME="Pasties"
BUNDLE_ID="com.pasties"
VERSION="1.0.0"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${ROOT_DIR}/target"
JPACKAGE_INPUT_DIR="${TARGET_DIR}/jpackage-input"
APP_IMAGE_DIR="${TARGET_DIR}/dist"
BUILT_APP="${APP_IMAGE_DIR}/${APP_NAME}.app"
INSTALLED_APP="/Applications/${APP_NAME}.app"
JAR_NAME="pasties-${VERSION}.jar"
ENTITLEMENTS="${ROOT_DIR}/packaging/entitlements.plist"

ASSUME_YES=false
SKIP_TESTS=true

usage() {
  cat <<EOF
Usage: scripts/install.sh [options]

Build, install, sign, and launch Pasties for local use.

Options:
  -y, --yes       Accept install prompts for missing Homebrew dependencies.
  --run-tests     Run tests before packaging.
  -h, --help      Show this help.

Examples:
  scripts/install.sh
  scripts/install.sh --yes
  scripts/install.sh --run-tests
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -y|--yes)
      ASSUME_YES=true
      ;;
    --run-tests)
      SKIP_TESTS=false
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
  shift
done

ask_yes_no() {
  local prompt="$1"
  if [[ "${ASSUME_YES}" == "true" ]]; then
    return 0
  fi
  local answer
  read -r -p "${prompt} [y/N] " answer
  case "${answer}" in
    y|Y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

prepend_java_home() {
  local java_home="$1"
  if [[ ! -x "${java_home}/bin/java" || ! -x "${java_home}/bin/jpackage" ]]; then
    echo "That path does not look like a full JDK: ${java_home}" >&2
    echo "Expected both ${java_home}/bin/java and ${java_home}/bin/jpackage" >&2
    return 1
  fi
  export JAVA_HOME="${java_home}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
}

ensure_jdk() {
  if command -v java >/dev/null 2>&1 && command -v jpackage >/dev/null 2>&1; then
    return
  fi

  echo "A full JDK with java and jpackage is required."

  local default_home=""
  if [[ -x /usr/libexec/java_home ]]; then
    default_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [[ -n "${default_home}" && -x "${default_home}/bin/jpackage" ]]; then
    echo "Found JDK 21 at ${default_home}"
    prepend_java_home "${default_home}"
    return
  fi

  if command -v brew >/dev/null 2>&1; then
    if ask_yes_no "Install OpenJDK 21 with Homebrew now?"; then
      brew install openjdk@21
      local brew_prefix
      brew_prefix="$(brew --prefix openjdk@21)"
      prepend_java_home "${brew_prefix}/libexec/openjdk.jdk/Contents/Home"
      return
    fi
  fi

  local custom_home
  read -r -p "Enter the path to a JDK home, or press Enter to cancel: " custom_home
  if [[ -z "${custom_home}" ]]; then
    echo "Install cancelled. Install JDK 21, then rerun scripts/install.sh." >&2
    exit 1
  fi
  prepend_java_home "${custom_home}"
}

ensure_maven() {
  if command -v mvn >/dev/null 2>&1; then
    return
  fi

  echo "Maven is required to build Pasties from source."
  if command -v brew >/dev/null 2>&1; then
    if ask_yes_no "Install Maven with Homebrew now?"; then
      brew install maven
      return
    fi
  fi

  echo "Install Maven, then rerun scripts/install.sh:" >&2
  echo "  brew install maven" >&2
  exit 1
}

ensure_jdk
ensure_maven
require_command codesign
require_command jpackage
require_command open
require_command pkill

cd "${ROOT_DIR}"

echo "==> Using Java: $(java -version 2>&1 | head -n 1)"
echo "==> Using Maven: $(mvn -version | head -n 1)"

echo "==> Stopping running ${APP_NAME} instances"
pkill -f "${APP_NAME}" >/dev/null 2>&1 || true
sleep 1

echo "==> Removing previous build output"
rm -rf "${APP_IMAGE_DIR}" "${JPACKAGE_INPUT_DIR}" "${TARGET_DIR}/${JAR_NAME}" "${TARGET_DIR}/original-${JAR_NAME}"

echo "==> Building ${APP_NAME}"
if [[ "${SKIP_TESTS}" == "true" ]]; then
  mvn clean package -DskipTests
else
  mvn clean package
fi

echo "==> Staging jpackage input"
mkdir -p "${JPACKAGE_INPUT_DIR}"
cp "${TARGET_DIR}/${JAR_NAME}" "${JPACKAGE_INPUT_DIR}/"

echo "==> Creating macOS app image"
jpackage \
  --input "${JPACKAGE_INPUT_DIR}" \
  --main-jar "${JAR_NAME}" \
  --main-class com.pasties.Main \
  --name "${APP_NAME}" \
  --type app-image \
  --dest "${APP_IMAGE_DIR}" \
  --icon "${ROOT_DIR}/src/main/resources/icon.icns" \
  --java-options "-Xmx64m -Dapple.awt.UIElement=true" \
  --mac-package-name "${APP_NAME}" \
  --mac-package-identifier "${BUNDLE_ID}"

echo "==> Installing to ${INSTALLED_APP}"
rm -rf "${INSTALLED_APP}"
cp -R "${BUILT_APP}" "${INSTALLED_APP}"

echo "==> Signing app bundle"
codesign --force --deep --sign - --entitlements "${ENTITLEMENTS}" "${INSTALLED_APP}"

echo "==> Launching ${APP_NAME}"
open "${INSTALLED_APP}"

cat <<EOF
==> Done

If this is your first launch, enable Pasties in:
  System Settings -> Privacy & Security -> Accessibility
  System Settings -> Privacy & Security -> Input Monitoring

After granting permissions, quit and relaunch Pasties:
  open /Applications/Pasties.app

History menu hotkey:
  Command+Shift+S
EOF
