"""农技 / 惠农政策知识切片入库 CLI。

流程：读取 ``--input-dir`` 下的文档 → :class:`DocumentSplitter` 切片 →
:class:`Embedder` 向量化 → 事务批量写入 ``t_knowledge_chunk``。

用法示例（验收命令）：
    python scripts/ingest_docs.py --init-table --tenant-id tenant_yuzhuang_001 \\
        --category DISEASE_PEST

参数：
    --input-dir  待入库文档目录（默认 ai-service/data/raw_docs）
    --tenant-id  租户标识（默认 global）
    --category   知识分类（默认 GENERAL；如 DISEASE_PEST / AGRICULTURAL_TECH）
    --init-table 启动时自动建表 / 建索引（幂等）
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from typing import List, Sequence, Tuple

# 允许从任意工作目录以 `python scripts/ingest_docs.py` 运行：
# 将 ai-service 根目录插入 sys.path，保证 `import app.*` 可解析。
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import get_settings  # noqa: E402
from app.db.init_tables import (  # noqa: E402
    KNOWLEDGE_CHUNK_TABLE,
    init_knowledge_chunk_table_sync,
)
from app.db.session import (  # noqa: E402
    close_sync_pool,
    open_sync_pool,
    sync_pool,
)
from app.rag.embedder import Embedder  # noqa: E402
from app.rag.splitter import (  # noqa: E402
    DocumentChunk,
    DocumentSplitter,
    PdfDependencyError,
    SUPPORTED_SUFFIXES,
    UnsupportedFormatError,
)
from pgvector import Vector  # noqa: E402  (仅类型提示用)

logger = logging.getLogger("ingest_docs")

# 与 app/db/init_tables.py 建表列一一对应（此处不写列名清单，直接内联保证一致）
_INSERT_CHUNK_SQL = f"""
INSERT INTO {KNOWLEDGE_CHUNK_TABLE}
    (tenant_id, doc_title, page_number, category, chunk_text, embedding, metadata)
VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb)
"""


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="农技/政策文档切片入库：读取 → 切片 → 向量化 → 写入 t_knowledge_chunk",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=PROJECT_ROOT / "data" / "raw_docs",
        help="待入库文档所在目录",
    )
    parser.add_argument("--tenant-id", default="global", help="租户标识")
    parser.add_argument("--category", default="GENERAL", help="知识分类（如 DISEASE_PEST）")
    parser.add_argument(
        "--init-table",
        action="store_true",
        help="启动时自动创建/确认 t_knowledge_chunk 表与 HNSW 索引（幂等）",
    )
    parser.add_argument("--verbose", action="store_true", help="输出 DEBUG 级别日志")
    return parser.parse_args(argv)


def discover_documents(input_dir: Path) -> List[Path]:
    """返回目录下受支持的文档文件（按文件名排序，保证输出确定性）。"""
    if not input_dir.exists():
        raise FileNotFoundError(f"输入目录不存在: {input_dir}")
    if not input_dir.is_dir():
        raise NotADirectoryError(f"输入路径不是目录: {input_dir}")
    docs = [
        p
        for p in input_dir.iterdir()
        if p.is_file() and p.suffix.lower() in SUPPORTED_SUFFIXES
    ]
    return sorted(docs, key=lambda p: p.name.lower())


def _build_rows(
    file: Path,
    chunks: Sequence[DocumentChunk],
    vectors: Sequence[Vector],
    tenant_id: str,
    category: str,
) -> List[Tuple]:
    """把切片与向量拼成待写入行（metadata 以 JSON 串 + ::jsonb 落库）。"""
    rows: List[Tuple] = []
    for chunk, vec in zip(chunks, vectors):
        metadata = {
            "source": file.name,
            "doc_path": str(file),
            "chunk_index": chunk.chunk_index,
            "page_number": chunk.page_number,
            "char_count": len(chunk.chunk_text),
            "splitter": "yuzhuang-document-splitter",
        }
        rows.append(
            (
                tenant_id,
                file.stem,            # doc_title：文件名（去扩展名）
                chunk.page_number,
                category,
                chunk.chunk_text,
                vec,                  # pgvector.Vector（严禁传裸 list）
                json.dumps(metadata, ensure_ascii=False),
            )
        )
    return rows


def _insert_chunks(rows: Sequence[Tuple]) -> int:
    """单事务批量写入；任一行失败则整体回滚并抛出。返回写入行数。"""
    with sync_pool.connection() as conn:
        with conn.cursor() as cur:
            cur.executemany(_INSERT_CHUNK_SQL, list(rows))
        conn.commit()
    return len(rows)


def run(args: argparse.Namespace) -> int:
    """执行入库流水线，返回进程退出码（0 表示成功）。"""
    started = time.perf_counter()
    settings = get_settings()
    logger.info(
        "ingest_docs 启动 input_dir=%s tenant_id=%s category=%s db=%s",
        args.input_dir,
        args.tenant_id,
        args.category,
        settings.database_url,
    )

    # 1) 建表（可选）
    if args.init_table:
        logger.info("[init-table] 开始创建/确认表 %s 与 HNSW 索引 ...", KNOWLEDGE_CHUNK_TABLE)
        init_knowledge_chunk_table_sync()
        logger.info("[init-table] 表与索引就绪 ✔")

    # 2) 打开同步连接池（注册 pgvector 类型适配器）
    open_sync_pool()
    try:
        # 3) 发现文档
        docs = discover_documents(args.input_dir)
        if not docs:
            logger.warning("目录下未发现受支持文档: %s", args.input_dir)
            print("未发现可入库文档，未执行任何写入。")
            return 0
        logger.info("发现 %d 个待入库文档", len(docs))

        splitter = DocumentSplitter()
        embedder = Embedder()  # 未配置密钥时自动 Mock，可离线运行

        total_docs = 0
        total_chunks = 0
        total_inserted = 0
        failed: List[str] = []

        # 4) 逐文件：切片 → 向量化 → 批量入库
        for index, file in enumerate(docs, start=1):
            try:
                chunks = splitter.split_file(file)
            except (UnsupportedFormatError, PdfDependencyError) as exc:
                logger.warning("跳过 %s：%s", file.name, exc)
                failed.append(file.name)
                continue
            except Exception as exc:  # noqa: BLE001
                logger.exception("切分 %s 失败", file.name)
                failed.append(file.name)
                continue

            if not chunks:
                logger.info("跳过 %s：切分为空（无可切片正文）", file.name)
                continue

            total_docs += 1
            total_chunks += len(chunks)
            logger.info(
                "[%d/%d] 切分 %s → %d 个切片",
                index,
                len(docs),
                file.name,
                len(chunks),
            )

            vectors = embedder.embed_texts([c.chunk_text for c in chunks])
            if len(vectors) != len(chunks):
                raise RuntimeError(
                    f"向量化数量不一致: chunks={len(chunks)} vectors={len(vectors)}（{file.name}）"
                )

            rows = _build_rows(file, chunks, vectors, args.tenant_id, args.category)
            inserted = _insert_chunks(rows)
            total_inserted += inserted
            logger.info(
                "[%d/%d] 已入库 %s：写入 %d 行",
                index,
                len(docs),
                file.name,
                inserted,
            )

        elapsed = time.perf_counter() - started
        # 5) 汇总（stdout，便于验收 grep）
        print(
            "=" * 64,
            flush=True,
        )
        print(
            f"切片入库完成: 文档={total_docs} 切片={total_chunks} "
            f"成功写入={total_inserted} 耗时={elapsed:.2f}s "
            f"embedding={'Mock' if embedder.is_mock else 'Real'}"
            f" tenant={args.tenant_id} category={args.category}",
            flush=True,
        )
        if failed:
            print(f"跳过/失败文件: {', '.join(failed)}", flush=True)
        print("=" * 64, flush=True)

        return 0
    finally:
        close_sync_pool()


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
        stream=sys.stdout,
    )
    try:
        return run(args)
    except KeyboardInterrupt:
        logger.warning("用户中断，未完成全部入库")
        return 130
    except Exception as exc:  # noqa: BLE001 - CLI 兜底：打印错误并返回非 0
        logger.exception("入库失败: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
