# 운영 가이드

OCI Always Free VM 두 대에서 diary 앱을 돌리고, DB는 OCI HeatWave MySQL(Always Free)을 쓴다. 이 문서는 구조, 파일 위치, 자주 하는 작업(배포, 백업 복원, 정비)과 겪은 함정을 정리한다.
비밀값(비밀번호, 키, webhook)은 repo에 두지 않고 서버의 env 파일에만 둔다.

## 1. 구성

| | oci-diary | oci-diary-2 |
|---|---|---|
| 접속 | `ssh oci-diary` (opc) | `ssh oci-diary-2` (opc) |
| 사설 IP | 10.0.0.108 | 10.0.0.19 |
| hostname | `my-finance` | `my-finance-db` (옛 이름이 남아 있음) |
| 추가 역할 | 매일 mysqldump 백업, HeatWave 관리자 env | |
| 사양 | AMD micro 1/8 OCPU, RAM 1GB, swap 2.5GB, Oracle Linux 9 | 동일 |

리전 ap-chuncheon-1. VM 서브넷 10.0.0.0/24, HeatWave는 사설 서브넷 10.0.1.0/24(`10.0.1.93:3306`). 프론트(정적, Cloudflare Pages)는 `https://diary-6c6.pages.dev/`이고, 그 JS가 호출하는 API의 공개 진입점은 `https://api.ssobbs13.pp.ua/diary`이다(`pages.dev/api/diary`는 HTML을 주므로 API 확인에 쓰지 않는다).
두 서버의 cloudflared가 같은 터널에 붙어 있고, 터널은 `api.ssobbs13.pp.ua`를 `http://127.0.0.1:8080`(haproxy)으로 넘긴다(설정은 Cloudflare 대시보드에서 관리하며, 서버에서는 `podman logs cloudflared`의 `Updated to new configuration`에서 볼 수 있다).

```
브라우저 → Cloudflare → cloudflared(두 서버) → haproxy 127.0.0.1:8080 ─┬─ 10.0.0.108:18080 (oci-diary 앱) ─┐
                                                                       └─ 10.0.0.19:18080  (oci-diary-2 앱) ┴─ JDBC(TLS) → HeatWave MySQL 10.0.1.93
```

- **haproxy**: 두 앱을 roundrobin으로 본다. `OPTIONS /diary`가 200이 아니면 5초 안에 제외하고, 실패한 요청은 다른 앱으로 재시도한다. 단 POST는 연결 실패만 재시도한다(응답이 실패한 POST를 다시 보내면 중복 저장될 수 있다). 두 서버가 같은 `haproxy.cfg`를 쓴다.
- **앱**: 컨테이너 `diary-app` (`--memory=512m`). 호스트에 `127.0.0.1:18080`, 사설 IP`:18080`(상대 haproxy용), `127.0.0.1:18090`(Actuator)으로 열린다. JPA(Hibernate) + Flyway로 `mydb.diaries`를 쓴다.
- **HeatWave MySQL**: DB 시스템 `mysql20261001232543`, 데이터베이스 `mydb`, 앱 계정 `diary_app`(TLS 필수, `mydb.*`만 권한).

### HeatWave Always Free의 제약 (알고 쓴다)
- **HA 없음, 읽기 복제본 없음.** DB 시스템이 내려가면 두 앱 모두 DB를 잃는다. 앱은 연결 실패를 503으로 돌려주고 회복되면 스스로 이어간다
- **점검 창 월요일 16:44 UTC**(한국 화요일 01:44 전후). 이때 재시작될 수 있고 끌 수 없다. 이 시간대에 앱 배포나 마이그레이션을 하지 않는다
- **버전이 점검 때 강제로 올라간다**(현재 26.7.0-cloud, Innovation). Flyway가 "지원 확인된 버전보다 새롭다"는 경고를 낸다. 배포 뒤 앱 시작 로그와 `/diary`를 확인한다
- **자체 백업은 1일 보관이고 파일로 받을 수 없다.** 복원은 새 DB 시스템으로만 되는데 Always Free는 테넌시당 1개라서 사실상 못 쓴다. 이식 가능한 사본은 아래 mysqldump뿐이다
- 삭제 보호가 켜져 있다. 지우려면 콘솔에서 먼저 풀어야 한다

