# 운영 가이드

diary 앱을 hoody 컴퓨터 하나(`default`)에서 돌린다. DB는 같은 컴퓨터의 PostgreSQL 17이고, 공개 주소는 그 컴퓨터에 이미 있는 `cloudflared`가 이어 준다. 이 문서는 구조, 파일 위치, 자주 하는 작업(배포, 백업 복원), OCI에서 옮겨 오는 절차(컷오버)와 겪은 함정을 정리한다.
비밀값(DB 비밀번호, 키, webhook)은 repo에 두지 않는다. 컴퓨터의 `/etc/diary/*.env`에만 둔다.

> 2026-11-14에 OCI(VM 두 대 + HeatWave MySQL)에서 hoody로 옮기기로 하고 코드와 배포 파일을 바꿨다. 무엇이 왜 바뀌었는지는 12절에 있다. **컷오버는 아직 하지 않았다.** 8절이 그 절차다. OCI는 그때까지 옛 이미지 그대로 돌고 있다.

## 1. 구성

| | hoody `default` (컴퓨터 하나) |
|---|---|
| 접속 | `ssh hoody-diary` (root. `~/.ssh/config`에 별칭을 둔다. 주소와 키는 hoody 대시보드의 SSH 정보) |
| OS, 사양 | Debian 13, **CPU 2코어와 RAM 6GB(스왑 없음)를 다른 앱과 나눠 쓴다**, 디스크 80GB, systemd |
| 이미 돌던 것 | PostgreSQL 17(`/var/lib/postgresql/17/main`, `127.0.0.1:5432`과 유닉스 소켓), cloudflared(`/etc/cloudflared/config.yml`), 떠나는 앱 cheotmadi-api(loopback 8080, DB `cheotmadi`) |
| 이 문서가 더한 것 | JDK 25, diary 앱(loopback 18080, 액추에이터 18081), DB `diary`와 역할 `diary`, 배포·백업 타이머 |

```
브라우저(Cloudflare Pages) → Cloudflare 엣지(캐시) → cloudflared(기존 서비스, 터널 하나) ─┬─ api.cheotmadi.app  → 127.0.0.1:8080  (떠나는 앱)
                                                                                         └─ api.ssobbs13.pp.ua → 127.0.0.1:18080 (diary 앱, JVM)
                                                                                                                      └─ 유닉스 소켓 → PostgreSQL 17 (DB diary)
```

프론트(정적, Cloudflare Pages)는 `https://diary-6c6.pages.dev/`이고, 그 JS가 호출하는 API의 공개 진입점은 `https://api.ssobbs13.pp.ua/diary`이다(`pages.dev/api/diary`는 HTML을 주므로 API 확인에 쓰지 않는다). 프론트가 이 API에 요구하는 것은 7절에 있다.

### 왜 컴퓨터 하나인가
hoody 계정에는 컴퓨터를 세 개까지 만들 수 있지만 **세 개가 같은 물리 서버 하나의 2코어와 6GB를 나눠 쓴다.** 컴퓨터를 늘려도 용량이 늘지 않고, 서버가 죽으면 셋이 함께 죽으므로 하드웨어 이중화도 되지 않는다. 게다가 컴퓨터끼리는 TCP로 닿을 수 없어서 DB를 한 곳에 두고 앱만 늘리는 구성이 안 된다. 직접 시험한 사실:
- 컴퓨터 사이에는 네트워크 다리가 없다. 사설 주소(10.10.x.x)끼리 ping과 TCP가 모두 막힌다(방화벽 규칙이 없는데도 그렇다. 격리는 플랫폼에 있다)
- 컴퓨터 사이 저장소 공유는 NFSv4 마운트라서 공유 폴더에 유닉스 소켓을 둬도 `connect()`가 거절된다
- hoody SSH 게이트웨이는 `-L` 포트 포워딩을 끊는다
- `hoody tunnel pull`은 컴퓨터 안에서는 "SSH 게이트웨이로는 쓸 수 없다"며 거절된다
- 각 컴퓨터의 egress 프록시(HTTPS CONNECT)는 loopback과 사설 주소 목적지에 403(destination not permitted)을 준다
- 컴퓨터 사이에 되는 것은 Hoody Proxy를 지나는 HTTP/WebSocket뿐이다
그래서 앱은 DB가 있는 이 컴퓨터에서만 돈다. 한 대가 재시작하는 동안 다른 대가 받는 구성(옛 OCI의 haproxy 두 대)은 없다. 대신 3절의 배포는 재시작 동안 짧게 끊긴다.

## 2. 파일 위치와 비밀 값

| 무엇 | repo | 컴퓨터 |
|---|---|---|
| 앱 jar와 AOT 캐시 | (CI가 시험한 커밋을 컴퓨터가 빌드한다) | `/opt/diary/releases/<커밋 12자리>/app/`, 현재 `/opt/diary/current`, 직전 `/opt/diary/previous` |
| 실행/배포/점검 스크립트 | `deploy/hoody/*` | `/opt/diary/bin/` (`setup-server.sh`가 설치) |
| systemd 유닛 | `deploy/hoody/*.service`, `*.timer` | `/etc/systemd/system/` (`diary`, `diary-deploy`, `diary-pg-backup`) |
| 앱 환경(비밀 포함) | | `/etc/diary/diary.env` (`root:diary 0640`) |
| Object Storage 키 | | `/etc/diary/objectstorage.env` (`root:root 0600`) |
| 소스 체크아웃과 deploy key | | `/var/lib/diary-build/src`, `/var/lib/diary-build/.ssh/deploy` (사용자 `diary-build`) |
| 배포 상태 | | `/var/lib/diary-deploy/` (`last-commit` 등) |
| 백업 | | 로컬 `/var/backups/diary/`(최신 3개), 이벤트 로그 `/var/lib/diary-backup/events.jsonl` |
| 일회성 데이터 이전 | `deploy/migrate/*` | `diary-import-heatwave`는 `/opt/diary/bin/`, `diary-export-heatwave`는 OCI의 oci-diary에서 쓴다 |
| 공개 API 점검 | `deploy/diary-public-check` | 어디서든 실행 (Mac에서도) |

`diary.env`의 값(OCI Vault에서 옮겨 오는 3개 포함):

| 키 | 값 | 출처 |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME` | 4절 | `setup-server.sh`가 채운다 |
| `SPRING_DATASOURCE_PASSWORD` | 소켓(peer)이면 쓰이지 않는다 | `setup-server.sh`가 무작위로 만들어 역할에 설정한다 |
| `SLACK_WEBHOOK_URL` | 앱 오류 알림과 백업 실패 알림이 같이 쓴다 | OCI Vault `diary-slack-webhook-url` |
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID` | 엣지 캐시 purge (Zone → Cache Purge 권한만) | OCI Vault `diary-cloudflare-api-token`, 옛 `diary.env`의 zone id |
| `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` | 18080, 18081 (loopback. 바인딩 주소는 `diary-run`이 고정) | |

**값은 `diary-env`로 쓴다.** 화면과 셸 기록에 남지 않고, 스크립트는 비밀 값을 출력하지 않는다.
```bash
pbpaste | ssh hoody-diary diary-env set SLACK_WEBHOOK_URL     # Mac 클립보드에서 바로
ssh hoody-diary diary-env has SLACK_WEBHOOK_URL && echo 채워짐   # 값 없이 확인 (CHANGE_ME면 실패)
```
`diary-env get`은 터미널로는 출력을 거부한다(파이프로만). 환경 파일은 systemd `EnvironmentFile` 형식이라 값에 따옴표를 붙이지 않는다. `CHANGE_ME`가 남아 있으면 `diary-pull-deploy`가 배포를 거부한다.

## 3. 앱 배포

### 실행 방식: jar + systemd + 컴퓨터에 설치한 JDK 25
buildpack 이미지를 Docker로 돌리는 방법과 비교해 jar를 골랐다.
- **소켓과 peer 인증**: 앱이 `diary` 사용자로 PostgreSQL 소켓에 직접 붙는다. 컨테이너라면 소켓 디렉터리를 마운트하고 컨테이너 uid를 호스트 사용자와 맞춰야 한다. Docker는 설치만 되어 있고 한 번도 돌려 본 적이 없어서(방화벽 규칙과의 상호작용이 미지수) 그 위험을 얹을 이유가 없다
- **AOT 캐시와 JVM 플래그**: 캐시는 JDK와 jar 경로에 묶이므로 서버에서 그 JDK로 만든다(훈련 실행을 배포 스크립트가 한다). 플래그를 한 파일(`diary-jvm.conf`)에서 직접 정하고 buildpack 메모리 계산기를 거치지 않는다
- **배포와 되돌리기**: 릴리스 디렉터리와 `current` 링크라서 되돌리기가 링크 교체와 재시작이다. 이미지 방식은 ghcr 읽기 토큰을 컴퓨터에 둬야 한다
- **움직이는 부품**: jar 방식은 JDK(apt), 사용자 둘, 유닛 셋이다. 이미지 방식은 Docker 데몬(메모리 상시 점유), 레지스트리 인증, 컨테이너 사용자 매핑이 더 든다
- 치르는 값: 컴퓨터에서 빌드한다(아래). cheotmadi-api의 배포와 같은 방식이라 같은 도구로 읽힌다

