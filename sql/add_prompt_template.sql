-- ============================================================
-- sys_prompt_template —— LLM Prompt 模板管理表
-- 适用版本: PostgreSQL 14+
-- 目标：将 AI 服务中所有硬编码 Prompt 集中到数据库管理，支持热更新。
-- 幂等：所有语句均可在已有库上重复执行。
-- ============================================================

-- ── 1. 建表 ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_prompt_template (
    id          BIGSERIAL    PRIMARY KEY,
    -- 场景+角色的唯一标识，格式约定：{SCENE}_{ROLE}，如 HYDE_GENERAL_SYSTEM
    prompt_key  VARCHAR(64)  NOT NULL,
    -- 功能场景分组（供管理界面按组展示），枚举见下方初始数据
    scene       VARCHAR(64)  NOT NULL,
    -- 消息角色：system（定义 AI 角色） / user（含查询内容，支持 {query} 占位符）
    role        VARCHAR(16)  NOT NULL CHECK (role IN ('system', 'user')),
    -- Prompt 正文：user role 中 {query} 为查询词占位符，{docs} 为文档列表占位符
    content     TEXT         NOT NULL,
    -- 中文说明，供管理员理解该 Prompt 的用途与适用场景
    description VARCHAR(256),
    -- 是否启用：0=停用并退回 AI 服务内置兜底值，1=启用（默认）
    is_active   SMALLINT     DEFAULT 1,
    -- 修改次数（每次 UPDATE content 时 +1，用于追溯变更频率）
    version     INT          DEFAULT 1,
    -- 最后修改人 ID（对应系统登录用户）
    updated_by  VARCHAR(64),
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_prompt_key UNIQUE (prompt_key)
);

COMMENT ON TABLE  public.sys_prompt_template             IS 'LLM Prompt 模板管理表（AI 服务所有大模型调用的Prompt集中管理，支持热更新）';
COMMENT ON COLUMN public.sys_prompt_template.prompt_key IS 'Prompt 唯一标识，格式：{SCENE}_{ROLE}，对应 AI 服务 PromptRegistry 的查询 key';
COMMENT ON COLUMN public.sys_prompt_template.scene      IS '功能场景分组：HYDE_GENERAL/HYDE_SHORT/HYDE_GONGSHU/HYDE_FAGUI/HYDE_TONGZHI/REWRITE/RERANK/LEGACY_HYDE';
COMMENT ON COLUMN public.sys_prompt_template.role       IS '消息角色：system=系统提示（定义AI角色），user=用户提示（含查询内容）';
COMMENT ON COLUMN public.sys_prompt_template.content    IS 'Prompt 正文文本，支持 {query}、{docs} 等占位符，AI 服务在调用时动态替换';
COMMENT ON COLUMN public.sys_prompt_template.is_active  IS '是否启用：1=生效，0=停用（AI服务退回内置默认值，不影响服务可用性）';
COMMENT ON COLUMN public.sys_prompt_template.version    IS 'Prompt 内容修改次数，每次更新自动 +1';

-- 快速按场景查询索引（管理界面按 scene 分组展示时使用）
CREATE INDEX IF NOT EXISTS idx_spt_scene_active ON public.sys_prompt_template (scene, is_active);


-- ── 2. 初始化 Prompt 数据（16 条，对应 main.py 中全部 8 组场景）──────────
-- 说明：
--   ON CONFLICT DO NOTHING：首次执行插入，后续重复执行幂等跳过。
--   如需覆盖更新已有 Prompt，手动执行 UPDATE 或通过管理界面修改。
-- ────────────────────────────────────────────────────────────

INSERT INTO public.sys_prompt_template
    (prompt_key, scene, role, content, description)
VALUES