### 접속 정보가 들어 있는 곳
```
# 두 서버의 ~/.config/diary.env (앱 컨테이너가 읽는다)
SPRING_DATASOURCE_URL=jdbc:mysql://10.0.1.93:3306/mydb?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true&connectTimeout=2000&socketTimeout=30000
SPRING_DATASOURCE_USERNAME=diary_app
SPRING_DATASOURCE_PASSWORD=...
```

### 읽기 캐시 (두 층)
앱 안에는 캐시를 두지 않는다(엣지가 막아 주므로 서버 간 캐시 동기화가 필요 없다). 매번 DB에서 읽는다.

| 층 | 무엇을 | 얼마나 | 글을 쓰면 |
|---|---|---|---|
| Cloudflare 엣지 | `GET /diary*` 200 응답 | 목록 5분, 한 건 1일 (`Cloudflare-CDN-Cache-Control`) | 쓴 서버가 `Cache-Tag: diary-list`를 purge한다(API 토큰 필요) |
| 브라우저 | ETag | 매번 확인(`Cache-Control: no-cache`), 같으면 304 | 새 ETag로 자연히 바뀐다 |

- purge는 **가상 스레드에서 비동기**로 실행한다(POST 응답을 막지 않고, DB 커밋 뒤에 나간다). 그래서 글을 쓴 직후 목록을 읽으면 purge가 끝나기 전의 옛 목록이 엣지에서 나올 수 있다(보통 1초 안쪽). purge가 실패하면 로그(`layer=cache action=edge_purge_failed`)만 남기고 글쓰기는 성공으로 둔다. 그 경우 TTL(5분) 뒤에 맞아진다. 앱 종료 때는 진행 중인 purge를 최대 5초 기다린다
- 엣지 캐시는 Cloudflare **캐시 규칙**이 켜져 있어야 동작한다. 규칙: 호스트 `api.ssobbs13.pp.ua`, 경로가 `/diary`로 시작 → Eligible for cache, Edge TTL "Use cache-control header if present, bypass cache if not", Browser TTL "Respect origin". 404/5xx에는 캐시 헤더가 없어 캐시되지 않는다
- 캐시된 응답이 Origin 없이 온 요청의 것이어도 브라우저가 거부하지 않도록, GET 응답에는 `Access-Control-Allow-Origin: *`를 항상 붙인다
- 무료 플랜의 태그 purge는 분당 요청 수 제한이 있다. 글이 몰려 제한에 걸리면 purge 실패 로그가 남고 엣지 목록은 최대 5분 늦는다
- 캐시를 끄고 싶으면 Cloudflare 캐시 규칙만 끄면 된다

## 2. 파일 위치

| 무엇 | repo | 서버 |
|---|---|---|
| 앱 시작/배포/haproxy | `deploy/start.sh`, `deploy.sh`, `haproxy.cfg` | `~/` (GitHub Actions가 올림) |
| 백업/계정 스크립트 | `deploy/mysql/diary-mysql-*` | `~/.local/bin/` |
| Object Storage 도구 | `deploy/diary-os-put` | `~/.local/bin/` |
| systemd unit | `deploy/mysql/*.service`, `*.timer` | `~/.config/systemd/user/` (oci-diary만) |
| 캐시 설정 | | `~/.config/diary.env`의 `CLOUDFLARE_ZONE_ID`, Secret Manager `diary-cloudflare-api-token`(Zone → Cache Purge 권한만) |
| 앱 DB 비밀값 | | 비밀번호는 Secret Manager `diary-db-password`, URL과 사용자명은 `~/.config/diary.env`. 계정 생성과 백업용 `~/.config/diary-app-db.env`(`APP_DB_HOST/NAME/USER/PASSWORD`, oci-diary에만 둔다. 스크립트가 거기서만 돈다) |
| HeatWave 관리자 | | oci-diary `~/.config/heatwave-admin.env` (`MYSQL_ADMIN_USERNAME/PASSWORD`) |
| Object Storage 키 | | `~/.config/diary-objectstorage.env` (두 서버) |
| Slack webhook | | 앱은 Secret Manager `diary-slack-webhook-url`, 백업 알림은 oci-diary `~/.config/diary-backup.env`의 `SLACK_WEBHOOK_URL` |
| 백업 이벤트 로그 | | oci-diary `~/.local/state/diary-mysql/events.jsonl` |
| 로컬 백업 | | oci-diary `~/mysql-backups/` (최신 3개) |

