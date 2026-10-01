# syntax=docker/dockerfile:1
# 빌드 스테이지: Gradle 로 bootJar 생성 (테스트는 안 돌린다 — Testcontainers 는 배포 빌드에 불필요)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
# 의존성 캐시를 먼저 받아두면 소스만 바뀔 때 재빌드가 빠르다
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
COPY db ./db
# 골든 케이스 수를 산출물에 심는다(백오피스 "계산 오류율"). 세는 곳이 이 파일 하나뿐이라 같이 넣는다 —
# 없으면 스탬프가 비고 대시보드는 개수를 null 로 낸다(빌드는 안 깨진다).
COPY docs/testing.md ./docs/testing.md
RUN ./gradlew --no-daemon clean bootJar

# 런타임 스테이지: JRE 만 있으면 된다 (이미지 작게)
FROM eclipse-temurin:21-jre
WORKDIR /app
# 컨테이너 메모리에 맞춰 힙을 잡는다 (fly [[vm]] memory 기준)
# G-85 b. OOM 뒤 좀비로 남지 않고 재시작되게, 512MB VM 에서 비힙(메타스페이스·스레드) 여유를 남긴다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
COPY --from=build /app/build/libs/yogobi-0.0.1-SNAPSHOT.jar app.jar
# root 로 돌리지 않는다(G-89). 런타임에 쓰는 파일은 /tmp(Tomcat 작업 디렉터리)뿐이다.
RUN useradd --system --uid 10001 --no-create-home app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
