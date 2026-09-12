# 智汇于庄 · 云服务器部署手册（10.2.0.13）

将本仓库全栈部署到云服务器 **10.2.0.13**：Postgres(pgvector) / Redis / Nginx 网关 /
Spring Boot backend / FastAPI ai-service / Next.js web / 轻量 H5。
对外统一由网关暴露 `/`（状态页）、`/api`（业务后端）、`/ai`（AI·RAG）、`/b`（B 端大屏）、`/h5`（C 端）。

## 0. 网络前提

`10.2.0.13` 是云 VPC 内网地址，公网不可直达，需满足以下之一：

| 方式 | 说明 |
|------|------|
| **弹性公网 IP（EIP）** | 服务器绑定 EIP，安全组放行 TCP 22（SSH）与 TCP 80（网关）—— 本手册主推路径 |
| VPN / 专线 | 先接入 VPC（本机已装 OpenVPN `TAP-Windows Adapter V9` 与 WireGuard `Wintun`，当前均为 Disconnected），之后可直接访问 `10.2.0.13` |
| 跳板机 | 经堡垒机中转：`ssh -J <跳板机> <user>@10.2.0.13` |

> 实测（本机 Windows，WLAN 192.168.43.75）：直连 `10.2.0.13` 的 ping 与
> 22/80/443/5433/6379 端口**全部超时**。启用 EIP 或 VPN 之前无法部署。

## 1. 服务器前置条件

| 项 | 要求 |
|----|------|
| 操作系统 | Linux x86_64（Ubuntu 20.04+ / Debian 11+ / Alibaba Cloud Linux / CentOS 7+） |
| 资源 | ≥ 2 vCPU、≥ 4 GiB 内存、≥ 20 GiB 磁盘（镜像构建峰值约需 8 GiB） |
| Docker | Docker Engine 20.10+ 与 Compose **v2** 插件（命令为 `docker compose`） |
| 出网 | 能访问 Docker Hub（或镜像加速器）、npm、Maven Central、PyPI |

Docker 未安装时（脚本步骤 0 会明确报错）：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
```

## 2. 打包源码（务必保持 LF 行尾）

本仓库在 Windows 工作区为 CRLF（`core.autocrlf=true`、仓库无 `.gitattributes`），
而 **bash 脚本以 CRLF 上传到 Linux 会直接崩溃**（实测报 `set: pipefail: invalid option name`）。
因此推荐用 `git archive` 导出（git 内存储的是 LF，可端到端规避）：

```powershell
# 本地，仓库根目录
git archive --format=tar.gz -o yuzhuang-deploy.tar.gz HEAD
```

> 若必须包含未提交的工作区改动，可改用：
> `tar --exclude=.git --exclude=node_modules --exclude=target --exclude=.next --exclude=dist -czf yuzhuang-deploy.tar.gz .`
> 但需在服务器上先执行 `sed -i 's/\r$//' deploy/remote-deploy.sh` 再运行脚本
> （`remote-deploy.sh` 内部的“行尾规范化”会处理其余文件，脚本自身需先修复才能启动）。

## 3. 上传并一键部署

> **推荐：本地驱动器一键执行**（Windows 无 sshpass/plink，密码认证需借助 paramiko）：
>
> ```powershell
> python -m pip install paramiko            # 一次性
> $env:YZ_SSH_PASSWORD = '<实例密码>'        # 密码仅经环境变量传递，不进 shell 历史
> python deploy/remote-deploy-runner.py --host 192.144.160.196 --user ubuntu `
>        --bundle yuzhuang-deploy.tar.gz --remote-dir /opt/zhihui-yuzhuang
> # 附加开关：--gateway-port 8080 / --pg-password 'xxx' / --with-seed / --preflight-only
> #           --background（后台执行并写 deploy.log）/ --tail-log 60（轮询日志）
> #           --skip-deploy（仅上传解包）/ --set-env K=V（透传任意变量，可重复）
> ```
>
> 驱动器会自动：预检环境（OS/架构/内存/磁盘/docker/compose/sudo/端口占用）→ SFTP 上传 →
> 解包到 `/opt/zhihui-yuzhuang` → `sudo bash deploy/remote-deploy.sh`（实时回显，首次构建较久）。

