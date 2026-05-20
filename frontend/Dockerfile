# ── 前端 Vue 应用 Docker 镜像 ────────────────────────────────────────────────
# 策略：直接将已构建好的 dist/ 目录打包进 nginx 镜像，无需在容器内 npm build
# nginx.conf 通过 volume 挂载，修改后 docker exec nginx -s reload 即生效
FROM nginx:1.25-alpine

LABEL maintainer="boyang" \
      app="knowledge-base-frontend" \
      version="1.0.0"

# 清除默认配置，使用自定义配置
RUN rm /etc/nginx/conf.d/default.conf

# 复制构建产物
COPY dist/ /usr/share/nginx/html/

# 将默认 nginx.conf 复制进镜像（作为 fallback，宿主机未挂载时生效）
COPY config/nginx.conf /etc/nginx/conf.d/app.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost/index.html || exit 1

CMD ["/bin/sh", "-c", "printf 'window.__APP_CONFIG__ = { API_BASE_URL: \"%s\" };\\n' \"${API_BASE_URL:-/api/v1}\" > /usr/share/nginx/html/env-config.js && nginx -g 'daemon off;'"]
