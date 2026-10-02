#!/usr/bin/env bash
# OCI Vault를 만들고 GCP Secret Manager의 값을 옮긴다. 한 번만 쓰는 도구이고 사용자가 Mac에서 직접 실행한다
# (Vault, 키, 시크릿, IAM 동적 그룹과 정책을 만든다). 비밀 값은 화면과 로그에 나오지 않는다.
#
# 사전 준비 (사용자 인증이 필요한 부분):
#   oci session authenticate --region ap-chuncheon-1 --profile-name diary   # 브라우저 로그인
#   gcloud auth list                                                       # 값을 읽을 GCP 계정
# 여러 번 실행해도 같은 이름이 이미 있으면 건너뛴다.
set -Eeuo pipefail

readonly REGION="ap-chuncheon-1"
readonly GCP_PROJECT="key-decorator-356314"
readonly SECRETS=(diary-db-password diary-slack-webhook-url diary-cloudflare-api-token)
export OCI_CLI_PROFILE="${OCI_CLI_PROFILE:-diary}" OCI_CLI_AUTH=security_token

oci os ns get >/dev/null || { echo "OCI 인증이 없습니다: oci session authenticate --region $REGION --profile-name diary" >&2; exit 1; }

# 두 서버의 컴파트먼트와 인스턴스 OCID (읽기 전용 메타데이터)
meta() { ssh -o BatchMode=yes "$1" "curl -s -m 5 -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/instance/$2"; }
readonly COMPARTMENT_ID="$(meta oci-diary compartmentId)"
readonly INSTANCE_1="$(meta oci-diary id)"
readonly INSTANCE_2="$(meta oci-diary-2 id)"
readonly TENANCY_ID="$(awk -v p="[$OCI_CLI_PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f&&/^tenancy=/{sub("tenancy=","");print;exit}' ~/.oci/config)"
for v in COMPARTMENT_ID INSTANCE_1 INSTANCE_2 TENANCY_ID; do [[ "${!v}" == ocid1.* ]] || { echo "$v 값을 못 읽었습니다" >&2; exit 1; }; done

echo "1/5 Vault"
VAULT_ID="$(oci kms management vault list -c "$COMPARTMENT_ID" --all \
  --query "data[?\"display-name\"=='diary-vault' && \"lifecycle-state\"=='ACTIVE'].id | [0]" --raw-output 2>/dev/null | grep -E '^ocid1\.' || true)"
if [[ -z "$VAULT_ID" ]]; then
  VAULT_ID="$(oci kms management vault create -c "$COMPARTMENT_ID" --display-name diary-vault \
    --vault-type DEFAULT --wait-for-state ACTIVE --query 'data.id' --raw-output)"
fi
MGMT_ENDPOINT="$(oci kms management vault get --vault-id "$VAULT_ID" --query 'data."management-endpoint"' --raw-output)"

echo "2/5 마스터 키 (소프트웨어 보호 = 무료)"
KEY_ID="$(oci kms management key list -c "$COMPARTMENT_ID" --endpoint "$MGMT_ENDPOINT" --all \
  --query "data[?\"display-name\"=='diary-secrets-key' && \"lifecycle-state\"=='ENABLED'].id | [0]" --raw-output 2>/dev/null | grep -E '^ocid1\.' || true)"
if [[ -z "$KEY_ID" ]]; then
  KEY_ID="$(oci kms management key create -c "$COMPARTMENT_ID" --display-name diary-secrets-key \
    --key-shape '{"algorithm":"AES","length":32}' --protection-mode SOFTWARE \
    --endpoint "$MGMT_ENDPOINT" --wait-for-state ENABLED --query 'data.id' --raw-output)"
fi

echo "3/5 시크릿 (GCP에서 읽어 OCI로 바로 넘긴다)"
for name in "${SECRETS[@]}"; do
  exists="$(oci vault secret list -c "$COMPARTMENT_ID" --vault-id "$VAULT_ID" --name "$name" --all \
    --query 'data[?"lifecycle-state"==`ACTIVE`].id | [0]' --raw-output 2>/dev/null | grep -E '^ocid1\.' || true)"
  if [[ -n "$exists" ]]; then echo "  $name: 이미 있어 건너뜀"; continue; fi
  b64="$(gcloud secrets versions access latest --secret="$name" --project "$GCP_PROJECT" | base64 | tr -d '\n')"
  [[ -n "$b64" ]] || { echo "  $name: GCP에서 값을 읽지 못했습니다" >&2; exit 1; }
  oci vault secret create-base64 -c "$COMPARTMENT_ID" --vault-id "$VAULT_ID" --key-id "$KEY_ID" \
    --secret-name "$name" --secret-content-content "$b64" --wait-for-state ACTIVE >/dev/null
  unset b64
  echo "  $name: 만들었습니다"
done

echo "4/5 동적 그룹 (두 서버 인스턴스)"
if oci iam dynamic-group list -c "$TENANCY_ID" --all --query 'data[?name==`diary-instances`].id | [0]' --raw-output 2>/dev/null | grep -q '^ocid1\.'; then
  echo "  이미 있어 건너뜀"
else
  oci iam dynamic-group create -c "$TENANCY_ID" --name diary-instances \
    --description "diary app servers (read Vault secrets via instance principal)" \
    --matching-rule "Any {instance.id = '$INSTANCE_1', instance.id = '$INSTANCE_2'}" >/dev/null
fi

echo "5/5 정책 (이 Vault의 시크릿 읽기만 허용)"
if oci iam policy list -c "$TENANCY_ID" --all --query 'data[?name==`diary-read-secrets`].id | [0]' --raw-output 2>/dev/null | grep -q '^ocid1\.'; then
  echo "  이미 있어 건너뜀"
else
  oci iam policy create -c "$TENANCY_ID" --name diary-read-secrets \
    --description "diary servers may read secret bundles of diary-vault" \
    --statements "[\"Allow dynamic-group diary-instances to read secret-bundles in compartment id $COMPARTMENT_ID where target.vault.id = '$VAULT_ID'\"]" >/dev/null
fi

echo
echo "완료. 다음 값을 Claude에게 알려 주세요 (비밀이 아닙니다):"
echo "  VAULT_ID=$VAULT_ID"