**`deploy/mysql/`과 `diary-os-put`은 GitHub Actions가 배포하지 않는다.** 서버에 직접 설치한다. 파일은 표준 입력으로 보내고 해시를 대조한다.

```bash
F=deploy/mysql/diary-mysql-backup
md5 -q $F                                    # macOS. 서버 쪽 해시와 같아야 한다
ssh oci-diary "cat > ~/.local/bin/diary-mysql-backup.new && md5sum ~/.local/bin/diary-mysql-backup.new" < $F
ssh oci-diary 'chmod 755 ~/.local/bin/diary-mysql-backup.new && mv ~/.local/bin/diary-mysql-backup.new ~/.local/bin/diary-mysql-backup'
# unit을 바꿨다면: systemctl --user daemon-reload
```

### 앱 계정을 처음 만들 때 (DB 시스템을 새로 만들었을 때)
관리자 env(`heatwave-admin.env`)를 oci-diary에 두고 `diary-mysql-setup`을 실행한다. `mydb`와 `diary_app`을 만들고, 정책에 맞는 비밀번호를 만들어 `~/.config/diary-app-db.env`에 쓴다(출력하지 않는다). 그 값 중 비밀번호는 Secret Manager `diary-db-password`의 새 버전으로 넣고, URL과 사용자명은 두 서버의 `diary.env`에 넣는다. 비밀번호 정책은 대문자, 소문자, 숫자, 특수문자를 모두 요구한다.

## 3. 앱 배포

`v*` 태그를 push하면 GitHub Actions(`publish-image.yml`)가 검사 → 이미지 발행 → 두 서버에 배포 → 공개 API 확인을 한다.

```bash
git tag v0.0.23 && git push origin v0.0.23
```

- 서버에서는 `start.sh`와 `deploy.sh`, `haproxy.cfg`를 올리고 `deploy.sh <이미지>`를 실행한다. `deploy.sh`는 먼저 haproxy 설정을 검사하고(틀리면 앱을 건드리지 않고 멈춘다) 재시작 없이 reload(SIGHUP)한다. 설정 파일은 제자리에서 덮어써야 한다(`scp`, `cat >`). `mv`로 바꾸면 컨테이너가 옛 파일을 본다. 새 컨테이너가 health check(`/diary`)를 통과하지 못하면 이전 컨테이너로 되돌린다. 배포 뒤 최신 3개 버전 태그만 남기고 이전 이미지를 지운다
- 두 서버를 차례로 하므로 한쪽이 재시작하는 동안 다른 쪽이 받는다
- `start.sh`는 `~/.config/diary-gcp-sa.json`(Secret Manager 서비스 계정 키)이 없으면 실패한다. DB 비밀번호, Slack webhook, Cloudflare 토큰은 `diary-db-password`, `diary-slack-webhook-url`, `diary-cloudflare-api-token` 시크릿에서 읽고 서버 `diary.env`에는 두지 않는다. 키 파일은 `644`, `~/.config`는 `700`이어야 한다(컨테이너 앱 uid가 읽어야 하고, 600이면 기동이 Permission denied로 실패한다)
- 앱 이미지가 바뀌지 않는 변경(`haproxy.cfg`, `deploy/mysql/*`)은 태그가 필요 없다. 서버에 직접 올린다
- 스키마는 앱이 시작할 때 Flyway가 맞춘다(`V1__init.sql`). Hibernate는 `ddl-auto: validate`라 스키마가 다르면 시작하지 못한다

