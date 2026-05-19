from typing import List


class TableProcessor:
    """
    业务功能：将 Markdown 表格行列表转换为自然语言陈述句，提升 BGE-M3 的语义理解精度。
    从 SemanticChunker 提取（架构重构阶段三），职责隔离：只负责表格文本化，不参与切片逻辑。

    转换策略：
      原有：[表格数据摘要] 表头1:值1; 表头2:值2  → BGE 语义理解差
      修复：[表格数据] 表头1为值1，表头2为值2。  → BGE-M3 能正确理解中文句法结构

    [P0-C 修复] 超大表格分批：
      原有：100+ 行表格全量拼成单个超长字符串，超出 BGE-M3 的 512 token 上限后截断，底部数据丢失。
      修复：超过 MAX_ROWS_PER_CHUNK 行时自动分批，返回 List[str]，每批携带编号避免语义歧义。
      注意：调用方（CoarseChunker.push_table）需适配 List[str] 遍历逻辑。
    """
    # 单批最大数据行数，超过此数自动分批索引（防向量化截断）
    MAX_ROWS_PER_CHUNK = 20

    def flatten(self, table_lines: List[str]) -> List[str]:
        """
        业务功能：将 Markdown 表格行列表扁平化为自然语言 chunk 列表。
        关键流程：
          小表格（<= MAX_ROWS_PER_CHUNK 行）→ 原有逻辑，返回单元素列表。
          大表格（> MAX_ROWS_PER_CHUNK 行）→ 按批次分片，返回多元素列表。
        """
        if not table_lines or len(table_lines) < 3:
            return [" ".join(table_lines)]

        # 提取表头（第一行）
        headers   = [h.strip() for h in table_lines[0].split('|') if h.strip()]
        data_rows = table_lines[2:]  # 跳过表头行和分隔符行（---）

        if len(data_rows) <= self.MAX_ROWS_PER_CHUNK:
            # 小表格：原有逻辑，单 chunk
            return [self._rows_to_nl(headers, data_rows, batch_idx=0)]

        # 大表格：按批次分片，每批携带编号，避免语义歧义
        chunks = []
        for i in range(0, len(data_rows), self.MAX_ROWS_PER_CHUNK):
            batch     = data_rows[i: i + self.MAX_ROWS_PER_CHUNK]
            batch_idx = i // self.MAX_ROWS_PER_CHUNK
            chunks.append(self._rows_to_nl(headers, batch, batch_idx))
        return chunks

    def _rows_to_nl(self, headers: List[str], rows: List[str], batch_idx: int) -> str:
        """
        业务功能：将一批数据行转换为自然语言陈述句字符串。
        关键流程：每数据行生成"X为Y"式陈述句 → 合并输出，大表格携带批次编号。
        """
        # 大表格第 2 批及以后携带批次编号，避免切片后语义不完整
        prefix    = f"[表格数据第{batch_idx + 1}组] " if batch_idx > 0 else "[表格数据] "
        sentences = []
        for row in rows:
            cols  = [c.strip() for c in row.split('|') if c.strip()]
            parts = []
            for i, val in enumerate(cols):
                header = headers[i] if i < len(headers) else f"第{i+1}项"
                # 跳过空值和常见占位符
                if val and val not in ('-', '--', '—', ''):
                    parts.append(f"{header}为{val}")
            if parts:
                # 生成完整陈述句：X为a，Y为b，Z为c。
                sentences.append("，".join(parts) + "。")

        if not sentences:
            return prefix + " ".join(str(r) for r in rows)

        return prefix + " ".join(sentences)
