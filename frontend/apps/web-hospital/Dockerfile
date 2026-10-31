# 构建上下文是 frontend/。
FROM node:22-bookworm-slim AS build
WORKDIR /src
RUN corepack enable
ENV CI=true
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc turbo.json ./
COPY apps ./apps
COPY packages ./packages
COPY internal ./internal
COPY scripts ./scripts
RUN pnpm install --frozen-lockfile
RUN pnpm --filter @crdp/web-hospital build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web-hospital/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/web-hospital/dist /usr/share/nginx/html
EXPOSE 8080
