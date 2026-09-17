import sys
import unittest
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
AI_SERVICE = WORKSPACE / "ai_service"
sys.path.insert(0, str(AI_SERVICE))

from core.model_manager import model_manager, resolve_embedding_max_len


class _FakeTokenizer:
    """
    业务功能：为 token 统计单元测试提供确定性的 tokenizer 替身。
    关键流程：把输入文本中的空格分词数量映射为 input_ids 长度，并模拟首尾特殊 token。
    设计原因：测试截断统计逻辑不应依赖真实 transformers 运行时和本地模型文件。
    """

    def __call__(self, texts, padding=False, truncation=False, add_special_tokens=True):
        input_ids = []
        for text in texts:
            token_count = len(str(text).split()) if str(text).strip() else 0
            if add_special_tokens:
                token_count += 2
            input_ids.append(list(range(token_count)))
        return {"input_ids": input_ids}


class EmbeddingTokenStatsTest(unittest.TestCase):
    """
    业务功能：验证入库向量化前的 token 统计逻辑。
    关键流程：替换 model_manager.tokenizer 为轻量假对象，覆盖空输入、平均长度、最大长度和截断数量。
    设计原因：截断风险是召回质量问题的关键观测指标，必须在不加载模型的情况下可测试。
    """

    def setUp(self):
        self._old_tokenizer = model_manager.tokenizer
        self._old_max_len = model_manager.max_len
        model_manager.tokenizer = _FakeTokenizer()
        model_manager.max_len = 5

    def tearDown(self):
        model_manager.tokenizer = self._old_tokenizer
        model_manager.max_len = self._old_max_len

    def test_reports_empty_input(self):
        """
        业务功能：确认空文本集合返回稳定的零值统计。
        关键流程：传入空列表并检查 text_count、max_tokens 和截断比例。
        设计原因：空文档或全量低质 chunk 被过滤时，report 仍应保持结构稳定。
        """
        stats = model_manager.embedding_token_stats([])

        self.assertEqual(stats["text_count"], 0)
        self.assertEqual(stats["max_tokens"], 0)
        self.assertEqual(stats["truncated_count"], 0)
        self.assertEqual(stats["truncated_ratio"], 0.0)

    def test_reports_truncation_ratio(self):
        """
        业务功能：确认超过 embedding max_len 的文本会被计入截断风险。
        关键流程：构造一条 4 token 文本和一条 6 token 文本，叠加特殊 token 后仅第二条超过 max_len。
        设计原因：动态 chunk 配置可能超过模型窗口，统计必须准确暴露该风险。
        """
        stats = model_manager.embedding_token_stats([
            "a b",
            "a b c d",
        ])

        self.assertEqual(stats["text_count"], 2)
        self.assertEqual(stats["max_len"], 5)
        self.assertEqual(stats["max_tokens"], 6)
        self.assertEqual(stats["truncated_count"], 1)
        self.assertEqual(stats["truncated_ratio"], 0.5)

    def test_resolve_embedding_max_len_uses_explicit_value_first(self):
        """
        业务功能：确认 EMBEDDING_MAX_LEN 显式配置优先于 MAX_CHUNK_SIZE 推导。
        关键流程：同时传入显式窗口和小 chunk 配置，断言返回显式窗口。
        设计原因：生产环境需要一个直接、可审计的模型窗口配置入口。
        """
        self.assertEqual(resolve_embedding_max_len("1536", "250"), 1536)

    def test_resolve_embedding_max_len_defaults_to_current_chunk_strategy(self):
        """
        业务功能：确认未配置时默认覆盖当前 500 字 chunk 策略。
        关键流程：显式窗口和 MAX_CHUNK_SIZE 都为空，断言返回 1024。
        设计原因：当前文档类型策略最大 chunk 为 500 字，默认 512 token 会产生静默截断风险。
        """
        self.assertEqual(resolve_embedding_max_len(None, None), 1024)

    def test_resolve_embedding_max_len_keeps_small_chunk_compatibility(self):
        """
        业务功能：确认显式小 chunk 配置仍可使用较小 tokenizer 窗口。
        关键流程：传入 MAX_CHUNK_SIZE=300，断言兼容返回 512。
        设计原因：小 chunk 场景无需强制使用更大窗口，避免无谓增加推理显存和延迟。
        """
        self.assertEqual(resolve_embedding_max_len(None, "300"), 512)

    def test_resolve_embedding_max_len_ignores_invalid_values(self):
        """
        业务功能：确认非法窗口配置不会让模型管理器启动失败。
        关键流程：传入非法 EMBEDDING_MAX_LEN 和非法 MAX_CHUNK_SIZE，断言回退到 1024。
        设计原因：配置错误应暴露在观测数据里，而不应导致服务导入阶段崩溃。
        """
        self.assertEqual(resolve_embedding_max_len("bad", "also-bad"), 1024)


if __name__ == "__main__":
    unittest.main()
