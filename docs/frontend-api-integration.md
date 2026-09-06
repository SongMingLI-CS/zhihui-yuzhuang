# 前端接口接驳说明

## 已核实并接入的接口

- `GET /api/v1/products`：H5 商品列表，React Query 缓存与刷新。
- `POST /api/v1/orders/checkout`：H5 下单，Mutation、防重复提交与幂等键。
- `GET /api/v1/orders`：B 端订单服务端分页/搜索（`page/pageSize/status/fulfillmentStatus/keyword/orderSource`），双轴徽标展示。
- `POST /api/v1/orders/{orderNo}/ship`：一键出库（READY→SHIPPED）。
- `POST /api/v1/orders/{orderNo}/mark-ready`：拣货完成置为待出库（PICKING→READY）。
- `POST /api/v1/orders/{orderNo}/recover`：异常单恢复拣货（ABNORMAL→PICKING）。
- `POST /ai/v1/qa/ask`：B/H5 农技问答，统一错误包裹与引用来源。
- `POST /ai/v1/marketing/generate`：B 端营销 Agent 生成。
- `/healthz`、`/api/v1/healthz`、`/ai/v1/healthz`：链路探针。

所有请求携带 `X-Tenant-Id`；若本地存在 `yuzhuang.access_token`，请求层会额外携带 `Authorization: Bearer <token>`。后端当前尚未声明认证接口或 OpenAPI security scheme，因此前端不虚构登录流程。

## 预留契约

- 分页统一为 `PageResult<T> { items, page, pageSize, total, totalPages }`。
- B 端订单列表/履约操作已接入真实后端（见上），页面不再用演示快照伪装；知识库与 Dashboard 聚合接口仍不存在，继续显示“演示数据”。
- SSE 约定端点为 `POST /ai/v1/qa/ask/stream`，事件 `data` JSON 可包含 `token`、`citations` 或 `message`，以 `data: [DONE]` 结束。后端上线后设置 `NEXT_PUBLIC_AI_STREAMING_ENABLED=true` / `VITE_AI_STREAMING_ENABLED=true` 即可启用；默认走现有 JSON 接口。
- 真实分页与排序接口就绪后，将 `usePagination` 的 URL 状态直接映射到请求参数即可。

## 缓存与重试策略

- 查询默认不对 4xx 重试，网络错误和 5xx 最多重试两次。
- H5 商品缓存 60 秒；下单成功后主动失效商品查询以刷新库存。
- Service Worker 仅缓存 AppShell 与商品 GET；订单 POST 和 AI 响应不进入运行时缓存。
