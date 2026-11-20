#!/bin/sh
# macOS / 本地回归使用，不挂载主机文件：只将两份输入打包流送入无网络容器。
set -eu
work=$1
format=$2
case "$format" in pdf|docx|jpg|png) ext=pdf; case "$format" in jpg|png) ext=png;; esac ;; *) exit 64;; esac
container="crdp-preview-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'exit 143' HUP INT TERM
# 输入 tar 由本启动器生成，名称固定；患者数据不进入参数或日志。
tar -C "$work" -cf - input.bin watermark.txt | docker run --rm -i --name "$container" \
  --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --pids-limit=128 --memory=512m --cpus=1 --user=65532:65532 \
  --tmpfs /tmp:rw,nosuid,nodev,size=512m,mode=1777 crdp-preview-renderer:oqr07 "$format" > "$work/output/result.$ext"
