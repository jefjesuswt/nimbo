#!/bin/sh
# ponytail: usa el Gradle cacheado que pida gradle-wrapper.properties.
# En otra máquina con wrapper real, reemplazar por gradlew oficial.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
WANT=$(grep -o 'gradle-[0-9.]*-bin' gradle/wrapper/gradle-wrapper.properties | head -1)
VER=$(echo "$WANT" | sed 's/gradle-//; s/-bin//')
GRADLE_BIN=$(find "$HOME/.gradle/wrapper/dists/${WANT}" /tmp/gradle-dist -path "*/gradle-${VER}/bin/gradle" -type f 2>/dev/null | head -1)
if [ -z "$GRADLE_BIN" ]; then
  echo "Descargando Gradle $VER..." >&2
  mkdir -p /tmp/gradle-dist && cd /tmp/gradle-dist || exit 1
  curl -sL -o gradle.zip "https://services.gradle.org/distributions/gradle-${VER}-bin.zip" && unzip -q -o gradle.zip
  GRADLE_BIN="/tmp/gradle-dist/gradle-${VER}/bin/gradle"
fi
if [ ! -x "$GRADLE_BIN" ]; then echo "Sin Gradle usable" >&2; exit 1; fi
cd - > /dev/null || exit 1
exec "$GRADLE_BIN" "$@"