-- ── 场景1：HYDE_GENERAL（通用 HyDE，适用 >20字含疑问词的检索）────────────
(
    'HYDE_GENERAL_SYSTEM',
    'HYDE_GENERAL',
    'system',
    '你是政务/公安/法律领域专家。请生成一段100字以内的简短文档片段，该片段是能直接回答用户查询的文档正文内容。只输出文档内容本身，不含解释、前缀、标签。',
    'HyDE 通用场景：系统角色提示，定义模型为政务法律领域专家，约束输出格式为文档正文片段'
),
(
    'HYDE_GENERAL_USER',
    'HYDE_GENERAL',
    'user',
    '查询：{query}
请生成一段法规文档原文片段，直接包含该查询所寻找的答案内容：',
    'HyDE 通用场景：用户提示，{query} 为搜索词占位符，引导模型生成假设文档用于向量检索'
),

-- ── 场景2：HYDE_SHORT（短查询后台预热，4~20字关键词）────────────────────
(
    'HYDE_SHORT_SYSTEM',
    'HYDE_SHORT',
    'system',
    '你是政务/公安/法律领域专家。请生成一段60字以内的文档片段，该片段是能直接说明该关键词的文档正文内容。只输出文档内容本身，不含解释、前缀、标签。',
    'HyDE 短查询预热场景：对4~20字关键词进行后台异步预热，填充语义缓存，字数限制更严格'
),
(
    'HYDE_SHORT_USER',
    'HYDE_SHORT',
    'user',
    '关键词：{query}
请生成一段直接包含该关键词相关内容的文档片段：',
    'HyDE 短查询预热场景：用户提示，以"关键词："开头引导模型生成简短文档片段'
),

-- ── 场景3：HYDE_GONGSHU（公示类文档 HyDE，生成人员名单格式）────────────
(
    'HYDE_GONGSHU_SYSTEM',
    'HYDE_GONGSHU',
    'system',
    '你是人事/干部管理专家。请根据用户的查询，生成一段标准任职公示中的人员名单文本，包含 2-3 位拟任人员的基本信息（姓名、性别、出生年、籍贯/民族、现任职位、拟任职位）。直接输出人员列表内容，不含标题、前言或解释。',
    '公示文档专属 HyDE：生成与人事公示语义空间匹配的假设文档，解决"查2024年任职公示"类查询向量偏移问题'
),
(
    'HYDE_GONGSHU_USER',
    'HYDE_GONGSHU',
    'user',
    '查询：{query}
请生成符合该公示主题的人员名单片段，格式如下：
1. 张X，男，1985年生，汉族，现任XX镇党委副书记，拟任XX镇党委书记、镇长。
2. 李X，女，1988年生，回族，现任XX县教育局副局长，拟任XX县教育局局长。
请仿照上述格式生成，姓名用模糊代替，职位与查询主题相关：',
    '公示文档专属 HyDE：用户提示，含 few-shot 格式示例引导模型输出标准人事公示格式'
),

-- ── 场景4：HYDE_FAGUI（法规类文档 HyDE，生成条文原文格式）──────────────
(
    'HYDE_FAGUI_SYSTEM',
    'HYDE_FAGUI',
    'system',
    '你是政务/法律专家。请根据用户的查询，生成一段法律法规或规范性文件的条文原文，包含具体的规定内容、适用范围和法律依据，格式正式规范。直接输出条文内容，不含解释。',
    '法规文档专属 HyDE：生成与法律条文语义空间匹配的假设文档片段'
),
(
    'HYDE_FAGUI_USER',
    'HYDE_FAGUI',
    'user',
    '查询：{query}
请生成该类法规文件中典型的条文片段（60-100字）：',
    '法规文档专属 HyDE：用户提示，约束输出为 60-100 字的法律条文格式'
),

-- ── 场景5：HYDE_TONGZHI（通知/意见/方案类 HyDE）────────────────────────
(
    'HYDE_TONGZHI_SYSTEM',
    'HYDE_TONGZHI',
    'system',
    '你是政务工作人员。请根据用户的查询，生成一段政务通知或工作方案的正文内容，包含具体工作要求、时间节点或执行措施。直接输出正文内容。',
    '通知/公告文档专属 HyDE：生成政务通知类格式的假设文档'
),
(
    'HYDE_TONGZHI_USER',
    'HYDE_TONGZHI',
    'user',
    '查询：{query}
请生成该类通知的正文片段（60-100字）：',
    '通知/公告文档专属 HyDE：用户提示，约束通知正文格式'
),

