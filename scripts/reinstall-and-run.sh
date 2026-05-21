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
APP_DB="${HOME}/Library/Application Support/${APP_NAME}/pasties.db"

RUN_TESTS=true
LAUNCH_APP=true
RESET_PERMISSIONS=false

usage() {
  cat <<EOF
Usage: scripts/reinstall-and-run.sh [options]

Build, reinstall, sign, and launch Pasties.

Options:
  --skip-tests          Build without running tests.
  --reset-permissions   Reset Accessibility and Input Monitoring grants before launch.
  --no-launch           Reinstall only; do not launch the app.
  -h, --help            Show this help.

Examples:
  scripts/reinstall-and-run.sh
  scripts/reinstall-and-run.sh --reset-permissions
  scripts/reinstall-and-run.sh --skip-tests --no-launch
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      RUN_TESTS=false
      ;;
    --reset-permissions)
      RESET_PERMISSIONS=true
      ;;
    --no-launch)
      LAUNCH_APP=false
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

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_command mvn
require_command jpackage
require_command codesign
require_command open
require_command pkill
require_command sqlite3
require_command tccutil

cd "${ROOT_DIR}"

echo "==> Stopping running ${APP_NAME} instances"
pkill -f "${APP_NAME}" >/dev/null 2>&1 || true
sleep 1

echo "==> Removing previous build output"
rm -rf "${APP_IMAGE_DIR}" "${JPACKAGE_INPUT_DIR}" "${TARGET_DIR}/${JAR_NAME}" "${TARGET_DIR}/original-${JAR_NAME}"

echo "==> Removing installed app at ${INSTALLED_APP}"
rm -rf "${INSTALLED_APP}"

if [[ "${RUN_TESTS}" == "true" ]]; then
  echo "==> Running tests and packaging fat JAR"
  mvn clean package
else
  echo "==> Packaging fat JAR without tests"
  mvn clean package -DskipTests
fi

echo "==> Staging jpackage input"
rm -rf "${JPACKAGE_INPUT_DIR}" "${APP_IMAGE_DIR}"
mkdir -p "${JPACKAGE_INPUT_DIR}"
cp "${TARGET_DIR}/${JAR_NAME}" "${JPACKAGE_INPUT_DIR}/"

echo "==> Creating macOS app image"
JAVA_OPTIONS=(
  --java-options "-Xmx64m"
  --java-options "-Dapple.awt.UIElement=true"
)

jpackage \
  --input "${JPACKAGE_INPUT_DIR}" \
  --main-jar "${JAR_NAME}" \
  --main-class com.pasties.Main \
  --name "${APP_NAME}" \
  --type app-image \
  --dest "${APP_IMAGE_DIR}" \
  --icon "${ROOT_DIR}/src/main/resources/icon.icns" \
  "${JAVA_OPTIONS[@]}" \
  --mac-package-name "${APP_NAME}" \
  --mac-package-identifier "${BUNDLE_ID}"

echo "==> Installing fresh app to /Applications"
cp -R "${BUILT_APP}" "${INSTALLED_APP}"

echo "==> Ad-hoc signing app bundle"
codesign --force --deep --sign - --entitlements "${ENTITLEMENTS}" "${INSTALLED_APP}"

if [[ "${RESET_PERMISSIONS}" == "true" ]]; then
  echo "==> Resetting macOS privacy grants"
  tccutil reset Accessibility "${BUNDLE_ID}" || true
  tccutil reset ListenEvent "${BUNDLE_ID}" || true
fi

echo "==> Refreshing signature"
codesign --force --deep --sign - --entitlements "${ENTITLEMENTS}" "${INSTALLED_APP}"

if [[ -f "${APP_DB}" ]]; then
  echo "==> Setting history menu hotkey to Ctrl/Cmd+Shift+S"
  sqlite3 "${APP_DB}" \
    "INSERT INTO config(key, value) VALUES ('hotkey_key', 'S') ON CONFLICT(key) DO UPDATE SET value = 'S';"
fi

if [[ "${LAUNCH_APP}" == "true" ]]; then
  echo "==> Launching ${APP_NAME}"
  open "${INSTALLED_APP}"
else
  echo "==> Reinstall complete; launch skipped"
fi

echo "==> Done"
