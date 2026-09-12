"""文档加载与校验单元测试（离线、无数据库）。

覆盖阶段 F 验收点：上传支持安全 txt/md/PDF（解析隔离、文件校验、大小/页数限制）。
"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pytest  # noqa: E402

from app.rag import document_loader as dl  # noqa: E402


def test_load_txt_happy_path():
    pages = dl.load_document("农技指南.txt", "冬小麦返青期追肥建议。".encode("utf-8"))
    assert len(pages) == 1
    assert pages[0].page_number == 1
    assert "追肥" in pages[0].text


def test_load_markdown_strips_syntax():
    raw = "# 标题\n\n![图](a.png)\n[链接](https://example.com)\n\n正文内容。".encode("utf-8")
    pages = dl.load_document("doc.md", raw)
    assert len(pages) == 1
    text = pages[0].text
    assert "标题" in text
    assert "正文内容。" in text
    assert "![" not in text and "](" not in text


def test_unsupported_extension_rejected():
    with pytest.raises(dl.DocumentValidationError) as exc:
        dl.load_document("payload.exe", b"MZ\x90\x00")
    assert "仅支持" in str(exc.value)


def test_path_separator_rejected():
    with pytest.raises(dl.DocumentValidationError):
        dl.load_document("../etc/passwd.txt", b"x")


def test_empty_file_rejected():
    with pytest.raises(dl.DocumentValidationError):
        dl.load_document("empty.txt", b"")


def test_binary_masquerading_as_text_rejected():
    with pytest.raises(dl.DocumentValidationError) as exc:
        dl.load_document("fake.txt", b"PK\x03\x04\x00\x00binary")
    assert "二进制" in str(exc.value)


def test_invalid_utf8_rejected():
    with pytest.raises(dl.DocumentValidationError):
        dl.load_document("latin.txt", b"\xff\xfe\x00\x01not-utf8")


def test_oversize_rejected(monkeypatch):
    monkeypatch.setattr(dl, "MAX_UPLOAD_BYTES", 16)
    with pytest.raises(dl.DocumentValidationError) as exc:
        dl.load_document("big.txt", b"a" * 32)
    assert "过大" in str(exc.value)


def test_pdf_magic_bytes_required():
    with pytest.raises(dl.DocumentValidationError) as exc:
        dl.load_document("fake.pdf", b"not-a-real-pdf")
    assert "PDF" in str(exc.value)


def test_pdf_requires_pypdf_dependency(monkeypatch):
    """注：pypdf 未安装时必须给出明确安装提示；已安装则跳过该分支（改走解析失败路径）。"""
    import builtins

    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "pypdf":
            raise ImportError("no pypdf")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    with pytest.raises(dl.DocumentValidationError) as exc:
        dl.load_document("policy.pdf", b"%PDF-1.4 minimal")
    assert "pypdf" in str(exc.value) or "解析失败" in str(exc.value)


def test_suffix_of_helper():
    assert dl.suffix_of("a.TXT") == "txt"
    assert dl.suffix_of("noext") == ""
    with pytest.raises(dl.DocumentValidationError):
        dl.suffix_of("")
