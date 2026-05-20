"""
qa_generator.py — QA 问答对生成器（单一职责 + 依赖倒置）

设计原则：
  SRP（单一职责）：本类只负责把 chunk_content 转换为问题列表，
                   不关心向量化、ES写入、HTTP传输等任何外部细节。
  OCP（开闭原则）：Prompt 策略、解析规则均封装于此，调用方对变化零感知。
  DIP（依赖倒置）：main.py（HTTP 门面）和 rag_pipeline.py（流程编排）
                   均依赖本模块，不再相互依赖（消除 HTTP 自环）。

并发策略：
  generate_batch() 内置 Fan-out/Fan-in 模式（ThreadPoolExecutor）。
  调用方无需关心并发细节，直接传入 chunks 列表即可获得聚合结果。
  LLM 调用是 IO 密集型（等待 Ollama 推理），线程模型天然适合此场景。
"""

import os
import re
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List

from core.llm_client import LLMClient


class QAGenerator:
    """
    政务/法律条文 QA 问答对生成器。

    业务功能：为知识库文档的细粒度条文（fine chunk）生成口语化问题，
             供后续向量化写入 kb_qa_pairs 索引驱动 QA 检索。

    关键方法：
      generate()       → 单 chunk 同步生成（线程安全，供并发池调用）
      generate_batch() → 批量并发生成（Fan-out/Fan-in 内置）
    """

    # ── Prompt 常量（集中管理，修改时只改此处）────────────────────────────────
    _SYSTEM_PROMPT: str = (
        "/no_think\n"
        "你是一位政府文件检索专家，熟悉普通市民如何用口语提问政务/法律条文。"
        "领域覆盖：公安执法、刑事司法、行政处罚、商事调解、医疗卫生、安全生产、"
        "社会保障、环境保护、市场监管、教育民政等。"
        "输出格式规定：直接输出2-3个问题，每行仅一个问题，绝对不输出思考过程、编号、解释、前缀或JSON。"
    )

    # few-shot 示例：让 LLM 明确覆盖费用/资金/资产等角度
    _FEW_SHOT: str = (
        "# 示例\n"
        "条文：设立商事调解组织应当符合：（四）有30万元以上的资产。\n"
        "口语化问题（每行一个，不要编号）：\n"
        "成立这家机构最少需要多少钱？\n"
        "开办这个调解机构需要准备多少资金？\n"
    )

    def generate(self, content: str, timeout: float | None = None) -> List[str]:
        """
        为单个 fine chunk 生成口语化问题列表（同步，线程安全）。

        业务功能：将政务/法律条文内容转换为 2~3 个用户可能提问的口语化问题。
        关键流程：
          1. 构建带 few-shot 示例的 prompt（固定格式，避免 LLM 输出偏移）
          2. 调用 LLMClient.ask() 获取 LLM 原始输出
          3. 按换行 + 中文问号双重切分，清洗编号和前缀噪音
          4. 过滤长度不合法的问题（5~60字），最多保留 2 条

        降级策略：LLM 调用失败或输出为空时返回空列表，外层 generate_batch 自动跳过。

        @param content:  fine chunk 的原始文本内容
        @param timeout:  LLM 调用超时（秒），默认 45s，与 main.py 保持一致
        @return:         清洗后的问题列表（0~2 条）
        """
        content = (content or "").strip()
        if len(content) < 8:
            return []
        if timeout is None:
            timeout = float(os.getenv("QA_GENERATION_TIMEOUT_SECONDS", "45"))

        prompt = (
            f"{self._FEW_SHOT}\n"
            f"# 当前任务\n"
            f"/no_think\n"
            f"条文：{content}\n"
            f"口语化问题（每行一个，不要编号，不要解释）："
        )

        messages = [
            {"role": "system", "content": self._SYSTEM_PROMPT},
            {"role": "user",   "content": prompt},
        ]

        raw = LLMClient.ask(
            messages=messages,
            model_env_key="QA_LLM_MODEL",
            temperature=0.3,
            max_tokens=int(os.getenv("QA_LLM_MAX_TOKENS", "256")),
            timeout=timeout,
            enable_thinking=False,
            allow_incomplete_think=True,
        )
        if not raw:
            return []

        return self._parse_questions(raw)

    def generate_batch(
        self,
        chunks: List[str],
        max_workers: int | None = None,
        timeout: float | None = None,
    ) -> Dict[int, List[str]]:
        """
        Fan-out/Fan-in：并发批量为多个 chunks 生成问题。

        业务功能：将串行 O(N) LLM 调用改为并发 O(ceil(N/max_workers)) 批次，
                  在 CPU 离线环境下可将吞吐量提升 2~3 倍（受 Ollama 并发上限约束）。

        并发策略：
          - LLM 调用为 IO 密集型（阻塞等待 Ollama），线程池天然适合此场景。
          - max_workers 默认读取环境变量 QA_LLM_CONCURRENCY（推荐离线 CPU 设 2~3）。
          - 局部失败不影响整体：某个 chunk LLM 调用失败时，该 idx 对应空列表，流程继续。

        @param chunks:      fine chunk 内容列表（已归一化为 str）
        @param max_workers: 并发线程数（None 时读取环境变量，默认 3）
        @param timeout:     单次 LLM 调用超时（秒）
        @return:            {chunk_idx: [questions]} 字典，保留原始索引顺序
        """
        if max_workers is None:
            max_workers = int(os.getenv("QA_LLM_CONCURRENCY", "3"))
        if timeout is None:
            timeout = float(os.getenv("QA_GENERATION_TIMEOUT_SECONDS", "45"))

        results: Dict[int, List[str]] = {}

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            # Fan-out：将每个 chunk 的 LLM 调用投入线程池
            future_to_idx = {
                executor.submit(self.generate, chunk, timeout): idx
                for idx, chunk in enumerate(chunks)
            }

            # Fan-in：按完成顺序收集结果，局部异常不中断整体
            for future in as_completed(future_to_idx):
                idx = future_to_idx[future]
                try:
                    results[idx] = future.result()
                except Exception as e:
                    print(f"  ⚠️ [QAGenerator] chunk[{idx}] 生成失败，已跳过: {e}")
                    results[idx] = []

        return results

    @staticmethod
    def _parse_questions(raw: str) -> List[str]:
        """
        解析 LLM 原始输出为干净的问题列表。

        业务功能：处理 LLM 常见的输出噪音：
          - 同行多问题（用中文问号分割）
          - 行首编号、符号、空白（正则清除）
          - 过长或过短的无效问题（5~60字过滤）
          - 重复问题去重（保序）

        @param raw:  LLM 原始输出字符串
        @return:     清洗后的问题列表（最多 2 条）
        """
        raw = re.sub(r"<think>.*?</think>", "", raw, flags=re.DOTALL).strip()
        parts: List[str] = []
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, list):
                parts.extend(str(item) for item in parsed)
            elif isinstance(parsed, dict):
                for key in ("questions", "data", "items"):
                    value = parsed.get(key)
                    if isinstance(value, list):
                        parts.extend(str(item) for item in value)
                        break
        except Exception:
            pass

        raw = raw.replace("？", "？\n").replace("?", "?\n")
        for line in raw.split("\n"):
            line = line.strip()
            if not line:
                continue
            if re.match(r"^(以下|下面|生成|问题列表|口语化问题|答案|输出)[:：\s]*", line):
                continue
            # 将同行多问题拆分（如"A？B？"→["A？","B？"]）
            sub = re.split(r"(?<=[？?])\s*", line)
            parts.extend(sub)

        cleaned: List[str] = []
        for p in parts:
            # 剔除行首编号、列表符号、空白（兼容全角/半角数字与中文序号）
            q = re.sub(r"^[\d１-９\uff0e\u3001\-\.\*\u25ca\u30fb#\s]+", "", p).strip()
            q = re.sub(r"^(问题\s*\d*|问\s*\d*|Q\s*\d*)[:：.、\s]+", "", q, flags=re.IGNORECASE).strip()
            q = q.strip("\"'“”‘’[]，,。；;")
            if not q.endswith(("？", "?")) and ("？" in q or "?" in q):
                q = re.split(r"(?<=[？?])", q)[0].strip()
            if 5 <= len(q) <= 80 and ("？" in q or "?" in q) and q not in cleaned:
                cleaned.append(q)
            if len(cleaned) >= 2:
                break

        return cleaned


# ── 模块级单例（供 rag_pipeline 直接复用，避免重复实例化）────────────────────
# 注意：QAGenerator 无状态（所有方法依赖局部变量），单例是安全的
qa_generator = QAGenerator()
