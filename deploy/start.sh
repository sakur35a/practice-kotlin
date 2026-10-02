#!/usr/bin/env bash
set -Eeuo pipefail

readonly IMAGE="${1:-ghcr.io/sakur35a/practice-kotlin:latest}"
readonly ENV_FILE="${DIARY_ENV_FILE:-$HOME/.config/diary.env}"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Missing deployment environment file: $ENV_FILE" >&2
  exit 1
fi

# 비밀 값은 OCI Vault에서 가져온다(인스턴스 주체 인증이라 키 파일이 없다). 값은 이 셸의 환경변수로만 두고
# podman에는 이름만 넘겨서 ps에 노출되지 않게 한다. 하나라도 못 가져오면 여기서 멈춘다(deploy.sh가 이전 컨테이너를 되살린다).
readonly VAULT_ID="${DIARY_VAULT_ID:-ocid1.vault.oc1.ap-chuncheon-1.gjvl7yfvaadhs.ab4w4ljrgxcjm73vavmo4xhuqkvlrur5ysc4rpenpcpisdoivjvaggmqdaia}"
fetch_secret() {
  podman run --rm --network host --memory=300m ghcr.io/oracle/oci-cli:latest \
    secrets secret-bundle get-secret-bundle-by-name \
    --secret-name "$1" --vault-id "$VAULT_ID" --auth instance_principal \
    --connection-timeout 10 --read-timeout 30 \
    --query 'data."secret-bundle-content".content' --raw-output | base64 -d
}
SPRING_DATASOURCE_PASSWORD="$(fetch_secret diary-db-password)"
SLACK_WEBHOOK_URL="$(fetch_secret diary-slack-webhook-url)"
CLOUDFLARE_API_TOKEN="$(fetch_secret diary-cloudflare-api-token)"
for name in SPRING_DATASOURCE_PASSWORD SLACK_WEBHOOK_URL CLOUDFLARE_API_TOKEN; do
  if [[ -z "${!name}" ]]; then
    echo "Empty secret from OCI Vault: $name" >&2
    exit 1
  fi
done
export SPRING_DATASOURCE_PASSWORD SLACK_WEBHOOK_URL CLOUDFLARE_API_TOKEN

# 상대 서버 haproxy가 사설망으로 직접 붙는다 (firewalld와 OCI 보안 목록에서 상대 IP만 허용)
readonly PRIVATE_IP="$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')"
if [[ -z "$PRIVATE_IP" ]]; then
  echo "Cannot determine the private IP address" >&2
  exit 1
fi

podman run -d \
  --name diary-app \
  --restart=always \
  --memory=512m \
  -p 127.0.0.1:18080:8080 \
  -p "$PRIVATE_IP:18080:8080" \
  -p 127.0.0.1:18090:8081 \
  -e BPL_JVM_THREAD_COUNT=50 \
  -e BPL_JVM_CLASS_ADJUSTMENT=125% \
  -e BPL_JVM_AOTCACHE_ENABLED=true \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_PASSWORD \
  -e SLACK_WEBHOOK_URL \
  -e CLOUDFLARE_API_TOKEN \
  -e 'JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=96M -XX:MetaspaceSize=128M -Xss512k -XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0 -Xlog:gc,gc+metaspace=info' \
  --env-file "$ENV_FILE" \
  "$IMAGE"
