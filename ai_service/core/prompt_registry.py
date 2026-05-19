"""
PromptRegistry —— LLM Prompt 集中管理与热更新缓存

业务功能：
    将 main.py 中所有散落的硬编码 Prompt 统一通过 Java 内部 API 拉取并缓存。
    AI 服务完全不感知 Prompt 的存储方式（DB/文件/配置中心），只依赖此模块。

关键设计：
    1. Java 是唯一数据源，AI 侧只做只读 TTL 缓存（60s），避免 DB 直连
    2. Java 不可达时退回内置默认值（FALLBACK_PROMPTS），保证推理链路不中断
    3. 后台线程异步刷新，刷新期间不阻塞当前请求（Stale-While-Revalidate 模式）
    4. 支持 get(key) 和 build_messages(scene, query) 两种使用方式

典型使用：
    from core.prompt_registry import PromptRegistry

    # 方式一：直接获取单条 Prompt 文本
    system_text = PromptRegistry.get("HYDE_GENERAL_SYSTEM")

    # 方式二：按场景构建标准 messages 列表（最推荐）
    messages = PromptRegistry.build_messages("HYDE_GENERAL", query=query)
    messages = PromptRegistry.build_messages("RERANK", query=query,
                                              docs=docs_text, doc_count=str(n))
"""

import os
import time
import threading
import requests
from typing import Dict, List, Optional

# ─────────────────────────────────────────────────────────────
# 内置兜底 Prompt（Java 不可达时的降级保障，与数据库初始值保持一致）
# 注意：这些值仅作降级保障，日常运行应以数据库内容为准。
# ─────────────────────────────────────────────────────────────
FALLBACK_PROMPTS: Dict[str, str] = {

    # ── HYDE_GENERAL ─────────────────────────────────────────
    "HYDE_GENERAL_SYSTEM": (
        "你是政务/公安/法律领域专家。请生成一段100字以内的简短文档片段，"
        "该片段是能直接回答用户查询的文档正文内容。"
        "只输出文档内容本身，不含解释、前缀、标签。"
    ),
    "HYDE_GENERAL_USER": (
        "查询：{query}\n"
        "请生成一段法规文档原文片段，直接包含该查询所寻找的答案内容："
    ),

    # ── HYDE_SHORT（短查询后台预热）────────────────────────────
    "HYDE_SHORT_SYSTEM": (
        "你是政务/公安/法律领域专家。请生成一段60字以内的文档片段，"
        "该片段是能直接说明该关键词的文档正文内容。"
        "只输出文档内容本身，不含解释、前缀、标签。"
    ),
    "HYDE_SHORT_USER": (
        "关键词：{query}\n"
        "请生成一段直接包含该关键词相关内容的文档片段："
    ),

    # ── HYDE_GONGSHU（公示/人事类）───────────────────────────
    "HYDE_GONGSHU_SYSTEM": (
        "你是人事/干部管理专家。请根据用户的查询，生成一段标准任职公示中的人员名单文本，"
        "包含 2-3 位拟任人员的基本信息（姓名、性别、出生年、籍贯/民族、现任职位、拟任职位）。"
        "直接输出人员列表内容，不含标题、前言或解释。"
    ),
    "HYDE_GONGSHU_USER": (
        "查询：{query}\n"
        "请生成符合该公示主题的人员名单片段，格式如下：\n"
        "1. 张X，男，1985年生，汉族，现任XX镇党委副书记，拟任XX镇党委书记、镇长。\n"
        "2. 李X，女，1988年生，回族，现任XX县教育局副局长，拟任XX县教育局局长。\n"
        "请仿照上述格式生成，姓名用模糊代替，职位与查询主题相关："
    ),

    # ── HYDE_FAGUI（法规/条文类）─────────────────────────────
    "HYDE_FAGUI_SYSTEM": (
        "你是政务/法律专家。请根据用户的查询，生成一段法律法规或规范性文件的条文原文，"
        "包含具体的规定内容、适用范围和法律依据，格式正式规范。"
        "直接输出条文内容，不含解释。"
    ),
    "HYDE_FAGUI_USER": (
        "查询：{query}\n"
        "请生成该类法规文件中典型的条文片段（60-100字）："
    ),

    # ── HYDE_TONGZHI（通知/公告/方案类）─────────────────────
    "HYDE_TONGZHI_SYSTEM": (
        "你是政务工作人员。请根据用户的查询，生成一段政务通知或工作方案的正文内容，"
        "包含具体工作要求、时间节点或执行措施。直接输出正文内容。"
    ),
    "HYDE_TONGZHI_USER": (
        "查询：{query}\n"
        "请生成该类通知的正文片段（60-100字）："
    ),

    # ── REWRITE（意图改写/核心词提取）────────────────────────
    "REWRITE_SYSTEM": "You are a helpful text classification assistant.",
    "REWRITE_USER": (
        "从下面的提问中提取2~5个核心名词（政务/法律/医疗专业词），同时将政务缩略语展开为全称，空格分隔，勿解释。\n"
        "规则：若词语是政务缩略语，请输出全称（如『环评』->『环境影响评价』，"
        "『三资』->『外资企业』，『政采』->『政府采购』，"
        "『城改』->『城市改造』，『食药监』->『食品药品监督管理局』）。\n"
        "提问：{query}\n"
        "核心词："
    ),

    # ── RERANK（LLM 批量打分重排）────────────────────────────
    "RERANK_SYSTEM": "你是文档相关性评判专家。严格按格式输出，每行一个0-10的整数。",
    "RERANK_USER": (
        "查询：{query}\n\n"
        "请对以下每个文档与查询的相关性打分（0-10分，10分最相关，0分完全无关）：\n\n"
        "{docs}\n\n"
        "输出要求：只输出 {doc_count} 个数字，每行一个，"
        "顺序对应文档1到文档{doc_count}，不要任何解释。\n"
        "评分："
    ),

    # ── LEGACY_HYDE（/api/ai/vector/hyde 旧版接口）────────────
    "LEGACY_HYDE_SYSTEM": (
        "你是一位政务/公安/法律文件检索专家。"
        "用户的输入是口语化的问题或需求，你的任务是生成一段正式政务政策/法律/公文文件摘要，"
        "该摘要应能直接对应并回答用户的这个问题。"
        "关键原则：1)先判断问题所属领域（医疗/法律/行政/安全/公安等）；"
        "2)在该领域内生成规范条文文字；3)直接输出60-80字的正式条文文本，不要解释。"
    ),
    "LEGACY_HYDE_USER": (
        "示例1：问题：开办这个调解机构需要多少錢？ → 摘要：设立商事调解组织应当符合下列条件：有30万元以上的资产。\n"
        "示例2：问题：大医院能不能把看病号源留一些给社区卫生院？ → 摘要：三级医院应按规定比例预留特定数量普通门诊号源，优先满足社区卫生服务中心转识患者需求。\n"
        "现在请对以下查询生成一段直接对应的正式条文摘要，需要属于与此问题对应领域的政务文件，"
        "不得庄尌或跨领域。13-80字，不要解释起因。\n"
        "查询：{query}\n摘要："
    ),
}


