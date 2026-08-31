#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${1:-ghcr.io/sakur35a/practice-kotlin:latest}"
readonly ENV_FILE="${DIARY_ENV_FILE:-$HOME/.config/diary.env}"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Missing deployment environment file: $ENV_FILE" >&2
  exit 1
fi

podman run -d \
  --name diary-app \
  --restart=always \
  --memory=512m \
  -p 127.0.0.1:18080:8080 \
  -e BPL_JVM_THREAD_COUNT=50 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e 'JAVA_TOOL_OPTIONS=-XX:ReservedCodeCacheSize=128M -XX:MetaspaceSize=64M -Xss512k -Xlog:gc,safepoint' \
  --env-file "$ENV_FILE" \
  "$IMAGE"
