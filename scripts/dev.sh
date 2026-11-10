#!/usr/bin/env bash
# 一条命令拉起本地依赖、后端（demo 剖面）和三个前端。演示口令与地址见 README。
# 主仓库：https://github.com/icedkong/crdp-platform
# 本脚本只用于虚构数据。接入华为云 KMS 之前不要导入真实患者。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

lan_ip() {
  ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit }}'
}

port_open() {
  timeout 1 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/$1" >/dev/null 2>&1
}

if [[ -z "${CRDP_H5_UPLOAD_MOBILE_BASE_URL:-}" ]]; then
  ip_addr="$(lan_ip || true)"
  if [[ -n "${ip_addr}" && "${ip_addr}" != "127.0.0.1" ]]; then
    export CRDP_H5_UPLOAD_MOBILE_BASE_URL="http://${ip_addr}:14175"
  else
    export CRDP_H5_UPLOAD_MOBILE_BASE_URL="http://127.0.0.1:14175"
  fi
fi
# 与种子数据同一把演示钥匙。换钥匙后已有密文解不开。不要拿到生产。
export CRDP_CRYPTO_LOCAL_KEY="${CRDP_CRYPTO_LOCAL_KEY:-dev-only-not-a-production-key}"

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  DOCKER=()
fi

if [[ ${#DOCKER[@]} -gt 0 ]]; then
  "${DOCKER[@]}" compose up -d
  echo "等待 MySQL 就绪..."
  ready=0
  for _ in $(seq 1 60); do
    if "${DOCKER[@]}" compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot -p'DevOnly#root2026' --silent >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  if [[ "$ready" -ne 1 ]]; then
    echo "MySQL 未在 120 秒内就绪。查看：docker compose logs mysql" >&2
    exit 1
  fi
else
  echo "未检测到 Docker。改为使用本机已经监听的 MySQL 13306 与 Redis 16379。"
  if ! port_open 13306 || ! port_open 16379; then
    echo "13306 或 16379 没有服务。请先按 README「没有 Docker 时」启动 MySQL 8 与 Redis，再运行本脚本。" >&2
    exit 1
  fi
fi

if ! curl -sf "http://127.0.0.1:18080/actuator/health" >/dev/null 2>&1; then
  echo "打包并启动后端（demo 剖面：本地信封加密 + 模拟短信）..."
  mvn -B -f "$ROOT/backend/pom.xml" -pl crdp-server -am -DskipTests package
  java -jar "$ROOT/backend/crdp-server/target/crdp-server.jar" --spring.profiles.active=demo >"$ROOT/backend/crdp-server.log" 2>&1 &
  echo $! >"$ROOT/backend/crdp-server.pid"
  ready=0
  for _ in $(seq 1 90); do
    if curl -sf "http://127.0.0.1:18080/actuator/health" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  if [[ "$ready" -ne 1 ]]; then
    echo "后端未在 180 秒内健康。日志末尾：" >&2
    tail -n 80 "$ROOT/backend/crdp-server.log" >&2 || true
    exit 1
  fi
fi

cat <<EOF

演示已就绪（虚构数据，接入华为云 KMS 前禁止导入真实患者）。

  医院端     http://127.0.0.1:14173     租户 demoa 或 demob，用户 nurse.demo / doctor.demo，口令 Demo@2026
  平台端     http://127.0.0.1:14174     用户 platform.admin，口令 Demo@2026
  手机上传   ${CRDP_H5_UPLOAD_MOBILE_BASE_URL}
  后端       http://127.0.0.1:18080
  模拟短信   http://127.0.0.1:18080/dev/mock-sms
             也可在后端日志里搜「模拟短信」。短信不会发到真实手机。

局域网另一台设备扫码上传时，二维码会指向 ${CRDP_H5_UPLOAD_MOBILE_BASE_URL}。
若该地址不对，停掉后端后执行：
  export CRDP_H5_UPLOAD_MOBILE_BASE_URL=http://<这台机器的局域网 IP>:14175
  ./scripts/dev.sh

EOF

cd "$ROOT/frontend"
pnpm install
pnpm --parallel --filter @crdp/web-hospital --filter @crdp/web-platform --filter @crdp/mobile-upload dev