## 4. 백업과 복원

- **언제**: oci-diary의 `diary-mysql-backup.timer`가 매일 18:30 UTC(최대 10분 지연)에 실행한다. 타이머는 **한 서버에만** 둔다
- **무엇을**: `mysqldump`(mysql:8.4 컨테이너, `--single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob`)를 gzip한 `mydb-<시각>-<hostname>.sql.gz`. 크기와 내용 점검을 통과해야 올린다
- **어디에**: 서버 `~/mysql-backups/`에 최신 3개, 버킷 `bucket-20260929-0252`(namespace `axnekfrmygfs`, ap-chuncheon-1)의 `mysql/` 아래 **가장 최근 1개(하루치)**
- **정리**: 버킷은 **업로드에 성공한 뒤에만** 이전 백업을 지운다(`diary-os-put prune mysql/ 0 --keep-newest 1`). 업로드가 실패한 날에는 정리가 돌지 않아 이전 백업이 남는다. `--keep-newest`는 최소 1이라 전부 지워지지 않는다. 로컬은 날짜가 아니라 개수로 남긴다
- **주의**: 버킷에 하루치만 있으므로 그날 덤프가 잘못되면(데이터가 이미 망가진 뒤의 백업) 되돌릴 이전 백업이 버킷에 없다. 로컬 3개가 메워 주지만 같은 서버에 있다
- **실패 알림**: 덤프나 업로드가 실패하면 `backup_failed`/`os_upload_failed` 이벤트와 Slack. 타이머가 아예 안 돌면 알리는 곳은 아직 없다 → `backup_done` 이벤트를 가끔 확인한다

```bash
ssh oci-diary 'tail -5 ~/.local/state/diary-mysql/events.jsonl; systemctl --user list-timers diary-mysql-backup.timer'
```

### 복원 검증 (운영 DB를 건드리지 않음)
임시 MySQL 컨테이너에 복원해 건수와 내용을 본다. HeatWave에는 복원하지 않는다.

```bash
ssh oci-diary
~/.local/bin/diary-os-put list mysql/                               # 버킷 목록
~/.local/bin/diary-os-put get mysql/<파일> /tmp/restore.sql.gz       # 또는 ~/mysql-backups/의 파일
podman run -d --name restorecheck -e MYSQL_ROOT_PASSWORD=check --memory=400m docker.io/library/mysql:8.4
# 기동까지 약 30초
gunzip -c /tmp/restore.sql.gz | podman exec -i restorecheck mysql -uroot -pcheck --default-character-set=utf8mb4
podman exec restorecheck mysql -uroot -pcheck -N -e 'select count(*) from mydb.diaries'
podman rm -f restorecheck; rm /tmp/restore.sql.gz
```
2026-10-01에 첫 덤프로 확인했다(30건, 행 단위 해시가 운영 DB와 동일).

### 전체 유실 시 복원
DB 시스템이 사라졌다면: 콘솔에서 새 HeatWave Always Free를 만들고(관리자 계정은 콘솔에서 지정) `diary-mysql-setup`으로 `mydb`와 `diary_app`을 만든다. 앱이 한 번 떠서 Flyway가 스키마를 만들게 하지 말고, 덤프(`--databases mydb`로 떠서 `create database`/`create table`/`flyway_schema_history`를 포함한다)를 관리자 계정으로 먼저 복원한 뒤 `diary_app` 권한을 다시 부여한다. 새 DB의 사설 IP가 바뀌면 두 서버의 `diary.env`와 `diary-app-db.env`를 고치고 앱을 재시작한다.

