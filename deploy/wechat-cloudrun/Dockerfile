# Reviewed Java artifact only. Build on Linux/amd64; do not upload an old ARM image.
FROM eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
RUN apt-get update && apt-get install --no-install-recommends -y libssl3=3.0.2-0ubuntu1.30 libfreetype6=2.11.1+dfsg-1ubuntu0.4 libpng16-16=1.6.37-3ubuntu0.7 libbrotli1=1.0.9-2ubuntu0.1 && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 pilates && useradd --uid 10001 --gid pilates --no-create-home pilates && mkdir -p /data/uploads && chown pilates:pilates /data/uploads
WORKDIR /app
COPY --chown=10001:10001 pilates-server.jar /app/app.jar
USER 10001:10001
ENV TZ=Asia/Shanghai JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai -XX:MaxRAMPercentage=70" \
    SPRING_PROFILES_ACTIVE=staging SERVER_PORT=8080
EXPOSE 8080
# Redis, private storage and scoped MySQL credentials are required; no fallback to dev.
# Preflight is a separate, non-Spring process; it cannot expose business endpoints.
ENTRYPOINT ["sh","-c","case \"${PILATES_RUN_MODE:-application}\" in preflight) exec java -Dloader.main=com.yoyi.pilates.infra.tools.CloudrunPreflight -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher ;; application) exec java -Dpilates.production.artifact=true -jar /app/app.jar ;; *) echo 'STOP: unsupported run mode; values withheld.' >&2; exit 2 ;; esac"]
