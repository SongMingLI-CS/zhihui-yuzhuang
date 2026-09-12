"""知识文档加载与校验（隔离解析，防止恶意/超限文件）。

安全校验（阶段 F）：
1. **扩展名白名单**：``.txt`` / ``.md`` / ``.markdown`` / ``.pdf``（其余拒绝）；
2. **大小上限**：``MAX_UPLOAD_BYTES``（默认 5 MiB），超限直接拒绝；
3. **魔数/内容校验**：
   - PDF 必须以内含 ``%PDF-`` 开头，且经 ``pypdf`` 解析（未安装时给出明确安装提示）；
   - 纯文本必须为合法 UTF-8 且不含 NUL 字节（防止二进制伪装成文本）；
4. **结构限制**：PDF 页数上限 ``MAX_PDF_PAGES``（默认 200），避免超大文档拖垮服务。

解析结果按“页/段”返回 :class:`ExtractedPage`，保留 ``page_number``，
便于问答引用定位（PDF 可按真实页码溯源）。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import List

logger = logging.getLogger(__name__)

# 允许的扩展名（小写，不含点）
SUPPORTED_DOC_SUFFIXES = {"txt", "md", "markdown", "pdf"}

# 纯文本类扩展名
TEXT_SUFFIXES = {"txt", "md", "markdown"}

# 单文件大小上限（5 MiB）
MAX_UPLOAD_BYTES = 5 * 1024 * 1024

# PDF 页数上限
MAX_PDF_PAGES = 200

# 单文档可提取文本总长上限（约 2M 字符，防止极端内存占用）
MAX_TEXT_CHARS = 2_000_000


class DocumentValidationError(Exception):
    """文档校验/解析失败（消息直接返回调用方）。"""


@dataclass(frozen=True)
class ExtractedPage:
    """提取出的一段文本（PDF 为页，文本文件为单段）。"""

    page_number: int
    text: str


def suffix_of(filename: str) -> str:
    """取小写扩展名；文件名不得为空或含路径分隔符。"""
    name = (filename or "").strip()
    if not name:
        raise DocumentValidationError("文件名不能为空")
    if "/" in name or "\\" in name:
        raise DocumentValidationError("文件名不能包含路径分隔符")
    _, dot, suffix = name.rpartition(".")
    if not dot:
        return ""
    return suffix.lower()


def validate_upload_size(raw: bytes) -> None:
    """校验大小上限。"""
    if raw is None or len(raw) == 0:
        raise DocumentValidationError("文件内容为空")
    if len(raw) > MAX_UPLOAD_BYTES:
        raise DocumentValidationError(
            f"文件过大（{len(raw)} 字节），上限 {MAX_UPLOAD_BYTES // (1024 * 1024)} MiB"
        )


def load_document(filename: str, raw: bytes) -> List[ExtractedPage]:
    """按扩展名分派解析；返回按页/段切分的文本列表。"""
    suffix = suffix_of(filename)
    if suffix not in SUPPORTED_DOC_SUFFIXES:
        raise DocumentValidationError(
            "仅支持 " + " / ".join("." + s for s in sorted(SUPPORTED_DOC_SUFFIXES)) + " 文档"
        )
    validate_upload_size(raw)
    if suffix in TEXT_SUFFIXES:
        return _load_text(raw, suffix)
    return _load_pdf(filename, raw)


def _load_text(raw: bytes, suffix: str) -> List[ExtractedPage]:
    """纯文本：严格 UTF-8 解码 + NUL 字节拒绝；Markdown 先做轻量清洗。"""
    if b"\x00" in raw:
        raise DocumentValidationError("文本文件包含二进制内容（NUL 字节），已拒绝")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise DocumentValidationError("文本文件不是合法 UTF-8 编码，请转换后再上传") from exc
    if suffix in {"md", "markdown"}:
        from app.rag.splitter import normalize_markdown  # noqa: PLC0415

        text = normalize_markdown(text)
    text = text.strip()
    if not text:
        raise DocumentValidationError("文件解析后无有效文本")
    if len(text) > MAX_TEXT_CHARS:
        raise DocumentValidationError("文本过长，请拆分后上传")
    return [ExtractedPage(page_number=1, text=text)]


def _load_pdf(filename: str, raw: bytes) -> List[ExtractedPage]:
    """PDF：魔数校验 + pypdf 逐页提取 + 页数上限。"""
    if not raw.lstrip()[:5] == b"%PDF-":
        raise DocumentValidationError("文件内容不是合法 PDF（缺少 %PDF- 头）")
    try:
        from pypdf import PdfReader  # noqa: PLC0415
    except ImportError as exc:  # pragma: no cover - 取决于可选依赖
        raise DocumentValidationError(
            "服务端未安装 PDF 解析依赖（pypdf），暂时无法处理 PDF，请上传 .txt/.md"
        ) from exc

    import io  # noqa: PLC0415

    try:
        reader = PdfReader(io.BytesIO(raw))
        if reader.is_encrypted:
            try:
                reader.decrypt("")  # 尝试空口令
            except Exception as exc:  # noqa: BLE001
                raise DocumentValidationError("PDF 已加密，无法解析") from exc
        total_pages = len(reader.pages)
        if total_pages == 0:
            raise DocumentValidationError("PDF 不含任何页面")
        if total_pages > MAX_PDF_PAGES:
            raise DocumentValidationError(
                f"PDF 页数 {total_pages} 超过上限 {MAX_PDF_PAGES}，请拆分后上传"
            )
        pages: List[ExtractedPage] = []
        total_chars = 0
        for index, page in enumerate(reader.pages, start=1):
            try:
                text = (page.extract_text() or "").strip()
            except Exception as exc:  # noqa: BLE001
                logger.warning("PDF 第 %d 页解析失败（已跳过）：%s", index, exc)
                continue
            if not text:
                continue
            total_chars += len(text)
            if total_chars > MAX_TEXT_CHARS:
                raise DocumentValidationError("PDF 文本量过大，请拆分后上传")
            pages.append(ExtractedPage(page_number=index, text=text))
        if not pages:
            raise DocumentValidationError("PDF 未提取到可检索文本（可能为扫描件，请转为文本后再上传）")
        return pages
    except DocumentValidationError:
        raise
    except Exception as exc:  # noqa: BLE001
        raise DocumentValidationError(f"PDF 解析失败：{exc}") from exc


__all__ = [
    "DocumentValidationError",
    "ExtractedPage",
    "MAX_PDF_PAGES",
    "MAX_UPLOAD_BYTES",
    "SUPPORTED_DOC_SUFFIXES",
    "load_document",
    "suffix_of",
    "validate_upload_size",
]
