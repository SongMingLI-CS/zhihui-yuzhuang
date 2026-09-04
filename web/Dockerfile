# 智汇于庄 web · B 端中枢大脑（Next.js 14 App Router 数据大屏）
# 说明：next.config.mjs 已开启 output:'standalone'，本镜像基于该产出构建最小运行目录。
# 对外访问契约（与 deploy/nginx/nginx.conf 对齐）：
#   location /b/ -> proxy_pass http://web:3000（变量式，不剥离前缀，basePath:'/b' 已内建）
# 构建：cd deploy && docker compose build web；一键见仓库根 Makefile 的 web-* 目标。

# ---------- 构建阶段 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 复制依赖清单（无 lock 文件时回退 npm install）
COPY package.json package-lock.json* ./
RUN npm install

# 复制源码并构建（Next 默认执行 TypeScript 类型检查；ESLint 已在配置中跳过）
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---------- 运行阶段 ----------
FROM node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 以非 root 运行前先准备 nextjs 用户组/用户
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 nextjs

# 仅保留 standalone 最小产物（含 server.js 与裁剪后 node_modules）与静态资源
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000

CMD ["node", "server.js"]
