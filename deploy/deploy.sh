#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <image>" >&2
  exit 2
fi

readonly IMAGE="$1"
readonly APP_CONTAINER="diary-app"
readonly BACKUP_CONTAINER="diary-app-rollback"
readonly HEALTHCHECK_URL="http://127.0.0.1:18080/diary"
readonly START_SCRIPT="${START_SCRIPT:-$HOME/start.sh}"
export REGISTRY_AUTH_FILE="${REGISTRY_AUTH_FILE:-$HOME/.config/containers/auth.json}"

if [[ ! -r "$REGISTRY_AUTH_FILE" ]]; then
  echo "Missing registry authentication file: $REGISTRY_AUTH_FILE" >&2
  exit 1
fi

container_exists() {
  podman container exists "$1"
}

restore_previous_container() {
  echo "Deployment failed; restoring the previous container." >&2

  if container_exists "$APP_CONTAINER"; then
    podman rm -f "$APP_CONTAINER" >/dev/null
  fi

  if container_exists "$BACKUP_CONTAINER"; then
    podman rename "$BACKUP_CONTAINER" "$APP_CONTAINER"
    podman start "$APP_CONTAINER" >/dev/null
  fi
}

podman pull "$IMAGE"

if container_exists "$BACKUP_CONTAINER"; then
  podman rm -f "$BACKUP_CONTAINER" >/dev/null
fi

if container_exists "$APP_CONTAINER"; then
  podman stop "$APP_CONTAINER" >/dev/null
  podman rename "$APP_CONTAINER" "$BACKUP_CONTAINER"
fi

# 기존 컨테이너를 옮긴 뒤부터 복구 대상이다. 그 전에 trap을 걸면 pull 실패 시 운영 중인 컨테이너를 지운다.
# ERR 대신 EXIT를 쓴다: 명시적 `exit 1`(health check 실패/타임아웃)에서는 ERR trap이 실행되지 않는다.
trap restore_previous_container EXIT

"$START_SCRIPT" "$IMAGE" >/dev/null

for attempt in {1..18}; do
  if curl --fail --silent --show-error --max-time 5 "$HEALTHCHECK_URL" >/dev/null; then
    trap - EXIT
    if container_exists "$BACKUP_CONTAINER"; then
      podman rm "$BACKUP_CONTAINER" >/dev/null
    fi
    echo "Deployment succeeded: $IMAGE"
    exit 0
  fi

  if ! podman container exists "$APP_CONTAINER" ||
    [[ "$(podman inspect --format '{{.State.Running}}' "$APP_CONTAINER")" != "true" ]]; then
    echo "The new container stopped before becoming healthy." >&2
    exit 1
  fi

  echo "Waiting for the application to become healthy ($attempt/18)..."
  sleep 5
done

echo "Health check timed out: $HEALTHCHECK_URL" >&2
exit 1
