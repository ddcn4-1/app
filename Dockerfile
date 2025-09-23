# multi-stage build for Java 21
FROM --platform=linux/amd64 openjdk:21-jdk-slim as builder

WORKDIR /app

# gradle wrapper와 설정 파일들 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# 의존성 다운로드 (캐시 최적화)
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# runtime stage
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy

# curl 설치 (헬스체크용)
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN addgroup --system spring && adduser --system spring --ingroup spring

# 로그 디렉토리 생성
RUN mkdir -p /app/logs

# 빌드된 JAR 파일 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 권한 설정
RUN chown -R spring:spring /app

USER spring:spring

# 포트 노출
EXPOSE 8080

# 헬스체크 (기존 actuator 활용)
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM 최적화 옵션 (메모리 줄임)
ENTRYPOINT ["java", \
    "-Xms256m", \
    "-Xmx800m", \
    "-XX:+UseG1GC", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.profiles.active=prod", \
    "-jar", "/app/app.jar"]