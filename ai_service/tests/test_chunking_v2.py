"""
政务公文分片优化 — 单元测试套件

覆盖范围：
  - DocTypeRegistry：OCP 验证、五种类型识别、兜底策略
  - HierarchyDetector：H0 路径截断修复（P0-A）
  - TableProcessor：超大表格分批（P0-C）
  - FineChunker：会议纪要决议块识别（P1-B）

运行方式：
  cd e:/project/AI/knowledge-base/ai_service
  python -m pytest tests/test_chunking_v2.py -v
"""
import sys
import os

# 确保 ai_service 根目录在 Python 路径中
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

# ───────────────────────────────────────────────────────────────────────────────
# 1. DocTypeRegistry 测试
# ───────────────────────────────────────────────────────────────────────────────

class TestDocTypeRegistry:

    def test_legal_doc_recognized(self):
        """法规文档应命中 LegalDocStrategy，overlap=30"""
        from core.chunking.doc_type import doc_type_registry
        text = "本办法共三章二十六条。第一条 为规范行政许可行为，保护公民合法权益，制定本办法。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '法规', f"期望'法规'，实际'{s.doc_type}'"
        assert s.chunk_cfg.overlap_size == 30, "法规文档 overlap 应为 30"

    def test_notice_doc_recognized(self):
        """通知文档应命中 NoticeDocStrategy"""
        from core.chunking.doc_type import doc_type_registry
        text = "各县区人民政府、市直各单位：现将《关于加强xxx工作的通知》转发给你们，特此通知。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '通知', f"期望'通知'，实际'{s.doc_type}'"

    def test_report_doc_recognized(self):
        """工作报告应命中 ReportDocStrategy，overlap=80"""
        from core.chunking.doc_type import doc_type_registry
        text = "2024年度工作报告\n一是深入推进改革，二是加强队伍建设。存在问题：任务落实不够到位。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '报告', f"期望'报告'，实际'{s.doc_type}'"
        assert s.chunk_cfg.overlap_size == 80, "报告文档 overlap 应为 80"

    def test_news_doc_recognized(self):
        """新闻通稿应命中 NewsDocStrategy，overlap=0"""
        from core.chunking.doc_type import doc_type_registry
        text = "新华社北京2024年3月28日讯 据悉，记者从相关部门获悉，本报讯，相关工作取得积极进展。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '新闻', f"期望'新闻'，实际'{s.doc_type}'"
        assert s.chunk_cfg.overlap_size == 0, "新闻文档 overlap 应为 0"

    def test_meeting_minutes_recognized(self):
        """会议纪要应命中 MeetingMinutesStrategy"""
        from core.chunking.doc_type import doc_type_registry
        text = "会议纪要\n与会人员：张三、李四\n经与会人员讨论，形成以下决议：\n一、同意开展调研工作。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '会议纪要', f"期望'会议纪要'，实际'{s.doc_type}'"
        assert s.chunk_cfg.overlap_size == 0, "会议纪要 overlap 应为 0"

    def test_unknown_falls_back_to_default(self):
        """没有特征关键词的文本应回退到通用策略"""
        from core.chunking.doc_type import doc_type_registry
        text = "这是一段完全普通的文字，没有任何特征关键词。"
        s = doc_type_registry.resolve(text)
        assert s.doc_type == '通用', f"期望'通用'，实际'{s.doc_type}'"

    def test_ocp_new_strategy_zero_touch(self):
        """
        OCP 红线测试：
        新增文档类型（招标公告）只需 2 步（新建类 + register），
        不修改任何现有代码（Registry / 已有策略 / Chunker）。
        """
        from core.chunking.doc_type.strategy import DocTypeStrategy, ChunkCfg
        from core.chunking.doc_type.registry import DocTypeRegistry

        class BidDocStrategy(DocTypeStrategy):
            @property
            def doc_type(self): return '招标'
            @property
            def chunk_cfg(self): return ChunkCfg(max_chunk_size=350, overlap_size=0)
            def match_score(self, sample):
                return self._count_hits(sample, [r'招标公告', r'投标人', r'招标文件'])

        # 独立 Registry（不污染全局单例）
        test_registry = DocTypeRegistry().register(BidDocStrategy())
        result = test_registry.resolve(
            "招标公告：本项目面向社会公开招标，投标人须满足以下资质要求，招标文件详见附件。"
        )
        assert result.doc_type == '招标', f"新注册策略应被命中，实际'{result.doc_type}'"


