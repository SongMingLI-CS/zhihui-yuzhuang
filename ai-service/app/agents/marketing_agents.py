"""特产营销多 Agent 节点：TrendAgent / CopywriterAgent / ComplianceAgent。

三个 Agent 的职责边界（由 :class:`app.services.marketing_service.MarketingOrchestrator`
串联为流水线）：

1. **TrendAgent（热点捕捉）**：结合豫东平原民俗、鹿邑老子文化、农历节气，为特产
   提炼 2~3 个富有乡土文化底蕴与共情力的营销故事切入点（story angle）。
2. **CopywriterAgent（文案生成）**：针对目标渠道（朋友圈 / 小红书 / 直播口播）
   生成差异化文案：朋友圈短平快带烟火气、小红书痛点对比 + 干净配料表 + 种草标签、
   直播口播快节奏排比 + 痛点反问 + 价格锚点。
3. **ComplianceAgent（合规质检）**：严格依照《广告法》对文案二次质检——识别剔除
   极限绝对化用语（最 / 第一 / 顶级…）、严防食用农产品宣称疾病预防/治疗功效，
   输出 0-100 合规评分并在必要时做脱敏替换。

运行韧性：
- 未配置 DeepSeek 密钥（或显式 ``force_offline=True``）时，三个 Agent 自动切换到
  确定性 Mock 模板链路（高质量预置文案），保证离线单测与前端联调畅通；
- 配置密钥时优先调用 DeepSeek 生成富文本，任何异常 / 解析失败都会安全回退到
  模板链路，绝不把异常抛给 HTTP 层。
"""

from __future__ import annotations

import hashlib
import json
import logging
from typing import List, Optional

from app.llm.deepseek import DeepSeekChatClient
from app.schemas.marketing import (
    CHANNEL_LABELS,
    DEFAULT_CHANNELS,
    ComplianceReport,
    MarketingChannel,
    MarketingCopyItem,
    MarketingGenerateRequest,
)

logger = logging.getLogger(__name__)

# 卖点不足时的确定性兜底卖点（不含广告法违禁词）
FALLBACK_SELLING_POINTS: List[str] = [
    "传统工艺制作",
    "无任何添加剂",
    "源头直供，产地现发",
]

# ---------------------------------------------------------------- LLM JSON 解析

