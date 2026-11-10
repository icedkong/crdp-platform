# 构建上下文是 backend/。镜像给以后的华为云 CCE 用，这里不放云凭证。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --home /app crdp \
    && mkdir -p /app \
    && chown crdp:crdp /app
WORKDIR /app
COPY --from=build /src/crdp-server/target/crdp-server.jar /app/crdp-server.jar
USER 10001
EXPOSE 18080
# 镜像默认演示剖面：本地信封加密 + 模拟短信，不连华为云 KMS。
# 真实生产在 KMS 接入后改为 SPRING_PROFILES_ACTIVE=prod（当前 prod 会因 KMS 未接入而拒绝启动）。
# 仅虚构数据。接入 KMS 之前禁止导入真实患者。
ENV SPRING_PROFILES_ACTIVE=demo
ENV CRDP_CRYPTO_LOCAL_KEY=dev-only-not-a-production-key
ENTRYPOINT ["java", "-jar", "/app/crdp-server.jar"]
