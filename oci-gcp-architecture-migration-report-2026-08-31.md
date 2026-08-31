# Diary 인프라 전환 보고서

- 작성 기준: 2026-08-31
- 전환 전: OCI 백엔드 1대 + OCI PostgreSQL 1대
- 전환 후: OCI 백엔드 2대 + GCP PostgreSQL 1대
- 관련 배포 버전: `v0.0.12`~`v0.0.13`

## 1. 요약

기존에는 `oci-diary`가 애플리케이션을, `oci-diary-2`가 PostgreSQL을 전담했다. 이 구조는 백엔드 장애 시 서비스 전체가 중단되는 단일 백엔드 구조였다.

전환 과정에서 기존 OCI DB의 스키마와 데이터를 GCP `diary-db`로 이동하고, DB 역할이 사라진 `oci-diary-2`를 두 번째 백엔드로 전환했다. 두 OCI 서버에는 동일한 Spring Boot 이미지, HAProxy, Cloudflare Tunnel connector, 상대 백엔드로 연결되는 SSH 터널, GCP DB로 연결되는 IAP 터널을 설치했다.

최종적으로 애플리케이션 계층은 active-active 형태로 이중화됐지만, 데이터베이스는 GCP 단일 인스턴스이므로 여전히 단일 장애 지점이다.

## 2. 전환 전 구조

```text
사용자
  │
  ▼
Cloudflare Tunnel
  │
  ▼
OCI oci-diary
  └─ Spring Boot / Podman
       │
       │ OCI 사설망 10.0.0.19:5432
       ▼
OCI oci-diary-2
  └─ PostgreSQL 18
```

### 전환 전 문제점

1. `oci-diary` 한 대가 중단되면 정상 DB가 있어도 API를 제공할 수 없었다.
2. 애플리케이션 배포 중에도 대체 백엔드가 없었다.
3. `oci-diary-2`의 자원은 DB 용도로만 사용되어 백엔드 이중화에 활용할 수 없었다.
4. 캐시는 서버 로컬 메모리에만 존재해 이중화 이후 서버 간 일관성을 보장할 수 없었다.

## 3. 전환 후 구조

```text
                              ┌────────────────────────────┐
                              │ GCP us-west1-b             │
                              │ diary-db / e2-micro        │
                              │ PostgreSQL 18.6            │
                              │ 내부 IP 10.138.0.3:5432    │
                              └─────────────▲──────────────┘
                                            │
                              IAP TCP tunnel│
                     ┌──────────────────────┴──────────────────────┐
                     │                                             │
┌────────────────────┴──────────┐              ┌───────────────────┴───────────┐
│ OCI oci-diary                 │              │ OCI oci-diary-2               │
│                               │              │                               │
│ cloudflared                   │              │ cloudflared                   │
│   ↓                           │              │   ↓                           │
│ HAProxy 127.0.0.1:8080        │◀─SSH tunnel─▶│ HAProxy 127.0.0.1:8080        │
│   ├─ local app :18080         │              │   ├─ local app :18080         │
│   └─ peer app  :18081         │              │   └─ peer app  :18081         │
│                               │              │                               │
│ IAP listener :15432 ──────────┼──────────────┼─ IAP listener :15432          │
└───────────────────────────────┘              └───────────────────────────────┘
```

각 OCI 서버는 로컬 앱과 상대 서버의 앱을 모두 HAProxy backend로 등록한다. Cloudflare 요청이 어느 OCI 서버로 들어오더라도 HAProxy가 정상인 백엔드 중 하나로 전달한다.

## 4. 서버별 현재 역할

| SSH 별칭 | 호스트명 | 현재 역할 | 주요 프로세스 |
|---|---|---|---|
| `oci-diary`, `oci-diary-1` | `my-finance` | 백엔드 1, ingress | `diary-app`, `haproxy`, `cloudflared`, peer SSH tunnel, GCP IAP tunnel |
| `oci-diary-2` | `my-finance-db` | 백엔드 2, ingress | `diary-app`, `haproxy`, `cloudflared`, peer SSH tunnel, GCP IAP tunnel |
| `gcp-diary-db` | `diary-db` | PostgreSQL 전용 | rootful Podman `postgres-18` |

세 서버는 모두 로컬 SSH config에 위 별칭으로 등록했다. GCP 서버는 공인 IP 없이 IAP를 통해 접속한다.

## 5. 전환 과정

### 5.1 GCP DB 서버 준비

1. GCP `us-west1-b`에 `e2-micro` VM `diary-db`를 준비했다.
2. 부팅 디스크는 30GB이며 현재 내부 IP는 `10.138.0.3`이다.
3. 유료 고정 공인 IP를 사용하지 않도록 외부 IP를 제거했다.
4. IAP proxy 주소 범위 `35.235.240.0/20`에서 오는 TCP 22, 5432만 허용하는 `diary-db-iap` 방화벽 규칙을 적용했다.

