#!/usr/bin/env bash
# diary-pg-* 스크립트 공통 함수. 각 스크립트가 source 한다. root로 돈다 (Object Storage 키는 root만 읽는다).
#
# 필요한 파일 (repo에 두지 않는다)
#   /etc/diary/diary.env           SLACK_WEBHOOK_URL (실패 알림. 앱과 같은 값을 읽는다)
#   /etc/diary/objectstorage.env   OCI_S3_ACCESS_KEY, OCI_S3_SECRET_KEY, OCI_NAMESPACE, OCI_BUCKET, OCI_REGION

# shellcheck disable=SC2034 # 이 파일을 source 하는 스크립트가 쓰는 값들이다
set -uo pipefail

readonly STATE_DIR=/var/lib/diary-backup
readonly BACKUP_DIR=/var/backups/diary
readonly OS_PREFIX="diary-pg/" # 버킷에서 이 백업만의 접두사. MySQL 시절의 mysql/ 과 섞이지 않는다
readonly OS_TOOL=/opt/diary/bin/diary-os-put
export DIARY_OS_ENV=/etc/diary/objectstorage.env

mkdir -p "$STATE_DIR" "$BACKUP_DIR"
chmod 700 "$STATE_DIR" "$BACKUP_DIR"

# 값을 변수로만 받는다 (화면과 로그에 찍지 않는다). CHANGE_ME는 비어 있는 것으로 본다
SLACK_WEBHOOK_URL="$(/opt/diary/bin/diary-env get SLACK_WEBHOOK_URL --file /etc/diary/diary.env 2>/dev/null | cat)"
[[ "$SLACK_WEBHOOK_URL" == CHANGE_ME* ]] && SLACK_WEBHOOK_URL=""

# 판단 근거를 한 줄 JSON으로 남긴다. stdout은 journald로, 파일은 journald가 지워져도 남도록.
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
  # webhook 주소는 curl 인자로 나가지 않게 설정 파일(-K)을 표준 입력으로 준다
  printf 'url = "%s"\n' "$SLACK_WEBHOOK_URL" |
    curl -fsS --max-time 5 -H 'Content-Type: application/json' -d "$payload" -K - >/dev/null ||
    log_event slack_failed
}

# 앱 소유자(diary)로 소켓(peer)에 붙어 실행한다. 백업은 앱의 로그인 방식(TCP/소켓)과 상관없이 항상 소켓이다
as_diary() { runuser -u diary -- "$@"; }

# 덤프 안의 diaries 행 수 (COPY 블록의 줄 수. 줄바꿈은 \n으로 이스케이프되어 한 행이 한 줄이다)
dump_rows() {
  pg_restore -a -t diaries -f - "$1" | awk '/^COPY /{c=1; next} /^\\\.$/{c=0} c{n++} END{print n+0}'
}

# 행 단위 해시 목록을 만드는 SQL 파일 (psql -f). 데이터 이전 검증과 같은 파일을 쓴다
readonly ROW_HASH_SQL_FILE=/opt/diary/bin/diary-row-hash.sql
