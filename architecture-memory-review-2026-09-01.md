# Diary architecture and memory review

작성일: 2026-09-01 KST  
검토 범위: Cloudflare Pages부터 GCP PostgreSQL까지, 무료 비용 제약, p99 latency, OCI/GCP 메모리

## 결론

현재 구조는 **Spring/Kotlin 학습용으로는 유지 가능하지만, 23건의 diary 데이터를 서비스하는 제품 구조로는 과도하다.** 두 OCI 백엔드는 애플리케이션 장애를 우회하지만 GCP PostgreSQL 한 대와 그 앞의 IAP 경로가 단일 장애점이므로 end-to-end HA는 아니다. 공개 요청 p99는 JVM이 아니라 Cloudflare 동적 프록시와 서울↔오리건 DB 왕복이 지배한다.

엄격한 의미의 “청구액이 절대 0원”이 목표라면 현재 GCP 구조는 조건을 만족하지 못한다. GCP Free Tier는 한도 초과분을 청구하며 서비스 사용을 자동 중단하지 않는다. 반면 Workers/D1 Free plan은 일일 한도에 도달하면 요청이 실패하므로 비용 상한이 0원이다. 제품만 놓고 보면 **Cloudflare Pages Functions + D1로 합치는 안이 가장 단순하고 빠르며 비용 조건에도 가장 정확히 맞는다.** 단, Spring Boot/jOOQ/PostgreSQL을 버리는 재작성이다.

Spring/Kotlin을 유지해야 한다면 v0.0.15 구성이 현재 하드웨어에서 가장 나은 실측 후보다. IAP를 당장 바꾸기보다, 다음 단계로 무료 외부 IPv6를 이용한 OCI→GCP PostgreSQL 직접 TLS 경로를 한 서버에서 A/B 검증하는 것이 합리적이다.

## 실제 요청 경로

```text
Browser
  -> Cloudflare Pages (static assets)
  -> /api Pages Function/proxy
  -> Cloudflare Tunnel (동일 token의 cloudflared replica 2개)
  -> OCI별 HAProxy
  -> local Spring Boot 또는 SSH peer tunnel의 다른 Spring Boot
  -> 각 OCI의 gcloud IAP TCP tunnel
  -> GCP us-west1-b e2-micro
  -> Podman PostgreSQL 18
```

Cloudflare 문서상 동일 tunnel의 replica는 host/connector 장애 시 자동 failover하지만 round-robin이나 health 기반 traffic steering을 제공하지 않는다. 따라서 현재 HAProxy+peer tunnel은 단순 중복이 아니라 **cloudflared는 살아 있으나 local app만 죽은 경우**를 무료로 우회하는 역할이 있다. 이를 제거하려면 이 장애 모드를 포기하거나 유료 Cloudflare Load Balancer가 필요하므로 유지했다. [Cloudflare tunnel replicas](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-availability/)

## 실측 성능

| 구간/버전 | 표본 | p50 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|---:|
| v0.0.15 OCI1 direct | 100 | 137.7ms | 179.9ms | 268.6ms | 308.3ms |
| v0.0.15 OCI2 direct | 100 | 136.5ms | 191.5ms | 210.9ms | 311.5ms |
| v0.0.15 public 전체 경로 | 150 | 384.8ms | 484.1ms | 490.8ms | 494.8ms |
| v0.0.14 기존 HTTP 로그 | 692/595 | 266~267ms | 309~310ms | 400~401ms | 1.9~2.0s |

표본과 부하 형태가 달라 v0.0.14와 v0.0.15의 public p99를 직접 개선율로 비교하면 안 된다. 확실히 말할 수 있는 것은 다음 두 가지다.

