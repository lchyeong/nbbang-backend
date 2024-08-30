# 1. Build stage
FROM gradle:7.6.0-jdk17 AS build

# 컨테이너 내 작업 디렉토리 설정
WORKDIR /app

# 프로젝트의 모든 파일을 컨테이너로 복사
COPY . .

# 애플리케이션 빌드
RUN ./gradlew build -x test --no-daemon

# 2. Runtime stage
FROM openjdk:17-jdk-slim

# 컨테이너 내 작업 디렉토리 설정
WORKDIR /app

# 빌드한 JAR 파일을 가져와서 컨테이너로 복사
COPY --from=build /app/build/libs/nbbang-0.0.1-SNAPSHOT.jar /app/nbbang.jar

#.env 파일을 컨테이너의 작업 디렉토리로 복사
COPY .env /app/.env

# 애플리케이션이 사용할 포트 노출
EXPOSE 8080

# 컨테이너 시작 시 실행할 명령어 설정
CMD ["java", "-jar", "/app/nbbang.jar"]
