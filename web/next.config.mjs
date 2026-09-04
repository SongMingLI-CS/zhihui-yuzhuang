/**
 * 智汇于庄 web · Next.js 配置
 *
 * 网关路径映射契约（三者必须对齐，见 deploy/nginx/nginx.conf）：
 *   location /b/ -> proxy_pass $web_upstream;（变量式，不剥离前缀）
 * 因此浏览器访问 /b/dashboard 会被原样透传到本服务（web:3000）。
 * basePath: '/b' 让 Next 在 /b 前缀下服务页面与 _next 静态资源。
 */
/** @type {import('next').NextConfig} */
const nextConfig = {
  // 网关以 /b/ 前缀反代本服务（对应 B 端入口 http://host/b/）
  basePath: '/b',
  // 独立输出：产出 .next/standalone（含最小 server.js 与 node_modules），
  // 便于轻量容器托管 / 宿主构建后打包产物分发（规避镜像拉取受限场景）。
  output: 'standalone',
  reactStrictMode: true,
  // 本仓库不引入 ESLint 规则集；类型检查仍由 next build 默认执行。
  eslint: {
    ignoreDuringBuilds: true,
  },
  images: {
    // 当前未启用 next/image 远程优化，全部走静态/外链，避免构建期联网
    unoptimized: true,
  },
};

export default nextConfig;
