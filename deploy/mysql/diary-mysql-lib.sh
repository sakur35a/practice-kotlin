#!/usr/bin/env bash
# diary-mysql 스크립트 공통 함수. 각 스크립트가 source 한다.
#
# 필요한 파일 (repo에 두지 않는다)
#   ~/.config/diary-app-db.env   APP_DB_HOST, APP_DB_NAME, APP_DB_USER, APP_DB_PASSWORD (diary-mysql-setup이 만든다)
#   ~/.config/diary-backup.env   SLACK_WEBHOOK_URL (실패 알림용, 없어도 동작한다)

set -uo pipefail

readonly MYSQL_IMAGE="docker.io/library/mysql:8.4"
readonly STATE_DIR="$HOME/.local/state/diary-mysql"

# shellcheck source=/dev/null
source "$HOME/.config/diary-app-db.env"
# shellcheck source=/dev/null
[[ -r "$HOME/.config/diary-backup.env" ]] && source "$HOME/.config/diary-backup.env"

mkdir -p "$STATE_DIR"

# 판단 근거를 한 줄 JSON으로 남긴다. stdout은 journald로, 파일은 재부팅 후에도 남도록.
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
  payload="$(python3 -c 'import json,socket,sys; print(json.dumps({"text": "[diary-mysql " + socket.gethostname() + "] " + sys.argv[1]}))' "$1")"
  curl -fsS --max-time 5 -H 'Content-Type: application/json' -d "$payload" "$SLACK_WEBHOOK_URL" >/dev/null ||
    log_event slack_failed
}
