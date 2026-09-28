#!/usr/bin/env bash
# diary-pg 스크립트 공통 함수. 각 스크립트가 source 한다.
#
# 필요한 파일 (repo에 두지 않는다)
#   ~/.config/diary-pg.env           POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB, REPL_PASSWORD
#   ~/.config/diary-pg/failover.env  SELF_IP, PEER_IP, SLOT_NAME, INTERVAL, FAIL_THRESHOLD,
#                                    FENCE_UNREACHABLE(abort|promote), SLACK_WEBHOOK_URL

set -uo pipefail

readonly CONF_DIR="$HOME/.config/diary-pg"
readonly STATE_DIR="$HOME/.local/state/diary-pg"
readonly PG_IMAGE="docker.io/library/postgres:18.6"
readonly PG_CONTAINER="diary-pg"
readonly PG_VOLUME="diary-pgdata"
readonly PGDATA_IN_CONTAINER="/var/lib/postgresql/18/docker"

# shellcheck source=/dev/null
source "$HOME/.config/diary-pg.env"
# shellcheck source=/dev/null
source "$CONF_DIR/failover.env"

INTERVAL="${INTERVAL:-10}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-6}"
FENCE_UNREACHABLE="${FENCE_UNREACHABLE:-abort}"

mkdir -p "$STATE_DIR"

# 판단 근거를 한 줄 JSON으로 남긴다. stdout은 journald로, 파일은 journald가 휘발성이어도 남도록.
log_event() {
  local line
  line="$(python3 - "$@" <<'PY'
import datetime, json, socket, sys
d = {
    "ts": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "host": socket.gethostname(),
    "event": sys.argv[1],
}
d.update(a.split("=", 1) for a in sys.argv[2:])
print(json.dumps(d, ensure_ascii=False))
PY
)"
  echo "$line"
  echo "$line" >>"$STATE_DIR/events.jsonl"
}

slack() {
  [[ -n "${SLACK_WEBHOOK_URL:-}" ]] || return 0
  local payload
  payload="$(python3 -c 'import json,socket,sys; print(json.dumps({"text": "[diary-pg " + socket.gethostname() + "] " + sys.argv[1]}))' "$1")"
  curl -fsS --max-time 5 -H 'Content-Type: application/json' -d "$payload" "$SLACK_WEBHOOK_URL" >/dev/null ||
    log_event slack_failed
}

# 로컬 PG 역할: primary | standby | down
local_role() {
  local r
  r="$(timeout 10 podman exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc 'select pg_is_in_recovery()' 2>/dev/null)"
  case "$r" in
    f) echo primary ;;
    t) echo standby ;;
    *) echo down ;;
  esac
}

# 상대 PG 역할: primary | standby | down
# 로컬 컨테이너가 없으면(부팅 직후) 일회용 컨테이너로 묻는다.
peer_role() {
  local conn="host=$PEER_IP port=5432 dbname=$POSTGRES_DB user=$POSTGRES_USER sslmode=require connect_timeout=3"
  local r
  if podman container exists "$PG_CONTAINER" && [[ "$(podman inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null)" == true ]]; then
    r="$(timeout 10 podman exec -e PGPASSWORD="$POSTGRES_PASSWORD" "$PG_CONTAINER" psql "$conn" -Atc 'select pg_is_in_recovery()' 2>/dev/null)"
  else
    r="$(timeout 60 podman run --rm --network host -e PGPASSWORD="$POSTGRES_PASSWORD" "$PG_IMAGE" psql "$conn" -Atc 'select pg_is_in_recovery()' 2>/dev/null)"
  fi
  case "$r" in
    f) echo primary ;;
    t) echo standby ;;
    *) echo down ;;
  esac
}

# 볼륨 안 PGDATA의 호스트 경로 (파일은 podman unshare로 다룬다)
pgdata_host_path() {
  echo "$(podman volume inspect "$PG_VOLUME" --format '{{.Mountpoint}}')/18/docker"
}
