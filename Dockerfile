# 多阶段构建: 容器内完成编译打包
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
COPY src src
RUN ./mvnw -q package -DskipTests

# 运行镜像: JRE 21 + git(增量变更检测与 Git 克隆依赖 git 命令)
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends git curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD curl -sf http://localhost:8080/api/projects > /dev/null || exit 1
ENTRYPOINT ["java", "-Xmx1g", "-jar", "app.jar"]