### 흐름 (pull 방식, GitHub에는 서버에 들어갈 비밀이 없다)
1. `main`에 push하면 GitHub Actions(`deploy.yml`)가 `./gradlew check`(PostgreSQL Testcontainers 포함)를 돌리고, 통과하면 `deploy` 가지를 그 커밋으로 옮긴다
2. 컴퓨터의 `diary-deploy.timer`가 1분마다 `diary-pull-deploy`를 부른다. 읽기 전용 deploy key로 `deploy` 가지를 가져와 새 커밋이면:
   1. 권한 없는 사용자 `diary-build`로 `./gradlew bootJar`(데몬 없이, 힙 1GB, 이 서비스에 `MemoryMax=2G`, `Nice=15`)
   2. 풀어 쓴 형태로 `releases/<커밋>/app`에 풀고, 그 경로에서 AOT 캐시를 훈련한다(`application-training.yaml`, DB 없이)
   3. `current` 링크를 바꾸고 `systemctl restart diary`. 서비스는 앱이 DB까지 붙어 `/diary`가 200을 줄 때까지(최대 150초) `active`가 되지 않는다
   4. 실패하면 링크를 직전 릴리스로 되돌리고 다시 시작한다. 그 커밋은 `last-commit`에 기록되어 1분마다 다시 시도하지 않는다. 고친 새 커밋이 오면 다시 시도한다
3. 기록은 `journalctl -u diary-deploy`, 앱 로그는 `journalctl -u diary`. 릴리스는 최신 3개만 남긴다

```bash
ssh hoody-diary 'systemctl list-timers diary-deploy.timer; journalctl -u diary-deploy -n 20 --no-pager'
ssh hoody-diary /opt/diary/bin/diary-pull-deploy     # 기다리지 않고 지금 한 번 (바뀐 게 없으면 바로 끝난다)
```
처음 설치와 설정: 컴퓨터에서 한 번 `setup-server.sh`와 `setup-auto-deploy.sh`를 돌린다(8절 P1~P2). deploy key는 공개 키가 출력되면 GitHub 저장소 Settings → Deploy keys에 **Allow write access를 끈 채** 넣는다. `setup-*` 스크립트나 유닛을 고쳤다면 `deploy/`를 다시 올려 `setup-server.sh`를 다시 돌린다(배포 스크립트는 설치된 헬퍼가 저장소와 다르면 로그에 경고한다).

### 알아 둘 것
- **컴퓨터가 하나라서 배포(재시작) 동안 앱이 응답하지 못한다.** 로컬에서 재었다: `systemctl restart diary`가 2.5초, 그동안 요청이 실패한 구간은 **약 1.6~1.7초**(20ms 간격 요청 220개 중 80개 실패, 3회). 이 측정은 코어 8개의 리눅스 VM, AOT 캐시 사용, 빈 DB 기준이고 실제 컴퓨터(코어 2개를 다른 프로세스와 나눔)에서는 몇 초(대략 3~10초로 짐작)로 늘 수 있다. **Cloudflare 엣지 캐시에 있는 GET은 그동안에도 나간다**(목록 5분, 상세 1일). 캐시에 없는 요청과 POST, 프리플라이트(`OPTIONS`는 캐시되지 않는다)는 그 사이에 실패한다. 점검 창(예: 새벽)을 피하려면 `diary-deploy.timer`를 잠시 멈춘다
- 배포 직후 첫 요청은 JIT 워밍업 전이라 느리다. 기동 시간은 AOT 캐시가 있으면 약 1.2초, 없으면 약 2.5초(로컬 측정)
- **스키마는 앱이 시작할 때 Flyway가 맞춘다**(`V1__init.sql`). Hibernate는 `ddl-auto: validate`라 스키마가 다르면 시작하지 못한다. 배포가 실패해 이전 릴리스로 돌아가도 **새 스키마에서 옛 앱이 돌아야 하므로** 마이그레이션은 더하기만 한다(열 삭제·이름 바꾸기는 그 열을 안 쓰는 릴리스가 배포된 뒤에 따로). 마이그레이션이 실패하는 릴리스는 DDL이 트랜잭션이라 흔적 없이 롤백되고 이전 릴리스로 돌아간다(로컬에서 일부러 잘못된 `V2`로 확인했다)
- 컴퓨터에서 빌드하는 값: 첫 빌드는 Gradle과 의존성을 내려받아 몇 분 걸린다(8코어 VM에서 약 2.5분). 코어 2개를 나눠 쓰는 컴퓨터에서는 그보다 길고, 그동안 앱이 조금 느려진다(낮은 우선순위로 돌린다)
- 앱 이미지가 사라졌다: ghcr 이미지 발행(`publish-image.yml`)과 `bootBuildImage` 설정은 지웠다. OCI의 두 서버에 이미 올라간 옛 이미지는 그대로 돌고 있으므로 컷오버 전까지는 OCI를 코드에서 다시 배포할 수 없다(코드는 PostgreSQL 전용이다). OCI 쪽 파일(`start.sh`, `deploy.sh`, `haproxy.cfg`, `deploy/mysql/*`)은 커밋 `b2aa88d`까지 repo에 있었고 서버의 `~/`에 그대로 남아 있다

## 4. DB 연결: 유닉스 소켓과 loopback TCP는 한 줄 차이

앱은 같은 컴퓨터의 PostgreSQL에 붙는다. **`/etc/diary/diary.env`의 `SPRING_DATASOURCE_URL` 한 줄이 선택이다**(사용자 `diary`는 같고, TCP에서만 비밀번호가 쓰인다).

| | 유닉스 소켓 + peer (기본, 권장) | loopback TCP + scram 비밀번호 |
|---|---|---|
| URL | `jdbc:postgresql://localhost/diary?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432&...` | `jdbc:postgresql://127.0.0.1:5432/diary?...` |
| 인증 | OS 사용자 `diary` = DB 역할 `diary`. 비밀번호 없음 | 비밀번호(`diary.env`) |
| 방화벽 | 무관 | 이 컴퓨터는 5432를 loopback까지 막는다는 기록이 있다(cheotmadi README). root의 `pg_isready -h 127.0.0.1`은 통과하지만 서비스 사용자도 통과하는지는 직접 해 봐야 안다 |
| 비용 | JDBC 소켓 팩토리(junixsocket, JNI 라이브러리)가 jar에 들어간다 | 없음 |

**권장은 소켓이다.** DB가 네트워크에 열리지 않고 비밀번호를 쓰지 않으며 방화벽 규칙과 무관하다. 이미 cheotmadi가 같은 방식으로 이 컴퓨터에서 도는 것이 증거다. TCP는 소켓이 막혔을 때의 대안이다.

**컴퓨터에서 확인할 것** (`setup-server.sh` 뒤, 컷오버 전):
```bash
ssh hoody-diary /opt/diary/bin/diary-db-check
```
`diary` 사용자로 (1) 소켓 peer 로그인, (2) loopback 5432 TCP 연결, (3) TCP + 비밀번호 로그인을 각각 시험하고 권장을 출력한다(비밀번호는 출력하지 않는다). 소켓이 PASS면 기본값 그대로 두고, 소켓만 FAIL이고 TCP가 PASS면 `diary.env`의 URL 줄을 TCP 줄(파일에 주석으로 있다)로 바꾸고 `systemctl restart diary`. 소켓 방식이 JDK 25와 systemd 샌드박스 안에서 JNI 라이브러리를 읽는지는 로컬 Debian 13 + systemd 컨테이너에서 확인했다(경고 없음).

- DB `diary`와 역할 `diary`는 `setup-server.sh`가 만든다: 로그인만 가능, 슈퍼유저/DB 생성/역할 생성/복제/RLS 우회 없음, 연결 20개 제한. DB `diary`의 소유자이므로 Flyway가 테이블을 만들 수 있고, `public`에서 PUBLIC의 권한을 거두고 다른 역할(cheotmadi 등)의 `diary` 접속을 막는다
- `pg_hba.conf`는 건드리지 않는다. 기존 `local all all peer`와 `host ... 127.0.0.1/32 scram-sha-256`이 이미 두 방식을 모두 받는다
- cheotmadi의 DB와 역할은 건드리지 않는다. 다만 PostgreSQL은 새 데이터베이스에 `PUBLIC CONNECT`를 기본으로 주므로 역할 `diary`는 DB `cheotmadi`에 **접속은 할 수 있다**(그 안의 표는 소유자 권한이라 읽지 못한다). 막으려면 cheotmadi 쪽에서 `revoke connect on database cheotmadi from public`이 필요한데, 그 DB를 건드리는 일이라 하지 않았다. cheotmadi가 떠난 뒤 DB를 지우면 해결된다
- DB가 내려가면: 앱은 요청을 **503 + `Retry-After: 5`**로 돌려주고(트랜잭션을 열지 못하는 경우 포함, 7절 오류 계약) 첫 오류에 Slack을 한 번 보내며, DB가 돌아오면 재시작 없이 스스로 이어간다(Hikari `keepalive-time` 30초). 로컬에서 PostgreSQL을 멈추고 실제 요청으로 확인했다. 예전(MySQL 시절과 이 이전 버전)에는 같은 상황이 500이었다

