#!/usr/bin/env bash
# Caps the JVMs of a CI build on a small runner (private-repository runners have about 7 GB of RAM):
# a 2 GB Gradle heap, and Kotlin compiled inside the Gradle daemon instead of in a second daemon.
# Written to the user's gradle.properties, which overrides the project's.
set -euo pipefail

dir="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$dir"
cat >> "$dir/gradle.properties" << 'PROPS'
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC -Dfile.encoding=UTF-8
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=2
PROPS
free -m || true
