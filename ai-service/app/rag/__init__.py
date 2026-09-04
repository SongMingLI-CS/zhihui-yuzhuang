"""RAG 数据流水线：文本切片与向量化。

- ``app.rag.splitter.DocumentSplitter``  文档 → 结构化切片（DocumentChunk）
- ``app.rag.embedder.Embedder``          切片文本 → pgvector.Vector（OpenAI 兼容）
"""