## 5. 메모리와 JVM 플래그

한 컴퓨터가 **2코어와 6GB(스왑 없음)**를 모두 쓴다. 같이 도는 것과 예산:

| 항목 | 대략적인 사용량 |
|---|---|
| diary 앱(JVM) | 힙 최대 768MB + 비힙 약 250MB = 최대 약 1GB. 평소 RSS는 로컬 측정으로 약 270~300MB |
| PostgreSQL 17 | 약 250MB (shared_buffers 128MB + 연결). cheotmadi도 같은 서버를 쓴다 |
| cloudflared | 약 50MB |
| cheotmadi-api (Go) | 수십 MB |
| 배포 빌드(Gradle) | 최대 2GB (`diary-deploy.service`의 `MemoryMax`). 몇 분 동안만 |
| 합계 | 최대 약 4GB. 나머지는 페이지 캐시 |

앱 유닛에는 `MemoryHigh=1200M`, `MemoryMax=1500M`을 두었다(스왑이 없어서 OOM은 곧 프로세스 종료이고, 이 컴퓨터에는 다른 앱이 있다). 플래그는 `deploy/hoody/diary-jvm.conf` 한 곳이고 `diary-run`과 AOT 훈련이 같이 읽는다. 512MB 컨테이너 시절(`start.sh`)과 달라진 것:

| | 옛 | 지금 | 이유 |
|---|---|---|---|
| 힙 | buildpack 메모리 계산기 | `-Xms256m -Xmx768m` | 계산기가 없다. 본문 수 MB를 ETag 필터가 통째로 버퍼링해도 수십 MB라 충분하고 여유를 둔다 |
| GC | (코어 1이라 Serial) | `-XX:+UseSerialGC` | 코어 2개와 6GB를 보고 JVM이 G1을 고르는데, 이 힙과 CPU 사정에서는 G1의 스레드와 메타데이터가 낭비다 |
| JIT | `-XX:TieredStopAtLevel=1` | 유지 | **이유가 메모리에서 CPU로 바뀌었다.** 코어 2개를 PostgreSQL, cloudflared, 다른 앱, 배포 빌드와 나눠 쓰므로 C2 컴파일이 시작 직후 한 코어를 몇 초 잡는 것을 피한다. 트래픽 대부분이 엣지 캐시에서 끝나 최고 처리량보다 CPU 점유가 중요하다 |
| 코드 캐시 | `-XX:ReservedCodeCacheSize=96M` | 유지 | 예약만 하는 값이라 비용이 없고 여유가 있다 |
| 메타스페이스 | `-XX:MetaspaceSize=128M` | 유지 + `MaxMetaspaceSize=256M` | 사용량(약 110MB)이 기본 임계값을 넘을 때 기동 중 나던 Full GC(300~800ms)를 피한다. 상한은 누수 방어 |
| 스레드 스택 | `-Xss512k` | 삭제 | 스레드 수십 개라 이득이 없고 기본 1MB가 Hibernate/Jackson 깊은 호출에 안전하다 |
| `BPL_JVM_THREAD_COUNT`, `BPL_JVM_CLASS_ADJUSTMENT` | 있음 | 삭제 | buildpack 메모리 계산기 입력이었다 |
| OOM | | `-XX:+ExitOnOutOfMemoryError` | 죽고 systemd가 바로 다시 띄운다(반쯤 죽은 JVM을 두지 않는다) |
| GC 로그 | `-Xlog:gc,gc+metaspace=info` | 유지 | 값이 싸고 메타스페이스 진단에 쓴다 (journald) |
| 네이티브 | | `--enable-native-access=ALL-UNNAMED` | junixsocket의 JNI를 JDK 25가 경고 없이 읽게 한다 |
| AOT 캐시 | buildpack 훈련 | `-XX:AOTCacheOutput`(배포 때, `-XX:AOTCache`로 사용) | 로컬 측정으로 기동 약 2.5초 → 약 1.2초. 캐시가 없거나 깨지면 JVM이 경고만 내고 캐시 없이 뜬다. 캐시는 JDK와 jar 경로에 묶여서 컴퓨터에서 만든다. 재배치(`ArchiveRelocationMode`)는 기본값(ASLR)을 유지한다 |
| Tomcat/Hikari | 최대 32 스레드, 풀 4 | 그대로 | 동작을 바꾸지 않는다 |

키우고 싶으면 `diary.env`에 `JAVA_OPTS_EXTRA=-Xmx1g` 처럼 임시로 더한다(`diary-run`이 뒤에 붙인다).

## 6. 백업과 복원

- **언제**: `diary-pg-backup.timer`가 매일 18:30 UTC(한국 새벽 03:30, 최대 10분 지연)에 실행한다. 컴퓨터가 하나뿐이라 이 덤프가 컴퓨터 밖으로 나가는 유일한 사본이다
- **무엇을**: `pg_dump -Fc`(압축, 부분 복원 가능) → `diary-<시각>-<hostname>.dump`. 소켓(peer)으로 `diary` 사용자가 뜬다. 서비스는 root지만(Object Storage 키를 읽는다) 쓰기 가능한 곳을 두 디렉터리로 줄인 샌드박스다
- **올리기 전 점검**(하나라도 걸리면 아무것도 올리거나 지우지 않고 이벤트와 Slack): `pg_dump` 성공, 2KB 이상, `pg_restore -l`로 읽힘, `diaries`와 `flyway_schema_history` 데이터가 있음, **행 수가 직전 정상 백업보다 줄지 않았음**(일기는 만들기만 하고 지우는 API가 없다. 일부러 지웠다면 `/var/lib/diary-backup/last-rows`를 고친다)
- **어디에**: 컴퓨터 `/var/backups/diary/`에 최신 3개, 버킷 `bucket-20260929-0252`(namespace `axnekfrmygfs`, ap-chuncheon-1)의 **`diary-pg/`** 아래 최신 3개. `mysql/`(옛 HeatWave 덤프)와 섞이지 않는다. 옛 MySQL 시절에는 버킷에 1개(하루치)만 두었다. 컴퓨터 한 대에서는 잘못된 덤프가 유일한 사본을 덮는 위험이 커서 3개로 늘렸다(일기 DB는 작고 무료 한도 20GB에 영향이 없다. `OS_KEEP_NEWEST`로 바꾼다)
- **정리**: 버킷은 **업로드와 크기 대조(HEAD)에 성공한 뒤에만** 이전 백업을 지운다(`diary-os-put prune diary-pg/ 0 --keep-newest 3`). 업로드가 실패한 날에는 정리가 돌지 않는다. `--keep-newest`는 최소 1이라 전부 지워지지 않는다. 로컬은 날짜가 아니라 개수로 남긴다
- **알림**: 덤프나 업로드가 실패하면 `backup_failed`/`os_upload_failed` 이벤트와 Slack, 유닛이 실패 상태로 남는다(`systemctl --failed`). Object Storage 키가 비어 있으면 외부 사본이 없다는 알림이 간다. 타이머가 아예 안 돌면 알리는 곳은 아직 없다 → `backup_done` 이벤트를 가끔 확인한다

```bash
ssh hoody-diary 'tail -3 /var/lib/diary-backup/events.jsonl; systemctl list-timers diary-pg-backup.timer; /opt/diary/bin/diary-os-put list diary-pg/'
```

### 복원 검증 (운영 DB를 건드리지 않음)
```bash
ssh hoody-diary /opt/diary/bin/diary-pg-restore-check          # 가장 최근 로컬 백업
ssh hoody-diary /opt/diary/bin/diary-pg-restore-check --os     # 버킷의 최신 백업을 내려받아
```
같은 PostgreSQL 안에 임시 DB `diary_restorecheck`를 만들어 `pg_restore`하고, 복원본의 모든 행이 운영 DB에 **같은 내용(행 해시)으로** 있는지 본다. 백업 뒤에 쓴 새 글만 운영에 더 있어야 하고, 다르면 실패한다. 끝나면 임시 DB를 지운다. 다른 DB는 건드리지 않는다. 컷오버 직후와 가끔(분기마다) 한다.

