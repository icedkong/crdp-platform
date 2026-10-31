#!/usr/bin/env bash
# 一条命令拉起本地依赖、后端和两个前端。演示口令见 README。
# 主仓库：https://github.com/icedkong/crdp-platform
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
else
  DOCKER=(sudo docker)
fi

"${DOCKER[@]}" compose up -d

echo "等待 MySQL 就绪..."
for _ in $(seq 1 60); do
  if "${DOCKER[@]}" compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot -p'DevOnly#root2026' --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! curl -sf "http://127.0.0.1:18080/actuator/health" >/dev/null 2>&1; then
  echo "打包并启动后端..."
  mvn -B -f "$ROOT/backend/pom.xml" -pl crdp-server -am -DskipTests package
  java -jar "$ROOT/backend/crdp-server/target/crdp-server.jar" --spring.profiles.active=local >"$ROOT/backend/crdp-server.log" 2>&1 &
  echo $! >"$ROOT/backend/crdp-server.pid"
  for _ in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:18080/actuator/health" >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
fi

cd "$ROOT/frontend"
pnpm install
pnpm dev