1. v0.0.15 direct p50은 약 137ms인데 public p50은 약 385ms다. 약 248ms가 Pages Function/proxy, Tunnel, HAProxy 및 인터넷 경로에서 추가된다.
2. warm timer 제거 후 약 3분간 실제 query가 없었던 첫 direct 요청은 OCI1 266.0ms, OCI2 259.4ms였다. Hikari keepalive 덕분에 timeout은 없었지만 안전한 connection validation과 실제 SELECT가 각각 원격 왕복을 사용하므로 연속 warm 요청보다 한 번 더 왕복한다.

배포 중에는 IAP 경로에서 실제 `SocketTimeoutException: Read timed out`이 한 번 발생했고 Podman restart로 복구됐다. 과거의 “첫 요청 timeout, 다음 요청 성공”과 같은 종류의 위험이 아직 IAP/원격 DB 경로에 남아 있다는 직접 증거다.

## 이번에 적용한 구조 개선

### 1. DB를 조회하던 HAProxy health check 제거

이전에는 두 HAProxy가 local/peer에 `GET /diary`를 1초마다 실행했다. 실제 로그 증가율은 backend당 초당 1.47~1.58 query로, 전체 약 26만 SELECT/day였다. 현재는 `OPTIONS /diary`, 5초 간격이다. HTTP stack 생존 여부는 확인하지만 repository SQL은 실행하지 않는다.

이 변경은 다음 효과가 있다.

- GCP까지의 불필요한 국제망 왕복과 DB CPU 제거
- health check가 latency 로그와 p99 표본을 오염시키던 문제 제거
- 월간 GCP egress 초과 위험 대폭 감소
- 향후 배포에서 `haproxy.cfg`도 Actions가 업로드하고 container를 재시작하므로 구성 drift 제거

### 2. 1분 warm timer 제거