### 전체 유실 시 복원
컴퓨터가 새로 만들어졌다면: `setup-server.sh`로 DB와 역할을 만들고(앱은 아직 띄우지 않는다), 덤프를 받아서
```bash
/opt/diary/bin/diary-os-put get diary-pg/<파일> /root/restore.dump
# 백업 폴더는 root만 읽는다. 파일은 root가 열어서 표준 입력으로 넘긴다
runuser -u postgres -- pg_restore --no-owner --role=diary -d diary --exit-on-error < /root/restore.dump
```
덤프에는 `flyway_schema_history`가 들어 있어서 앱이 마이그레이션을 다시 돌리지 않는다. 그다음 `systemctl start diary`.
`--role=diary`가 복원되는 객체의 소유자를 `diary`(앱 역할)로 맞춘다.

### Object Storage 접근
- 키는 OCI 콘솔의 **Customer Secret Key**(사용자당 최대 2개, Secret은 발급 때 한 번만 보임)를 `/etc/diary/objectstorage.env`에 둔다(`OCI_NAMESPACE`, `OCI_BUCKET`, `OCI_REGION` 줄은 유지)
- **서버의 `curl --aws-sigv4`는 쓰지 않는다.** curl 7.76은 OCI가 필수로 요구하는 `x-amz-content-sha256` 헤더를 보내지 않아 "secret key could not be found"로 거부된다. 키가 틀린 것처럼 보이지만 아니다. `diary-os-put`이 SigV4를 직접 서명한다(`put`, `get`, `list`, `prune`)

## 7. Pages 프런트와 API: 이사 뒤에도 맞아야 하는 것, 그리고 오류 계약

프런트는 별도 저장소(`practice-react`, 패키지 `diary-fe-react`, React 19 + `ky` + Vite)이고 Cloudflare Pages 프로젝트 `diary`로 `https://diary-6c6.pages.dev`와 `https://ssobbs13.pp.ua`에 배포된다. 프로덕션 API 주소는 `.env.production`의 `VITE_API_BASE_URL=https://api.ssobbs13.pp.ua/`이고 `ky`가 `diary`, `diary/{id}`를 붙여 부른다(소스 확인). 아래는 이 저장소의 앱이 실제로 하는 일(로컬에서 앱을 띄워 `diary-public-check --local`과 통합 시험으로 확인)이다.

프런트가 보내는 요청 (전부 `credentials` 없는 교차 출처 요청이다):

| 화면 | 요청 | 비고 |
|---|---|---|
| 목록(`Homepage`) | `GET /diary`, `GET /diary?cursorId=<id>` | `size`는 보내지 않는다(기본 20) |
| 쓰기(`WritePage`) | `POST /diary` + `{"title","content"}` (둘 다 `trim()`) | `id`는 보내지 않는다. `Content-Type: application/json`이라 프리플라이트가 먼저 간다 |
| 상세(`DiaryDetailModal`) | `GET /diary/{id}` | |

- **호출 주소**: `https://api.ssobbs13.pp.ua/diary` (목록 `GET /diary?size=&cursorId=`, 상세 `GET /diary/{id}`, 쓰기 `POST /diary`). 컷오버로 바뀌는 것은 DNS가 가리키는 곳뿐이라 프런트를 고칠 필요가 없다
- **CORS** (앱의 `@CrossOrigin`과 `HttpCacheFilter`):
  - `GET` 응답에는 요청에 Origin이 있든 없든 항상 `Access-Control-Allow-Origin: *`. 엣지가 Origin 없는 요청(curl 등)의 응답을 캐시해도 브라우저가 거부하지 않게 하려는 것이다
  - `OPTIONS /diary` 프리플라이트(`Origin`, `Access-Control-Request-Method: POST`, `-Headers: content-type`)는 200, `Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: POST`(요청한 메서드), `Access-Control-Allow-Headers: content-type`(요청한 헤더), `Access-Control-Max-Age: 1800`. `OPTIONS`는 Cloudflare가 캐시하지 않아 매번 앱까지 온다(그래서 배포 재시작 동안 프리플라이트가 실패할 수 있다)
  - `POST` 201에도 `Access-Control-Allow-Origin: *`
  - **오류 응답에도** `Access-Control-Allow-Origin: *`와 `Access-Control-Expose-Headers: Retry-After`가 붙는다. 컨트롤러에 닿기 전의 오류, 필터 예외, Tomcat이 직접 거절하는 요청도 마찬가지다. `Retry-After`는 CORS 안전 목록에 없어서 노출하지 않으면 브라우저 JS가 읽지 못한다
  - 출처는 가리지 않는다(`*`, 쿠키 없음). 통합 시험(`DiaryApiIntegrationTest`)이 프리플라이트와 오류 응답을 `https://ssobbs13.pp.ua`, `https://diary-6c6.pages.dev`, 미리보기 배포(`https://<해시>.diary-6c6.pages.dev`), 로컬 개발(`http://localhost:5173`)의 `Origin`으로 실제 HTTP 요청해서 확인한다
- **캐시 헤더**: 목록 첫 페이지 `Cache-Control: no-cache`(브라우저가 매번 ETag로 확인, 같으면 304)와 `Cloudflare-CDN-Cache-Control: max-age=300`, `Cache-Tag: diary-list`. 상세와 커서로 가는 이전 페이지는 `Cache-Control: max-age=86400, immutable`. 오류 응답에는 성공용 캐시 헤더가 없고 `Cache-Control: no-store`가 붙는다(아래 오류 계약). 공개 주소의 응답에서는 Cloudflare가 `Cache-Tag`와 `Cloudflare-CDN-Cache-Control`을 지울 수 있어서 점검 스크립트는 공개 모드에서 이 둘을 필수로 보지 않는다(로컬 모드에서만 요구)
- **Cloudflare 캐시 규칙**(존 `ssobbs13.pp.ua`, **이 규칙은 호스트 이름 기준이라 DNS를 옮겨도 그대로 적용된다**): 호스트 `api.ssobbs13.pp.ua`, 경로가 `/diary`로 시작 → Eligible for cache, Edge TTL "Use cache-control header if present, bypass cache if not", Browser TTL "Respect origin". 엣지 캐시는 이 규칙이 켜져 있어야 동작한다. 끄고 싶으면 규칙만 끈다
- **purge**: 글을 쓴 앱이 `Cache-Tag: diary-list` 태그 purge를 요청한다(`CLOUDFLARE_API_TOKEN`/`ZONE_ID`). 앱이 하나라 어느 서버가 purge하느냐는 문제가 아니다. purge는 가상 스레드에서 **비동기**로 실행한다(POST 응답을 막지 않고 DB 커밋 뒤에 나간다). 그래서 글을 쓴 직후 목록을 읽으면 purge가 끝나기 전의 옛 목록이 엣지에서 나올 수 있다(보통 1초 안쪽). purge가 실패하면 로그(`layer=cache action=edge_purge_failed`)만 남기고 글쓰기는 성공으로 둔다. 그 경우 TTL(5분) 뒤에 맞아진다. 앱 종료 때는 진행 중인 purge를 최대 5초 기다린다. 무료 플랜의 태그 purge는 분당 요청 수 제한이 있다
- 상세와 이전 페이지는 만든 뒤 바뀌지 않으므로(고치거나 지우는 API가 없다) 한국에서 입구가 멀어도 브라우저가 서버에 다시 묻지 않게 오래 캐시한다

### 오류 계약 (RFC 9457 / RFC 9110)

2026-11-14에 오류 응답을 표준에 맞췄다. **성공 응답은 한 바이트도 바뀌지 않았다**(요청 21개의 상태, 헤더, 본문을 변경 전후로 비교). 바뀐 것은 오류뿐이다. 이전에는 클라이언트 입력 오류도 대부분 500(과 Slack 알림)이었고 본문은 제각각이었다.

**모든 오류는 `application/problem+json`(RFC 9457)이다.** 컨트롤러에 닿기 전의 오류(404, 405, 406, 415, 본문 해석, 파라미터 변환, 검증), 서블릿 필터의 예외(`/error`), Tomcat이 직접 거절하는 요청(경로의 `%zz`, HTTP가 아닌 요청 줄, 너무 긴 URI)까지 같은 형식이다. 어느 경로로 만들어졌는지는 `GlobalExceptionHandler`(Spring MVC 안), `ProblemErrorController`(필터와 컨테이너 오류), `ProblemErrorReportValve`(Tomcat이 서블릿에 닿기 전)다. 모두 실제 요청으로 시험한다(`DiaryApiIntegrationTest`).

본문 멤버:

