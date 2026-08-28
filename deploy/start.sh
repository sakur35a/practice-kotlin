#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${1:-ghcr.io/sakur35a/practice-kotlin:latest}"

podman run -d \
  --name diary-app \
  --restart=always \
  --memory=512m \
  -p 127.0.0.1:8080:8080 \
  -e BPL_JVM_THREAD_COUNT=50 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e 'JAVA_TOOL_OPTIONS=-XX:ReservedCodeCacheSize=64M -Xss512k' \
  "$IMAGE"
