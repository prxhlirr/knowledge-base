"""
文档类型策略注册中心（单例）。

设计要点：
- 单例：整个进程共享一个 Registry 实例，避免重复初始化。
- 调用方解耦：只需 resolve(text) 拿策略，不需要知道任何具体策略类的存在。
- 链式注册：.register() 返回 self，方便 bootstrap.py 连续调用。
- 可演化：未来可扩展 register_from_yaml()，无需修改任何现有代码。
"""
from typing import List
from .strategy import DocTypeStrategy
from .strategies import DefaultDocStrategy


class DocTypeRegistry:
    """
    文档类型策略注册中心。
    扩展时只操作 bootstrap.py，Registry 自身永远不修改（严格 OCP）。
    """
    # [缺陷7 修复] 头尾联合采样参数
    # 原因：纯头部2000字采样，导致前置应用背景的长文档被误路由
    # （如法规文件头部为工作报告式导语，会被误判为 ReportDocStrategy）
    # 修复：头部（1500字）+ 尾部（500字）联合采样，确保尾部高辨识度特征词被覆盖
    _HEAD_LEN = 1500   # 头部采样字数
    _TAIL_LEN = 500    # 尾部采样字数（特此通知/特此通告/签发机关等高辨识度信号集中在尾部）
    # 低于此置信分视为未命中，回退冀底策略
    _MIN_CONFIDENCE = 0.25

    def __init__(self):
        self._strategies: List[DocTypeStrategy] = []
        self._default = DefaultDocStrategy()

    def register(self, strategy: DocTypeStrategy) -> 'DocTypeRegistry':
        """
        注册一个文档类型策略。
        链式调用：registry.register(A()).register(B()).register(C())
        """
        self._strategies.append(strategy)
        return self

    def resolve(self, text: str) -> DocTypeStrategy:
        """
        路由入口：对所有已注册策略评分，返回置信分最高且超过阀値的策略。
        无命中时返回 DefaultDocStrategy（冀底），调用方无需处理 None。

        [缺陷7 修复] 关键流程：头尾联合采样 → 并行评分 → 取最高分 → 阈値门控 → 返回策略或冀底
        采样策略：头部（分析+标题） + 尾部（特此 XX/署发机关）联合采样，
                  屏蔽长文档前置背景层对类型识别的干扰。
        """
        if len(text) <= self._HEAD_LEN + self._TAIL_LEN:
            # 短文档：全量采样
            sample = text
        else:
            # [缺陷7] 长文档：头尾联合采样，中间部分跳过
            # 尾部500字集中了「特此兺知/特此通告/签发机关」等高辨识度信号
            sample = text[:self._HEAD_LEN] + "\n" + text[-self._TAIL_LEN:]

        if not self._strategies:
            return self._default

        scored = [(s.match_score(sample), s) for s in self._strategies]
        best_score, best_strategy = max(scored, key=lambda x: x[0])

        return best_strategy if best_score >= self._MIN_CONFIDENCE else self._default
