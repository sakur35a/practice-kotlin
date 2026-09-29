# 운영 가이드

OCI Always Free VM 두 대에서 diary 앱과 PostgreSQL을 이중화해 운영한다. 이 문서는 구조, 파일 위치, 자주 하는 작업(배포, 장애 전환, 백업 복원, 정비)과 겪은 함정을 정리한다.
비밀값(비밀번호, 키, webhook)은 repo에 두지 않고 서버의 env 파일에만 둔다.

## 1. 구성

| | oci-diary | oci-diary-2 |
|---|---|---|
| 접속 | `ssh oci-diary` (opc) | `ssh oci-diary-2` (opc) |
| 사설 IP | 10.0.0.108 | 10.0.0.19 |
| hostname | `my-finance` | `my-finance-db` (옛 이름이 남아 있음) |
| PostgreSQL 역할 | primary (평소) | standby (평소) |
| 사양 | AMD micro 1/8 OCPU, RAM 1GB, swap 2.5GB, Oracle Linux 9 | 동일 |

리전 ap-chuncheon-1, 서브넷 10.0.0.0/24. 공개 진입점은 `https://diary-6c6.pages.dev/api/diary`이다.
두 서버의 cloudflared가 같은 터널에 붙어 있고, 터널은 `api.ssobbs13.pp.ua`를 `http://127.0.0.1:8080`(haproxy)으로 넘긴다(설정은 Cloudflare 대시보드에서 관리하며, 서버에서는 `podman logs cloudflared`의 `Updated to new configuration`에서 볼 수 있다).

```
브라우저 → Cloudflare → cloudflared(두 서버) → haproxy 127.0.0.1:8080 ─┬─ 10.0.0.108:18080 (oci-diary 앱)
                                                                       └─ 10.0.0.19:18080  (oci-diary-2 앱)

앱 ─ JDBC (targetServerType=primary) ─▶ PostgreSQL primary ══ 스트리밍 복제 ══▶ standby
```

- **haproxy**: 두 앱을 roundrobin으로 본다. `OPTIONS /diary`가 200이 아니면 5초 안에 제외하고, 실패한 요청은 다른 앱으로 재시도한다. 두 서버가 같은 `haproxy.cfg`를 쓴다.
- **앱**: 컨테이너 `diary-app` (`--memory=512m`). 호스트에 `127.0.0.1:18080`, 사설 IP`:18080`(상대 haproxy용), `127.0.0.1:18090`(Actuator)으로 열린다.
- **PostgreSQL 18.6**: rootless podman quadlet `diary-pg` (호스트 네트워크, 5432). standby는 primary에서 스트리밍 복제로 따라간다.

### 접속 정보가 들어 있는 곳
앱 컨테이너는 pasta 네트워크라서 **자기 호스트의 DB는 `host.containers.internal`로, 상대 호스트는 사설 IP로** 가리킨다.

```
# oci-diary의 ~/.config/diary.env
SPRING_DATASOURCE_URL=jdbc:postgresql://host.containers.internal:5432,10.0.0.19:5432/mydb?targetServerType=primary&sslmode=require&connectTimeout=2&loginTimeout=5&socketTimeout=30&hostRecheckSeconds=2
# oci-diary-2는 호스트 순서만 다르다: 10.0.0.108:5432,host.containers.internal:5432
```

## 2. 파일 위치

