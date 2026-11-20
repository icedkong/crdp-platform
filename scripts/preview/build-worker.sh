#!/bin/sh
# 生成只含纯 Java 渲染入口与 PDFBox 的部署包，不携带应用配置、账号或患者数据。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
mvn -B -Daether.syncContext.named.factory=noop -f backend/pom.xml -pl crdp-followup -am -Dmaven.test.skip=true install
stage="$root/backend/crdp-followup/target/preview-image"
# 仅清理固定 target 构建产物，避免旧版本依赖混入新镜像。
rm -rf "$root/backend/crdp-followup/target/preview-lib"
mvn -B -Daether.syncContext.named.factory=noop -f backend/crdp-followup/pom.xml dependency:copy-dependencies \
  -DincludeGroupIds=org.apache.pdfbox,commons-logging -DincludeScope=runtime -DoutputDirectory=target/preview-lib
rm -rf "$stage/worker"
mkdir -p "$stage/worker/classes/com/crdp/followup/file/infra/preview/render" "$stage/worker/lib"
cp backend/crdp-followup/target/classes/com/crdp/followup/file/infra/preview/render/*.class "$stage/worker/classes/com/crdp/followup/file/infra/preview/render/"
cp backend/crdp-followup/target/preview-lib/*.jar "$stage/worker/lib/"
cp deploy/preview/Dockerfile deploy/preview/entrypoint.sh "$stage/"
printf '%s\n' "$stage"