### 5.2 PostgreSQL 이전

1. 기존 OCI PostgreSQL과 동일한 메이저 버전인 `postgres:18` 컨테이너를 GCP에서 기동했다.
2. PostgreSQL은 SSL을 활성화하고 서버 인증서와 키를 Podman secret으로 주입했다.
3. 데이터는 `pgdata` 영구 볼륨에 저장하고 컨테이너 재시작 정책을 `always`로 설정했다.
4. OCI DB의 스키마와 데이터를 GCP DB로 이전했다.
5. 애플리케이션 기동 시 Flyway 검증과 실제 조회를 통해 이전 결과를 확인했다.

2026-08-31 확인 결과는 다음과 같다.

| 항목 | 값 |
|---|---:|
| PostgreSQL | 18.6 |
| DB 크기 | 7,806kB |
| `diaries` 데이터 | 23건 |
| 성공한 Flyway migration | 4개 |
| 현재 schema version | 4 |
| 컨테이너 재시작 정책 | `always` |

### 5.3 OCI에서 GCP DB로 연결

GCP VM에 공인 IP가 없으므로 각 OCI 백엔드에서 다음 IAP TCP tunnel을 상시 실행한다.

```text
OCI Spring container
  → host.containers.internal:15432
  → OCI host의 gcloud IAP listener
  → 인증된 HTTPS/IAP tunnel
  → GCP diary-db 내부 IP:5432
  → PostgreSQL
```

`deploy/diary-db-iap-tunnel.service`는 `gcloud compute start-iap-tunnel`을 실행하며, 네트워크 복구나 프로세스 종료 시 3초 후 자동 재시작한다. 두 OCI 서버 모두 서비스가 active 상태다.

실제 DB URL과 계정 정보는 이미지에 포함하지 않고 각 서버의 `$HOME/.config/diary.env`에서 주입한다. `deploy/start.sh`의 `--env-file`이 이를 컨테이너에 전달한다.

### 5.4 OCI DB 서버를 두 번째 백엔드로 전환

1. GCP DB의 스키마, migration, 데이터와 애플리케이션 연결을 검증했다.
2. 기존 `oci-diary-2`의 PostgreSQL 컨테이너를 종료·삭제했다.
3. 사용하지 않는 PostgreSQL 이미지, 볼륨, 데이터 경로를 삭제했다.
4. `oci-diary-2`에 첫 번째 서버와 동일한 `diary-app` 이미지를 배포했다.
5. 애플리케이션은 호스트의 `127.0.0.1:18080`으로만 공개했다.

현재 `oci-diary-2`에는 PostgreSQL 컨테이너, 이미지, Podman 볼륨 및 PostgreSQL 데이터 경로가 남아 있지 않다.

### 5.5 백엔드 이중화

각 OCI 서버에 HAProxy 컨테이너를 설치했다.

```text
HAProxy frontend : 127.0.0.1:8080

backend local : 127.0.0.1:18080
backend peer  : 127.0.0.1:18081
```

`18081`은 `deploy/diary-peer-tunnel.service`가 생성하는 SSH local forwarding 포트다. 각 서버의 `diary-peer` SSH 별칭은 상대 OCI 서버를 가리킨다.

HAProxy는 다음 정책을 적용한다.

- round-robin 분산
- 1초 간격 `/diary` health check
- 한 번 실패하면 즉시 backend 제외
- 복구 확인 한 번 후 다시 투입
- retry 가능한 오류는 다른 backend로 redispatch

HAProxy와 Cloudflare Tunnel connector는 두 OCI 서버 모두에서 실행한다. 시스템 패키지 HAProxy가 아니라 `haproxy:3.2-alpine` Podman 컨테이너다.

### 5.6 CI/CD 이중 배포

GitHub Actions production 배포에 `OCI_HOST_2`를 추가했다. 태그가 push되면 다음 순서로 동작한다.

1. `./gradlew check`
2. Spring Boot OCI image 빌드 및 GHCR push
3. 두 OCI 서버에 `start.sh`, `deploy.sh` 업로드
4. 각 서버에 동일 버전 순차 배포
5. 서버별 `127.0.0.1:18080/diary` health check
6. 실패 시 직전 컨테이너로 rollback
7. 공개 API 최종 검증

관련 변경은 다음 커밋에 포함된다.

| 커밋 | 내용 |
|---|---|
| `8ccbc6b` / `v0.0.12` | OCI dual backend, HAProxy, peer SSH tunnel, 두 서버 배포 |
| `535f274` | 양쪽 OCI의 persistent GCP IAP DB tunnel |
| `1af5029` / `v0.0.13` | 원격 DB read transaction 왕복 제거, Code Cache 증설 |

## 6. 장애 시 동작