### Object Storage 접근
- 키는 OCI 콘솔의 **Customer Secret Key**(사용자당 최대 2개, Secret은 발급 때 한 번만 보임)를 두 서버의 `~/.config/diary-objectstorage.env`에 둔다. 다시 발급하면 두 서버 모두 바꾼다(`OCI_NAMESPACE`, `OCI_BUCKET`, `OCI_REGION` 줄은 유지)
- **서버의 `curl --aws-sigv4`는 쓰지 않는다.** curl 7.76은 OCI가 필수로 요구하는 `x-amz-content-sha256` 헤더를 보내지 않아 "secret key could not be found"로 거부된다. 키가 틀린 것처럼 보이지만 아니다. `diary-os-put`이 SigV4를 직접 서명한다(`put`, `get`, `list`, `prune`)

## 5. 메모리와 꺼 둔 것들

- **JVM AOT 캐시**: 이미지를 만들 때(`bootBuildImage`) 빌드 안에서 앱을 한 번 띄워(`application-training.yaml`, DB 없이) 클래스 로딩 결과를 `application.aot`로 이미지에 담고, `start.sh`의 `BPL_JVM_AOTCACHE_ENABLED=true`로 쓴다. 로컬 비교에서 기동 3.3초 → 1.8초(-46%), 메모리도 약간 줄었다. 캐시를 못 읽으면 JVM은 캐시 없이 기동한다. 이미지는 약 140MB 커진다. 끄려면 `start.sh`에서 이 변수를 지운다
- **MetaspaceSize 128M**: 메타스페이스 사용량(약 110MB)이 96M 기준을 넘으면 기동 중 `Metadata GC Threshold` Full GC(300~800ms)가 한 번 났다. 128M은 Full GC 0회이고 메모리 차이는 없었다(oci-diary에서 2회씩 비교)

1GB 서버에서 가용 메모리는 평소 약 350MB 이상이다(DB가 외부로 빠져 PostgreSQL 몫이 없어졌다). 오래 걸리는 작업(`dnf`, 이미지 pull)은 한 번에 한 서버에서만 한다.

| 항목 | 대략적인 사용량 |
|---|---|
| diary-app (JVM) | 200~235MB |
| cloudflared | 35~40MB |
| haproxy | 5~10MB |

꺼 둔 것과 이유:
- `dnf-makecache.timer`: `MemoryHigh=192M` drop-in 때문에 캐시 갱신이 끝나지 않고 18일간 214MB를 잡고 있었다. **OS 업데이트는 수동**이다(아래)
- `pcp`(pmcd, pmlogger, pmie), `tuned`: OCI 이미지 기본 구성이며 이 서버에서는 쓰지 않는다. `dnf upgrade`가 pcp를 다시 켜므로 업데이트 뒤 확인한다. 다시 켜려면 `sudo systemctl enable --now pmcd pmlogger pmie tuned`
- Oracle Cloud Agent 플러그인(콘솔 → 인스턴스 → Oracle Cloud Agent): **Compute Instance Monitoring은 켜 둔다**(유휴 회수 판정에 쓰이는 지표). Run Command, Workload Protection, Custom Logs Monitoring은 끈다
- journald는 영구 저장(최대 200MB)이다(`/etc/systemd/journald.conf.d/persistent.conf`)

## 6. 정비

### 점검(읽기 전용)
```bash
for h in oci-diary oci-diary-2; do ssh $h 'echo "== $(hostname)"; free -m | sed -n 2p; podman ps --format "{{.Names}} {{.Status}}"; systemctl --user --failed --no-legend; curl -s -m 5 http://127.0.0.1:18090/actuator/health'; echo; done
```
부팅 직후에는 `diary-ping.service`가 앱이 뜨기 전에 한 번 실패해 남을 수 있다. 해롭지 않으므로 `systemctl --user reset-failed`로 지운다.

