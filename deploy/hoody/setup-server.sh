#!/usr/bin/env bash
# hoody 컴퓨터(default)에서 한 번 root로 돌린다. 다시 돌려도 이미 있는 건 건드리지 않는다 (스크립트와 유닛 파일만 새 것으로 덮어쓴다).
#
# 하는 일: 패키지(JDK 25 등), 사용자, 데이터베이스 diary와 최소 권한 역할 diary, 환경 파일(자리표시값), 앱·배포·백업 유닛.
# 이 컴퓨터에는 PostgreSQL 17과 cloudflared가 이미 돌고 있다(다른 앱 cheotmadi가 쓴다). 둘 다 새로 설치하거나 설정을 바꾸지 않는다.
#
# 이 스크립트가 들어 있는 deploy/ 폴더 전체를 컴퓨터에 올려 둔 채로 돌린다:
#   scp -r deploy hoody-diary:/root/diary-deploy && ssh hoody-diary /root/diary-deploy/hoody/setup-server.sh
#
# 비밀 값은 화면에 찍지 않는다. 생성한 DB 비밀번호는 /etc/diary/diary.env에만 쓴다.
# 사람이 채워야 하는 값은 CHANGE_ME로 두고, diary-pull-deploy가 CHANGE_ME가 남아 있으면 배포를 거부한다.
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo "root로 실행한다" >&2; exit 1; }

here="$(cd "$(dirname "$0")" && pwd)"
deploy_dir="$(dirname "$here")"

say() { echo "setup: $*"; }

# --- 패키지 ---------------------------------------------------------------
# JDK 25는 Debian 13 저장소에 있다(openjdk-25). 빌드(Gradle)가 JDK를 쓰므로 jre가 아니라 jdk다.
say "패키지 설치"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-25-jdk-headless git curl ca-certificates python3 postgresql-client openssl util-linux >/dev/null
command -v cloudflared >/dev/null || { echo "cloudflared가 없다. 이 컴퓨터에는 이미 있어야 한다 (/etc/cloudflared/config.yml)" >&2; exit 1; }

# --- 사용자와 디렉터리 -------------------------------------------------------
# diary: 앱 실행 전용(로그인 불가). diary-build: 소스를 받아 빌드하는 전용 사용자(비밀 값을 읽지 못한다)
id diary >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin diary
id diary-build >/dev/null 2>&1 || useradd --system --home-dir /var/lib/diary-build --create-home --shell /usr/sbin/nologin diary-build
install -d -o diary-build -g diary-build -m 750 /var/lib/diary-build
install -d -m 755 /opt/diary /opt/diary/bin /opt/diary/releases
install -d -m 750 -o root -g diary /etc/diary
install -d -m 755 /var/lib/diary-deploy
install -d -m 700 /var/backups/diary /var/lib/diary-backup

# --- 스크립트와 유닛 -----------------------------------------------------------
say "스크립트와 유닛 파일 설치"
for f in diary-run diary-wait-healthy diary-pull-deploy diary-env diary-db-check diary-ingress diary-pg-backup diary-pg-lib.sh diary-pg-restore-check; do
  install -m 755 "$here/$f" "/opt/diary/bin/$f"
done
install -m 644 "$here/diary-jvm.conf" "$here/diary-row-hash.sql" /opt/diary/bin/
install -m 755 "$deploy_dir/diary-os-put" /opt/diary/bin/diary-os-put
install -m 755 "$deploy_dir/migrate/diary-import-heatwave" /opt/diary/bin/diary-import-heatwave
for f in diary.service diary-deploy.service diary-deploy.timer diary-pg-backup.service diary-pg-backup.timer; do
  install -m 644 "$here/$f" "/etc/systemd/system/$f"
done

# journald: 재부팅 뒤에도 로그가 남게 한다 (옛 운영 가이드도 영구 저장이었다). 크기·보관 기간 설정은 건드리지 않는다.
# cheotmadi-api가 /etc/systemd/journald.conf.d/retention.conf에 MaxRetentionSec=90day(처리방침)를 이미 두었을 수 있고,
# 이 컴퓨터 전체 설정이라 여기서 덮지 않는다. /var/log/journal 디렉터리만 있으면 Storage=auto가 영구 저장이 된다
install -d -m 2755 -g systemd-journal /var/log/journal
systemd-tmpfiles --create --prefix /var/log/journal >/dev/null 2>&1 || true
journalctl --flush >/dev/null 2>&1 || true

# --- 환경 파일 --------------------------------------------------------------
# 이미 있으면 덮어쓰지 않는다 (값을 이미 채웠을 수 있다)
if [[ ! -f /etc/diary/diary.env ]]; then
  umask 027
  # 기본은 유닉스 소켓(peer 인증)이다. 아래 TCP 줄은 diary-db-check로 시험한 뒤 필요할 때만 바꿔 쓴다
  db_url='jdbc:postgresql://localhost/diary?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432&ApplicationName=diary&connectTimeout=5&socketTimeout=30'
  alt_url='jdbc:postgresql://127.0.0.1:5432/diary?ApplicationName=diary&connectTimeout=5&socketTimeout=30'
  cat >/etc/diary/diary.env <<ENV