| 무엇 | repo | 서버 |
|---|---|---|
| 앱 시작/배포/haproxy | `deploy/start.sh`, `deploy.sh`, `haproxy.cfg` | `~/` (GitHub Actions가 올림) |
| PG 스크립트 | `deploy/pg/diary-pg-*`, `diary-os-put` | `~/.local/bin/` |
| systemd unit | `deploy/pg/*.service`, `*.timer` | `~/.config/systemd/user/` |
| PG quadlet | `deploy/pg/diary-pg.container` | `~/.config/containers/systemd/` |
| 앱 비밀값 | | `~/.config/diary.env` (DB URL/계정), `~/.bashrc`의 `SLACK_WEBHOOK_URL` |
| PG 비밀값 | | `~/.config/diary-pg.env` (`POSTGRES_PASSWORD`, `REPL_PASSWORD`) |
| PG 설정 | `pg_hba.conf.example` | `~/.config/diary-pg/` (`pg_hba.conf`, `server.crt/key`, `failover.env`) |
| Object Storage 키 | | `~/.config/diary-objectstorage.env` |
| 이벤트 로그 | | `~/.local/state/diary-pg/events.jsonl` |
| 백업 | | `~/pg-backups/` (최신 3개) |

**`deploy/pg/`의 파일은 GitHub Actions가 배포하지 않는다.** 서버에 직접 설치한다. 파일은 표준 입력으로 보내고 해시를 대조한다.

```bash
F=deploy/pg/diary-pg-backup
md5 -q $F                                    # macOS. 서버 쪽 해시와 같아야 한다
ssh oci-diary "cat > ~/.local/bin/diary-pg-backup.new && md5sum ~/.local/bin/diary-pg-backup.new" < $F
ssh oci-diary 'chmod 755 ~/.local/bin/diary-pg-backup.new && mv ~/.local/bin/diary-pg-backup.new ~/.local/bin/diary-pg-backup'
# unit을 바꿨다면: systemctl --user daemon-reload (quadlet 포함)
```

두 서버 모두에 같은 파일을 설치한다. `failover.env`만 호스트마다 다르다(`SELF_IP`, `PEER_IP`, `SLOT_NAME`).

## 3. 앱 배포

`v*` 태그를 push하면 GitHub Actions(`publish-image.yml`)가 검사 → 이미지 발행 → 두 서버에 배포 → 공개 API 확인을 한다.

```bash
git tag v0.0.22 && git push origin v0.0.22
```

- 서버에서는 `start.sh`와 `deploy.sh`, `haproxy.cfg`를 올리고 haproxy를 재시작한 뒤 `deploy.sh <이미지>`를 실행한다. 새 컨테이너가 health check(`/diary`)를 통과하지 못하면 이전 컨테이너로 되돌린다.
- 배포에 성공하면 옛 앱 이미지를 지운다. 숫자 버전 태그 중 **최신 3개만 남긴다**(`prune_old_images`). 이전에는 배포마다 약 150MB씩 쌓였다. 실행 중인 컨테이너의 이미지는 지워지지 않고, 정리가 실패해도 배포 결과에는 영향이 없다. 지운 버전이 필요하면 ghcr.io에서 태그로 다시 받는다
- 두 서버를 차례로 하므로 한쪽이 재시작하는 동안 다른 쪽이 받는다.
- `start.sh`는 `SLACK_WEBHOOK_URL`이 없으면 실패한다.
- 앱 이미지가 바뀌지 않는 변경(`haproxy.cfg`, `deploy/pg/*`)은 태그가 필요 없다. 서버에 직접 올린다.

## 4. PostgreSQL 장애 전환

### 자동 승격 (`diary-pg-failover`, 두 서버에서 상주)
로컬이 standby일 때만 10초 간격으로 상대 primary를 확인한다. 연속 2번 실패하면 아래 안전장치를 거쳐 승격한다.

1. **연속 실패**: `FAIL_THRESHOLD`(2)회
2. **자기 고립 확인**: 게이트웨이와 외부(1.1.1.1)에 모두 닿지 않으면 승격하지 않는다
3. **옛 primary 차단**: SSH로 상대 PG를 중지하고 `~/.config/diary-pg/FENCED`를 만든다. 중지하지 못하면 승격을 중단한다. 상대에 SSH도 닿지 않으면 `FENCE_UNREACHABLE`(현재 `abort`)에 따라 승격하지 않고 Slack으로 알린다
4. **부팅 시 자동 primary 금지** (`diary-pg-guard`, PG 기동 전): 차단됐거나 상대가 이미 primary면 standby로 기동한다
5. **기동 직후 유예**: 상대를 primary로 한 번도 보지 못했다면 감시 시작 후 `STARTUP_GRACE`(300초) 동안 실패를 세지 않는다. 두 서버를 함께 재부팅할 때 오판 승격을 막는다

