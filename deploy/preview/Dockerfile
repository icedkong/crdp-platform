FROM eclipse-temurin:21-jre-noble
# 仅构建阶段联网安装系统包，运行转换时必须由启动器禁网。
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o APT::Update::Error-Mode=any update && apt-get install -y --no-install-recommends libreoffice-writer fonts-noto-cjk fontconfig util-linux coreutils \
    && rm -rf /var/lib/apt/lists/*
COPY worker/classes/ /opt/crdp-preview/classes/
COPY worker/lib/ /opt/crdp-preview/lib/
COPY entrypoint.sh /opt/crdp-preview/entrypoint.sh
RUN chmod 0555 /opt/crdp-preview/entrypoint.sh && ln -sf /opt/java/openjdk/bin/java /usr/bin/java
USER 65532:65532
ENTRYPOINT ["/opt/crdp-preview/entrypoint.sh"]
