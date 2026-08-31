# Diary latency 개선 검증 보고서

- 작성 기준: 2026-08-31
- 대상: `/diary` 조회 API
- 원칙: 보존된 운영 로그, 직접 실행한 요청, 현재 운영 컨테이너 로그로 확인된 사실만 사용
- 제외: 수치가 없는 체감, 일반적인 JVM 이론만으로 추정한 효과, 서로 다른 환경을 동일 조건으로 간주한 비교

## 1. 결론

실제 데이터로 확인된 결과는 다음 세 가지다.

1. 단일 SELECT 조회에서 `@Transactional(readOnly = true)`를 제거한 뒤 GCP DB 환경의 HTTP p50이 427ms에서 267.3ms, p95가 630ms에서 311.5ms로 감소했다.
2. 애플리케이션 warm-up은 최초 repository 조회의 수 초짜리 cold cost를 서버가 요청을 받기 전으로 이동시켰다. 다만 최초 HTTP 요청 자체는 1,696ms가 걸려 HTTP 경로 전체를 완전히 예열하지는 못했다.
3. `ReservedCodeCacheSize`를 64MB에서 128MB로 늘린 뒤 과거 기동 28초에 발생했던 `CodeCache GC Threshold` Full GC는 현재 두 서버의 기동 후 15~16분 구간에서 0회였다. 장기 실행 시 endpoint latency 개선까지는 아직 확인되지 않았다.

`MetaspaceSize=64M`은 개선 완료로 볼 수 없다. 현재 v0.0.13에서 두 서버 합계 82회의 `Metadata GC Threshold` Full GC가 다시 확인됐다.

## 2. 증거 자료

| 파일 | 내용 |
|---|---|
| `latency-investigation-evidence.log` | OCI DB 시절 cold request, warm-up 이후 latency, 과거 GC 로그 |
| `gcp-iap-latency-2026-08-31.log` | GCP DB + IAP + read-only transaction 상태의 repository/HTTP latency |
| `latency-v0.0.13-2026-08-31.log` | read-only transaction 제거 후 두 OCI 서버 직접 요청 20회 |
| `latency-live-v0.0.13-2026-08-31.log` | v0.0.13 두 운영 컨테이너의 15~16분 누적 latency 및 GC 집계 |

모든 percentile은 보존된 정수 millisecond 값을 오름차순 정렬한 뒤 nearest-rank 방식으로 계산했다.

## 3. 최초 cold latency 조사

### 3.1 변경 전 관측값

OCI 백엔드와 OCI DB를 사용하던 기존 컨테이너에서 다음 값이 관측됐다.

| 사례 | repository | controller |
|---|---:|---:|
| cold request 1 | 7,379ms | 7,608ms |
| cold request 2 | 8,981ms | 9,225ms |
| 같은 컨테이너의 후속 warm request | 6ms | 14ms |

근거: `latency-investigation-evidence.log` 5~15행.

두 번째 cold 사례는 repository 8,981ms 중 SQL 구간이 8,494ms였고 connection 측정값은 67ms였다. 따라서 해당 요청에서 긴 시간의 대부분이 repository 내부 SQL 실행 구간으로 기록됐다는 것까지만 확인된다.

당시 표본 21개의 집계는 다음과 같다.

| 계층 | n | p50 | p95 | 최대 |
|---|---:|---:|---:|---:|
| repository | 21 | 8ms | 7,379ms | 8,981ms |
| controller | 21 | 15ms | 7,608ms | 9,225ms |

표본이 21개뿐이므로 이 p95는 운영 SLA용 percentile이 아니라 보존된 cold/warm 혼합 표본의 분포다.

### 3.2 장시간 idle 후 관측값

약 12시간 간격의 요청은 repository 357ms, service 383ms, controller 591ms였다. 근거는 `latency-investigation-evidence.log` 17~22행이다.

이 데이터에서 확인할 수 있는 것은 장시간 idle 후 요청이 직전 요청의 repository 179ms/controller 184ms보다 느려졌다는 사실이다. 단일 로그만으로 원인이 DB page cache, 네트워크, JVM pause 또는 다른 요소인지 분리할 수는 없다.

## 4. warm-up 적용 결과

### 4.1 변경 내용

애플리케이션 초기화 과정에서 실제 `findDiarySlice()`를 한 번 실행하도록 변경했다. 이 호출은 jOOQ query 생성, DB 조회, record mapping을 포함한다.

### 4.2 관측 결과

