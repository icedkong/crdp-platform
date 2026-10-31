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
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/crdp-server.jar"]
