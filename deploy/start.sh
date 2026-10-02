#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${1:-ghcr.io/sakur35a/practice-kotlin:latest}"
readonly ENV_FILE="${DIARY_ENV_FILE:-$HOME/.config/diary.env}"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Missing deployment environment file: $ENV_FILE" >&2
  exit 1
fi

# Secret Manager 접근용 서비스 계정 키 (읽기 전용으로 컨테이너에 마운트한다)
readonly GCP_KEY_FILE="${GCP_KEY_FILE:-$HOME/.config/diary-gcp-sa.json}"
if [[ ! -r "$GCP_KEY_FILE" ]]; then
  echo "Missing GCP service account key: $GCP_KEY_FILE" >&2
  exit 1
fi

# 상대 서버 haproxy가 사설망으로 직접 붙는다 (firewalld와 OCI 보안 목록에서 상대 IP만 허용)
readonly PRIVATE_IP="$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')"
if [[ -z "$PRIVATE_IP" ]]; then
  echo "Cannot determine the private IP address" >&2
  exit 1
fi

podman run -d \
  --name diary-app \
  --restart=always \
  --memory=512m \
  -p 127.0.0.1:18080:8080 \
  -p "$PRIVATE_IP:18080:8080" \
  -p 127.0.0.1:18090:8081 \
  -e BPL_JVM_THREAD_COUNT=50 \
  -e BPL_JVM_CLASS_ADJUSTMENT=125% \
  -e SPRING_PROFILES_ACTIVE=prod \
  -v "$GCP_KEY_FILE:/secrets/gcp-sa.json:ro,Z" \
  -e GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcp-sa.json \
  -e 'JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=96M -XX:MetaspaceSize=96M -Xss512k -Xlog:gc,gc+metaspace=info' \
  --env-file "$ENV_FILE" \
  "$IMAGE"
