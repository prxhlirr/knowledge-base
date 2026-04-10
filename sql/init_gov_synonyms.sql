-- ============================================================
-- 政务同义词词典表 · PostgreSQL DDL
-- 版本: v1 | 幂等可重复执行
-- 用途: 支撑 SearchService 的应用层查询时同义词展开（方案 X1）
--       管理员通过后台 UI 维护，无需修改代码即可热更新词典
-- ============================================================

CREATE TABLE IF NOT EXISTS public.sys_gov_synonyms (
    id           BIGSERIAL    PRIMARY KEY,                          -- 主键ID（自增）
    abbr         VARCHAR(64)  NOT NULL,                             -- 缩略词 / 简称（检索触发词）
    full_terms   VARCHAR(512) NOT NULL,                             -- 完整词列表（逗号分隔，如"环境影响评价,环境评价"）
    synonym_type VARCHAR(16)  NOT NULL DEFAULT 'ABBR'
                     CHECK (synonym_type IN ('ABBR', 'EQUIV')),     -- 类型：ABBR=缩略语展开，EQUIV=等价词双向同义
    enabled      SMALLINT     NOT NULL DEFAULT 1,                   -- 是否启用：1=启用，0=停用（软删除代替物理删除）
    remark       VARCHAR(256),                                      -- 备注（如：来源标准、更新原因）
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 创建时间
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 最后更新时间
    CONSTRAINT uq_gov_synonyms_abbr UNIQUE (abbr)                  -- 同一缩略词只允许一条记录
);

COMMENT ON TABLE  public.sys_gov_synonyms             IS '政务同义词词典表（用于 SearchService 查询时展开，管理员可通过后台 UI 热维护）';
COMMENT ON COLUMN public.sys_gov_synonyms.id          IS '主键ID';
COMMENT ON COLUMN public.sys_gov_synonyms.abbr        IS '缩略词或触发词（如"环评"、"城管"），为检索词击中时的触发键';
COMMENT ON COLUMN public.sys_gov_synonyms.full_terms  IS '对应的完整词列表，逗号分隔（如"环境影响评价,环境评价"）';
COMMENT ON COLUMN public.sys_gov_synonyms.synonym_type IS '同义词类型：ABBR=缩略语展开（单向），EQUIV=等价词（双向）';
COMMENT ON COLUMN public.sys_gov_synonyms.enabled     IS '启用状态：1=启用，0=停用（软禁用，不物理删除）';
COMMENT ON COLUMN public.sys_gov_synonyms.remark      IS '备注说明（如数据来源、适用范围等）';
COMMENT ON COLUMN public.sys_gov_synonyms.created_at  IS '记录创建时间';
COMMENT ON COLUMN public.sys_gov_synonyms.updated_at  IS '记录最后修改时间';

CREATE INDEX IF NOT EXISTS idx_gov_synonyms_enabled ON public.sys_gov_synonyms (enabled);

-- ============================================================
-- 初始化预置政务高频同义词（仅在不存在时插入）
-- ============================================================
INSERT INTO public.sys_gov_synonyms (abbr, full_terms, synonym_type, remark) VALUES
    ('环评',   '环境影响评价,环境评价',                   'ABBR',  '《环境影响评价法》标准缩略语'),
    ('城管',   '城市管理,城市综合执法,城市管理执法',       'ABBR',  '城市管理综合执法标准叫法'),
    ('三农',   '农业,农村,农民',                          'ABBR',  '中央一号文件核心主题词'),
    ('两违',   '违法建设,违章建筑,违规建设',               'ABBR',  '城乡规划执法通用术语'),
    ('低保',   '最低生活保障,低保金,生活保障',             'ABBR',  '民政系统标准缩略语'),
    ('五险一金', '养老保险,医疗保险,失业保险,工伤保险,生育保险,住房公积金', 'ABBR', '社会保障标准组合词'),
    ('营业执照', '工商登记,市场主体登记,营业注册',          'EQUIV', '工商系统等价术语'),
    ('征地',   '土地征收,征收土地,土地收储',               'EQUIV', '自然资源和规划系统用词'),
    ('拆迁',   '房屋征收,搬迁安置,房屋拆迁',               'EQUIV', '住房和城乡建设系统用词'),
    ('社保',   '社会保险,社会保障',                       'ABBR',  '人力资源和社会保障系统标准缩略语'),
    ('医保',   '医疗保险,基本医疗保险',                    'ABBR',  '医疗保障系统缩略语'),
    ('公积金', '住房公积金',                              'ABBR',  '住建系统标准简称'),
    ('扶贫',   '脱贫攻坚,精准扶贫,帮扶',                   'EQUIV', '脱贫攻坚相关政策词'),
    ('双碳',   '碳中和,碳达峰,减碳',                      'ABBR',  '国家碳排放战略目标'),
    ('EIA',    '环境影响评价,环评',                        'ABBR',  '环评英文缩写')
ON CONFLICT (abbr) DO NOTHING;
