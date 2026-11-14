#!/usr/bin/env bash
# 데이터 이전 스크립트의 로컬 리허설. 일회용 MySQL 8.4와 PostgreSQL 17 컨테이너를 띄워 실제 스크립트를 끝까지 돌린다.
# 운영에는 아무 영향이 없다 (컨테이너 이름은 diary-*-rehearsal, 끝나면 지운다).
#
#   DOCKER_HOST/CONTAINER_HOST는 podman 소켓을 가리켜야 한다.  deploy/migrate/rehearse.sh
#
# 하는 일
#   1. MySQL에 옛 스키마(binary(16), datetime(6), mediumtext)를 만들고 어려운 행들을 넣는다 (한글, 이모지, 따옴표, 백슬래시,
#      줄바꿈 종류, \.와 \N 같은 COPY 예약어, 255자 제목, 수 MB 본문, 마이크로초 경계 시각, 부호 비트가 다른 id)
#   2. 실제 앱(bootJar)을 PostgreSQL에 띄워 Flyway가 V1과 이력 테이블을 만들게 한다
#   3. diary-export-heatwave → diary-import-heatwave load (자동 검증 포함)
#   4. 스크립트와 별개로: 앱 API(JPA 경로)가 돌려주는 모든 행을 파이썬 원본과 글자 단위로 대조하고, 페이지 순서를 확인한다
#   5. 음성 시험: 손상된 파일, 비어 있지 않은 대상, 행이 바뀐 대상, NUL 문자가 든 원본
set -Eeuo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
mysql_name=diary-mysql-rehearsal
pg_name=diary-pg-rehearsal
app_pid=""
cleanup() {
  [[ -z "$app_pid" ]] || kill "$app_pid" 2>/dev/null || true
  podman rm -f "$mysql_name" "$pg_name" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
step() { printf '\n== %s\n' "$*"; }

step "컨테이너 시작"
podman rm -f "$mysql_name" "$pg_name" >/dev/null 2>&1 || true
podman run -d --name "$mysql_name" -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=mydb docker.io/library/mysql:8.4 \
  --max-allowed-packet=256M >/dev/null
podman run -d --name "$pg_name" -e POSTGRES_USER=diary -e POSTGRES_PASSWORD=diarypw -e POSTGRES_DB=diary \
  -p 127.0.0.1:55432:5432 docker.io/library/postgres:17 >/dev/null
for _ in $(seq 90); do
  podman exec "$mysql_name" mysqladmin -uroot -prootpw ping >/dev/null 2>&1 && \
    podman exec "$pg_name" pg_isready -U diary -d diary >/dev/null 2>&1 && break
  sleep 2
done
# mysql 컨테이너는 초기화 중에 한 번 재시작한다. 진짜 준비될 때까지 한 번 더 확인한다
sleep 5
podman exec "$mysql_name" mysql -uroot -prootpw -e 'select version()' 2>/dev/null

step "MySQL: 옛 스키마와 시험 행"
python3 - "$work" <<'PY'
import json, random, sys, uuid

out = sys.argv[1]
rnd = random.Random(20261114)

def v7(ts_ms, tail):
    msb = (ts_ms << 16) | (7 << 12) | (tail & 0xFFF)
    lsb = (0b10 << 62) | rnd.getrandbits(62)
    return uuid.UUID(int=(msb << 64) | lsb)

def rand_text(n):
    chars = []
    while len(chars) < n:
        cp = rnd.choice([rnd.randint(0x20, 0x7E), rnd.randint(0xAC00, 0xD7A3), rnd.randint(0x1F300, 0x1FAFF),
                         rnd.randint(0x4E00, 0x9FFF), rnd.randint(0xA0, 0x24F), 0x0A, 0x09, 0x5C, 0x22, 0x27])
        chars.append(chr(cp))
    return "".join(chars)

rows = []
def add(ts_ms, created, title, content):
    rows.append({"id": str(v7(ts_ms, rnd.getrandbits(12))), "created": created, "title": title, "content": content})

base = 1_790_000_000_000  # 2026년 가을
add(base + 1, "2026-10-01 00:00:00.000000", "한글 제목 😀", "안녕하세요. 오늘은 날씨가 좋아요 😀🎉")
add(base + 2, "2026-12-31 23:59:59.999999", "따옴표 \"쌍\" '단' `백틱`", "그가 말했다: \"안녕\" 그리고 'ㅎㅎ'. \\ 백슬래시 \\n 은 글자 그대로, \\0 도, \\N 도, \\. 도")
add(base + 3, "1999-01-01 12:34:56.123456", "줄바꿈", "LF\nCRLF\r\n홀로 CR\r탭\t폼피드\x0c수직탭\x0b라인구분\u2028문단구분\u2029NEL\x85끝")
add(base + 4, "2026-03-29 01:30:00.500000", "COPY 예약", "\\.\n\\N\nNULL\n\\\\.\n.\n\\.")
add(base + 5, "2026-11-14 00:00:00.000001", "이모지 ZWJ 😀", "👨\u200d👩\u200d👧\u200d👦 🇰🇷 🏳️\u200d🌈 \u1112\u1161\u11ab 한(NFD) ñ é ☃ 𠮷 \U0001f600")
add(base + 6, "2026-11-14 12:00:00.000000", "", "")  # 빈 제목과 본문
add(base + 7, "2026-11-14 12:00:00.100000", "😀" * 255, "255자 제목 (코드 포인트 기준)")
add(base + 8, "2026-11-14 12:00:01.000000", "큰 본문 한글/이모지", "".join(rnd.choice(["가나다라마바사", "😀🎉✨", "\n", "\"'\\", "abc"]) for _ in range(1_200_000)))
add(base + 9, "2026-11-14 12:00:02.000000", "큰 본문 ASCII", ("line \\ with \\n backslash \"q\" 'q'\n" * 100_000))
# 부호 비트가 다른 id (자바 UUID 비교와 부호 없는 바이트 비교가 갈리는 경우)
rows.append({"id": str(v7(0x8000_0000_0001, 0x123)), "created": "2030-01-01 00:00:00.000000", "title": "먼 미래 id", "content": "msb 최상위 비트가 1"})
for i in range(200):
    add(base + 100 + i * 7, f"2026-11-{1 + i % 28:02d} {i % 24:02d}:{i % 60:02d}:{(i * 7) % 60:02d}.{rnd.randint(0, 999999):06d}",
        rand_text(rnd.randint(1, 60)), rand_text(rnd.randint(0, 3000)))

assert len({r["id"] for r in rows}) == len(rows)
for r in rows:
    assert len(r["title"]) <= 255, r["title"][:20]

with open(f"{out}/fixture.json", "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False)
with open(f"{out}/seed.sql", "w") as f:
    f.write("set names utf8mb4;\n")
    for r in rows:
        f.write("insert into diaries (id, created_at, title, content) values (unhex('%s'), '%s', convert(unhex('%s') using utf8mb4), convert(unhex('%s') using utf8mb4));\n" % (
            uuid.UUID(r["id"]).hex, r["created"], r["title"].encode().hex(), r["content"].encode().hex()))
print(f"{len(rows)} rows, largest content {max(len(r['content'].encode()) for r in rows)} bytes")
PY
# 옛 deploy/mysql 시절의 V1__init.sql (MySQL용). 운영 HeatWave 스키마와 같다
podman exec -i "$mysql_name" mysql -uroot -prootpw --default-character-set=utf8mb4 mydb 2>/dev/null <<'SQL'
create table diaries (
    id         binary(16)   not null primary key,
    created_at datetime(6)  not null,
    title      varchar(255) not null,
    content    mediumtext   not null
);
SQL
podman exec -i "$mysql_name" mysql -uroot -prootpw --default-character-set=utf8mb4 --max-allowed-packet=256M mydb 2>/dev/null <"$work/seed.sql"
podman exec "$mysql_name" mysql -uroot -prootpw -N -e 'select count(*), max(length(content)) from mydb.diaries' 2>/dev/null

step "앱을 PostgreSQL에 띄워 Flyway가 스키마와 이력을 만들게 한다"
(cd "$root" && ./gradlew -q bootJar --console=plain)
SPRING_PROFILES_ACTIVE=prod \
SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:55432/diary?ApplicationName=rehearsal' \
SPRING_DATASOURCE_USERNAME=diary SPRING_DATASOURCE_PASSWORD=diarypw \
  java -Xmx512m -jar "$root/build/libs/diary.jar" --server.port=18099 --management.server.port=18098 >"$work/app.log" 2>&1 &
app_pid=$!
for _ in $(seq 60); do curl -fs "http://127.0.0.1:18099/diary?size=1" >/dev/null 2>&1 && break; sleep 1; done
curl -fs "http://127.0.0.1:18099/diary?size=1"; echo
psql_pg() { podman exec -i "$pg_name" psql -U diary -d diary -X -q -At -v ON_ERROR_STOP=1 "$@"; }
psql_pg -c "select 'flyway: ' || version || ' ' || success from flyway_schema_history"

step "내보내기 (실제 diary-export-heatwave)"
cat >"$work/app-db.env" <<ENV
APP_DB_HOST=127.0.0.1
APP_DB_NAME=mydb
APP_DB_USER=root
APP_DB_PASSWORD=rootpw
ENV
export APP_ENV="$work/app-db.env"
export MYSQL_CMD="podman exec -i -e MYSQL_PWD $mysql_name mysql"
MYSQL_SSL=REQUIRED
export MYSQL_SSL
time "$root/deploy/migrate/diary-export-heatwave" --out "$work/export"
archive="$(ls "$work"/export/diary-migrate-*.tar.gz)"
ls -l "$archive" "$work/export/data.tsv"

step "적재 (실제 diary-import-heatwave load) + 자동 검증"
export DIARY_PSQL="podman exec -i $pg_name psql -U diary -d diary -X -q -At -v ON_ERROR_STOP=1"
time "$root/deploy/migrate/diary-import-heatwave" load "$archive"

step "별도 검증: 앱 API(JPA 경로)가 돌려주는 값을 파이썬 원본과 대조"
python3 - "$work/fixture.json" <<'PY'
import json, sys, urllib.request, uuid
rows = json.load(open(sys.argv[1], encoding="utf-8"))
base = "http://127.0.0.1:18099/diary"
bad = 0
for r in rows:
    with urllib.request.urlopen(f"{base}/{r['id']}") as resp:
        got = json.loads(resp.read().decode("utf-8"))
    if got["title"] != r["title"] or got["content"] != r["content"] or got["id"] != r["id"]:
        bad += 1
        print("불일치:", r["id"])
print(f"상세 {len(rows)}건 중 불일치 {bad}건")
# 목록(keyset) 순서: 부호 없는 16바이트 내림차순이어야 한다
expected = sorted((uuid.UUID(r["id"]).bytes for r in rows), reverse=True)
ids, cursor = [], None
while True:
    url = f"{base}?size=100" + (f"&cursorId={cursor}" if cursor else "")
    with urllib.request.urlopen(url) as resp:
        page = json.loads(resp.read().decode("utf-8"))
    ids += [uuid.UUID(i["id"]).bytes for i in page["items"]]
    if not page["hasNext"]:
        break
    cursor = page["nextCursorId"]
print("목록 순서 일치" if ids == expected else "목록 순서 불일치", f"({len(ids)}건)")
sys.exit(1 if bad or ids != expected else 0)
PY
# created_at이 마이크로초까지 같은지 (API는 노출하지 않으므로 DB를 직접 본다)
python3 - "$work/fixture.json" <<'PY' >"$work/expected-created.txt"
import json, sys, uuid
for r in sorted(json.load(open(sys.argv[1], encoding="utf-8")), key=lambda r: uuid.UUID(r["id"]).bytes):
    print(uuid.UUID(r["id"]), r["created"])
PY
psql_pg -c "select id, to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') from diaries order by id" | tr '|' ' ' >"$work/actual-created.txt"
cmp "$work/expected-created.txt" "$work/actual-created.txt" && echo "created_at 전부 마이크로초까지 같다"
psql_pg -c "select 'flyway: ' || version || ' ' || description || ' success=' || success from flyway_schema_history"

step "음성 시험"
expect_fail() { # 이름 명령...
  local name="$1"; shift
  if "$@" >"$work/neg.log" 2>&1; then echo "실패해야 하는데 통과: $name"; cat "$work/neg.log"; exit 1; fi
  echo "OK (거부됨) $name: $(tail -n 1 "$work/neg.log" | cut -c1-110)"
}
expect_fail "이미 행이 있는 대상에 적재" "$root/deploy/migrate/diary-import-heatwave" load "$archive"
mkdir "$work/tampered" && tar -C "$work/tampered" -xzf "$archive"
printf 'x' >>"$work/tampered/data.tsv"
expect_fail "손상된 파일(SHA256SUMS)" "$root/deploy/migrate/diary-import-heatwave" verify "$work/tampered"
psql_pg -c "update diaries set content = content || 'x' where id = (select id from diaries order by id limit 1)"
expect_fail "행 하나가 바뀐 대상 검증" "$root/deploy/migrate/diary-import-heatwave" verify "$archive"
psql_pg -c "update diaries set content = left(content, length(content) - 1) where id = (select id from diaries order by id limit 1)"
"$root/deploy/migrate/diary-import-heatwave" verify "$archive" | tail -n 1
podman exec -i "$mysql_name" mysql -uroot -prootpw mydb 2>/dev/null -e "insert into diaries values (unhex('0123456789abcdef0123456789abcdef'), now(6), 'nul', concat('a', char(0), 'b'))"
expect_fail "NUL 문자가 든 원본" "$root/deploy/migrate/diary-export-heatwave" --out "$work/export-nul"
step "리허설 통과"
