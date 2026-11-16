# syntax=docker/dockerfile:1.7
FROM node:24.22.3-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json vite.config.ts index.html ./
COPY src src
RUN npm run build

FROM nginx:1.30.6-alpine
# The official image renders /etc/nginx/templates/*.template with envsubst at startup, substituting only
# variables that are set (so nginx's own $uri etc. are left alone). REVIEWPULSE_ADMIN_TOKEN is injected
# server-side for /api/admin/** and never shipped to the browser.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