手动方式（等效）：

```powershell
# 本地：上传到服务器（<EIP> 为弹性公网 IP，<PORT> 默认 22，<USER> 为登录用户）
scp -P <PORT> yuzhuang-deploy.tar.gz <USER>@<EIP>:/tmp/
```

```bash
# 服务器：解包并执行部署
sudo mkdir -p /opt/zhihui-yuzhuang
sudo tar -xzf /tmp/yuzhuang-deploy.tar.gz -C /opt/zhihui-yuzhuang
cd /opt/zhihui-yuzhuang
sudo bash deploy/remote-deploy.sh
```

带参数示例（换端口 / 覆盖密码 / 灌入种子数据）：

```bash
sudo GATEWAY_PORT=8080 POSTGRES_PASSWORD='StrongPwd!' WITH_SEED=1 bash deploy/remote-deploy.sh
```

脚本步骤（**幂等**，可重复执行；不会删除 `postgres_data` / `redis_data` 数据卷）：

| 步骤 | 内容 |
|------|------|
| 0 | 前置检查（docker / compose v2 / 磁盘）+ CRLF→LF 行尾规范化 |
| 1 | Docker Hub 连通性检测；不通则自动写入 `/etc/docker/daemon.json` 加速器并重启 Docker |
| 2 | 由 `.env.example` 生成 `.env`，写入 `GATEWAY_PORT` / 密码 / 密钥 |
| 3 | 80、5433、6379 端口占用检查 |
| 4 | `docker compose up -d --build` 构建并启动全部 7 个服务 |
| 5 | 等待网关 `/healthz` 就绪 + 执行 `scripts/verify_gateway.sh` 端到端验证 |
| 6 | 可选：业务种子数据 + RAG 向量化（`WITH_SEED=1`） |

## 4. 部署后验证

```bash
cd /opt/zhihui-yuzhuang
curl -fsS http://127.0.0.1/api/v1/healthz   # backend（含真实 Postgres SELECT 1）
curl -fsS http://127.0.0.1/ai/v1/healthz    # ai-service（真实 Postgres ping）
bash scripts/verify_gateway.sh              # 5 项端到端：网关/后端/AI/下单闭环/营销生成
```

浏览器访问（云主机需在安全组放行 TCP 80）：

- `http://<EIP>/` 网关状态页
- `http://<EIP>/h5/` C 端轻量 H5
- `http://<EIP>/b/` B 端数据大屏

## 5. 运维命令

```bash
cd /opt/zhihui-yuzhuang
sudo docker compose --env-file .env -f deploy/docker-compose.yml ps
sudo docker compose --env-file .env -f deploy/docker-compose.yml logs -f --tail 100
sudo docker compose --env-file .env -f deploy/docker-compose.yml restart backend
sudo docker compose --env-file .env -f deploy/docker-compose.yml down    # 停服务，保留数据卷
```

**更新发布**：重复第 2、3 步（数据保留，`up -d --build` 会替换镜像）。
**回滚**：用 `git archive <历史提交> ...` 重新导出后执行第 3 步；数据在命名卷中不受影响。

## 6. 常见问题