| 장애 | 예상 동작 |
|---|---|
| 한쪽 `diary-app` 장애 | 양쪽 HAProxy가 health check로 제외하고 정상 앱으로 전달 |
| 한쪽 OCI 서버 전체 장애 | 남은 Cloudflare connector와 OCI 서버가 요청 처리 |
| peer SSH tunnel 장애 | 해당 HAProxy는 local backend만 사용 |
| 한쪽 IAP tunnel 장애 | 해당 서버의 DB 요청 실패 가능, systemd가 tunnel 재시작 |
| GCP PostgreSQL 장애 | 두 백엔드 모두 DB 사용 불가 |
| GCP VM 장애 | 두 백엔드 모두 DB 사용 불가 |

백엔드는 이중화됐지만 DB는 복제되지 않았으므로 전체 시스템은 완전한 HA 구성이 아니다.

## 7. 성능 영향

기존 OCI 백엔드와 OCI DB는 같은 OCI 네트워크에 있어 warm query가 약 4~8ms였다. GCP DB 이전 후 OCI 춘천과 GCP 오리건 사이 물리적 거리 및 IAP tunnel 때문에 DB 왕복은 약 130~170ms가 됐다.

초기 이관 직후 단일 목록 API는 명시적 read-only transaction 때문에 대략 세 번의 원격 왕복이 발생해 p50 약 427ms였다.

`v0.0.13`에서 단일 SELECT 조회의 `@Transactional(readOnly = true)`를 제거한 뒤 확인한 결과는 다음과 같다.

| 지표 | 이전 | 이후 |
|---|---:|---:|
| 평균 | 440.4ms | 263.0ms |
| p50 | 427ms | 267.3ms |
| p95 | 630ms | 311.5ms |

현재 남은 지연의 대부분은 Hikari connection validation과 OCI↔GCP SQL 왕복이다. IAP는 연결 보안과 공인 IP 제거에는 도움이 되지만 물리적 네트워크 거리를 줄이지는 않는다.

## 8. 비용 관련 조치

1. GCP VM은 Free Tier 대상인 `e2-micro`, `us-west1-b`, 30GB 디스크로 구성했다.
2. 과금 가능한 GCP 외부 IPv4를 제거했다.
3. OCI와 GCP에 예산 알림을 설정했다.
4. 이 구성은 비용 최소화를 목표로 하지만 Free Tier 정책, 네트워크 송신량, IAP 사용량에 따라 무과금이 보장되지는 않는다.

## 9. 현재 검증 결과

2026-08-31 운영 서버 점검 결과:

- 두 OCI 서버 모두 `diary-app:0.0.13` 실행 중
- 두 OCI 서버 모두 `cloudflared`, `haproxy` 실행 중
- 두 OCI 서버 모두 peer SSH tunnel active
- 두 OCI 서버 모두 GCP IAP DB tunnel active
- 두 OCI 서버의 HAProxy listener `127.0.0.1:8080` 정상
- 두 OCI 서버의 local app listener `127.0.0.1:18080` 정상
- 두 OCI 서버의 peer app listener `127.0.0.1:18081` 정상
- GCP `postgres-18` 컨테이너와 `pgdata` 영구 볼륨 정상
- GCP DB 23건 및 Flyway schema version 4 확인
- 기존 OCI DB 서버의 PostgreSQL 관련 데이터 제거 확인
- `v0.0.13` GitHub Actions build, dual deploy, 공개 API 검증 성공

## 10. 남은 위험과 권고

1. GCP DB가 단일 장애 지점이다. 최소한 정기적인 `pg_dump`와 복구 테스트가 필요하다.
2. IAP는 일반적인 애플리케이션 전용 사설망보다 지연이 크며, 장기적으로 일관된 낮은 latency가 필요하면 DB와 백엔드를 같은 리전에 배치해야 한다.
3. `deploy/diary-db-iap-tunnel.service`는 OCI의 `0.0.0.0:15432`에 bind한다. OCI security list와 host firewall에서 외부 15432 접근이 차단되어 있는지 주기적으로 확인해야 한다.
4. `application-prod.yaml`에는 과거 OCI DB 주소 `10.0.0.19:5432`가 기본값으로 남아 있다. 현재는 `diary.env`가 덮어쓰지만, 환경 파일 누락 시 오래된 DB로 접속을 시도하지 않도록 후속 정리가 필요하다.
5. `ReservedCodeCacheSize=128M`은 초기 반복 Full GC를 제거했지만 장시간 운영 로그를 계속 확인해야 한다.

## 11. 결론

이번 전환으로 사용하지 못하던 OCI DB 서버 자원을 두 번째 백엔드로 재활용했고, 애플리케이션 배포 및 단일 백엔드 장애에 대한 내성을 확보했다. GCP DB는 공인 IP 없이 IAP로 접근하며, 기존 OCI DB 데이터는 정리됐다.

현재 구조의 핵심 제약은 GCP 단일 DB와 한국↔미국 간 네트워크 latency다. 비용 제약 안에서는 애플리케이션 이중화 목표를 달성했으며, 다음 우선순위는 자동 DB 백업과 복구 검증이다.