실측: primary를 중지하면 약 22초 뒤 승격되고, 앱은 약 24초 뒤 정상 조회한다.

### 이벤트 로그 읽기
```bash
ssh oci-diary-2 'tail -20 ~/.local/state/diary-pg/events.jsonl'
```
주요 이벤트: `peer_check_failed`(→ `_in_grace`), `peer_recovered`, `failover_start`, `isolation_check`, `fence_result`, `promoted`, `promote_failed`, `failover_abort`, `guard_start`, `guard_demoted`, `split_brain`, `no_primary`, `backup_done`, `backup_skipped`, `os_upload_failed`. 승격, 중단, split brain은 Slack으로도 알린다.

### 승격 뒤 옛 primary를 standby로 복귀
옛 primary에서 실행한다. 볼륨을 `~/pg-backups/pgdata-before-rejoin-*.tar`로 내보낸 뒤 primary에서 `pg_basebackup`으로 다시 만든다.

```bash
ssh <옛 primary> '~/.local/bin/diary-pg-rejoin'
```
확인한 뒤 남은 tar는 지운다. 시험 때 하나가 약 83MB였고, 지우지 않으면 디스크에 계속 쌓인다.

### 계획된 점검 (primary를 내려야 할 때)
자동 전환 경로를 그대로 쓰는 것이 가장 짧다(약 24초, 두 번 시험). 감시를 멈추지 않는다.
1. 백업이 최근 24시간 안에 성공했는지 확인한다
2. primary에서 `systemctl --user stop diary-pg` → 약 20초 뒤 standby가 승격된다
3. 옛 primary에서 작업(재부팅 등)을 한다
4. 작업 뒤 `diary-pg-rejoin`으로 standby로 복귀시킨다

**primary PG를 일부러 재시작하면서 감시를 유지하지 않는다.** 그러면 20초 뒤 승격이 일어난다. 재시작만 하고 싶다면 standby에서 먼저 `systemctl --user stop diary-pg-failover`로 감시를 멈추고, 끝나면 다시 시작한다.

### 수동 승격 / split brain
- 자동 승격이 중단된 뒤(`failover_abort`)에는 **옛 primary가 정말 내려가 있는지 먼저 확인**하고 승격한다. 살아 있는 primary가 있는데 승격하면 두 서버가 모두 primary가 된다.
  ```bash
  ssh <standby> 'podman exec diary-pg psql -U myuser -d mydb -c "select pg_promote(true, 60)"'
  ```
- `split_brain` 이벤트(두 서버가 모두 primary)가 오면 어느 쪽에 최신 데이터가 있는지 확인한다. 한쪽을 중지하고 `diary-pg-rejoin`으로 복귀시킨다. 유실될 쪽의 새 데이터는 그 전에 덤프를 떠 둔다.

## 5. 백업과 복원

- **언제**: 두 서버의 `diary-pg-backup.timer`가 매일 18:30 UTC(최대 10분 지연)에 실행한다. **현재 primary에서만** 덤프하고 standby는 `backup_skipped`를 남긴다. 장애 전환으로 primary가 바뀌면 새 primary가 이어받는다
- **어디에**: 서버 `~/pg-backups/`에 최신 3개(`mydb-<시각>-<hostname>.dump`, `pg_dump -Fc`), 버킷 `bucket-20260929-0252`(namespace `axnekfrmygfs`, ap-chuncheon-1)의 `pg/` 아래 30일
- **정리**: 버킷은 업로드할 때마다 30일 지난 객체를 지우되 최신 3개는 항상 남긴다. 로컬은 날짜가 아니라 개수로 남긴다
- **실패 알림**: 업로드가 실패하면 `os_upload_failed` 이벤트와 Slack. 타이머가 아예 안 돌면 알리는 곳은 아직 없다 → `backup_done` 이벤트를 가끔 확인한다