| 멤버 | 뜻 |
|---|---|
| `type` | 문제 종류. 아래 표의 URI. **없으면 `about:blank`**다(RFC 9457 3.1.1. 상태 코드만으로 뜻이 다 전해지는 오류) |
| `title` | `about:blank`면 상태 문구(`Not Found`), 아니면 종류별 고정 문구 |
| `status` | HTTP 상태 코드 (응답 상태와 같다) |
| `detail` | 이 발생에 대한 사람이 읽는 설명. 영어 고정 문구이고 내부 정보(예외 이름, SQL, 스택)나 일기 내용, 요청 값을 담지 않는다 |
| `instance` | 요청 경로 (`/diary/…`). Tomcat이 직접 거절한 응답에는 없다 (요청에서 온 값을 되돌려 보내지 않으려고) |
| `errors` | (`validation-error`, `malformed-request`만) 필드/파라미터별 오류 배열 |

`errors[]` 한 건: `code`(안정적인 기계용 코드), `detail`(영어 문구), 그리고 위치 하나. 요청 **본문**의 필드면 `pointer`(RFC 6901 JSON Pointer, 예 `/title`), **쿼리나 경로** 입력이면 `parameter`(예 `size`). 둘은 함께 오지 않는다. `errors`는 pointer, parameter 순으로 정렬되어 같은 요청은 항상 같은 응답이다.

상황별 계약 (RFC 9110 15.5, 15.6):

| 상황 | 상태 | `type` | `title` | 추가 멤버, 헤더 |
|---|---|---|---|---|
| 본문 제약 위반 (`title`/`content` 없음·null, 제목 255자 초과, NUL 문자, `id`가 UUID v7 아님) | **422** | `…/problems/validation-error` | `Validation failed` | `errors[].pointer` + `code` |
| 쿼리 파라미터 제약 위반 (`size`가 1~100 밖) | **400** | `…/problems/validation-error` | `Validation failed` | `errors[].parameter` + `code` |
| JSON 문법 오류, 본문 없음, 필드 타입이 틀림(`"title":{…}`, `"id":"abc"`) | 400 | `…/problems/malformed-request` | `Malformed request` | 필드를 알면 `errors[].pointer` (code `invalid-value`) |
| 파라미터를 해석할 수 없음 (`size=abc`, `cursorId=abc`, `/diary/abc`, 깨진 퍼센트 인코딩) | 400 | `…/problems/malformed-request` | `Malformed request` | 이름을 알면 `errors[].parameter` (code `invalid-format`) |
| 같은 `id`로 이미 있음 (데이터베이스 기본 키 위반) | **409** | `…/problems/duplicate-id` | `Duplicate diary id` | |
| 없는 일기(`GET /diary/{id}`), 없는 경로 | 404 | (`about:blank`) | `Not Found` | |
| 지원하지 않는 메서드 | 405 | (`about:blank`) | `Method Not Allowed` | `Allow: GET, POST` |
| 받을 수 없는 `Accept` | 406 | (`about:blank`) | `Not Acceptable` | |
| 지원하지 않는 `Content-Type` | 415 | (`about:blank`) | `Unsupported Media Type` | |
| 데이터베이스를 쓸 수 없음 (연결 실패, 연결 풀 시간 초과, 트랜잭션을 열지 못함, 서버 종료·재시작 중) | **503** | (`about:blank`) | `Service Unavailable` | `Retry-After: 5` |
| 그 밖의 서버 결함 | 500 | (`about:blank`) | `Internal Server Error` | |
| Tomcat이 직접 거절 (경로의 `%zz`, HTTP가 아닌 요청, 너무 긴 URI·헤더) | 400 | (`about:blank`) | `Bad Request` | `instance` 없음 |

(`…`는 `https://api.ssobbs13.pp.ua`. 이 URI들은 **식별자**일 뿐 문서 주소가 아니다. 지금은 열어도 404다. RFC 9457 3.1.1은 소비자가 URI를 자동으로 가져오지 말라고 한다.)

`errors[].code`:

| code | 뜻 | 제약 |
|---|---|---|
| `required` | 필수 필드가 없거나 null | `@NotNull` |
| `too-long` | 제목이 255자(코드 포인트)를 넘음 | `@MaxCodePoints(255)` |
| `not-uuid-v7` | `id`가 UUID 버전 7이 아님 | `@UuidV7` |
| `nul-character` | NUL 문자(U+0000)가 들어 있음 | `@NoNul` |
| `too-small`, `too-large` | `size`가 1보다 작거나 100보다 큼 | `@Min`, `@Max` |
| `invalid-value` | 본문 필드의 타입이나 형식이 틀림 | (해석 단계) |
| `invalid-format` | 쿼리/경로 파라미터를 해석할 수 없음 | (해석 단계) |
| `invalid` | 위에 없는 제약 (앞으로 제약을 더하고 코드표를 갱신하지 않았을 때) | |

예 (검증 실패, `POST /diary` `{"id":"7d444840-9dc0-41d8-9e7d-0f4c2f9a8e4a","title":null}`):

```http
HTTP/1.1 422
Content-Type: application/problem+json
Access-Control-Allow-Origin: *
Access-Control-Expose-Headers: Retry-After
Cache-Control: no-store
Content-Language: en

{"detail":"The request violates one or more constraints. See the errors member.","instance":"/diary","status":422,"title":"Validation failed","type":"https://api.ssobbs13.pp.ua/problems/validation-error","errors":[{"code":"required","detail":"is required","pointer":"/content"},{"code":"not-uuid-v7","detail":"must be a UUID version 7","pointer":"/id"},{"code":"required","detail":"is required","pointer":"/title"}]}
```

**RFC가 선택을 남긴 곳과 고른 것**
- **422와 400의 경계**: RFC 9110 15.5.21은 422(Unprocessable Content)를 "내용의 문법은 맞고 내용 형식도 이해하지만 담긴 지시를 처리할 수 없는 경우"로 한정한다. 그래서 **요청 본문**의 제약 위반만 422이고, **대상 URI의 일부(쿼리, 경로)**가 어긋난 것은 내용이 없으므로 400(15.5.1)이다. `size=0`이 400인 이유다. 같은 `validation-error` 타입이므로 클라이언트는 상태가 아니라 `type`과 `errors`로 분기하면 된다. 한 곳을 고치면 모두 422로 통일할 수 있다(`handleHandlerMethodValidationException`의 상태 한 줄)
- **제약을 도메인과 똑같이**: 가장자리(Bean Validation)는 도메인(`Diary`, `CursorQuery`)이 거부하는 것과 정확히 같은 것을 거부한다. 더 엄격하지 않다: **빈 제목과 빈 본문은 받는다**(도메인과 DB가 받고, 옮겨 온 데이터에도 있다). 경로의 UUID가 버전 7이 아니면 검증 오류가 아니라 그냥 없는 일기(404)다(그런 id는 만들어진 적이 없다). 제목 길이는 코드 포인트로 센다(`@Size`는 UTF-16으로 세서 이모지 제목을 DB보다 일찍 거부한다)
- **필수 필드 누락은 422**: JSON 문법은 맞고 의미가 틀린 것이라 400이 아니다. 그래서 요청 DTO의 `title`과 `content`는 null 허용 타입이다(non-null이면 Jackson이 먼저 400으로 거부해 필드별 오류를 줄 수 없다)
- **409**(15.5.10): 대상 리소스의 현재 상태와 충돌. 미리 조회해서 막지 않는다(조회와 저장 사이의 경쟁). 데이터베이스 기본 키가 막고, 그 위반(SQLState 23505)을 서비스가 도메인 예외로 옮긴다. 동시에 8개를 보내면 201은 정확히 하나, 나머지는 409임을 시험한다
- **503 + `Retry-After`**(15.6.4, 10.2.3): 값은 초(5). DB 재시작이 보통 몇 초라서. 알림은 프로세스마다 한 번(`database-unavailable` 종류)
- **500은 서버 결함만**: 도메인 불변식 예외(`IllegalArgumentException`)가 HTTP까지 새어 나오면 400으로 돌리지 않고 500이다. 가장자리 검증이 어긋났다는 뜻이기 때문이다
- **`type`을 쓰는 것**: 상태만으로 뜻이 다 전해지는 오류는 `about:blank`(본문에서 생략)이고 `title`은 상태 문구다(RFC 9457 4.2.1). 상태 말고 알려야 하는 것(어느 필드가 틀렸나, 중복 id)이 있는 세 가지만 URI를 정했다
- **언어**: `title`과 `detail`은 영어 하나로 고정하고 `Content-Language: en`을 붙인다(`about:blank`의 `title`이 상태 문구, 곧 영어여야 해서). 검증 문구도 요청의 `Accept-Language`와 상관없이 같다. 사용자에게 보일 한국어 문구는 프런트가 `type`과 `code`로 고른다
- **오류 응답의 헤더**: `Content-Type: application/problem+json`(요청의 `Accept`가 안 맞아 406이 난 응답에서도), `Access-Control-Allow-Origin: *`와 `Access-Control-Expose-Headers: Retry-After`, `Cache-Control: no-store`(404와 405는 휴리스틱 캐시 대상이라 Cloudflare와 브라우저가 저장하지 않게 명시한다), `Content-Language: en`. **컨트롤러가 먼저 채운 성공용 헤더는 오류에서 모두 지운다**: 406은 컨트롤러가 `Cloudflare-CDN-Cache-Control: max-age=300`과 `Cache-Tag`를 채운 뒤 응답 변환 단계에서 나므로, 지우지 않으면 오류가 엣지에 5분 저장될 수 있다(실제 요청으로 발견해서 고쳤다)

