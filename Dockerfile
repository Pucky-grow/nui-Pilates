# 微信云托管(代码仓库构建): 多阶段从源码构建后端, 运行层沿用已验证制品配置.
# 构建平台为 Linux/amd64; 不要上传本机 ARM 镜像.
FROM maven:3.9.11-eclipse-temurin-17@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82 AS build
WORKDIR /build
COPY backend/pom.xml ./backend/pom.xml
COPY backend/src ./backend/src
RUN mvn -B -ntp -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
RUN apt-get update && apt-get install --no-install-recommends -y libssl3=3.0.2-0ubuntu1.30 libfreetype6=2.11.1+dfsg-1ubuntu0.4 libpng16-16=1.6.37-3ubuntu0.7 libbrotli1=1.0.9-2ubuntu0.1 && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 pilates && useradd --uid 10001 --gid pilates --no-create-home pilates && mkdir -p /data/uploads && chown pilates:pilates /data/uploads
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/backend/target/pilates-server.jar /app/app.jar
USER 10001:10001
ENV TZ=Asia/Shanghai JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai -XX:MaxRAMPercentage=70" \
    SPRING_PROFILES_ACTIVE=staging SERVER_PORT=8080
EXPOSE 8080
# Redis、私有存储与限定范围 MySQL 凭据必须由云托管环境变量提供, 无本地回退.
# 预检(CloudrunPreflight)是独立于业务服务的进程, 不能暴露业务接口.
ENTRYPOINT ["sh","-c","case \"${PILATES_RUN_MODE:-application}\" in preflight) exec java -Dloader.main=com.yoyi.pilates.infra.tools.CloudrunPreflight -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher ;; application) exec java -Dpilates.production.artifact=true -jar /app/app.jar ;; *) echo 'STOP: unsupported run mode; values withheld.' >&2; exit 2 ;; esac"]