# diary 앱 환경 (systemd EnvironmentFile). root:diary 0640. 값에 따옴표를 붙이지 않는다.
# 값은 diary-env로 채운다 (화면과 셸 기록에 남지 않는다):  pbpaste | ssh hoody-diary diary-env set KEY
SPRING_PROFILES_ACTIVE=prod
# 앱이 듣는 loopback 포트. 떠나는 앱(cheotmadi)이 8080을 쓰는 동안 겹치지 않게 18080이다. 바인딩 주소 127.0.0.1은 diary-run이 고정한다
SERVER_PORT=18080
MANAGEMENT_SERVER_PORT=18081
# DB 연결은 이 한 줄이 전부다. 유닉스 소켓(peer 인증, 비밀번호를 쓰지 않음)이 기본이고, 방화벽이 loopback TCP를 diary 사용자에게
# 막지 않는다는 것을 diary-db-check로 확인했을 때만 아래 TCP 줄로 바꿀 수 있다 (사용자 이름은 같고 비밀번호가 쓰인다).
# SPRING_DATASOURCE_URL=$alt_url
SPRING_DATASOURCE_URL=$db_url
SPRING_DATASOURCE_USERNAME=diary
SPRING_DATASOURCE_PASSWORD=CHANGE_ME
# 앱 오류 알림과 백업 실패 알림이 같이 쓴다 (OCI Vault diary-slack-webhook-url 이었던 값)
SLACK_WEBHOOK_URL=CHANGE_ME
# 글이 써지면 Cloudflare 엣지 목록 캐시를 지운다 (OCI Vault diary-cloudflare-api-token 이었던 값. Zone > Cache Purge 권한만)
CLOUDFLARE_ZONE_ID=CHANGE_ME
CLOUDFLARE_API_TOKEN=CHANGE_ME
ENV
  chown root:diary /etc/diary/diary.env
  chmod 640 /etc/diary/diary.env
  say "/etc/diary/diary.env 생성 (CHANGE_ME를 채운다)"
fi
if [[ ! -f /etc/diary/objectstorage.env ]]; then
  umask 077
  cat >/etc/diary/objectstorage.env <<'ENV'
# Object Storage(S3 호환) 접근 키. root:root 0600. diary-os-put과 백업이 읽는다
# (옛 oci-diary의 ~/.config/diary-objectstorage.env 와 같은 항목. namespace/bucket/region은 deploy/README.md 참고)
OCI_S3_ACCESS_KEY=CHANGE_ME
OCI_S3_SECRET_KEY=CHANGE_ME
OCI_NAMESPACE=axnekfrmygfs
OCI_BUCKET=bucket-20260929-0252
OCI_REGION=ap-chuncheon-1
ENV
  chmod 600 /etc/diary/objectstorage.env
fi

# --- PostgreSQL: 데이터베이스와 역할 -------------------------------------------
say "PostgreSQL: 데이터베이스 diary와 역할 diary"
pg() { runuser -u postgres -- psql -X -q -At -v ON_ERROR_STOP=1 "$@"; }
pg -c 'select 1' >/dev/null || { echo "PostgreSQL에 소켓으로 접속하지 못했다 (postgresql 서비스를 확인한다)" >&2; exit 1; }

# 최소 권한 역할: 로그인만 되고, 슈퍼유저/DB 생성/역할 생성/복제/RLS 우회가 모두 없다. 연결 수는 앱(풀 4 + Flyway)과 백업 정도다.
# 비밀번호는 loopback TCP로 붙을 때만 쓴다. 소켓(peer)으로 붙으면 쓰이지 않는다.
if [[ "$(pg -c "select 1 from pg_roles where rolname = 'diary'")" != 1 ]]; then
  pg -c "create role diary login nosuperuser nocreatedb nocreaterole noreplication nobypassrls connection limit 20"
fi
# 비밀번호는 한 번만 만든다. env 파일에 이미 있으면 그 값을 따른다 (재실행해도 앱 설정이 깨지지 않게)
if ! /opt/diary/bin/diary-env has SPRING_DATASOURCE_PASSWORD; then
  password="$(openssl rand -base64 33 | tr '+/' '-_' | tr -d '=\n')"
  printf '%s' "$password" | /opt/diary/bin/diary-env set SPRING_DATASOURCE_PASSWORD >/dev/null
  unset password
fi
# 값은 env 파일에서 읽어 psql 표준 입력으로만 넘긴다 (프로세스 인자에 나오지 않는다). 저장은 PostgreSQL 기본인 scram-sha-256이다
{
  printf "alter role diary password '"
  /opt/diary/bin/diary-env get SPRING_DATASOURCE_PASSWORD | tr -d '\n'
  printf "';\n"
} | pg >/dev/null

if [[ "$(pg -c "select 1 from pg_database where datname = 'diary'")" != 1 ]]; then
  # template0 + 명시적 로케일: 시스템 기본 로케일에 따라 달라지지 않게 한다 (재현성)
  pg -c "create database diary owner diary encoding 'UTF8' locale 'C.UTF-8' template template0"
fi
# 다른 역할(cheotmadi 앱 등)이 이 DB에 접속하지 못하게 한다. 이쪽 DB의 권한만 바꾼다. 다른 DB는 건드리지 않는다
pg -d diary -c "revoke all on database diary from public" -c "grant connect on database diary to diary" -c "revoke all on schema public from public"
say "diary 역할 검사: $(pg -c "select concat(rolsuper::int, rolcreatedb::int, rolcreaterole::int, rolreplication::int, rolbypassrls::int) from pg_roles where rolname = 'diary'") (모두 0이어야 한다)"

systemctl daemon-reload
systemctl enable diary.service >/dev/null 2>&1
# 백업 타이머는 데이터를 옮기고 Object Storage 키를 채운 뒤 켠다 (deploy/README.md 8절)

cat <<DONE

setup 끝. 남은 일:
  1. 비밀 값 채우기:  pbpaste | ssh hoody-diary diary-env set <KEY>   (diary-env has로 확인)
$(grep -l CHANGE_ME /etc/diary/*.env 2>/dev/null | sed 's/^/     CHANGE_ME가 남은 파일: /')
  2. DB 연결 시험:    /opt/diary/bin/diary-db-check
  3. 자동 배포:       $here/setup-auto-deploy.sh
DONE
