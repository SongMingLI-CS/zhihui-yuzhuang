# ============================================================
# 智汇于庄 H5 · C 端特产秒杀单页
# 多阶段构建：Node 构建静态产物 -> 轻量 nginx:alpine 托管
#
# 路径语义（必须三者对齐）：
#   - vite.config.ts base = '/h5/'
#   - 网关 nginx location /h5/ -> http://h5:80（proxy_pass 变量式，不剥离前缀）
#   - 本容器静态根 /usr/share/nginx/html，产物置于其下 /h5 子目录
# 因此浏览器 /h5/index.html、/h5/assets/* 均能原样命中本容器文件。
# ============================================================

# ---------- 阶段 1：Node.js 构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 先拷贝依赖清单以利用 Docker 层缓存
COPY package*.json ./
RUN npm ci

# 拷贝源码并构建
COPY . .
RUN npm run build

# ---------- 阶段 2：nginx 轻量静态托管 ----------
FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html/h5
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