class PromptRegistry:
    """
    Prompt 热更新注册表

    线程安全，使用 RLock 保护缓存读写。
    采用 Stale-While-Revalidate 策略：TTL 过期时先返回旧值，
    后台异步拉新，避免阻塞请求线程。
    """

    # ── 内部状态（类变量，全进程单例）────────────────────────────
    _cache: Dict[str, str] = {}         # {prompt_key: content}
    _loaded_at: float = 0.0             # 上次成功加载的时间戳
    _refreshing: bool = False           # 后台刷新是否正在进行
    _lock = threading.RLock()

    # ── 配置参数 ────────────────────────────────────────────────
    TTL: int = 60                       # 缓存有效期（秒）
    _java_host: str = os.getenv("JAVA_SERVICE_HOST", "http://knowledge-base-java:8080")
    _token: str = os.getenv("KB_INTERNAL_TOKEN", "kb-dev-token-change-me-in-prod")
    _endpoint: str = "/api/v1/internal/prompts/active"

    # ── 公开 API ────────────────────────────────────────────────

    @classmethod
    def get(cls, key: str, default: str = "") -> str:
        """
        获取单条 Prompt 文本，优先从缓存取，TTL 过期时后台静默刷新。

        :param key:     prompt_key，如 "HYDE_GENERAL_SYSTEM"
        :param default: Java 不可达且 key 不在内置兜底时的终极 fallback
        :return: Prompt 文本字符串
        """
        cls._ensure_fresh()
        with cls._lock:
            # 优先级：DB缓存 > 内置兜底 > 传入default
            return cls._cache.get(key) or FALLBACK_PROMPTS.get(key, default)

    @classmethod
    def build_messages(cls, scene: str, query: str = "", **kwargs) -> List[Dict]:
        """
        按场景名构建标准 messages 列表，供 LLMClient.ask(messages=...) 直接使用。

        占位符替换规则：{query} 自动替换，其余通过 **kwargs 传入：
            - RERANK 场景需额外传 docs=docs_text, doc_count="5"

        :param scene:  场景名，如 "HYDE_GENERAL" / "REWRITE" / "RERANK"
        :param query:  用户原始搜索词（替换 {query} 占位符）
        :param kwargs: 其余占位符（如 docs / doc_count）
        :return: [{"role": "system", "content": ...}, {"role": "user", "content": ...}]
        """
        system_key = f"{scene}_SYSTEM"
        user_key   = f"{scene}_USER"

        system_text = cls.get(system_key)
        user_raw    = cls.get(user_key)

        # 安全替换占位符：格式错误时退回原始模板（不崩溃）
        try:
            user_text = user_raw.format(query=query, **kwargs)
        except (KeyError, ValueError) as e:
            print(f"⚠️ [PromptRegistry] format error for {user_key}: {e}，使用原始模板")
            user_text = user_raw

        return [
            {"role": "system", "content": system_text},
            {"role": "user",   "content": user_text},
        ]

    @classmethod
    def reload(cls) -> bool:
        """
        手动强制从 Java 拉取最新 Prompt（/api/ai/config/reload 接口调用时触发）。

        :return: True=拉取成功，False=拉取失败（缓存保持不变）
        """
        return cls._load_from_java(force=True)

    # ── 内部方法 ────────────────────────────────────────────────

    @classmethod
    def _ensure_fresh(cls) -> None:
        """
        检查 TTL，过期时触发后台刷新（Stale-While-Revalidate）。
        首次调用（缓存为空）时同步加载，确保启动时一定有数据。
        """
        now = time.time()
        with cls._lock:
            is_empty  = not cls._cache
            is_stale  = (now - cls._loaded_at) > cls.TTL
            refreshing = cls._refreshing

        if is_empty:
            # 首次：同步加载，失败时使用内置兜底（保证启动不报错）
            cls._load_from_java(force=True)
        elif is_stale and not refreshing:
            # TTL 过期：后台异步刷新，本次请求直接用旧缓存（不阻塞）
            t = threading.Thread(
                target=cls._load_from_java,
                kwargs={"force": False},
                daemon=True,
                name="prompt-registry-refresh"
            )
            t.start()

    @classmethod
    def _load_from_java(cls, force: bool = False) -> bool:
        """
        从 Java 内部接口拉取全量激活 Prompt，写入 _cache。

        :param force: True=强制刷新（忽略 TTL 和 refreshing 标志）
        :return: True=成功，False=失败（缓存保持原状）
        """
        with cls._lock:
            if not force and cls._refreshing:
                return False            # 已有刷新线程，跳过防止并发重复拉取
            cls._refreshing = True

        try:
            url = f"{cls._java_host}{cls._endpoint}"
            resp = requests.get(
                url,
                headers={"X-Internal-Token": cls._token},
                timeout=5.0             # 5s 超时：Java 不可达时快速降级，不阻塞推理
            )
            resp.raise_for_status()
            payload = resp.json()

            if payload.get("code") == 200 and isinstance(payload.get("data"), dict):
                new_cache: Dict[str, str] = payload["data"]
                with cls._lock:
                    cls._cache = new_cache
                    cls._loaded_at = time.time()
                print(f"✅ [PromptRegistry] 从 Java 加载 {len(new_cache)} 条 Prompt")
                return True
            else:
                print(f"⚠️ [PromptRegistry] Java 返回异常: {payload.get('msg')}")
                return False

        except Exception as e:
            # 降级：Java 不可达时使用内置 FALLBACK_PROMPTS，不中断服务
            print(f"⚠️ [PromptRegistry] 无法连接 Java 服务，使用内置兜底 Prompt: {e}")
            with cls._lock:
                if not cls._cache:
                    # 首次失败时，将内置兜底写入缓存（避免每次都走异常分支）
                    cls._cache = dict(FALLBACK_PROMPTS)
                    cls._loaded_at = time.time()
            return False

        finally:
            with cls._lock:
                cls._refreshing = False