### 복원 검증 (운영 DB를 건드리지 않음)
primary에서 임시 DB로 복원해 건수를 본다. standby에서는 DB를 만들 수 없다.

```bash
ssh oci-diary
~/.local/bin/diary-os-put list pg/                              # 버킷 목록
~/.local/bin/diary-os-put get pg/<파일> /tmp/restore.dump       # 또는 ~/pg-backups/의 파일
podman cp /tmp/restore.dump diary-pg:/tmp/restore.dump
podman exec diary-pg createdb -U myuser restorecheck
podman exec diary-pg pg_restore -U myuser -d restorecheck --no-owner /tmp/restore.dump
podman exec diary-pg psql -U myuser -d restorecheck -c 'select count(*) from diaries'
podman exec diary-pg dropdb -U myuser restorecheck
rm /tmp/restore.dump; podman exec diary-pg rm /tmp/restore.dump
```
2026-09-29에 버킷의 덤프로 확인했다(`diaries` 30건 = 운영 DB).

### 전체 유실 시 복원
두 서버의 DB가 모두 사라졌다면: 새 `diary-pg`를 띄운다(quadlet이 `POSTGRES_*` env로 `mydb`와 `myuser`를 만든다). 덤프를 `pg_restore -U myuser -d mydb --no-owner`로 복원하고, 다른 서버는 `diary-pg-rejoin`으로 standby를 다시 만든다. Flyway 이력 테이블도 덤프에 포함되어 있어 앱이 마이그레이션을 다시 돌리지 않는다.

### Object Storage 접근
- 키는 OCI 콘솔의 **Customer Secret Key**(사용자당 최대 2개, Secret은 발급 때 한 번만 보임)를 두 서버의 `~/.config/diary-objectstorage.env`에 둔다. 다시 발급하면 두 서버 모두 바꾼다(`OCI_NAMESPACE`, `OCI_BUCKET`, `OCI_REGION` 줄은 유지)
- **서버의 `curl --aws-sigv4`는 쓰지 않는다.** curl 7.76은 OCI가 필수로 요구하는 `x-amz-content-sha256` 헤더를 보내지 않아 "secret key could not be found"로 거부된다. 키가 틀린 것처럼 보이지만 아니다. `diary-os-put`이 SigV4를 직접 서명한다

## 6. 메모리와 꺼 둔 것들

1GB 서버에서 가용 메모리는 평소 약 310~340MB이다. 오래 걸리는 작업(`dnf`, 이미지 pull)은 한 번에 한 서버에서만 한다.

| 항목 | 대략적인 사용량 |
|---|---|
| diary-app (JVM) | 200~235MB |
| diary-pg | 35~45MB (프로세스 약 8MB, 나머지는 회수 가능한 파일 캐시) |
| cloudflared | 35~40MB |
| haproxy | 5~10MB |

꺼 둔 것과 이유:
- `dnf-makecache.timer`: `MemoryHigh=192M` drop-in 때문에 캐시 갱신이 끝나지 않고 18일간 214MB를 잡고 있었다. **OS 업데이트는 수동**이다(아래)
- `pcp`(pmcd, pmlogger, pmie), `tuned`: OCI 이미지 기본 구성이며 이 서버에서는 쓰지 않는다. 다시 켜려면 `sudo systemctl enable --now pmcd pmlogger pmie tuned`
- Oracle Cloud Agent 플러그인(콘솔 → 인스턴스 → Oracle Cloud Agent): **Compute Instance Monitoring은 켜 둔다**(유휴 회수 판정에 쓰이는 지표). Run Command, Workload Protection, Custom Logs Monitoring은 끈다
- journald는 영구 저장(최대 200MB)이다(`/etc/systemd/journald.conf.d/persistent.conf`). **컨테이너 로그도 모두 journald로 간다**(로그 드라이버 journald). 컨테이너별 로그 파일이 없어서 크기 제한 옵션은 필요 없다. `podman logs <이름>`으로 본다