**서버 쪽 부수 효과**
- Slack은 **서버 결함(5xx)만**, 종류마다 프로세스당 한 번 알린다(`database-unavailable`=503, `server-fault`=그 밖의 5xx). 4xx는 알리지 않는다. 앞으로 잘못된 요청 하나가 Slack을 울릴 수 없다
- `SLACK_WEBHOOK_URL`이 비어 있으면 보내지 않는다(예전에는 `localhost:8080/dummy`로 보냈고 그 주소가 같은 컴퓨터의 다른 앱일 수 있다). 전송은 가상 스레드에서 연결 2초, 읽기 8초 제한으로 하고 요청 처리를 막지 않는다
- 로그는 5xx에서만 예외 **타입**(과 원인 타입)을 남긴다. 메시지와 요청 본문은 남기지 않는다(DB 오류 메시지에 일기 내용이 섞일 수 있다). 503은 WARN, 500은 ERROR

**표준에 맞추지 못한 것**
- Tomcat이 직접 거절한 응답의 `Content-Type`은 `application/problem+json;charset=UTF-8`이다(`charset` 매개변수가 붙는다. 본문은 ASCII라 영향이 없다)
- Cloudflare가 만드는 오류(520~530, 1xxx, 5xx 오리진 연결 실패 등)는 HTML이거나 본문이 없다. 앱이 통제할 수 없으므로 프런트는 `application/problem+json`이 아닌 응답도 견뎌야 한다
- `type` URI는 문서로 이어지지 않는다 (위)
- 프리플라이트가 거부될 때(허용하지 않는 메서드)는 CORS 헤더가 없는 200이다. 이것은 브라우저가 차단하는 방식이고 본문을 읽을 수 없는 단계라서 바꾸지 않았다

### 컷오버 뒤 점검 스크립트
```bash
deploy/diary-public-check                    # Mac에서. 공개 API를 프런트가 쓰는 방식으로 읽기만 한다
deploy/diary-public-check --write            # 글 한 건을 실제로 쓰고 엣지 목록에 나타나는지(purge)까지 본다 (명시했을 때만)
deploy/diary-public-check --base http://127.0.0.1:18080/diary --local   # 컴퓨터에서 앱만 직접 (Cloudflare 헤더는 건너뜀)
```
확인하는 것: 목록·상세·커서 페이지의 상태, JSON 모양(`items/hasNext/nextCursorId`, 상세는 `id/title/content`만), `ETag`와 304, 위 캐시 헤더, 없는 일기 404에 엣지 캐시 헤더가 없음, `Origin: https://diary-6c6.pages.dev`로 보낸 CORS(GET의 `*`, 프리플라이트 POST/content-type), 공개 모드에서는 `cf-ray`(Cloudflare를 거침)와 **같은 쿼리를 두 번 보내 둘째가 `cf-cache-status: HIT`**(캐시 규칙이 살아 있음), 오류 응답(404, 400 `size=0`/`size=abc`, 422(저장하지 않는 POST), 405)이 `application/problem+json`, 표준 멤버, `type`, `errors`, CORS, `Cache-Control: no-store`를 갖췄고 엣지가 저장하지 않는지(두 번 보내 HIT가 아닌지), `--write`에서는 `POST` 201과 새 글이 목록 맨 위에 나타나는 시간. 쓴 글은 API로 지울 수 없어서 스크립트가 지우는 SQL을 알려 준다.
(공개 모드의 Cloudflare 헤더 부분은 Cloudflare에 접속할 수 없어 로컬에서는 시험하지 못했다. 로컬 모드와 앱 쪽 전부는 실제 앱으로 통과했다.)

## 8. 데이터 이전과 컷오버 런북

HeatWave `mydb.diaries`를 PostgreSQL `diary.diaries`로 옮긴다. id(`binary(16)`), 시각(UTC `datetime(6)`), 글자는 한 비트도 바뀌지 않아야 한다. **스크립트는 만들어서 로컬에서 끝까지 시험했고, 운영에서는 아직 한 번도 실행하지 않았다.**

### 이전 방식
- `diary-export-heatwave`(oci-diary에서, 옛 mysqldump와 같은 방식으로 podman의 mysql:8.4 컨테이너 사용, 읽기 전용): 하나의 일관된 스냅샷 안에서 모든 행을 **16진수**(id, title, content)와 UTC 시각 문자열로 내리고, MySQL이 같은 행의 `sha2` 해시도 계산한다. 16진수를 쓰는 이유는 줄바꿈, 탭, 따옴표, 백슬래시, 이모지, 수 MB 본문을 이스케이프로 옮기다 한 글자가 바뀌는 일을 아예 없애려는 것이다. 문자셋이 utf8mb4인지, NUL 문자(PostgreSQL이 못 담는다)가 든 행이 없는지 먼저 확인하고 틀리면 멈춘다. 결과는 `SHA256SUMS`와 함께 `.tar.gz` 하나
- `diary-import-heatwave load`(컴퓨터에서): 아카이브 해시 확인 → **하나의 트랜잭션**으로 임시 표에 넣고 PostgreSQL 안에서 변환(`uuid`, `timestamptz`는 `AT TIME ZONE 'UTC'`, 16진수→UTF-8) → 건수 확인 → 커밋. 중간에 실패하면 아무 행도 남지 않는다. 이어서 자동으로 검증한다: PostgreSQL에서 같은 정규 문자열의 행 해시를 만들어 MySQL이 낸 해시 목록과 **건수와 행 단위로** 대조한다
- **Flyway 이력**: MySQL의 `flyway_schema_history`는 옮기지 않는다(체크섬이 다른 MySQL용 V1의 기록이다). 앱이 이 DB에서 처음 시작할 때 Flyway가 PostgreSQL용 `V1__init.sql`을 적용하며 이력을 만들고, 로더는 **그 뒤에 빈 `diaries`에 행만 넣는다.** 그래서 앱을 다시 시작할 필요도 없고 다음 시작에서도 Flyway는 "V1 이미 적용"으로 넘어간다. 이력이 없거나 `diaries`가 비어 있지 않으면 로더는 아무것도 쓰지 않고 멈춘다
- 로컬 리허설 `deploy/migrate/rehearse.sh`(일회용 MySQL 8.4와 PostgreSQL 17 컨테이너, `podman` 필요): 210행(한글, 이모지, ZWJ 이모지, 따옴표, 백슬래시와 `\n` `\0` `\N` `\.` 같은 COPY 예약어, LF/CRLF/CR/탭/폼피드/U+2028, 255자 제목, 빈 제목과 본문, 마이크로초 경계 시각, 부호 비트가 다른 id, 최대 9.4MB 본문)을 넣고 실제 스크립트로 옮긴 뒤, 실제 앱(JPA)이 돌려주는 값을 파이썬 원본과 글자 단위로 대조한다. 210건 모두 일치, 페이지 순서 일치, `created_at` 전부 마이크로초까지 일치, 적재 약 1.8초(`data.tsv` 26MB). 손상된 파일, 비어 있지 않은 대상, 행이 바뀐 대상, NUL 문자가 든 원본은 모두 거부된다