-- ── 场景6：REWRITE（意图改写，提取核心名词并展开政务缩略语）─────────────
(
    'REWRITE_SYSTEM',
    'REWRITE',
    'system',
    'You are a helpful text classification assistant.',
    '意图改写场景：系统角色提示，使用英文以提升小模型（0.5B）的指令遵循能力'
),
(
    'REWRITE_USER',
    'REWRITE',
    'user',
    '从下面的提问中提取2~5个核心名词（政务/法律/医疗专业词），同时将政务缩略语展开为全称，空格分隔，勿解释。
规则：若词语是政务缩略语，请输出全称（如『环评』->『环境影响评价』，『三资』->『外资企业』，『政采』->『政府采购』，『城改』->『城市改造』，『食药监』->『食品药品监督管理局』）。
提问：{query}
核心词：',
    '意图改写场景：用户提示，引导模型提取核心词并展开政务缩略语，{query} 为原始搜索词占位符'
),

-- ── 场景7：RERANK（LLM 大模型语义重排打分）──────────────────────────────
(
    'RERANK_SYSTEM',
    'RERANK',
    'system',
    '你是文档相关性评判专家。严格按格式输出，每行一个0-10的整数。',
    'LLM 重排场景：系统角色提示，要求模型严格按数字格式输出，防止 7B 模型输出冗余解释'
),
(
    'RERANK_USER',
    'RERANK',
    'user',
    '查询：{query}

请对以下每个文档与查询的相关性打分（0-10分，10分最相关，0分完全无关）：

{docs}

输出要求：只输出 {doc_count} 个数字，每行一个，顺序对应文档1到文档{doc_count}，不要任何解释。
评分：',
    'LLM 重排场景：用户提示，{query} 为查询词，{docs} 为文档列表，{doc_count} 为文档数量，引导模型批量打分'
),

-- ── 场景8：LEGACY_HYDE（/api/ai/vector/hyde 旧版接口，含 few-shot 示例）─
(
    'LEGACY_HYDE_SYSTEM',
    'LEGACY_HYDE',
    'system',
    '你是一位政务/公安/法律文件检索专家。用户的输入是口语化的问题或需求，你的任务是生成一段正式政务政策/法律/公文文件摘要，该摘要应能直接对应并回答用户的这个问题。关键原则：1)先判断问题所属领域（医疗/法律/行政/安全/公安等）；2)在该领域内生成规范条文文字；3)直接输出60-80字的正式条文文本，不要解释。',
    'LEGACY HyDE（/vector/hyde 接口）：包含领域判断三阶段指令的系统提示，适用于直接调用 /vector/hyde 接口的场景'
),
(
    'LEGACY_HYDE_USER',
    'LEGACY_HYDE',
    'user',
    '示例1：问题：开办这个调解机构需要多少錢？ → 摘要：设立商事调解组织应当符合下列条件：有30万元以上的资产。
示例2：问题：大医院能不能把看病号源留一些给社区卫生院？ → 摘要：三级医院应按规定比例预留特定数量普通门诊号源，优先满足社区卫生服务中心转识患者需求。
现在请对以下查询生成一段直接对应的正式条文摘要，需要属于与此问题对应领域的政务文件，不得庄尌或跨领域。13-80字，不要解释起因。
查询：{query}
摘要：',
    'LEGACY HyDE（/vector/hyde 接口）：包含两个 few-shot 示例引导模型生成跨领域正确条文摘要，{query} 为原始问题占位符'
)

ON CONFLICT (prompt_key) DO NOTHING;

-- ── 3. 验证插入结果 ──────────────────────────────────────────
-- 执行后应看到 16 条记录，场景分布如下：
-- HYDE_GENERAL(2) + HYDE_SHORT(2) + HYDE_GONGSHU(2) + HYDE_FAGUI(2)
-- + HYDE_TONGZHI(2) + REWRITE(2) + RERANK(2) + LEGACY_HYDE(2) = 16
-- SELECT scene, COUNT(*) FROM sys_prompt_template GROUP BY scene ORDER BY scene;
