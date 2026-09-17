import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))

from core.rag_pipeline import (
    VECTOR_DIMS,
    build_embedding_input_text,
    build_embedding_truncation_error,
    resolve_embedding_context_mode,
    resolve_embedding_truncation_policy,
    validate_dense_vectors_for_indexing,
)


class VectorIndexingQualityGateTest(unittest.TestCase):
    """
    业务功能：验证文档 chunk 写入 ES 前的向量质量门，防止缺失、错维度、零范数向量污染检索索引。
    关键流程：直接调用向量校验函数，覆盖正常向量、数量不匹配、维度不匹配、非法数值和零范数场景。
    设计原因：该逻辑是入库正确性的最后防线，必须用纯单元测试固定行为，避免依赖真实模型和外部 ES。
    """

    def test_accepts_valid_dense_vectors(self):
        """
        业务功能：确认合法 1024 维向量可以通过入库前校验。
        关键流程：构造单位向量并按期望数量传入校验函数。
        设计原因：质量门不能误伤正常 embedding 输出，否则会阻断合法文档入库。
        """
        vectors = [[1.0] + [0.0] * (VECTOR_DIMS - 1)]

        validate_dense_vectors_for_indexing(vectors, 1, "valid.txt")

    def test_rejects_vector_count_mismatch(self):
        """
        业务功能：确认模型返回向量数量少于 chunk 数量时会失败。
        关键流程：传入 1 条向量但要求 2 条，断言抛出数量不匹配错误。
        设计原因：缺失向量不能再用零向量兜底，否则会把错误数据写入生产索引。
        """
        vectors = [[1.0] + [0.0] * (VECTOR_DIMS - 1)]

        with self.assertRaisesRegex(ValueError, "dense_vector_count_mismatch"):
            validate_dense_vectors_for_indexing(vectors, 2, "count.txt")

    def test_rejects_vector_dimension_mismatch(self):
        """
        业务功能：确认非 1024 维向量不能进入当前 ES mapping。
        关键流程：传入短向量并断言抛出维度不匹配错误。
        设计原因：dense_vector 维度由索引 mapping 固定，错维度数据应在 bulk 前失败。
        """
        vectors = [[1.0, 0.0]]

        with self.assertRaisesRegex(ValueError, "dense_vector_dim_mismatch"):
            validate_dense_vectors_for_indexing(vectors, 1, "dim.txt")

    def test_rejects_zero_norm_vector(self):
        """
        业务功能：确认零范数向量不能写入语义检索索引。
        关键流程：传入全零向量并断言抛出零范数错误。
        设计原因：零向量没有语义信息，会污染召回分布并掩盖真实 embedding 失败。
        """
        vectors = [[0.0] * VECTOR_DIMS]

        with self.assertRaisesRegex(ValueError, "dense_vector_zero_norm"):
            validate_dense_vectors_for_indexing(vectors, 1, "zero.txt")

    def test_rejects_nan_vector_value(self):
        """
        业务功能：确认 NaN 和 Infinity 这类非法数值不能写入 ES。
        关键流程：在合法维度向量中注入 NaN，断言抛出非法数值错误。
        设计原因：非法浮点值会导致 bulk 写入失败或产生不可解释的检索异常。
        """
        vectors = [[float("nan")] + [0.0] * (VECTOR_DIMS - 1)]

        with self.assertRaisesRegex(ValueError, "dense_vector_invalid_value"):
            validate_dense_vectors_for_indexing(vectors, 1, "nan.txt")

    def test_indexing_entrypoints_do_not_use_zero_vector_fallback(self):
        """
        业务功能：扫描主入库和历史离线入口，确认不存在 1024 维零向量兜底。
        关键流程：读取生产相关 Python 文件源码，断言不包含历史坏模式。
        设计原因：零向量兜底会把 embedding 失败伪装成成功入库，必须用测试防止回归。
        """
        files = [
            WORKSPACE / "ai_service" / "core" / "rag_pipeline.py",
            WORKSPACE / "scripts" / "rag_pipeline.py",
            WORKSPACE / "ai_service" / "scripts" / "rag_pipeline.py",
        ]

        for path in files:
            source = path.read_text(encoding="utf-8", errors="replace")
            self.assertNotIn("[0.0] * 1024", source, msg=str(path))

    def test_embedding_truncation_policy_defaults_to_warn(self):
        """
        业务功能：确认 embedding 截断策略默认不阻断入库。
        关键流程：分别传入空值和非法值，断言都回退为 warn。
        设计原因：生产默认应先观测截断风险，避免配置拼写错误造成批量入库中断。
        """
        self.assertEqual(resolve_embedding_truncation_policy(None), "warn")
        self.assertEqual(resolve_embedding_truncation_policy("invalid"), "warn")

    def test_embedding_truncation_policy_accepts_fail(self):
        """
        业务功能：确认严格模式可以显式拒绝存在截断风险的文档。
        关键流程：传入 fail 并断言策略解析结果保持为 fail。
        设计原因：离线压测和高质量索引建设需要用失败暴露 chunk/token 配置问题。
        """
        self.assertEqual(resolve_embedding_truncation_policy("fail"), "fail")

    def test_embedding_truncation_error_contains_actionable_stats(self):
        """
        业务功能：确认截断风险错误信息包含可操作统计。
        关键流程：构造 token_stats 并检查 source、超窗数量、窗口和最大 token 都出现在消息中。
        设计原因：运维需要根据错误信息判断是调大 EMBEDDING_MAX_LEN 还是优化 chunk 策略。
        """
        message = build_embedding_truncation_error(
            {
                "truncated_count": 3,
                "text_count": 10,
                "max_len": 1024,
                "max_tokens": 1300,
            },
            "policy.txt",
        )

        self.assertIn("source=policy.txt", message)
        self.assertIn("truncated=3/10", message)
        self.assertIn("max_len=1024", message)
        self.assertIn("max_tokens=1300", message)


    def test_embedding_context_mode_defaults_to_raw(self):
        """
        业务功能：确认 embedding 上下文模式默认保持历史裸文行为。
        关键流程：传入空值和非法值，断言都解析为 raw。
        设计原因：上下文增强必须可灰度，不应默认改变已有向量空间分布。
        """
        self.assertEqual(resolve_embedding_context_mode(None), "raw")
        self.assertEqual(resolve_embedding_context_mode("bad"), "raw")

    def test_build_embedding_input_text_raw_mode_uses_raw_content(self):
        """
        业务功能：确认 raw 模式只使用 chunk.raw_content。
        关键流程：构造同时包含 content/raw_content/section_path 的 chunk，断言输出为纯正文。
        设计原因：默认模式必须与历史向量化输入保持一致，便于线上兼容。
        """
        chunk = SimpleNamespace(
            content="章节展示文本",
            raw_content="纯正文文本",
            section_path="第一章/第一节",
        )

        self.assertEqual(build_embedding_input_text(chunk, "文档标题", "raw"), "纯正文文本")

    def test_build_embedding_input_text_section_mode_adds_section_path(self):
        """
        业务功能：确认 section 模式为短 chunk 补充章节路径。
        关键流程：构造带 section_path 的 chunk，断言输出包含章节路径和原始正文。
        设计原因：短句条款缺少章节语义时，轻量路径前缀可以改善语义召回。
        """
        chunk = SimpleNamespace(
            content="展示文本",
            raw_content="不得超过规定标准。",
            section_path="第二章/办理条件",
        )

        text = build_embedding_input_text(chunk, "业务指南", "section")

        self.assertIn("章节路径：第二章/办理条件", text)
        self.assertTrue(text.endswith("不得超过规定标准。"))
        self.assertNotIn("文档标题：业务指南", text)

    def test_build_embedding_input_text_title_section_mode_adds_title_and_section(self):
        """
        业务功能：确认 title_section 模式同时补充文档标题和章节路径。
        关键流程：构造带标题和章节路径的 chunk，断言二者都出现在正文前缀中。
        设计原因：面向短 fine chunk 的严格召回场景，需要可选地引入更完整的局部语义。
        """
        chunk = SimpleNamespace(
            content="展示文本",
            raw_content="申请人应提交材料。",
            section_path="第三章/申请材料",
        )

        text = build_embedding_input_text(chunk, "政务服务办事指南", "title_section")

        self.assertIn("文档标题：政务服务办事指南", text)
        self.assertIn("章节路径：第三章/申请材料", text)
        self.assertTrue(text.endswith("申请人应提交材料。"))


if __name__ == "__main__":
    unittest.main()