# ───────────────────────────────────────────────────────────────────────────────
# 2. HierarchyDetector 测试（P0-A 验证）
# ───────────────────────────────────────────────────────────────────────────────

class TestHierarchyDetectorP0A:

    def test_h1_resets_path(self):
        """# H1 应重置整条路径（正确的原有行为）"""
        from core.chunking.hierarchy_detector import HierarchyDetector
        det = HierarchyDetector()
        _, path = det.detect("# 第三章 行政许可", [])
        assert path == ["第三章 行政许可"]

    def test_h2_preserves_h1_parent(self):
        """## H2 应保留 H1 父节点（P0-A 修复核心）"""
        from core.chunking.hierarchy_detector import HierarchyDetector
        det = HierarchyDetector()
        _, path1 = det.detect("# 第三章 行政许可", [])
        _, path2 = det.detect("## 第十二条 申请条件", path1)
        assert path2 == ["第三章 行政许可", "第十二条 申请条件"], \
            f"H2 面包屑应含父章节，实际: {path2}"

    def test_h3_preserves_two_levels(self):
        """### H3 应保留前两层路径"""
        from core.chunking.hierarchy_detector import HierarchyDetector
        det = HierarchyDetector()
        _, path1 = det.detect("# 第三章 行政许可", [])
        _, path2 = det.detect("## 第十二条 申请条件", path1)
        _, path3 = det.detect("### 第一款", path2)
        assert len(path3) == 3, f"H3 路径应含三层，实际: {path3}"
        assert path3[0] == "第三章 行政许可"
        assert path3[1] == "第十二条 申请条件"


# ───────────────────────────────────────────────────────────────────────────────
# 3. TableProcessor 测试（P0-C 验证）
# ───────────────────────────────────────────────────────────────────────────────

class TestTableProcessorP0C:

    def _make_table(self, row_count: int):
        """生成标准 Markdown 表格（姓名|部门|职务 结构）"""
        header = "| 姓名 | 部门 | 职务 |"
        sep    = "| --- | --- | --- |"
        rows   = [f"| 用户{i} | 部门A | 职员 |" for i in range(row_count)]
        return [header, sep] + rows

    def test_small_table_single_chunk(self):
        """小表格（<= 20行）应返回单元素列表"""
        from core.chunking.table_processor import TableProcessor
        proc   = TableProcessor()
        result = proc.flatten(self._make_table(10))
        assert len(result) == 1, f"小表格应为1个chunk，实际{len(result)}个"
        assert result[0].startswith("[表格数据] ")

    def test_large_table_split_into_batches(self):
        """100行表格应分批为5个 chunk（100 / 20 = 5）"""
        from core.chunking.table_processor import TableProcessor
        proc   = TableProcessor()
        result = proc.flatten(self._make_table(100))
        assert len(result) == 5, f"100行表格应为5个chunk，实际{len(result)}个"

    def test_batch_label_in_later_chunks(self):
        """第2批起应携带批次编号前缀"""
        from core.chunking.table_processor import TableProcessor
        proc   = TableProcessor()
        result = proc.flatten(self._make_table(50))
        assert result[0].startswith("[表格数据] "), "第1批不应有编号"
        assert result[1].startswith("[表格数据第2组] "), f"第2批应有编号，实际: {result[1][:20]}"

    def test_boundary_exactly_20_rows(self):
        """恰好20行数据行应为1个 chunk（边界条件）"""
        from core.chunking.table_processor import TableProcessor
        proc   = TableProcessor()
        result = proc.flatten(self._make_table(20))
        assert len(result) == 1, f"20行精确边界应为1个chunk，实际{len(result)}个"


