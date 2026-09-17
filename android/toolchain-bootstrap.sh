#!/bin/bash
# BAZICHE Android toolchain bootstrap (sandbox-local, NOT committed to snapshots).
# Installs: Temurin JDK 17, Gradle 9.7.1, Node 22, Android SDK (36 + 37-alias).
# Usage: bash android/toolchain-bootstrap.sh
# Then:  export JAVA_HOME=<tc>/jdk-17 GRADLE_USER_HOME=/home/user/.cache/gradle-home
set -euo pipefail
TC="${TOOLCHAIN_DIR:-/home/user/.cache/toolchain}"
SDK="$TC/android-sdk"
mkdir -p "$TC"

echo "==> JDK 17 (Temurin)"
if [ ! -x "$TC/jdk-17/bin/java" ]; then
  curl -fSL --retry 3 -o /tmp/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$TC/jdk-17" && tar -xzf /tmp/jdk17.tar.gz -C "$TC/jdk-17" --strip-components=1 && rm /tmp/jdk17.tar.gz
fi
"$TC/jdk-17/bin/java" -version 2>&1 | head -1

echo "==> Gradle 9.7.1"
if [ ! -x "$TC/gradle-9.7.1/bin/gradle" ]; then
  curl -fSL --retry 3 -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip"
  unzip -q /tmp/gradle.zip -d "$TC" && rm /tmp/gradle.zip
fi

echo "==> Node 22"
if [ ! -x "$TC/node22/bin/node" ]; then
  NV=$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c "import json,sys; print([r['version'] for r in json.load(sys.stdin) if r['version'].startswith('v22.')][0])")
  echo "node $NV"
  curl -fSL --retry 3 -o /tmp/node.tar.xz "https://nodejs.org/dist/$NV/node-$NV-linux-x64.tar.xz"
  mkdir -p "$TC/node22" && tar -xJf /tmp/node.tar.xz -C "$TC/node22" --strip-components=1 && rm /tmp/node.tar.xz
fi
"$TC/node22/bin/node" --version

echo "==> Android SDK"
export JAVA_HOME="$TC/jdk-17"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  curl -fSL --retry 3 -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools" && unzip -q /tmp/cmdtools.zip -d "$SDK/cmdline-tools" && rm /tmp/cmdtools.zip
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK"
yes | $SDKM --licenses >/dev/null 2>&1 || true
$SDKM "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -2
if [ ! -d "$SDK/platforms/android-37" ]; then
  echo "==> android-37 alias (platform-37 unpublished; see docs/ENVIRONMENT_SETUP.md)"
  cp -r "$SDK/platforms/android-36" "$SDK/platforms/android-37"
fi

mkdir -p /home/user/.cache/gradle-home
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ ! -f "$SCRIPT_DIR/local.properties" ]; then
  echo "sdk.dir=$SDK" > "$SCRIPT_DIR/local.properties"
fi

echo "==> DONE"
echo "export JAVA_HOME=$TC/jdk-17"
echo "export GRADLE_USER_HOME=/home/user/.cache/gradle-home"
echo "export PATH=$TC/node22/bin:\$PATH"
ls "$SDK/platforms"