두 OCI의 `diary-warm.timer`를 disable/stop했다. 애플리케이션에는 기동 시 jOOQ/query warmup이 있고 HikariCP 7.0.2에는 기본 2분 idle connection keepalive가 있다. 1분마다 전체 diary query를 수행해도 500ms 이후의 Hikari borrow validation 왕복을 없애지 못하므로 유지할 근거가 없었다. [HikariCP configuration](https://github.com/brettwooldridge/HikariCP)

### 3. HAProxy와 peer tunnel은 유지

HAProxy RSS는 약 4~7MB이고 peer SSH는 약 14MB다. 이 정도 비용으로 local app process 장애까지 우회한다. Cloudflare replica만 남기면 connector host 장애는 우회하지만 origin HTTP health에 따른 steering은 보장되지 않는다. 현재 무료 제약에서는 유지하는 편이 낫다.

## JVM/OCI 메모리 최종값

| 항목 | v0.0.14 | v0.0.15 | 판단 |
|---|---:|---:|---|
| Container limit | 512MiB | 512MiB | 유지 |
| GC | Serial GC | Serial GC | 1 vCPU/small heap에 적합 |
| Compiler | tiered level 4 | tiered level 1 (C1) | network-bound CRUD에 C2 비용 불필요 |
| CodeCache | 128MiB | 96MiB | 64MiB 실험은 Full GC 발생, 96MiB는 400초간 0회 |
| Metaspace trigger | 96MiB | 96MiB | 유지 |
| MaxMetaspace (Paketo) | 약 111MiB | 약 111MiB | 실사용 약 69MiB, 충분한 여유 |
| Xmx (Paketo) | 약 238MiB | 약 270MiB | CodeCache 절감분을 heap 여유로 환원 |
| Java process RSS | 약 267~271MiB | 약 200~202MiB | 약 25% 감소 |
| Runtime threads | 27 | 19 | Tomcat idle thread 축소 |
| Tomcat threads | 기본값 | max 32/min-spare 2 | 과부하 시 native stack 폭증 방지 |
| Full GC, 동일 초기 관찰창 | backend당 1회 | backend당 0회 | CodeCache pause 제거 |

v0.0.14는 backend별 343~351초에 CodeCache Full GC가 1회 발생해 154~227ms 멈췄다. C1+64MiB 후보도 186초에 94.8ms Full GC가 발생해 탈락시켰다. C1+96MiB는 사전 A/B 400초와 배포 후 양 서버 관찰창에서 Metadata/CodeCache Full GC가 모두 0회였다. 일반 young GC는 보통 1.7~2.2ms, 관찰 최대 5.2ms였다.

96MiB가 영구적으로 충분하다는 뜻은 아니다. 장시간 로그에서 `CodeCache GC Threshold`가 다시 한 번이라도 나오면 다음 값은 128MiB다. 지금 미리 늘리면 실측 근거 없이 Xmx만 32MiB 줄어드므로 보류했다. Java 25의 기본 CodeCache 최대값은 240MiB이며, 이 영역은 JIT compiled code를 저장한다. [Oracle Java 25 options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)

512MiB container limit도 유지가 맞다. OCI host가 946MiB이고 IAP Python, cloudflared, OS/OCI agent가 함께 실행되며 이미 swap이 약 200~250MiB 사용 중이다. 768MiB로 올리면 실제 메모리를 즉시 확보하는 것이 아니라 JVM이 더 큰 heap 상한을 계산하게 해 host OOM/swap tail latency 위험만 높인다. 반대로 384MiB로 낮춰도 현재 RSS가 자동으로 줄지 않고 OOM 여유만 줄어든다.

## GCP PostgreSQL 메모리 조정

| 항목 | 이전 | 이후 |
|---|---:|---:|
| VM memory available | 276MiB | 413MiB |
| Ops Agent | 약 189MiB RSS | disabled/inactive |
| `shared_buffers` | 128MiB | 128MiB |
| `effective_cache_size` | 4GiB | 512MiB |
| `max_connections` | 100 | 20 |
| `jit` | on | off |

Ops Agent는 이 서비스에서 사용하지 않는 telemetry를 수집하면서 가장 큰 단일 프로세스 메모리를 사용했고, 전송하는 metrics/logs가 과금 대상이 될 수도 있다. 중지로 약 137MiB의 실제 available memory가 늘었다. Budget alert는 GCP server-side 기능이므로 영향이 없다. [Google Ops Agent](https://docs.cloud.google.com/monitoring/agent/ops-agent)

`effective_cache_size`는 메모리를 예약하지 않는 planner hint지만 1GiB VM에 4GiB는 잘못된 모델이므로 512MiB로 고쳤다. `max_connections`는 PostgreSQL shared resource 크기에 직접 영향을 주며, 두 앱의 Hikari 최대 연결 합계가 8이므로 20이면 운영/마이그레이션 여유까지 충분하다. JIT는 짧은 CRUD에는 overhead가 이득보다 크다. `shared_buffers=128MiB`는 1GiB 미만 시스템에서는 OS 여유를 남기라는 PostgreSQL 권고와 현재 23-row working set을 고려해 유지했다. [PostgreSQL resource settings](https://www.postgresql.org/docs/18/runtime-config-resource.html), [planner/JIT settings](https://www.postgresql.org/docs/current/runtime-config-query.html), [when to use JIT](https://www.postgresql.org/docs/current/jit-decision.html)

## 무료 조건에서의 대안 평가

### A. 현재 Spring/Kotlin 유지 — 단기 권장

v0.0.15를 유지하고 장시간 Full GC, public p99, GCP egress만 관찰한다. 다음 네트워크 실험은 GCP VM에 무료 external IPv6를 붙이고 OCI의 고정 IPv6 `/128`만 firewall에 허용해 PostgreSQL TLS를 직접 연결하는 것이다.

장점:

- OCI마다 약 80MiB인 gcloud/IAP Python 제거
- IAP의 HTTPS wrapping과 deploy-time timeout 경로 제거
- PostgreSQL/jOOQ/Spring 유지

제약:

- 서울↔오리건 물리 RTT는 사라지지 않는다.
- GCP IPv6는 Premium Tier이고 IP 자체는 무료지만 egress Free Tier 한도는 지켜야 한다.
- PostgreSQL을 인터넷에 직접 노출하므로 source `/128`, TLS 인증서 검증, rollback을 먼저 준비해야 한다.

GCP는 VM에 할당된 external IPv6에는 주소 요금을 부과하지 않지만 external IPv4는 시간당 과금한다. [GCP network pricing](https://cloud.google.com/vpc/network-pricing), [GCP IPv6 support](https://docs.cloud.google.com/vpc/docs/ipv6-support) IAP TCP forwarding은 공식적으로 SSH/RDP 같은 administrative access를 위한 기능이다. [IAP TCP forwarding](https://docs.cloud.google.com/iap/docs/using-tcp-forwarding)

### B. Pages Functions + D1 — 제품 관점 최종 권장

23건의 diary CRUD가 목적이라면 Spring Boot 두 대, HAProxy 두 대, SSH peer tunnel 두 개, IAP tunnel 두 개, PostgreSQL VM 한 대를 모두 제거한다.

```text
Browser -> Cloudflare Pages Function -> D1
```

Workers Free는 100,000 request/day, D1 Free는 5,000,000 rows read/day, 100,000 rows written/day, 총 5GB storage다. Free 한도 초과 시 D1 요청이 실패하므로 초과 과금되지 않는다. 정적 Pages asset 요청은 무료·무제한이다. [Pages Functions pricing](https://developers.cloudflare.com/pages/functions/pricing/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)

이 안의 단점은 명확하다. Kotlin/Spring/jOOQ/PostgreSQL 학습 프로젝트가 사실상 TypeScript/Worker/SQLite 계열 프로젝트로 바뀐다. 따라서 목적이 “제품”일 때만 실행해야 한다.

### C. 무료 PostgreSQL HA 추가 — 기각

현재 1GiB급 OCI 두 대에 PostgreSQL primary/standby를 같이 얹거나 GCP e2-micro를 복제 DB로 쓰는 것은 메모리와 네트워크가 부족하다. 무료 범위에서 운영 가능한 PostgreSQL HA, 낮은 한국 latency, 무과금 상한을 동시에 만족하는 안은 없다. 백엔드 이중화를 “전체 서비스 HA”로 오해하지 않는 것이 중요하다.

## 구조상 남은 위험

1. GCP PostgreSQL은 단일 장애점이다.
2. IAP tunnel은 administrative tunnel을 상시 data plane으로 사용한다. 이번 배포에서도 read timeout이 실제 발생했다.
3. GCP Free Tier는 hard spending cap이 아니다. 예산 알림은 중단 장치가 아니다. [GCP Free Tier](https://docs.cloud.google.com/free/docs/free-cloud-features)
4. 애플리케이션 코드에는 인증 계층이 없고 `POST /diary`가 존재한다. Pages Function에서 별도 보호하는지는 이 repository만으로 확인할 수 없다. 실제 개인 데이터라면 성능보다 먼저 write 인증을 확인해야 한다.
5. public p99 약 491ms의 대부분은 JVM tuning으로 더 줄일 수 없다. 데이터 위치 또는 실행 위치를 바꿔야 한다.

## 최종 판단

- **Spring 학습이 목적:** 현재 v0.0.15 유지. 다음은 direct IPv6를 한 backend에만 A/B하고, p99와 장애 복구가 실제로 좋아질 때만 IAP를 교체한다.
- **빠르고 무료인 diary 제품이 목적:** Pages Functions + D1로 재작성. 이것이 구조·성능·비용을 동시에 가장 크게 개선한다.
- **하지 않을 것:** Redis/cache 서버, Kubernetes, G1 전환, PostgreSQL 복제, 유료 load balancer. 현재 규모에서 비용 또는 메모리만 늘고 실측 병목을 해결하지 않는다.

원시 수치와 로그 발췌는 `architecture-memory-evidence-2026-09-01.log`에 보존했다.