## 7. 정비

### 점검(읽기 전용)
```bash
for h in oci-diary oci-diary-2; do ssh $h 'echo "== $(hostname)"; free -m | sed -n 2p; podman ps --format "{{.Names}} {{.Status}}"; systemctl --user --failed --no-legend; tail -3 ~/.local/state/diary-pg/events.jsonl | cut -c1-160; podman exec diary-pg psql -U myuser -d mydb -Atc "select case when pg_is_in_recovery() then \$\$standby\$\$ else \$\$primary\$\$ end"'; done
```
부팅 직후에는 `diary-ping.service`가 앱이 뜨기 전에 한 번 실패해 남을 수 있다. 해롭지 않으므로 `systemctl --user reset-failed`로 지운다.

### OS 업데이트 (롤링)
`dnf-makecache`를 꺼 두어 자동 갱신이 없다. 한 번에 한 서버씩 한다.
1. 최근 백업이 성공했는지 확인한다
2. **standby부터**: `sudo dnf upgrade -y` → `sudo reboot`
3. 재부팅 뒤 PG가 standby로 `streaming` 중이고 앱이 200을 주는지 확인한다
4. **primary**: 4장의 "계획된 점검" 절차로 전환한 뒤 같은 방식으로 업데이트하고 `diary-pg-rejoin`으로 복귀시킨다

### PostgreSQL 마이너 업그레이드
이미지 태그(`postgres:18.6`)는 quadlet 파일에 고정되어 있다. standby에서 `Image=`를 바꾸고 `daemon-reload` → `systemctl --user restart diary-pg`, 복제 상태를 확인한다. 그 뒤 primary는 "계획된 점검" 절차로 전환하고 같은 방식으로 올린다.

## 8. 함정

- **macOS zsh에서 `scp file $h:dest`를 쓰지 않는다.** `$h:dest`의 `:s...`가 변수 치환 수정자로 해석되어 scp가 서버 대신 깨진 이름의 로컬 파일을 만들고 성공(exit 0)으로 끝난다. `${h}:dest`로 쓰거나 위처럼 `ssh host 'cat > file' < file`을 쓴다
- PG 설정 디렉터리 `~/.config/diary-pg`는 권한 711이어야 한다. 700이면 컨테이너 안 postgres가 인증서를 읽지 못해 기동하지 못한다
- 5432(PG)와 18080(앱)은 firewalld와 OCI 보안 목록 양쪽에서 **상대 서버 IP(/32)만** 허용한다. 하나라도 빠지면 `No route to host`(firewalld) 또는 타임아웃(보안 목록)이 난다
- `diary-peer` SSH 별칭은 옛 primary 차단과 `diary-pg-rejoin`에서 쓴다. 지우지 않는다
- quadlet의 `Exec=` 값에 공백이나 `\ `를 넣으면 unit 파일 구조가 깨진다. `log_line_prefix`는 공백 없는 값을 쓴다

## 9. 무료 조건에서 주의할 것

- OCI Always Free 컴퓨팅은 **7일 동안 CPU와 네트워크 사용률(95번째 백분위)이 모두 20% 미만이면 회수 대상**이다. 이 앱은 사용률이 낮아 해당될 수 있다
- 춘천 리전은 Always Free A1(Arm) 인스턴스를 만들 수 없다
- Object Storage 무료 한도는 20GB, 월 API 요청 5만 건이다. 백업은 하루 몇 건이라 여유가 크다
- 모든 백업이 같은 OCI 계정 안에 있다. 계정이 회수되면 함께 사라진다
- GCP는 2026-09-29에 모두 정리했다(VM, 디스크, 서비스 계정, 방화벽 규칙). 옛 IAP tunnel 구성은 남아 있지 않다
