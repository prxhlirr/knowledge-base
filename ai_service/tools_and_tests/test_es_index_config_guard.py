import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ESIndexConfigGuardTest(unittest.TestCase):
    """
    业务功能：防止重新引入危险 ES 索引配置。
    关键流程：扫描真实运行脚本和核心代码，禁止固定单分片零副本、宽泛 kb_* 模板和无保护模板删除。
    """

    SCAN_DIRS = [
        ROOT / "ai_service" / "core",
        ROOT / "ai_service" / "scripts",
        ROOT / "scripts",
    ]
    EXCLUDED_NAMES = {
        "__pycache__",
    }

    def _python_files(self):
        """
        业务功能：枚举需要纳入 ES 配置审计的 Python 文件。
        关键流程：只扫描真实运行目录，排除缓存目录，避免测试用例中的示例字符串造成误报。
        """
        for scan_dir in self.SCAN_DIRS:
            if not scan_dir.exists():
                continue
            for path in scan_dir.rglob("*.py"):
                if any(part in self.EXCLUDED_NAMES for part in path.parts):
                    continue
                yield path

    def test_no_fixed_single_shard_zero_replica_creation(self):
        """
        业务功能：禁止索引创建逻辑写死 1 shard / 0 replica。
        关键流程：允许通过 env helper 提供开发默认值，但不允许在 mapping body 中直接写死生产危险配置。
        """
        patterns = [
            re.compile(r'"number_of_shards"\s*:\s*1'),
            re.compile(r'"number_of_replicas"\s*:\s*0'),
            re.compile(r"'number_of_shards'\s*:\s*1"),
            re.compile(r"'number_of_replicas'\s*:\s*0"),
        ]
        offenders = []
        for path in self._python_files():
            text = path.read_text(encoding="utf-8", errors="replace")
            for pattern in patterns:
                if pattern.search(text):
                    offenders.append(f"{path.relative_to(ROOT)} matched {pattern.pattern}")

        self.assertEqual([], offenders)

    def test_no_default_wide_kb_template_pattern(self):
        """
        业务功能：禁止默认注册覆盖 kb_* 的宽泛模板。
        关键流程：kb_document_* 已有专用 v2 模板，历史 kb_* 模板会与其竞争，必须改为显式 legacy pattern。
        """
        patterns = [
            re.compile(r'"index_patterns"\s*:\s*\[\s*"kb_\*"\s*\]'),
            re.compile(r"index_patterns\s*=\s*\[\s*['\"]kb_\*['\"]\s*\]"),
        ]
        offenders = []
        for path in self._python_files():
            text = path.read_text(encoding="utf-8", errors="replace")
            for pattern in patterns:
                if pattern.search(text):
                    offenders.append(f"{path.relative_to(ROOT)} matched {pattern.pattern}")

        self.assertEqual([], offenders)

    def test_template_delete_requires_explicit_overwrite_flag(self):
        """
        业务功能：禁止无条件删除 ES index template。
        关键流程：允许保留 delete_index_template，但同一文件内必须有 KB_LEGACY_TEMPLATE_OVERWRITE 开关保护。
        """
        offenders = []
        for path in self._python_files():
            text = path.read_text(encoding="utf-8", errors="replace")
            if "delete_index_template(" in text and "KB_LEGACY_TEMPLATE_OVERWRITE" not in text:
                offenders.append(str(path.relative_to(ROOT)))

        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
