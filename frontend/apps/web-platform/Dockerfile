# 构建上下文是 frontend/。
FROM node:22-bookworm-slim AS build
WORKDIR /src
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps ./apps
COPY packages ./packages
RUN pnpm install --frozen-lockfile
RUN pnpm --filter @fu/web-platform build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web-platform/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/web-platform/dist /usr/share/nginx/html
EXPOSE 8080
