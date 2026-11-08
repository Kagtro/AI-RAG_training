# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先单独复制 pom，利用 Docker 层缓存下载依赖
COPY pom.xml .
COPY docker/maven-settings.xml /root/.m2/settings.xml
RUN mvn -q -DskipTests dependency:go-offline

# 再复制源码并打包
COPY src ./src
RUN mvn -q -DskipTests package

# 运行阶段：只保留 JRE 和 jar，镜像更小
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads && chown -R app:app /app

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