### OS 업데이트 (롤링)
`dnf-makecache`를 꺼 두어 자동 갱신이 없다. 한 번에 한 서버씩 하고, **월요일 16:44 UTC(HeatWave 점검 창)는 피한다**.
1. 최근 백업이 성공했는지 확인한다
2. `sudo dnf upgrade -y`. **재부팅 전에** 새 커널에 `crashkernel=448M`이 붙었는지 본다(`sudo grubby --info=ALL | grep crashkernel`). 붙었다면 `sudo grubby --update-kernel=ALL --remove-args="crashkernel=1G-64G:448M,64G-:512M"`로 뺀다. 안 빼면 MemTotal이 약 946→498MB로 줄어 앱이 못 뜬다. kexec-tools의 posttrans가 다시 넣는다
3. pcp가 다시 켜졌는지 확인하고 마스크한다(`sudo systemctl mask --now pmcd pmlogger pmie`)
4. `sudo reboot`. 재부팅 뒤 앱이 200을 주는지, 다른 서버가 그동안 받았는지 확인한다
5. 다른 서버도 같은 방식으로 한다. 재부팅 중에는 한쪽 앱만 받으므로 두 서버를 함께 내리지 않는다

## 7. 함정

- **macOS zsh에서 `scp file $h:dest`를 쓰지 않는다.** `$h:dest`의 `:s...`가 변수 치환 수정자로 해석되어 scp가 서버 대신 깨진 이름의 로컬 파일을 만들고 성공(exit 0)으로 끝난다. `${h}:dest`로 쓰거나 위처럼 `ssh host 'cat > file' < file`을 쓴다
- 앱 컨테이너(pasta 네트워크)는 사설 IP로 HeatWave에 바로 나간다. 18080(앱)은 firewalld와 OCI 보안 목록 양쪽에서 **상대 서버 IP(/32)만** 허용한다. 하나라도 빠지면 `No route to host`(firewalld) 또는 타임아웃(보안 목록)이 난다. HeatWave 3306은 VM 서브넷에서만 열려 있어야 한다
- MySQL `text`는 64KB다. 본문 컬럼은 `mediumtext`이고, 새 큰 컬럼을 추가하면 길이를 확인한다
- `group_concat`의 기본 길이는 1024바이트다. 긴 목록을 이어 붙이는 쿼리는 `group_concat_max_len`을 올린다
- 앱의 `created_at`은 UTC `datetime(6)`이다. JDBC URL의 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`를 빼지 않는다
- quadlet의 `Exec=` 값에 공백이나 `\ `를 넣으면 unit 파일 구조가 깨진다

## 8. 무료 조건에서 주의할 것

- **GCP Secret Manager 무료는 활성 버전 6개까지**다(`disabled`도 활성으로 센다. 현재 시크릿 3개 × 버전 1개). 이 GCP 프로젝트는 결제가 켜져 있어서 넘으면 청구된다. 값을 바꾸면 새 버전을 넣고 쓰던 옛 버전을 바로 `gcloud secrets versions destroy <N> --secret=<이름> --project key-decorator-356314`로 지운다(값을 바꾸기 전에 길이 확인: `... versions access latest ... | wc -c`)

- OCI Always Free 컴퓨팅은 **7일 동안 CPU와 네트워크 사용률(95번째 백분위)이 모두 20% 미만이면 회수 대상**이다. 이 앱은 사용률이 낮아 해당될 수 있다
- 춘천 리전은 Always Free A1(Arm) 인스턴스를 만들 수 없다
- Object Storage 무료 한도는 20GB, 월 API 요청 5만 건이다. 백업은 하루 몇 건이라 여유가 크다
- 모든 백업이 같은 OCI 계정 안에 있다. 계정이 회수되면 DB와 백업이 함께 사라진다
- GCP는 2026-09-29에 모두 정리했다. 2026-10-01에 DB를 PostgreSQL(VM 두 대 복제)에서 HeatWave MySQL로 옮기고 PostgreSQL 구성(컨테이너, 볼륨, failover 감시, 백업, 5432 규칙, 버킷 `pg/`)을 모두 지웠다