v0.0.11 기동 시 warm-up repository 호출은 2,811ms였으며 Tomcat started 로그보다 먼저 완료됐다. 이후 첫 servlet 요청의 repository는 5ms였다.

| 시점 | repository | HTTP |
|---|---:|---:|
| 기동 중 warm-up | 2,811ms | 해당 없음 |
| 기동 후 첫 HTTP | 5ms | 1,696ms |
| 다음 HTTP | 6ms | 14ms |

근거: `latency-investigation-evidence.log` 54~73행.

확인된 영향:

- repository cold cost가 사용자 요청 전, 애플리케이션 기동 구간으로 이동했다.
- repository 조회만으로는 DispatcherServlet과 전체 HTTP 경로가 예열되지 않아 첫 HTTP는 여전히 1,696ms였다.
- 다음 HTTP가 14ms였으므로 첫 HTTP 호출이 남은 web 경로를 예열한 사실은 확인된다.

서버에는 이후 실제 `/diary`를 호출하는 1분 주기 warm timer가 추가됐다. 현재 dual-backend 구성에서는 HAProxy도 양쪽에서 `/diary`를 1초마다 health check하므로 별도 사용자 요청이 없어도 endpoint가 지속적으로 호출된다.

## 5. warm 상태의 OCI DB latency

v0.0.11 전체 컨테이너 로그 집계는 다음과 같다.

| 지표 | 값 |
|---|---:|
| HTTP 전체 n | 207 |
| HTTP p50 | 7ms |
| HTTP p95 | 19ms |
| HTTP p99 | 82ms |
| 첫 요청 제외 HTTP p99 | 68ms |
| 첫 요청 제외 HTTP 최대 | 84ms |
| repository p50 | 2ms |
| repository p95 | 6ms |
| repository p99 | 24ms |

근거: `latency-investigation-evidence.log` 81~86행.

이 결과는 OCI 백엔드와 OCI DB가 가까운 네트워크에 있던 시기의 값이다. GCP DB 이전 후 측정값과 JVM 설정 효과를 비교하는 기준으로 사용하면 안 되고, DB 배치가 달랐을 때의 관측값으로만 사용한다.

## 6. GCP DB 이전 후 latency

GCP DB로 이전하고 IAP tunnel을 사용한 v0.0.12 로그의 집계는 다음과 같다.

| 계층 | n | 평균 | p50 | p95 | 최대 |
|---|---:|---:|---:|---:|---:|
| repository | 20 | 170.4ms | 169ms | 173ms | 299ms |
| HTTP | 21 | 440.4ms | 427ms | 630ms | 696ms |

근거: `gcp-iap-latency-2026-08-31.log` 전체 41행.

동일 thread의 대표 사례:

| thread | repository | HTTP | HTTP와 repository 차이 |
|---|---:|---:|---:|
| `nio-8080-exec-8` | 167ms | 427ms | 260ms |
| `nio-8080-exec-7` | 169ms | 427ms | 258ms |

근거: `gcp-iap-latency-2026-08-31.log` 18~21행.

확인된 사실은 repository 작업이 끝난 뒤 HTTP 요청 완료까지 약 258~260ms가 추가됐다는 것이다.

## 7. read-only transaction 제거 결과

### 7.1 변경 내용

각각 단일 jOOQ SELECT만 수행하는 `findAll()`과 `findDiarySlice()`에서 `@Transactional(readOnly = true)`를 제거했다. 쓰기 메서드의 `@Transactional`은 유지했다.

### 7.2 직접 요청 결과

v0.0.13 배포 후 두 OCI 서버에 각각 10회, 총 20회의 직접 HTTP 요청을 실행했다.

| 지표 | v0.0.12 | v0.0.13 | 차이 |
|---|---:|---:|---:|
| n | 21 | 20 | - |
| 평균 | 440.4ms | 263.0ms | -177.4ms |
| p50 | 427ms | 267.3ms | -159.7ms, -37.4% |
| p95 | 630ms | 311.5ms | -318.5ms, -50.6% |
| 최대 | 696ms | 378.9ms | -317.1ms |

근거: `latency-v0.0.13-2026-08-31.log` 9~18행, 37~46행 및 31~33행.

v0.0.13의 동일 요청 내 repository와 HTTP 차이는 다음과 같이 줄었다.

| repository | HTTP | 차이 |
|---:|---:|---:|
| 266ms | 268ms | 2ms |
| 277ms | 279ms | 2ms |
| 290ms | 292ms | 2ms |
| 250ms | 252ms | 2ms |