def _extract_json(text: str):
    """从 LLM 返回文本中稳健提取 JSON（兼容 markdown 代码围栏 / 前后缀说明）。

    解析失败返回 None，由调用方安全回退模板链路。
    """
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```"):
        lines = cleaned.splitlines()
        if lines and lines[0].strip().startswith("```"):
            lines = lines[1:]
        while lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        cleaned = "\n".join(lines).strip()

    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start = cleaned.find(open_ch)
        if start == -1:
            continue
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(cleaned)):
            ch = cleaned[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                elif ch == open_ch:
                    depth += 1
                elif ch == close_ch:
                    depth -= 1
                    if depth == 0:
                        try:
                            return json.loads(cleaned[start : i + 1])
                        except (json.JSONDecodeError, ValueError):
                            break
    return None


class _AgentBase:
    """Agent 公共基座：统一 LLM 可用性判定与安全调用。"""

    def __init__(
        self,
        llm: Optional[DeepSeekChatClient] = None,
        *,
        force_offline: bool = False,
    ) -> None:
        self.llm = llm
        self.online = bool(llm) and bool(getattr(llm, "available", False)) and not force_offline

    def _enabled_llm(self) -> Optional[DeepSeekChatClient]:
        """返回可用的 LLM 客户端；不可用 / 强制离线时返回 None（走模板）。"""
        return self.llm if self.online else None

    async def _ask_llm(self, messages: List[dict]) -> Optional[str]:
        """安全调用 DeepSeek：任何异常记录后返回 None，由上层回退模板。"""
        llm = self._enabled_llm()
        if llm is None:
            return None
        try:
            text = await llm.achat(
                messages,
                temperature=0.7,
                max_tokens=1400,
                timeout=45.0,
            )
            return text or None
        except Exception as exc:  # noqa: BLE001 - 任何 LLM 异常一律安全降级
            logger.warning("Agent 调用 DeepSeek 失败，回退模板链路: %s", exc)
            return None


# ================================================================ TrendAgent

# 乡土文化切入点素材池（豫东平原民俗 / 鹿邑老子文化 / 农历节气），
# 措辞均已规避广告法绝对化用语与疾病宣称，可安全作为良性基线。
CULTURE_POOL: List[dict] = [
    {
        "angle_title": "老子故里 · 道法自然",
        "hook": "鹿邑是老子故里，村里做手艺也信一个“道法自然”——不赶工、不抢快，让老物件一圈一圈稳稳转出真滋味。",
    },
    {
        "angle_title": "应季农时 · 白露收麻",
        "hook": "豫东有句老话叫“白露到，芝麻摇”。于庄人只收应季原料，晾足了再慢慢做，一年就出这么几批，香得扎实。",
    },
    {
        "angle_title": "灶台上的乡愁",
        "hook": "小时候村口作坊一开张，满街都是那股香。如今走得再远，一口熟悉的家乡味，总能把人拽回老家的灶台边。",
    },
    {
        "angle_title": "老把式守艺",
        "hook": "做了一辈子手艺的老把式说：好不好，全在慢火候、慢功夫里。机器图快，做不出这层实打实的厚香。",
    },
    {
        "angle_title": "人情往来 · 年节馈赠",
        "hook": "豫东人家走亲戚、备年货，讲究带点实在的好东西。一样土生土长的特产拎上门，是十里八乡都认的老礼数。",
    },
    {
        "angle_title": "给家人的安心",
        "hook": "配料干干净净，是敢给自家孩子吃、给老人尝的东西，才敢放心装好卖给你。",
    },
]


class TrendAgent(_AgentBase):
    """热点捕捉 Agent：提炼乡土文化营销故事切入点。"""

    agent_name = "TrendAgent"

    def _choose_angles(self, product_name: str, limit: int = 3) -> List[dict]:
        """从素材池中确定性选取 2~3 个切入点（按产品名哈希旋转起点）。"""
        digest = hashlib.md5((product_name or "特产").encode("utf-8")).digest()
        offset = int.from_bytes(digest[:4], "big") % len(CULTURE_POOL)
        angles: List[dict] = []
        for i in range(min(limit, 3)):
            angles.append(dict(CULTURE_POOL[(offset + i) % len(CULTURE_POOL)]))
        return angles

    def _summarize(self, product_name: str, angles: List[dict]) -> str:
        labels = "、".join(f"“{a['angle_title']}”" for a in angles)
        return (
            f"结合豫东平原民俗、鹿邑老子文化与农历节气，为「{product_name}」"
            f"提炼 {len(angles)} 个乡土切入点：{labels}"
        )

    async def run(
        self, request: MarketingGenerateRequest
    ) -> tuple[List[dict], str]:
        """提炼 2~3 个故事切入点，返回 ``(angles, summary)``。"""
        product_name = request.product_name
        angles = self._choose_angles(product_name)

        llm = self._enabled_llm()
        if llm is not None:
            system = (
                "你是“智汇于庄”特产营销策划，深谙豫东平原民俗、鹿邑老子文化与农历节气。"
                "请为特产提炼 3 个富有乡土文化底蕴与共情力的营销故事切入点。"
                "铁律：文案严禁出现“最、第一、顶级、国家级”等广告法绝对化用语，"
                "严禁任何疾病预防/治疗宣称。只输出 JSON，不输出任何解释。"
            )
            user = (
                f"产品：{product_name}\n核心卖点：{'、'.join(request.selling_points or FALLBACK_SELLING_POINTS)}\n"
                "请返回如下结构的 JSON 数组（长度 3）：\n"
                '[{"angle_title": "切入点标题", "hook": "一句有画面感的乡土故事钩子"}]'
            )
            raw = await self._ask_llm(
                [{"role": "system", "content": system}, {"role": "user", "content": user}]
            )
            parsed = _extract_json(raw) if raw else None
            if isinstance(parsed, list) and len(parsed) >= 2:
                candidate: List[dict] = []
                for item in parsed:
                    if isinstance(item, dict):
                        title = str(item.get("angle_title") or "").strip()
                        hook = str(item.get("hook") or "").strip()
                        if title and hook:
                            candidate.append({"angle_title": title, "hook": hook})
                if candidate:
                    angles = candidate[:3]

        summary = self._summarize(product_name, angles)
        return angles, summary


# ================================================================ CopywriterAgent

class CopywriterAgent(_AgentBase):
    """文案生成 Agent：按渠道调性生成差异化文案矩阵。"""

    agent_name = "CopywriterAgent"

    def _effective_points(self, request: MarketingGenerateRequest) -> List[str]:
        """返回 3 条以上卖点（不足时补确定性兜底卖点）。"""
        points: List[str] = []
        for point in request.selling_points or []:
            text = point.strip()
            if text:
                points.append(text)
        idx = 0
        while len(points) < 3:
            points.append(FALLBACK_SELLING_POINTS[idx % len(FALLBACK_SELLING_POINTS)])
            idx += 1
        return points

    @staticmethod
    def _audience(request: MarketingGenerateRequest) -> str:
        return request.target_audience or "注重健康与真材实料的家庭"

    # ------------------------------------------------ 离线模板（确定性高质量预置）

    def _template_item(
        self,
        request: MarketingGenerateRequest,
        channel: str,
        angles: List[dict],
        points: List[str],
        audience: str,
    ) -> MarketingCopyItem:
        product = request.product_name
        p1, p2, p3 = points[0], points[1], points[2]
        primary = angles[0] if angles else {"angle_title": "", "hook": ""}

        if channel == "MOMENTS":
            title = f"{product}｜{primary['angle_title']}"
            content = "\n".join(
                [
                    primary["hook"],
                    "",
                    f"{product}，{p1}、{p2}，实打实的本分味道。",
                    "厨房里随手一拌一蘸，就是老家的烟火气。",
                    f"给{audience}备上一份，大人小孩都喜爱。",
                ]
            )
            call_to_action = "想要的乡亲，评论区扣个「1」，我挨个给您留。"
        elif channel == "RED_BOOK":
            title = f"被反复问到的{product}，今天认真种个草🌿"
            content = "\n".join(
                [
                    "先来一个扎心对比 👇",
                    "货架上同类一堆，添加剂说明长长一串；它呢，配料表干干净净，主打一个简单实在。",
                    "",
                    f"「{product}」好在哪：",
                    f"· {p1}",
                    f"· {p2}",
                    f"· {p3}",
                    "",
                    "没有乱七八糟的添加，打开就是那种踏实、本真的香气。",
                    "拌凉菜、做蘸料、烧菜收尾滴两滴，香得全家追着问。",
                    "",
                    f"适合：{audience}",
                    "",
                    "#于庄特产 #传统工艺 #无添加 #干净配料表 #家乡好物",
                ]
            )
            call_to_action = "链接放主页啦～真心安利，吃完记得回来反馈"
        else:  # LIVESTREAM
            title = f"{product}｜直播间同款，今天这波别错过"
            content = "\n".join(
                [
                    "家人们都别划走，听我把话说完！",
                    f"{product}，{p1}、{p2}、{p3}。",
                    "",
                    "你品品，同样是花钱，你是想要添加剂占半页的，",
                    "还是想要一瓶配料表干净到一眼能看明白的？",
                    "",
                    "咱于庄人自己吃啥就卖啥，做得慢、做得细，开瓶那股香是实打实的。",
                    "拌菜香、蘸料香、烧菜收尾更香，家里的老人小孩都能放心吃。",
                    "",
                    "价格咱也不玩虚的，今天这一大瓶，够全家吃上一两个月，",
                    "值不值，你心里有杆秤！",
                ]
            )
            call_to_action = "想要的家人们抓紧拍 2 瓶，我再给您捎 1 瓶小样，倒计时 5 秒，上链接！"

        return MarketingCopyItem(
            channel=channel,  # type: ignore[arg-type]
            title=title,
            content=content,
            call_to_action=call_to_action,
        )

    # ------------------------------------------------ 单渠道生成（LLM 优先 + 模板兜底）

    async def _generate_one(
        self,
        request: MarketingGenerateRequest,
        channel: str,
        angles: List[dict],
        points: List[str],
        audience: str,
    ) -> MarketingCopyItem:
        llm = self._enabled_llm()
        if llm is not None:
            persona = {
                "MOMENTS": (
                    "微信朋友圈：短平快、带真实乡土生活气息，像熟人在唠家常，"
                    "避免营销腔。"
                ),
                "RED_BOOK": (
                    "小红书：痛点对比 + 配料表干净 + 图文种草标签，emoji 适度，"
                    "正文用分点与换行，结尾带话题标签。"
                ),
                "LIVESTREAM": (
                    "直播口播：快节奏排比、痛点反问、价格锚点，口语化、有煽动力，"
                    "适合主播连珠炮念稿。"
                ),
            }.get(channel, "通用卖货文案")
            system = (
                "你是“智汇于庄”金牌文案，为豫东鹿邑于庄土特产写卖货文案。"
                f"目标渠道调性：{persona}。"
                "铁律：严禁出现“最、第一、顶级、国家级、唯一、首选”等广告法绝对化用语；"
                "严禁宣称疾病预防/治疗功效；只输出 JSON，不输出任何解释。"
            )
            angle_text = "；".join(f"{a['angle_title']}：{a['hook']}" for a in angles)
            user = (
                f"产品：{request.product_name}\n"
                f"核心卖点：{'、'.join(points)}\n"
                f"目标客群：{audience}\n"
                f"营销切入点：{angle_text}\n"
                "请返回如下结构的 JSON 对象：\n"
                '{"title": "标题", "content": "正文", "call_to_action": "转化引导"}'
            )
            raw = await self._ask_llm(
                [{"role": "system", "content": system}, {"role": "user", "content": user}]
            )
            parsed = _extract_json(raw) if raw else None
            if isinstance(parsed, dict):
                title = str(parsed.get("title") or "").strip()
                content = str(parsed.get("content") or "").strip()
                cta = str(parsed.get("call_to_action") or "").strip()
                if title and content:
                    return MarketingCopyItem(
                        channel=channel,  # type: ignore[arg-type]
                        title=title,
                        content=content,
                        call_to_action=cta,
                    )

        return self._template_item(request, channel, angles, points, audience)

    async def run(
        self, request: MarketingGenerateRequest, angles: List[dict]
    ) -> tuple[List[MarketingCopyItem], str]:
        """按渠道偏好生成文案矩阵，返回 ``(copies, summary)``。"""
        channels: List[str] = list(request.channel_preferences) or list(DEFAULT_CHANNELS)
        points = self._effective_points(request)
        audience = self._audience(request)

        copies: List[MarketingCopyItem] = []
        for channel in channels:
            item = await self._generate_one(request, channel, angles, points, audience)
            copies.append(item)

        summary_parts = []
        for item in copies:
            label = CHANNEL_LABELS.get(item.channel, item.channel)
            summary_parts.append(f"{label}《{item.title}》")
        summary = f"按 {len(copies)} 个渠道调性完成差异化文案：{'；'.join(summary_parts)}"
        return copies, summary


# ================================================================ ComplianceAgent

# 《广告法》第九条：不得使用“国家级 / 最高级 / 最佳”等绝对化用语
ABSOLUTE_TERMS: List[str] = [
    "国家级",
    "世界级",
    "全球第一",
    "世界第一",
    "全国第一",
    "全网第一",
    "销量第一",
    "天下第一",
    "顶级",
    "顶尖",
    "最高级",
    "最高端",
    "最佳",
    "最好",
    "最强",
    "最优",
    "极致",
    "之王",
    "王牌",
    "首选",
    "唯一",
    "首个",
    "首创",
    "绝无仅有",
    "独一无二",
    "第一",
    "NO.1",
    "No.1",
    "no.1",
]

# 《广告法》第十七条：除医疗/药品外，食品与食用农产品不得涉及疾病预防、治疗功能
MEDICAL_TERMS: List[str] = [
    "包治百病",
    "治胃病",
    "治百病",
    "治病",
    "治疗",
    "疗效",
    "药到病除",
    "根治",
    "治愈",
    "痊愈",
    "防癌",
    "抗癌",
    "抗肿瘤",
    "降血压",
    "降血糖",
    "降血脂",
    "降压",
    "降糖",
    "降脂",
    "预防疾病",
    "预防感冒",
    "防感冒",
    "消炎",
    "杀菌",
    "止痛",
    "消肿",
    "祛湿",
    "排毒",
    "壮阳",
    "补肾",
    "延年益寿",
    "调理脾胃",
    "养胃治胃",
    "百病",
]

# 合规分数扣分权重
PENALTY_ABSOLUTE = 25
PENALTY_MEDICAL = 30
# 合规通过线（存在风险词即不通过；评分用于衡量严重程度）
COMPLIANCE_PASS_THRESHOLD = 60

# 绝对化用语的合规替代（脱敏替换用；疾病宣称词一律脱敏删除，交由人工复核）
SAFE_REPLACEMENTS: dict[str, str] = {
    "天下第一": "匠心传承",
    "全球第一": "匠心之作",
    "世界第一": "匠心之作",
    "全国第一": "匠心之作",
    "全网第一": "匠心之作",
    "销量第一": "口碑之选",
    "第一": "地道",
    "顶级": "上乘",
    "顶尖": "上乘",
    "最高级": "上乘",
    "最高端": "上乘",
    "最佳": "出色",
    "最好": "出色",
    "最强": "出色",
    "最优": "出色",
    "极致": "出众",
    "之王": "之选",
    "王牌": "招牌",
    "首选": "适合日常",
    "唯一": "实在",
    "首个": "用心",
    "首创": "用心",
    "绝无仅有": "难得",
    "独一无二": "独一份",
    "国家级": "",
    "世界级": "",
    "NO.1": "",
    "No.1": "",
    "no.1": "",
}

# 通用法条整改提示
SUGGEST_ABSOLUTE = (
    "删除或替换绝对化用语（《广告法》第九条禁止使用“国家级、最高级、最佳”等用语）"
)
SUGGEST_MEDICAL = (
    "普通食品/食用农产品不得宣称疾病预防、治疗功能（《广告法》第十七条），请删除疗效表述"
)


def _dedupe_terms(found: List[str]) -> List[str]:
    """仅保留不被更长命中词包含的敏感词，避免“天下第一/第一”重复报告。"""
    kept: List[str] = []
    for term in sorted(set(found), key=len, reverse=True):
        if any(term in k for k in kept):
            continue
        kept.append(term)
    return kept


def classify_term(term: str) -> str:
    """返回敏感词类别：absolute（绝对化用语）/ medical（疾病宣称）。"""
    return "medical" if term in MEDICAL_TERMS else "absolute"


def detect_risk_terms(texts: List[str]) -> List[str]:
    """在多段文本中扫描敏感词，返回去重（去包含）后的命中列表。"""
    found: List[str] = []
    for text in texts:
        if not text:
            continue
        for term in ABSOLUTE_TERMS + MEDICAL_TERMS:
            if term in text and term not in found:
                found.append(term)
    return _dedupe_terms(found)


def sanitize_text(text: str, terms: List[str]) -> str:
    """脱敏：对命中词按长度降序替换为合规替身；疾病宣称等无替身词删除。"""
    result = text or ""
    for term in sorted(terms, key=len, reverse=True):
        if term not in result:
            continue
        replacement = SAFE_REPLACEMENTS.get(term)
        if replacement is None:
            replacement = ""  # 疾病宣称/无映射词 → 删除，交由人工复核补写
        result = result.replace(term, replacement)
    return result


class ComplianceAgent:
    """广告法合规质检 Agent（确定性规则引擎，不依赖 LLM）。"""

    agent_name = "ComplianceAgent"

    def _scan_sources(
        self,
        request: MarketingGenerateRequest,
        copies: List[MarketingCopyItem],
    ) -> List[str]:
        """汇总扫描源：产品名 + 卖点 + 每套文案的标题/正文/CTA。"""
        texts: List[str] = [request.product_name]
        texts.extend(request.selling_points or [])
        for item in copies:
            texts.append(item.title)
            texts.append(item.content)
            texts.append(item.call_to_action)
        return texts

    def run(
        self,
        request: MarketingGenerateRequest,
        copies: List[MarketingCopyItem],
    ) -> tuple[ComplianceReport, List[MarketingCopyItem]]:
        """执行二次质检并做脱敏替换，返回 ``(report, sanitized_copies)``。"""
        texts = self._scan_sources(request, copies)
        terms = detect_risk_terms(texts)

        # 1) 评分：满分 100，绝对化用语 -25 / 疾病宣称 -30，下限 0
        score = 100
        for term in terms:
            score -= PENALTY_MEDICAL if classify_term(term) == "medical" else PENALTY_ABSOLUTE
        score = max(0, min(100, int(score)))

        # 2) 判定：存在任何敏感词即不通过（广告法明令禁止，必须人工复核）
        passed = not terms

        # 3) 整改建议
        suggestions: List[str] = []
        if passed:
            suggestions.append("未命中敏感词，文案可进入人工复核环节。")
        else:
            absolute_hits = [t for t in terms if classify_term(t) == "absolute"]
            medical_hits = [t for t in terms if classify_term(t) == "medical"]
            if absolute_hits:
                suggestions.append(
                    f"{SUGGEST_ABSOLUTE}。命中：{'、'.join(absolute_hits)}"
                )
            if medical_hits:
                suggestions.append(
                    f"{SUGGEST_MEDICAL}。命中：{'、'.join(medical_hits)}"
                )
            for term in terms:
                if classify_term(term) == "absolute":
                    replacement = SAFE_REPLACEMENTS.get(term)
                    if replacement:
                        suggestions.append(f"可将「{term}」替换为「{replacement}」。")
                    else:
                        suggestions.append(f"建议删除或弱化「{term}」。")
                else:
                    suggestions.append(
                        f"「{term}」属疾病预防/治疗宣称，已脱敏删除，请改写为工艺或风味描述。"
                    )
        # 去重
        uniq_suggestions: List[str] = []
        for s in suggestions:
            if s not in uniq_suggestions:
                uniq_suggestions.append(s)

        report = ComplianceReport(
            score=score,
            passed=passed,
            risk_terms_detected=terms,
            revision_suggestions=uniq_suggestions,
        )

        # 4) 必要时脱敏替换（良性输入无命中则原样返回）
        if terms:
            sanitized_copies: List[MarketingCopyItem] = []
            for item in copies:
                sanitized_copies.append(
                    MarketingCopyItem(
                        channel=item.channel,
                        title=sanitize_text(item.title, terms),
                        content=sanitize_text(item.content, terms),
                        call_to_action=sanitize_text(item.call_to_action, terms),
                    )
                )
            copies = sanitized_copies

        logger.info(
            "ComplianceAgent: score=%d passed=%s risk=%s",
            report.score,
            report.passed,
            report.risk_terms_detected,
        )
        return report, copies


def compliance_summary(report: ComplianceReport) -> str:
    """把质检报告折叠成 Agent 思考摘要。"""
    if report.passed:
        return f"广告法质检：评分 {report.score}/100，未命中敏感词，可进入人工复核。"
    terms = "、".join(report.risk_terms_detected)
    return (
        f"广告法质检：评分 {report.score}/100，命中 {len(report.risk_terms_detected)} 处"
        f"敏感词（{terms}），已脱敏并提示整改，需人工复核后发布。"
    )


__all__ = [
    "FALLBACK_SELLING_POINTS",
    "CULTURE_POOL",
    "ABSOLUTE_TERMS",
    "MEDICAL_TERMS",
    "SAFE_REPLACEMENTS",
    "TrendAgent",
    "CopywriterAgent",
    "ComplianceAgent",
    "detect_risk_terms",
    "sanitize_text",
    "compliance_summary",
]
