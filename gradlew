#!/bin/sh
# Gradle wrapper stub.
# If you opened this project in Android Studio, Android Studio will use its
# bundled Gradle wrapper. If you need command-line builds, install Gradle 8.2
# or let Android Studio generate the wrapper jar (Gradle > Wrapper).
# This stub delegates to a locally installed `gradle` if present.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle not found. Open this project in Android Studio and use Build > Build APK(s), or install Gradle 8.2 and run: gradle assembleDebug" >&2
  exit 1
fi