근거: `latency-v0.0.13-2026-08-31.log` 19~24행, 49~52행.

repository 시간이 v0.0.12의 약 169ms에서 v0.0.13의 약 250~290ms로 커진 것은 측정 위치가 달라졌기 때문이다. 트랜잭션이 있을 때 Spring이 service 진입 전에 connection을 획득했고, 제거 후에는 repository의 `dsl.fetch()`가 connection을 획득한다. 현재 repository 타이머는 `dsl.fetch()`를 포함하므로 connection 획득 및 검사가 repository 시간 안에 기록된다.

확인된 영향:

- 전체 HTTP latency가 감소했다.
- repository 종료 후 HTTP 완료까지의 추가 시간이 대표 사례 258~260ms에서 약 2ms로 감소했다.
- 단일 SELECT 결과나 쓰기 트랜잭션 동작은 변경하지 않았다.

비교 표본이 각각 20여 개이고 수집 방식도 완전히 동일하지 않으므로 이 수치를 장기 운영 p99로 사용하지 않는다. 다만 동일 endpoint의 repository/HTTP 간격 감소는 로그에서 직접 확인된다.

## 8. Code Cache 변경 결과

### 8.1 64MB에서 관측된 현상

`ReservedCodeCacheSize=64M`인 v0.0.11에서 다음 Full GC가 있었다.

| JVM uptime | 원인 | pause |
|---:|---|---:|
| 27.986초 | CodeCache GC Threshold | 182.129ms |
| 10,864.946초 | CodeCache GC Threshold | 2,214.953ms |

근거: `latency-investigation-evidence.log` 48~53행.

두 번째 Full GC safepoint total은 2,215.073ms였다. 해당 시점 직전 완료 요청은 HTTP 6ms였고 다음 기록 요청은 약 9초 후 HTTP 13ms였다. GC pause와 겹친 실제 요청이 로그에 없으므로 이 이벤트가 endpoint p99를 얼마만큼 증가시켰는지는 확인할 수 없다.

### 8.2 128MB 적용 후 관측

v0.0.13에서 Code Cache를 128MB로 변경했다. 기동 15~16분 후 확인 결과:

| 서버 | CodeCache GC Threshold 횟수 |
|---|---:|
| oci-diary | 0 |
| oci-diary-2 | 0 |

근거: `latency-live-v0.0.13-2026-08-31.log` 5~10행, 16~21행.

확인된 영향은 과거 28초에 발생했던 초기 Code Cache Full GC가 현재 같은 시간 범위에서 발생하지 않았다는 것이다.

아직 확인되지 않은 것:

- 과거 두 번째 이벤트가 발생했던 약 3시간 이후에도 0회인지
- Code Cache 변경만으로 endpoint p95 또는 p99가 얼마나 줄었는지
- 128MB가 장기적으로 충분한지

따라서 Code Cache는 초기 JVM 증상 개선은 확인됐지만 장기 latency 개선 완료로 판정하지 않는다.

## 9. Metaspace 변경 재검증

### 9.1 과거 데이터

v0.0.9에서는 35.975~44.994초 사이 `Metadata GC Threshold` Full GC가 19회 발생했다.

| 횟수 | 총 pause | 최대 pause |
|---:|---:|---:|
| 19 | 3,425.984ms | 398.701ms |

v0.0.11의 당시 보존 로그 집계에는 Metadata 원인 Full GC가 0회였다. 근거: `latency-investigation-evidence.log` 24~46행, 81~83행.

### 9.2 현재 v0.0.13 데이터

15~16분 실행 후 현재 데이터는 다음과 같다.

| 서버 | 횟수 | 총 pause | 최대 pause | 마지막 관측 시각 |
|---|---:|---:|---:|---:|
| oci-diary | 42 | 7,796.864ms | 402.783ms | JVM 366.480초 |
| oci-diary-2 | 40 | 7,561.678ms | 593.957ms | JVM 363.091초 |
| 합계 | 82 | 15,358.542ms | 593.957ms | 약 6분 |

근거: `latency-live-v0.0.13-2026-08-31.log` 5~25행.

결론:

- `MetaspaceSize=64M` 적용 후 특정 v0.0.11 로그에서는 0회였던 것은 사실이다.
- 그러나 v0.0.13 두 서버에서 동일 원인의 Full GC가 재발했으므로 지속적인 해결 효과는 확인되지 않았다.
- 현재 데이터만으로 재발 원인이 Code Cache 증설, class loading 양, JVM ergonomics 또는 다른 요소 중 무엇인지는 구분할 수 없다.
- 따라서 Metaspace 설정은 latency 개선 성공 항목에서 제외한다.

