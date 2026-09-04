# ai-service · FastAPI 大模型与 RAG 服务

提供 RAG 检索问答（农技/政策）、知识库管理与 DeepSeek 多智能体营销能力，
与高并发业务后端（`backend/`）解耦、独立伸缩。
详细设计见 [`docs/architecture.md`](../docs/architecture.md) 第 5 节。

## 技术栈（规划）

- Python 3.11+ / FastAPI / Uvicorn
- pgvector（知识库向量检索，共用 PostgreSQL）
- DeepSeek API（LLM + Embedding）
- PDF 解析（pypdf / pdfplumber）+ 结构化切片
- ASR 语音转写（预留适配层）

## 目录规划

```text
ai-service/
├── app/
│   ├── main.py             # FastAPI 入口
│   ├── config.py           # 配置（数据库/模型/密钥）
│   ├── api/                # 路由：qa / ingest / knowledge / marketing / agents
│   ├── rag/                # 切片、向量化、双路召回、重排
│   ├── agents/             # 多智能体编排（热点/文案/质检/定价）
│   ├── llm/                # DeepSeek 调用与 Prompt 管理
│   └── schemas/            # Pydantic 模型
├── tests/
├── requirements.txt
└── Dockerfile（规划）
```

## 核心能力规划

| 能力 | 说明 | 状态 |
|------|------|------|
| 知识库入库 | PDF → 结构化切片 → Embedding → pgvector | 规划中 |
| RAG 双路召回 | 向量相似度 + 关键词，合并重排 | 规划中 |
| 防幻觉问答 | 强约束 Prompt，仅依据检索片段，附引用 | 规划中 |
| 多智能体 | 热点捕捉 / 文案生成 / 合规质检 / 定价建议 | 规划中 |
| 语音问答 | 语音 → 转写 → RAG → 图文答复 | 规划中 |
| 人工审批 | Agent 产出对接 B 端审批 | 规划中 |

## 端口约定（规划）

- 本地开发：`8000`
- 容器内：`8000`（加入 `rural-network`，经网关 `/ai/*` 对外）
