#!/bin/sh
set -eu
case "$1" in pdf|docx|jpg|png) format=$1 ;; *) exit 64;; esac
work=$(mktemp -d /tmp/preview.XXXXXX)
trap 'rm -rf "$work"' EXIT
cd "$work"
tar -xf - input.bin watermark.txt
/usr/bin/timeout --signal=KILL 40s /usr/bin/java -Xmx256m -XX:ActiveProcessorCount=2 -Djava.awt.headless=true \
  -cp '/opt/crdp-preview/classes:/opt/crdp-preview/lib/*' \
  com.crdp.followup.file.infra.preview.render.PreviewRasterWorker "$work" "$format"
case "$format" in jpg|png) cat "$work/output/result.png" ;; *) cat "$work/output/result.pdf" ;; esac