## 10. 현재 운영 latency

v0.0.13 컨테이너의 15~16분 누적 로그는 다음과 같다.

| 서버 | HTTP n | p50 | p95 | p99 | 최대 |
|---|---:|---:|---:|---:|---:|
| oci-diary | 1,493 | 267ms | 311ms | 398ms | 4,608ms |
| oci-diary-2 | 1,392 | 260ms | 304ms | 393ms | 2,791ms |

근거: `latency-live-v0.0.13-2026-08-31.log` 10행, 21행.

이 값은 사용자 요청 percentile이 아니다. `deploy/haproxy.cfg`는 두 OCI 서버에서 local/peer backend의 `/diary`를 1초마다 검사한다. 따라서 누적 로그 대부분에는 HAProxy health check가 포함된다. repository 최대값에는 HTTP 요청이 아닌 기동 warm-up도 포함된다.

현재 로그로 확인 가능한 것은 다음뿐이다.

- 지속적으로 호출되는 `/diary` 경로의 p50은 260~267ms다.
- 같은 혼합 트래픽의 p95는 304~311ms, p99는 393~398ms다.
- health check와 실제 사용자를 구분하지 않으므로 실제 사용자 p99는 계산할 수 없다.

## 11. 개선으로 인정하지 않은 작업

다음 항목은 필요성이나 운영상 장점과 별개로, 독립적인 latency 개선 수치가 없어 이 보고서의 성과에서 제외했다.

| 작업 | 제외 이유 |
|---|---|
| Hikari pool size 조정 | 동일 환경의 변경 전후 독립 측정 없음 |
| Hikari keepalive | HikariCP 7.0.2 기본 2분이며 이번에 동작 변경 없음 |
| `Xss512k`, thread count 50 | 메모리 배분 변화는 있으나 endpoint latency 단독 효과 측정 없음 |
| SerialGC 유지 | collector 변경 전후 데이터 없음 |
| cache 제거 | 이중화 정책을 위한 변경이며 latency 개선 작업이 아님 |
| layer/HTTP info logging | 병목 위치 확인용이며 latency 개선 자체가 아님 |
| HAProxy 도입 | 가용성 개선이며 health check가 오히려 측정 표본에 섞임 |
| OCI DB에서 GCP DB로 이전 | warm latency가 수 ms에서 수백 ms로 증가했으므로 성능 개선이 아님 |
| jOOQ generated method 축소 | 독립적인 변경 전후 latency 데이터 없음 |

## 12. 최종 판정

### endpoint latency 개선 확인

- 단일 SELECT의 read-only transaction 제거
  - HTTP p50 427ms → 267.3ms
  - HTTP p95 630ms → 311.5ms
  - repository 완료 후 HTTP 완료 간격 약 258~260ms → 약 2ms

### cold cost 이동 확인

- 실제 diary query warm-up
  - repository cold cost 2,811ms를 Tomcat ready 이전에 실행
  - ready 이후 첫 repository 5ms
  - 최초 HTTP 1,696ms는 별도로 남았고 다음 HTTP는 14ms

### JVM 증상만 부분 개선 확인

- Code Cache 64MB → 128MB
  - 과거 28초 시점 CodeCache Full GC 존재
  - 현재 두 서버 15~16분 동안 CodeCache Full GC 0회
  - 3시간 이후 및 endpoint percentile 효과는 미확인

### 개선 실패 또는 미확인

- `MetaspaceSize=64M`: v0.0.13에서 Metadata Full GC 82회 재발
- Hikari, thread/stack, SerialGC 설정: 독립적인 latency 효과 미측정

## 13. 데이터 기반 후속 측정 조건

현재 구성에서 실제 사용자 p99를 얻으려면 최소한 다음 조건이 필요하다.

1. HTTP 로그에서 HAProxy health check와 사용자 요청을 구분한다.
2. 같은 버전, 같은 DB, 같은 요청 간격으로 변경 전후를 각각 충분한 횟수 측정한다.
3. Code Cache는 과거 문제 시점인 JVM uptime 3시간 이후까지 관찰한다.
4. Metadata GC 발생 시각과 같은 request ID의 HTTP elapsed를 연결해 실제 요청 영향 여부를 확인한다.

이 조건이 충족되기 전에는 현재 live log의 p99 393~398ms를 사용자 체감 p99로 사용하지 않는다.
