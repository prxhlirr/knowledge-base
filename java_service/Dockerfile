# ── 阶段：运行时（仅 JRE，最小化体积）──────────────────────────────────────
# 使用 eclipse-temurin 8 Alpine，比 openjdk 官方镜像更轻量且持续维护
FROM eclipse-temurin:8-jre-alpine

LABEL maintainer="boyang" \
      app="knowledge-base-java" \
      version="1.0.0"

# ── 创建专用非 root 用户（安全规范）─────────────────────────────────────────
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# ── 复制应用 jar ─────────────────────────────────────────────────────────────
# 仅复制 fat-jar；配置文件通过外部 volume 挂载，不打入镜像
COPY target/knowledge-base-1.0.0-SNAPSHOT.jar app.jar

# ── 创建配置挂载点和上传目录 ─────────────────────────────────────────────────
# /app/config  ← 宿主机 config/application.yml 挂载至此，可手动编辑
# /app/uploads ← 本地文件上传临时目录
RUN mkdir -p /app/config /app/uploads \
    && chown -R appuser:appgroup /app

USER appuser

# ── 端口声明 ─────────────────────────────────────────────────────────────────
EXPOSE 8080

# ── 健康检查 ─────────────────────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"' || exit 1

# ── 启动命令 ─────────────────────────────────────────────────────────────────
# 使用 shell 格式，确保 $JAVA_OPTS 环境变量可以被正确展开
# Spring Boot 2.x 会自动扫描 WORKDIR/config/ 目录，即 /app/config/application.yml
# 无需显式传 --spring.config.location，避免 JSON exec 格式下续行符解析歧义
ENTRYPOINT exec java $JAVA_OPTS \
    -Djava.security.egd=file:/dev/./urandom \
    -jar /app/app.jar
