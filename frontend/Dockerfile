FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_PAYFLOW_API_URL=/backend
ENV VITE_PAYFLOW_API_URL=$VITE_PAYFLOW_API_URL
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine AS runtime
COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html
COPY --chown=101:101 nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV PAYFLOW_UPSTREAM=http://payflow:8080
ENV NGINX_ENVSUBST_FILTER=PAYFLOW_UPSTREAM
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
