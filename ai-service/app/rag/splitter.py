"""文本切片器：把 .txt / .md（可选 .pdf）文档切成 400~600 字符的语义切片。

切分策略：
- 优先在「换行符 / 句末标点」等自然边界切分，尽量不把句子从中间劈开；
- 目标块大小 400~600 字符（默认目标 500），相邻块保留 60 字符重叠窗口，
  降低跨块语义断裂导致的检索漏召回；
- 超长且无自然断点的段落会按空白 / 固定长度二次硬切分（最多 600 字符/片）。

返回结构体 :class:`DocumentChunk`：``chunk_text`` / ``page_number`` / ``chunk_index``。

PDF 支持为可选依赖：未安装 ``pypdf`` 时抛出 :class:`PdfDependencyError`，
提示先执行 ``pip install pypdf``（避免把 pypdf 变成必装依赖）。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Tuple, Union

logger = logging.getLogger(__name__)

PathLike = Union[str, Path]

# 支持的纯文本格式
SUPPORTED_TEXT_SUFFIXES = frozenset({".txt", ".md", ".markdown"})
# 可选支持的 PDF
SUPPORTED_PDF_SUFFIXES = frozenset({".pdf"})
SUPPORTED_SUFFIXES = SUPPORTED_TEXT_SUFFIXES | SUPPORTED_PDF_SUFFIXES

# 句末断句标点（中文 + 英文 + 换行）。用零宽后行断言：切割点紧跟在这些字符之后，
# 标点/换行本身保留在左侧片段，避免信息丢失。
_SENTENCE_BOUNDARY_RE = re.compile(r"(?<=[。！？；.!?;\n])")

# Markdown 常用噪声：代码围栏 / 行内图片 / 行内链接 / HTML 注释
_MD_CODE_FENCE_RE = re.compile(r"```.*?```", re.S)
_MD_INLINE_IMAGE_RE = re.compile(r"!\[([^\]]*)\]\([^)]*\)")
_MD_INLINE_LINK_RE = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_MD_HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
_MD_HEADING_RE = re.compile(r"(?m)^[ \t]*#{1,6}[ \t]+")


class SplitterError(Exception):
    """切片器通用异常。"""


class UnsupportedFormatError(SplitterError):
    """不支持的文档格式。"""


class PdfDependencyError(SplitterError):
    """解析 PDF 需要安装 pypdf。"""


@dataclass(slots=True)
class DocumentChunk:
    """单个知识切片的结构体。

    字段：
    - ``chunk_text``：切片文本（正文，不含头部/页脚噪音）
    - ``page_number``：来源页码（txt/md 默认 1）
    - ``chunk_index``：文档内切片序号（从 0 开始，跨页连续）
    - ``doc_title``：来源文档名（可选，入库时便于落库）
    """

    chunk_text: str
    page_number: int = 1
    chunk_index: int = 0
    doc_title: str = field(default="")


def _normalize_markdown(md_text: str) -> str:
    """轻量清洗 Markdown 正文：去掉围栏代码块、图片/链接语法、HTML 注释与井号标题符号。

    保留文字本体，供切分与向量化使用；对中文农业文档足够，不追求完整 md 渲染。
    """
    text = md_text
    text = _MD_HTML_COMMENT_RE.sub("", text)
    text = _MD_CODE_FENCE_RE.sub("", text)
    text = _MD_INLINE_IMAGE_RE.sub(r"\1", text)  # 图片取其 alt 文本
    text = _MD_INLINE_LINK_RE.sub(r"\1", text)   # 链接取其显示文本
    text = _MD_HEADING_RE.sub("", text)          # 去掉 # 但保留标题文字
    return text


def normalize_markdown(md_text: str) -> str:
    """公开包装：供上传链路（document_loader）在切片前清洗 Markdown 正文。"""
    return _normalize_markdown(md_text)


class DocumentSplitter:
    """文档切片器。

    参数：
    - ``chunk_size``：目标块大小（默认 500）
    - ``min_chunk_size`` / ``max_chunk_size``：允许的块大小下限 / 硬上限（默认 400/600）
    - ``overlap``：相邻块重叠字符数（默认 60）
    - ``encoding``：读取 .txt/.md 的文本编码
    """

    def __init__(
        self,
        chunk_size: int = 500,
        min_chunk_size: int = 400,
        max_chunk_size: int = 600,
        overlap: int = 60,
        encoding: str = "utf-8",
    ) -> None:
        if not (0 < min_chunk_size <= chunk_size <= max_chunk_size):
            raise ValueError("需要满足 0 < min_chunk_size <= chunk_size <= max_chunk_size")
        if not (0 < overlap < chunk_size):
            raise ValueError("需要满足 0 < overlap < chunk_size")
        self.chunk_size = chunk_size
        self.min_chunk_size = min_chunk_size
        self.max_chunk_size = max_chunk_size
        self.overlap = overlap
        self.encoding = encoding

    # ------------------------------------------------------------ 对外入口

    def split_text(
        self,
        text: str,
        page_number: int = 1,
        chunk_index_offset: int = 0,
        doc_title: str = "",
    ) -> List[DocumentChunk]:
        """把一段纯文本切分为 :class:`DocumentChunk` 列表。"""
        units = self._tokenize_units(text)
        if not units:
            return []
        units = self._split_oversized_units(units)
        merged = self._merge_units(units)
        return [
            DocumentChunk(
                chunk_text=t,
                page_number=page_number,
                chunk_index=chunk_index_offset + idx,
                doc_title=doc_title,
            )
            for idx, t in enumerate(merged)
        ]

    def split_file(self, path: PathLike, page_number: Union[int, None] = None) -> List[DocumentChunk]:
        """读取文件并返回切片列表（多页文档页码逐页递增，chunk_index 全局连续）。"""
        p = Path(path)
        if not p.is_file():
            raise SplitterError(f"文档不存在或不是文件: {p}")
        pages = self.extract_pages(p)
        doc_title = p.stem
        chunks: List[DocumentChunk] = []
        offset = 0
        for page_no, text in pages:
            pg = page_number if page_number is not None else page_no
            page_chunks = self.split_text(
                text,
                page_number=pg,
                chunk_index_offset=offset,
                doc_title=doc_title,
            )
            offset += len(page_chunks)
            chunks.extend(page_chunks)
        return chunks

    def extract_pages(self, path: Path) -> List[Tuple[int, str]]:
        """抽取文档文本，返回 ``[(页码, 文本), ...]``。txt/md 为单页 ``(1, text)``。"""
        suffix = path.suffix.lower()
        if suffix in SUPPORTED_TEXT_SUFFIXES:
            text = path.read_text(encoding=self.encoding, errors="replace")
            if suffix in {".md", ".markdown"}:
                text = _normalize_markdown(text)
            return [(1, text)]
        if suffix in SUPPORTED_PDF_SUFFIXES:
            return self._extract_pdf_pages(path)
        raise UnsupportedFormatError(
            f"暂不支持的文档格式 {suffix or '(无扩展名)'}（{path.name}）；"
            f"支持: {', '.join(sorted(SUPPORTED_SUFFIXES))}"
        )

    # ------------------------------------------------------------ 内部实现

    def _extract_pdf_pages(self, path: Path) -> List[Tuple[int, str]]:
        """可选 PDF 解析：未安装 pypdf 时给出明确安装提示。"""
        try:
            from pypdf import PdfReader  # 延迟导入，避免强制安装 pypdf
        except ImportError:
            raise PdfDependencyError(
                f"解析 PDF 需要安装 pypdf：请执行 `pip install pypdf` 后重试（{path.name}）。"
            ) from None
        try:
            reader = PdfReader(str(path))
        except Exception as exc:  # noqa: BLE001
            raise SplitterError(f"PDF 解析失败（{path.name}）: {exc}") from exc
        pages: List[Tuple[int, str]] = []
        for index, page in enumerate(reader.pages, start=1):
            text = page.extract_text() or ""
            pages.append((index, text))
        return pages

    def _tokenize_units(self, text: str) -> List[str]:
        """按换行 / 句末标点切分为「自然单元」，标点与换行保留在单元尾部。

        返回的单元不含空串；首尾空白被规整，但单元内部的换行符得以保留，
        保证合并时相邻行（尤其英文/表格）不会粘连。
        """
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        units: List[str] = []
        for piece in _SENTENCE_BOUNDARY_RE.split(normalized):
            stripped = piece.strip(" \t")
            if not stripped.strip("\n"):
                continue  # 纯空白单元直接丢弃
            units.append(stripped)
        return units

    def _split_oversized_units(self, units: List[str]) -> List[str]:
        """把超过 ``max_chunk_size`` 的单元二次切分，保证后续合并不会越界。"""
        expanded: List[str] = []
        for unit in units:
            if len(unit) <= self.max_chunk_size:
                expanded.append(unit)
            else:
                expanded.extend(self._hard_split(unit))
        return expanded

    def _hard_split(self, text: str) -> List[str]:
        """无自然断点的超长文本：优先在空白处切断，其次固定 600 字符硬切。"""
        pieces: List[str] = []
        start = 0
        n = len(text)
        while start < n:
            end = min(start + self.max_chunk_size, n)
            if end < n:
                # 在 [start+overlap, end] 内找最后一个空白，优先贴近 end，避免把长词/句劈开
                cut = text.rfind(" ", start + self.overlap, end)
                if cut <= start:
                    cut = end
                end = cut
            piece = text[start:end].strip()
            if piece:
                pieces.append(piece)
            if end <= start:  # 防御：极端情况强制前进，避免死循环
                end = min(start + self.chunk_size, n)
            start = end
        return pieces

    def _merge_units(self, units: List[str]) -> List[str]:
        """滑动窗口合并单元：块内不超 max、尽量达到 min 与目标大小；按字符数回退制造重叠。"""
        chunks: List[str] = []
        i = 0
        n = len(units)
        while i < n:
            j = i
            size = 0
            while j < n:
                unit_len = len(units[j])
                # 已经收了内容且再加就超上限 → 收口
                if size > 0 and size + unit_len > self.max_chunk_size:
                    break
                size += unit_len
                j += 1
                # 达到下限：若已到末尾，或再加一个单元会超上限，则本块到此为止；
                # 否则继续吸收，让块靠近目标大小（≈500~600）。
                if size >= self.min_chunk_size and (
                    j >= n or size + len(units[j]) > self.max_chunk_size
                ):
                    break
            chunk = "".join(units[i:j]).strip()
            if chunk:
                chunks.append(chunk)
            if j >= n:
                break
            # 回退若干单元，使下一窗口与当前块末尾重叠约 overlap 字符
            k = j
            overlap_chars = 0
            while k > i:
                overlap_chars += len(units[k - 1])
                k -= 1
                if overlap_chars >= self.overlap:
                    break
            if k <= i:  # 无法重叠（单单元已近上限）→ 不重叠，直接前进，防止死循环
                k = j
            i = k
        return chunks