### 컷오버 전 준비 (며칠 전부터, 서비스에 영향 없음)
- **P1 컴퓨터 준비**: `scp -r deploy hoody-diary:/root/diary-deploy` 후 `ssh hoody-diary /root/diary-deploy/hoody/setup-server.sh`. `diary.env`의 CHANGE_ME를 `diary-env set`으로 채운다(Slack, Cloudflare 토큰과 zone id는 OCI Vault의 값. 사람이 콘솔이나 `oci` CLI로 꺼내 파이프로 넣는다). `/etc/diary/objectstorage.env`에 Customer Secret Key를 넣는다. `diary-db-check`로 4절의 선택을 확인한다
- **P2 자동 배포**: `ssh hoody-diary /root/diary-deploy/hoody/setup-auto-deploy.sh`가 출력하는 공개 키를 GitHub 저장소 Deploy keys에 읽기 전용으로 넣고 다시 돌린다. 이 브랜치를 `main`에 합치면 CI가 `deploy` 가지를 옮기고, 컴퓨터가 빌드해서 앱이 빈 DB로 뜬다(Flyway가 V1 적용). `curl http://127.0.0.1:18080/diary`가 `{"items":[],...}`를 주는지 컴퓨터에서 확인한다. 이 시점부터 `main`에 합치는 코드는 hoody로만 배포된다. **OCI는 옛 이미지 그대로 돌고 새 코드로는 재배포할 수 없다**
- **P3 cloudflared에 규칙 더하기**(다른 앱의 터널 설정이므로 신중히): `ssh hoody-diary /opt/diary/bin/diary-ingress`로 미리 보기(`api.ssobbs13.pp.ua → http://localhost:18080`이 마지막 catch-all 앞에 들어간다), 맞으면 `--apply`(백업 후 쓰고 `cloudflared tunnel ingress validate`로 검증, 틀리면 되돌린다), 조용한 때에 `systemctl restart cloudflared`(설정은 시작 때만 읽는다. cheotmadi의 터널이 몇 초 끊긴다). DNS가 이 터널을 가리키기 전에는 이 규칙에 트래픽이 오지 않으므로 며칠 전에 해 둬도 안전하다. 규칙 확인: `cloudflared tunnel --config /etc/cloudflared/config.yml ingress rule https://api.ssobbs13.pp.ua/diary`
- **P4 백업 경로 시험**: `systemctl start diary-pg-backup`(빈 DB의 덤프지만 업로드 경로를 증명한다) → Slack/이벤트 확인. 타이머는 컷오버 때 켠다
- **P5 실제 데이터 리허설**(선택, 권장): oci-diary에서 `diary-export-heatwave --upload`(읽기 전용이라 서비스에 영향 없음) → 컴퓨터에서 `diary-os-put get migrate/<파일> /root/m.tar.gz` → `diary-import-heatwave load /root/m.tar.gz`로 **빈 `diary`에 적재하고 검증** → 확인 후 `runuser -u postgres -- psql -d diary -c 'truncate diaries'`로 비운다. 아직 공개되지 않은 DB라서 안전하고, 소요 시간과 문제를 컷오버 전에 알 수 있다
- **P6 확인**: Cloudflare에서 이 순서에 쓸 것을 미리 찾아 적어 둔다: ① DNS 레코드 `api`의 **현재 CNAME 대상**(OCI 터널의 `<터널ID>.cfargotunnel.com`. 롤백에 쓴다), ② **hoody 터널의 UUID**(`ssh hoody-diary grep '^tunnel' /etc/cloudflared/config.yml`; 새 CNAME 대상은 `<UUID>.cfargotunnel.com`), ③ WAF 사용자 지정 규칙을 만들 수 있는 위치. ④ hoody의 터널과 `ssobbs13.pp.ua` 존이 **같은 Cloudflare 계정**에 있어야 한다(다른 계정이면 이 방식은 안 되고 새 터널이 필요하다)

### 컷오버 (쓰기 동결 창 약 10~15분)
쓰기 동결은 **Cloudflare 엣지에서 `POST`를 막는 것**으로 한다(OCI 서버를 건드리지 않고 한 곳에서 켜고 끌 수 있다. 앱에는 점검 모드가 없다). GET은 계속 나간다.
1. **동결**: Cloudflare 대시보드 → 존 `ssobbs13.pp.ua` → Security → WAF → Custom rules → 규칙 `diary-freeze`: `(http.host eq "api.ssobbs13.pp.ua" and http.request.method eq "POST")` → Block. 확인: `curl -i -X POST https://api.ssobbs13.pp.ua/diary -d '{}' -H 'Content-Type: application/json'`이 403. 진행 중이던 POST가 끝나도록 30초 기다린다. 이 시각을 적어 둔다
2. **내보내기**: oci-diary에서 `~/.local/bin/diary-export-heatwave --upload`. 출력의 행 수를 적어 둔다(`select count(*) from mydb.diaries`와 같아야 한다)
3. **적재**: 컴퓨터에서 `systemctl is-active diary`(앱이 떠 있고 Flyway가 V1을 적용했는지)와 `diaries`가 비어 있는지 확인 → `diary-os-put get migrate/<파일> /root/m.tar.gz` → `diary-import-heatwave load /root/m.tar.gz`. **"검증 통과"와 건수 일치**를 확인한다. 실패하면 더 진행하지 않는다(트랜잭션이라 흔적이 없다. 동결을 풀고 원인을 찾는다)
4. **백업**: `systemctl start diary-pg-backup`(`rows`가 맞는지 이벤트 확인) → `diary-pg-restore-check` → `systemctl enable --now diary-pg-backup.timer`
5. **전환**: Cloudflare DNS에서 `api.ssobbs13.pp.ua`의 CNAME 대상을 `<hoody 터널 UUID>.cfargotunnel.com`으로 바꾼다(프록시 켠 채). 곧바로 반영된다. 컴퓨터에서 `journalctl -u diary -f`에 요청 로그(`layer=http`)가 오는지 본다
6. **엣지 캐시 비우기**: 대시보드 Caching → Configuration → Purge Cache → Custom purge → Host `api.ssobbs13.pp.ua`(내용은 같지만 옛 응답이 남지 않게 한 번 비운다)
7. **동결 해제 전 점검**: `deploy/diary-public-check`(읽기만). 모두 통과해야 한다
8. **동결 해제**: WAF 규칙 `diary-freeze`를 끄거나 지운다 → `deploy/diary-public-check --write`로 쓰기와 purge를 한 번 확인하고(쓴 글은 지운다) 프런트(`https://diary-6c6.pages.dev/`)에서 글을 읽고 써 본다
9. **지켜보기**: 30분 동안 `journalctl -u diary`, Slack, 다음 날 `backup_done` 이벤트와 버킷의 `diary-pg/`를 확인한다

### 롤백 (OCI가 남아 있는 동안)
- **동결을 풀기 전(7번까지)**: OCI는 한 글자도 바뀌지 않았다. DNS의 CNAME 대상을 P6①의 옛 값으로 되돌리고 WAF 규칙을 끈다. 손실 없음
- **동결을 푼 뒤**: 글이 이미 hoody에만 쌓이기 시작했다. 앞으로 고치는 쪽이 보통 낫다. 정말 OCI로 돌아가야 하면 (1) 동결을 다시 켜고 (2) 동결 해제 시각 이후의 행(`created_at >= '<시각>'`)을 hoody에서 꺼내 HeatWave에 넣은 뒤(**이 역방향 스크립트는 만들지 않았다.** 글이 몇 건이면 손으로, 많으면 만들어야 한다) (3) DNS를 되돌린다. OCI의 앱과 DB, cloudflared가 모두 돌고 있어야 한다. 그래서 **OCI는 아래 삭제 전까지 그대로 둔다**
- hoody 쪽 문제(앱이 안 뜸 등)는 롤백이 아니라 3절의 릴리스 되돌리기와 `journalctl -u diary`로 먼저 푼다

### 컷오버 뒤 OCI에서 지울 것과 시기
**지우는 순서가 곧 안전 순서다. 앞 단계를 확인하기 전에 다음으로 가지 않는다.**
1. **컷오버 후 최소 7일은 아무것도 지우지 않는다**(롤백 창). 그동안 OCI의 MySQL 백업 타이머는 그대로 돌아 버킷 `mysql/`에 동결 시점의 마지막 사본이 남는다. 이 기간에 hoody의 `diary-pg-backup`이 7번 성공하고 `diary-pg-restore-check --os`가 한 번 정상이어야 한다
2. **GitHub**: 시크릿 `OCI_HOST`, `OCI_HOST_2`, `OCI_USER`, `OCI_SSH_PRIVATE_KEY`, `OCI_SSH_KNOWN_HOSTS`, `CF_ACCESS_CLIENT_ID`, `CF_ACCESS_CLIENT_SECRET`과 환경 `production`을 지운다(새 CI는 시크릿이 필요 없다). ghcr의 옛 이미지 패키지는 OCI를 지운 뒤에 지운다
3. **Cloudflare**: OCI 터널의 공개 호스트 `api.ssobbs13.pp.ua` 항목(이미 DNS는 떠났다), Access 앱 `diary-ssh`, 서비스 토큰 `github-deploy`, DNS `ssh1`/`ssh2`. 터널 자체는 OCI를 지울 때 함께
4. **OCI VM 두 대**(boot volume 포함)와 보안 목록/VCN 규칙. 지우기 전에 `mysql/`의 마지막 덤프가 버킷에 있는지 한 번 더 확인한다. Mac의 `~/.ssh/config` 별칭 `oci-diary`, `oci-diary-2`도 정리
5. **HeatWave DB 시스템은 맨 마지막에 지운다.** 삭제 보호를 콘솔에서 먼저 풀어야 하고, 자체 백업은 1일 보관에 파일로 받을 수 없어서 지우면 되돌릴 수 없다. 사본은 버킷 `mysql/`의 덤프와 hoody의 PostgreSQL뿐이다
6. **OCI Vault**(`diary-vault`의 시크릿 3개)는 값이 hoody `diary.env`로 옮겨졌고 앱이 정상인 것을 확인한 뒤에 지운다(삭제는 대기 기간이 있다). 동적 그룹 `diary-instances`와 정책 `diary-read-secrets`도 같이
7. **지우지 않는 것**: OCI **계정(테넌시)**, **Object Storage 버킷 `bucket-20260929-0252`**, **Customer Secret Key**(hoody의 백업이 쓴다), 컴파트먼트. 버킷의 `mysql/` 덤프는 한 달쯤 보관한 뒤 지워도 된다. 마이그레이션 때 올린 `migrate/` 객체는 컷오버 성공 직후 지운다(전체 데이터가 들어 있다)

