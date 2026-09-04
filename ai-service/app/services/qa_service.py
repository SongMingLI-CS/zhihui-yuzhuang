"""农技问答编排服务（RAG：检索召回 -> 熔断 -> 上下文组装 -> LLM 生成 -> 降级）。

职责与流程（对齐 docs/api-spec.yaml 的 ``/qa/ask``）：

1. **检索召回**：调用 :class:`KnowledgeRetriever` 获取高置信切片；
2. **熔断判定**：无任何切片满足 ``min_score`` 时直接返回标准兜底回答，
   不请求 LLM，``citations`` 留空；
3. **上下文拼接**：``[依据1] 《文档名》(第X页): 切片内容...``；
4. **Prompt 组装**：系统提示词严禁编造农药配比，正文以 ``[依据X]`` 标注结论，
   文末附加农药安全间隔期免责声明；
5. **LLM 生成**：调用 DeepSeek Chat（``deepseek-chat``，temperature=0.2），
   映射为包含 ``answer`` / ``citations`` / ``disclaimer`` 的
   :class:`AgriQAResponse`；
6. **降级容灾**：DeepSeek 未配置密钥 / 超时 / 报错时捕获并返回友好的服务降级
   提示（保留已召回的 citations 便于溯源），记录错误日志。
"""

from __future__ import annotations

import logging
from typing import List, Optional

from app.config import Settings, get_settings
from app.llm.deepseek import (
    DEFAULT_MODEL,
    DEFAULT_TEMPERATURE,
    DeepSeekChatClient,
    build_deepseek_chat,
)
from app.rag.retriever import (
    DEFAULT_MIN_SCORE,
    DEFAULT_TOP_K,
    KnowledgeRetriever,
)
from app.schemas.qa import AgriQARequest, AgriQAResponse, Citation

logger = logging.getLogger(__name__)

# 标准兜底回答（熔断：无可达阈值依据，不请求 LLM）
FALLBACK_ANSWER = (
    "未在鹿邑/于庄本地农技与政策库中检索到充分依据，建议咨询当地农技服务站。"
)
# 服务降级提示（检索成功但 DeepSeek 不可用/出错时返回，保留依据便于人工核验）
DEGRADE_ANSWER = (
    "本地农技/政策库已检索到以下相关依据，但大模型生成服务（DeepSeek）当前不可用，"
    "暂时无法生成自然语言回答。请直接参考下方引用依据，或稍后重试。"
)
# 农药安全间隔期免责声明（进入 disclaimer 字段，随回答返回）
PESTICIDE_DISCLAIMER = (
    "本回答由 AI 依据本地农技/政策知识库自动生成，仅供农技参考，不构成用药或生产决策依据；"
    "请以当地农技人员现场指导与农药产品标签说明为准，严格遵循农药安全间隔期与用量要求。"
)

# 系统提示词：约束模型只能依据检索片段作答、严禁编造、正文用 [依据X] 溯源
SYSTEM_PROMPT = """你是“智汇于庄”农技问答助手，面向河南省周口市鹿邑县（含试量镇于庄村）的农户与基层农技人员提供农技与惠农政策咨询服务。

回答必须遵循以下铁律：
1. 【禁止编造】只能依据用户给出的“[依据1]”“[依据2]”…标注的本地知识库片段作答，严禁编造事实，严禁自行杜撰或推算任何农药配比、用药剂量、肥料配比或政策条文细节。
2. 【溯源标注】回答中凡引用知识库得出的结论，必须在对应正文后用 [依据X] 标注来源序号（如“……建议亩施尿素8-10公斤[依据1]”）。
3. 【诚实边界】若给定依据不足以支撑该问题，请明确回答“本地知识库暂未覆盖该细节，建议咨询当地农技服务站”，不要臆测。
4. 【面向农户】使用朴实、专业、易读的中文分点作答，避免堆砌术语；涉及喷药时机、药剂、安全操作时请给出可执行的要点。
5. 【用药免责】如回答涉及农药/农事用药，请在回答正文的最后单独写一句农药安全间隔期与用量免责提示。
6. 【通用问题】对“GENERAL”类问题可在全部分类依据中综合回答；对病虫害类问题请先识别症状再给防治方案。
"""


def _to_citation(hit: dict) -> Citation:
    """把检索命中 dict 映射为契约 Citation。"""
    return Citation(
        docTitle=hit.get("doc_title") or "",
        pageNumber=hit.get("page_number"),
        chunkText=hit.get("chunk_text") or "",
        similarityScore=float(hit.get("score") or 0.0),
    )


