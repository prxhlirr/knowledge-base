import subprocess
import sys
import unittest


class ModelManagerLazyRuntimeTest(unittest.TestCase):
    """
    业务功能：验证 Worker 轻路径不会在导入期提前加载模型推理重依赖。
    关键流程：通过独立 Python 子进程隔离 sys.modules，分别检查轻路径导入和显式运行时加载。
    设计原因：权限投影与队列消费入口不应被 transformers/onnxruntime 等模型依赖拖慢冷启动。
    """

    def _run_probe(self, code: str) -> str:
        result = subprocess.run(
            [sys.executable, "-c", code],
            cwd=".",
            text=True,
            capture_output=True,
            timeout=90,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        return result.stdout.strip()

    def test_task_worker_import_does_not_load_model_runtime(self):
        code = (
            "import sys; "
            "sys.path.insert(0, 'ai_service'); "
            "import task_worker; "
            "blocked=['transformers','onnxruntime','sklearn','pandas']; "
            "loaded=[m for m in blocked if m in sys.modules]; "
            "print(','.join(loaded))"
        )
        self.assertEqual(self._run_probe(code), "")

    def test_explicit_session_options_loads_model_runtime(self):
        code = (
            "import sys; "
            "sys.path.insert(0, 'ai_service'); "
            "from core.model_manager import model_manager; "
            "model_manager._new_session_options(); "
            "print('onnxruntime' in sys.modules)"
        )
        self.assertEqual(self._run_probe(code), "True")


if __name__ == "__main__":
    unittest.main()