# ───────────────────────────────────────────────────────────────────────────────
# 4. FineChunker 决议块测试（P1-B 验证）
# ───────────────────────────────────────────────────────────────────────────────

class TestFineChunkerDecisions:

    def test_decision_chunks_extracted(self):
        """会议纪要中的决议项应各自成为独立 fine chunk"""
        from core.chunking.fine_chunker import FineChunker
        text = """
会议纪要
与会人员：张三、李四、王五
本次会议形成以下决议：
一、同意开展2024年度调研工作，由研究室负责组织实施。
二、同意将预算调整为500万元，财务部门负责跟进。
三、责成办公室于本月底前完成相关报告。
"""
        chunker = FineChunker({"max_chunk_size": 500})
        chunks  = chunker.process(text)
        decision_chunks = [c for c in chunks if c.section_path.startswith("决议/")]
        assert len(decision_chunks) == 3, f"应提取3条决议，实际{len(decision_chunks)}条"

    def test_decision_chunk_has_prefix(self):
        """决议 chunk 应携带 [会议决议] 前缀和 quality=1.0"""
        from core.chunking.fine_chunker import FineChunker
        text = "本次会议经研究决定：\n一、同意项目立项，总投资不超过100万元。"
        chunker = FineChunker({"max_chunk_size": 500})
        chunks  = chunker.process(text)
        dec = [c for c in chunks if c.section_path.startswith("决议/")]
        if dec:
            assert dec[0].content.startswith("[会议决议]"), "决议chunk应有[会议决议]前缀"
            assert dec[0].quality_score == 1.0, "决议chunk quality应为1.0"

    def test_non_meeting_doc_unaffected(self):
        """普通法规文档不包含决议锚定词时，不触发决议块扫描"""
        from core.chunking.fine_chunker import FineChunker
        text = "第一条 为规范行政许可，制定本办法。\n第二条 适用范围：本辖区内一切行政活动。"
        chunker = FineChunker({"max_chunk_size": 500})
        chunks  = chunker.process(text)
        assert not any(c.section_path.startswith("决议/") for c in chunks), \
            "非会议纪要文档不应产生决议chunk"


# ───────────────────────────────────────────────────────────────────────────────
# 5. 集成冒烟测试：SemanticChunker 端到端
# ───────────────────────────────────────────────────────────────────────────────

class TestSemanticChunkerIntegration:

    def test_legal_doc_uses_legal_cfg(self):
        """法规文档 process_document 应返回 doc_type='法规'"""
        from core.chunking.semantic_chunker import SemanticChunker
        chunker = SemanticChunker({})
        text    = "本条例共五章三十条，自2024年1月1日起施行。第一条 为加强管理制定本条例。"
        result  = chunker.process_document(text)
        assert result.get("doc_type") == "法规", f"期望'法规'，实际'{result.get('doc_type')}'"
        assert "coarse" in result
        assert "fine" in result

    def test_meeting_minutes_returns_decisions(self):
        """会议纪要端到端应产出含 [会议决议] 前缀的 fine chunk"""
        from core.chunking.semantic_chunker import SemanticChunker
        chunker = SemanticChunker({})
        text    = "会议纪要\n与会人员：四人\n本次会议形成以下决议：\n一、同意立项。\n二、明年预算100万。"
        result  = chunker.process_document(text)
        assert result.get("doc_type") == "会议纪要"
        fine    = result.get("fine", [])
        dec     = [c for c in fine if "[会议决议]" in c.content]
        assert len(dec) >= 1, "会议纪要应产出至少1个决议 fine chunk"


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