def _build_context(hits: List[dict]) -> str:
    """按 ``[依据X] 《文档名》(第X页): 切片内容...`` 组装上下文。"""
    blocks: List[str] = []
    for index, hit in enumerate(hits, start=1):
        doc = hit.get("doc_title") or "未知文档"
        page = hit.get("page_number")
        page_part = f"(第{page}页)" if page else ""
        blocks.append(f"[依据{index}] 《{doc}》{page_part}: {hit.get('chunk_text')}")
    return "\n".join(blocks)


class AgriQAService:
    """农技问答编排：向量检索 + DeepSeek 生成，带熔断与降级。"""

    def __init__(
        self,
        retriever: Optional[KnowledgeRetriever] = None,
        llm: Optional[DeepSeekChatClient] = None,
        *,
        settings: Optional[Settings] = None,
        top_k: int = DEFAULT_TOP_K,
        min_score: float = DEFAULT_MIN_SCORE,
    ) -> None:
        cfg = settings or get_settings()
        self.settings = cfg
        self.retriever = retriever if retriever is not None else KnowledgeRetriever()
        self.llm = llm if llm is not None else build_deepseek_chat()
        self.top_k = max(1, int(top_k))
        self.min_score = float(min_score)
        logger.info(
            "AgriQAService 就绪 model=%s temperature=%.1f top_k=%d min_score=%.2f llm_available=%s",
            getattr(self.llm, "model", DEFAULT_MODEL),
            getattr(self.llm, "temperature", DEFAULT_TEMPERATURE),
            self.top_k,
            self.min_score,
            bool(getattr(self.llm, "available", False)),
        )

    async def answer_question(
        self, request: AgriQARequest, tenant_id: str = "global"
    ) -> AgriQAResponse:
        """执行一问一答全链路，返回 :class:`AgriQAResponse`。"""
        question = (request.question or "").strip()
        if not question:
            # 非法空问题：直接兜底（FastAPI 层 min_length=2 一般已拦截）
            return AgriQAResponse(answer=FALLBACK_ANSWER, citations=[], disclaimer=None)

        # 1) 检索召回
        hits = await self.retriever.search(
            query_text=question,
            tenant_id=tenant_id,
            category=request.category,
            top_k=self.top_k,
            min_score=self.min_score,
        )

        # 2) 熔断判定：无任何切片达到 min_score -> 兜底，不请求 LLM
        if not hits:
            logger.info(
                "问答熔断（无 ≥%.2f 依据）tenant=%s question=%.60s，返回兜底回答",
                self.min_score,
                tenant_id,
                question,
            )
            return AgriQAResponse(answer=FALLBACK_ANSWER, citations=[], disclaimer=None)

        # 召回成功：映射 citations 并组装上下文
        citations = [_to_citation(hit) for hit in hits]
        context = _build_context(hits)
        user_content = (
            f"【本地知识库检索依据】\n{context}\n\n"
            f"【农户问题】\n{question}\n\n"
            f"请严格依据上述 [依据X] 片段作答并用 [依据X] 标注结论；若依据不足请如实说明。"
        )

        # 3/4/5) LLM 生成（DeepSeek 不可用时直接降级，不空转网络）
        if not bool(getattr(self.llm, "available", False)):
            logger.warning(
                "DeepSeek 未配置/不可用，问答降级返回（召回 %d 条依据）tenant=%s",
                len(citations),
                tenant_id,
            )
            return AgriQAResponse(
                answer=DEGRADE_ANSWER,
                citations=citations,
                disclaimer=PESTICIDE_DISCLAIMER,
            )

        try:
            answer_text = await self.llm.achat(
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_content},
                ],
                temperature=0.2,
            )
        except Exception as exc:  # noqa: BLE001 - 任何 LLM 异常统一降级
            logger.exception(
                "DeepSeek 生成失败，问答降级返回（召回 %d 条依据）tenant=%s: %s",
                len(citations),
                tenant_id,
                exc,
            )
            return AgriQAResponse(
                answer=DEGRADE_ANSWER,
                citations=citations,
                disclaimer=PESTICIDE_DISCLAIMER,
            )

        if not answer_text:
            logger.warning("DeepSeek 返回空正文，问答降级返回")
            return AgriQAResponse(
                answer=DEGRADE_ANSWER,
                citations=citations,
                disclaimer=PESTICIDE_DISCLAIMER,
            )

        # 6) 映射输出
        return AgriQAResponse(
            answer=answer_text,
            citations=citations,
            disclaimer=PESTICIDE_DISCLAIMER,
        )


__all__ = [
    "AgriQAService",
    "FALLBACK_ANSWER",
    "DEGRADE_ANSWER",
    "PESTICIDE_DISCLAIMER",
    "SYSTEM_PROMPT",
]
