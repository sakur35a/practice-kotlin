#!/usr/bin/env bash
# 컴퓨터에서 한 번 root로 돌린다 (setup-server.sh 뒤에): GitHub의 deploy 가지를 1분마다 보고, 새 커밋이면 빌드해서 올린다.
# 다시 돌려도 이미 있는 건 건드리지 않는다.
#
# usage: setup-auto-deploy.sh
#   처음에는 공개 키를 출력하고 멈춘다 (종료 코드 2). 그 키를 저장소 Deploy keys에 "읽기 전용"으로 넣고 다시 돌린다.
set -Eeuo pipefail

REPO="${DIARY_REPO:-git@github.com:sakur35a/practice-kotlin.git}"
BUILD_HOME=/var/lib/diary-build
KEY="$BUILD_HOME/.ssh/deploy"
[[ $EUID -eq 0 ]] || { echo "root로 실행한다" >&2; exit 1; }
id diary-build >/dev/null 2>&1 || { echo "먼저 setup-server.sh를 실행한다" >&2; exit 1; }

as_build() { runuser -u diary-build -- env HOME="$BUILD_HOME" "$@"; }

install -d -o diary-build -g diary-build -m 700 "$BUILD_HOME/.ssh"
[[ -f "$KEY" ]] || as_build ssh-keygen -q -t ed25519 -N '' -C "diary-deploy-$(hostname)" -f "$KEY"
# GitHub가 공개한 ed25519 호스트 키다 (SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU). 처음 접속 때 믿고 받아들이지 않도록 고정한다.
known="$BUILD_HOME/.ssh/known_hosts"
grep -q '^github.com ssh-ed25519 ' "$known" 2>/dev/null ||
  echo 'github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl' >>"$known"
chown diary-build:diary-build "$known"

ssh_cmd="ssh -i $KEY -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$known"
if ! as_build env GIT_SSH_COMMAND="$ssh_cmd" git ls-remote -q "$REPO" >/dev/null 2>&1; then
  echo "아래 공개 키를 GitHub 저장소 Deploy keys에 읽기 전용으로 넣고 다시 돌린다 (이름: diary-$(hostname)):"
  cat "$KEY.pub"
  exit 2
fi
[[ -d "$BUILD_HOME/src/.git" ]] || as_build env GIT_SSH_COMMAND="$ssh_cmd" git clone -q "$REPO" "$BUILD_HOME/src"
as_build chmod +x "$BUILD_HOME/src/gradlew"

install -d -m 755 /var/lib/diary-deploy
systemctl daemon-reload
systemctl enable --now diary-deploy.timer
echo "자동 배포 준비 끝. 지금 바로 한 번 올리려면: /opt/diary/bin/diary-pull-deploy"