## 9. 정비

### 점검(읽기 전용)
```bash
ssh hoody-diary 'free -m | sed -n 2p; systemctl is-active diary postgresql cloudflared; systemctl --failed --no-legend; curl -s -m 5 http://127.0.0.1:18081/actuator/health; echo; journalctl -u diary -n 5 --no-pager'
```

### OS 업데이트
컴퓨터를 cheotmadi와 나눠 쓰므로 `apt upgrade`는 둘 다에 영향이 있다.
1. 최근 백업이 성공했는지 확인한다
2. `postgresql` 패키지가 올라가면 PostgreSQL이 재시작될 수 있다. 그동안 두 앱 모두 DB 오류를 낸다(diary는 Hikari가 스스로 다시 붙는다). 사용량이 적은 때에 한다
3. JDK(`openjdk-25`)가 올라가면 **AOT 캐시가 맞지 않아** 다음 시작이 캐시 없이 뜬다(JVM이 경고만 낸다). 다음 배포가 캐시를 새로 만든다
4. 필요하면 `systemctl restart diary` 후 `curl http://127.0.0.1:18080/diary`

### 로그
journald는 영구 저장이다(`/var/log/journal`). 크기와 보관 기간은 건드리지 않는다. cheotmadi-api가 `/etc/systemd/journald.conf.d/retention.conf`에 `MaxRetentionSec=90day`(처리방침)를 둔 컴퓨터라서, 이 컴퓨터 전체에 걸리는 값을 diary가 덮지 않는다. 옛 OCI에서는 "재부팅 뒤에도 장애 원인을 보려고" 영구 저장에 최대 200MB를 두었다. 여기서는 영구 저장만 확인하고 크기 제한은 두지 않는다(디스크 80GB, 앱 요청 로그는 경로와 상태만 남긴다).

## 10. 함정

- **PostgreSQL `text`는 NUL 문자(U+0000)를 담지 못한다.** MySQL은 담았다. 본문에 `\u0000`이 든 POST는 422(`nul-character`)다(예전에는 PostgreSQL 오류가 500으로 나갔다). 데이터 이전 때는 내보내기 스크립트가 미리 세어 막는다
- **`ky`는 503의 `Retry-After`를 따른다.** 프런트의 `ky` 2는 GET이 503을 받으면 `Retry-After`만큼 기다렸다가 최대 2번 다시 보낸다(기본 설정). 서버가 `Access-Control-Expose-Headers: Retry-After`를 주므로 이제 그 헤더가 실제로 읽힌다. 목록 로딩이 DB 장애 때 최대 약 10초 걸릴 수 있다는 뜻이다(`maxRetryAfter`로 줄일 수 있다)
- 앱의 오류 로그(`layer=http action=unhandled_exception type=... rootCause=...`)는 예외 **타입만** 남기고 메시지는 남기지 않는다. DB 오류 메시지에 일기 내용(개인 정보)이 섞일 수 있어서다. 원인 확인은 타입으로 좁히고, 필요하면 재현해서 본다
- `uuid` 컬럼 정렬은 16바이트를 앞에서부터 **부호 없이** 비교한다. 자바 `UUID.compareTo`는 부호 있는 long 비교라 다르다. keyset 페이지네이션(`id < ? order by id desc`)은 항상 DB 정렬이므로 맞다(시험이 id 상위 비트가 1인 경우로 확인한다). 앱 코드에서 id를 자바로 정렬하지 않는다
- `created_at`은 `timestamptz`다. 어느 시간대에서 읽고 써도 같은 순간이다. `hibernate.jdbc.time_zone: UTC`를 빼지 않는다
- `text` 컬럼은 크기 제한이 없다(최대 1GB). MySQL의 `mediumtext` 16MB 상한이 사라졌다. 응답은 ETag 필터가 통째로 버퍼링하므로 아주 큰 본문은 앱 메모리를 쓴다
- `socketFactory=...AFUNIXSocketFactory$FactoryArg`의 `$`는 그대로 쓴다. systemd `EnvironmentFile`은 `$`를 풀지 않는다. 값에 따옴표를 붙이면 따옴표까지 값이 된다
- AOT 캐시는 훈련 때의 경로와 JDK에 묶인다. 릴리스 디렉터리를 옮기거나 `current` 링크 경로로 실행하면 캐시가 안 맞는다(`diary-run`이 실제 경로로 실행한다)
- 8080은 떠나는 앱(cheotmadi)이 쓴다. diary는 18080(액추에이터 18081)이다. 둘 다 loopback이고 cloudflared 규칙으로 호스트 이름이 나뉜다
- `diary-env get`은 터미널에서 거부한다. 비밀 값이 화면에 나오는 것을 막으려는 것이다(원격에서 `ssh host cmd`로 부르면 터미널이 아니므로 통과하니 조심한다)
- macOS zsh에서 `scp file $h:dest`를 쓰지 않는다. `$h:dest`의 `:s...`가 변수 치환 수정자로 해석되어 scp가 서버 대신 깨진 이름의 로컬 파일을 만들고 성공(exit 0)으로 끝난다. `${h}:dest`로 쓴다
- 컴퓨터를 여러 개 두지 않는다: 컴퓨터 사이에 TCP가 없다(1절)

## 11. 무료 조건에서 주의할 것

- OCI Always Free 컴퓨팅이 사라져도 **계정과 Object Storage 버킷은 남겨야 한다.** 백업이 거기에 있다. Always Free 계정이 회수되면 hoody 컴퓨터와 백업이 따로여도 백업이 함께 사라질 수 있다. 가끔 `diary-os-put get`으로 덤프를 Mac에 받아 두는 것을 권한다
- Object Storage 무료 한도는 20GB, 월 API 요청 5만 건이다. 백업은 하루 몇 건이라 여유가 크다
- hoody는 컴퓨터 볼륨이 없고 컴퓨터 스냅샷이 보존 수단이라고 알려져 있다. 스냅샷은 DB를 일관되게 담는다는 보장이 없어서 믿지 않고 위 덤프를 쓴다
- hoody 컴퓨터 슬롯은 3개지만 같은 서버를 나눠 쓴다(1절). 앱이 느려 보이면 다른 컴퓨터의 부하도 같은 코어를 쓴다

## 12. 무엇이 왜 바뀌었나 (2026-11-14)

| | 이전 (OCI) | 지금 (hoody) |
|---|---|---|
| 서버 | VM 두 대(1/8 OCPU, 1GB, haproxy 1:2) | 컴퓨터 하나(2코어, 6GB를 다른 앱과 공유) |
| DB | HeatWave MySQL(HA 없음, 점검 창 월요일, 버전이 강제로 올라감, 자체 백업 1일) | 같은 컴퓨터의 PostgreSQL 17 |
| 앱 | buildpack 이미지, podman, 512MB | jar + systemd, JDK 25(apt), 힙 768MB |
| 배포 | CI가 SSH(Cloudflare Access 서비스 토큰)로 두 서버에 접속해 이미지 교체 | CI는 `deploy` 가지만 옮기고, 컴퓨터가 가져가 빌드·교체·되돌리기. GitHub에는 서버 자격이 없다 |
| 비밀 | OCI Vault 3개 + 인스턴스 주체 | `/etc/diary/diary.env`(`root:diary 0640`) |
| 입구 | 터널 하나, 커넥터 두 개 → haproxy 두 앱 | 기존 cloudflared 터널의 규칙 하나 → 앱 하나 |
| 백업 | 매일 mysqldump → 버킷 `mysql/` | 매일 pg_dump → 버킷 `diary-pg/`, 행 수·복원 가능성 점검 |
| 앱 코드 | MySQL(`binary(16)`, `datetime(6)`, `mediumtext`) | PostgreSQL(`uuid`, `timestamptz`, `text`). JPA 코드는 그대로 |

사라진 것: haproxy(와 SSH 중계), podman과 quadlet, OCI Vault 조회, ghcr 이미지 발행, HeatWave 제약(점검 창, 강제 버전 업, Always Free 한도), MySQL 전용 설정(`rewriteBatchedStatements`, `connectionTimeZone`, `mediumtext`)과 그 주석. 성공 응답(본문, 캐시 헤더, ETag, CORS)은 같다. 오류 응답은 표준(RFC 9457, RFC 9110)에 맞췄다(7절 오류 계약).