| 现象 | 处理 |
|------|------|
| `docker compose` 命令不存在 | 安装 `docker-compose-plugin`，并确认是 v2（`docker compose version`） |
| 拉取基础镜像超时 | 脚本已自动配置加速器；仍失败则手工写 `registry-mirrors` 后 `sudo systemctl restart docker` |
| `set: pipefail: invalid option name` | 脚本是 CRLF：`sed -i 's/\r$//' deploy/remote-deploy.sh` |
| 80 端口被占用 | `sudo GATEWAY_PORT=8080 bash deploy/remote-deploy.sh`，并同步放行安全组 |
| backend 连接数据库失败 | 必须显式传 `--env-file .env`：Compose 默认从 `deploy/` 目录找 `.env`，放在仓库根不会被自动加载（实测确认） |
| ai-service 返回降级结果 | 未配置 `DEEPSEEK_API_KEY` / `EMBEDDING_API_KEY`，属预期的离线降级行为 |
| ai-service 反复重启（`Restarting`） | 报 `Form data requires "python-multipart"`：`ai-service/requirements.txt` 需含 `python-multipart`（已修复，见 7.2） |

## 7. 国内网络适配（实测数据 @ 腾讯云北京轻量 4C/3.6G）

### 7.1 Docker 镜像加速器（脚本自动）

Docker Hub 在该实例**不可达**，部署脚本 step 1 会自动写入 `/etc/docker/daemon.json` 的
`registry-mirrors`（daocloud / 南大 / 1ms）并重启 Docker，无需人工干预。

### 7.2 包管理器镜像（需显式启用，提速数十倍）

| 源 | 实测速度 | 国内镜像 | 实测速度 |
|----|---------|---------|---------|
| `deb.debian.org`（apt） | **32 KB/s** | `mirrors.cloud.tencent.com` | **18.9 MB/s** |
| `pypi.org`（pip） | **49 KB/s** | `mirrors.cloud.tencent.com` | **856 KB/s** |
| npm / Maven Central | ~0.6 MB/s | — | 无需加速 |

启用构建加速叠加层（**不修改 `ai-service/` 模块**，仅替换其构建用 Dockerfile）：

```bash
EXTRA_COMPOSE_FILES=deploy/docker-compose.mirror.yml bash deploy/remote-deploy.sh
```

- 叠加层：`deploy/docker-compose.mirror.yml`
- 替身 Dockerfile：`deploy/dockerfiles/ai-service.Dockerfile`（apt/pip 走腾讯云镜像，**失败自动回退官方源**）
- 效果：`ai-service` 构建从 **20+ 分钟** 降到 **约 1 分钟**

> 实现细节：`docker compose -f a:b` **不会**按 `:` 拆分多文件（实测报 `no such file`），
> 脚本内部会展开为多个 `-f` 参数（见 `COMPOSE_ARGS`）。

### 7.3 依赖完整性（部署暴露的真实缺陷）

`ai-service/app/api/knowledge.py` 使用 `File/Form/UploadFile`，但 `python-multipart`
**未被项目代码直接 import**（由 FastAPI 按需加载），因此既不在 `requirements.txt`、
静态扫描也发现不了；而 FastAPI ≥0.115 在注册含 `Form/File` 的路由时即强制校验，
缺失会导致 **ai-service 启动即崩溃并无限重启**。已在 `ai-service/requirements.txt` 补入：

```
python-multipart>=0.0.9,<1.0.0
```

## 8. 部署记录

| 项 | 值 |
|----|----|
| 实例 | 腾讯云轻量应用服务器 `lhins-1rduasp8`（Ubuntu 24.04.4 LTS，4C/3.6G/40G） |
| 公网 IP / 内网 IP | `192.144.160.196` / `10.2.0.13` |
| 部署目录 | `/opt/zhihui-yuzhuang` |
| 部署日志 | `/opt/zhihui-yuzhuang/deploy.log` |
| 网关端口 | 80（安全组已放行，公网已验证） |
| 数据库 | `rural_user` / `rural_revitalization`，密码见服务器 `/opt/zhihui-yuzhuang/.env`（`chmod 600`） |
| 访问入口 | `/` 状态页、`/h5/` C 端、`/b/dashboard` B 端、`/api/v1/*`、`/ai/v1/*` |
| 验证结果 | `verify_gateway.sh` **5/5 PASS**（网关/后端/AI/下单闭环/营销生成） |

