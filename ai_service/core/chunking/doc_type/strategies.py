"""
具体文档类型策略集合。

新增类型只需两步：
  1. 在此文件新建一个继承 DocTypeStrategy 的类
  2. 在 bootstrap.py 追加 .register(NewStrategy())
任何现有策略和 Registry 代码零修改。
"""
import re  # [Bug修复] PoliceDocStrategy.match_score 中直接调用 re.search，必须在本模块 import
from .strategy import DocTypeStrategy, ChunkCfg


class LegalDocStrategy(DocTypeStrategy):
    """
    法规/规章/条例/办法类文档。
    识别特征：'第X条'密集出现 + '本办法/本条例/本规定'等法律效力声明。
    overlap=30：条文边界需严格隔离，防止跨条文语义污染。
    """
    _PATTERNS = [
        r'第[一二三四五六七八九十百千零\d]+条',
        r'本办法', r'本条例', r'本规定', r'本细则',
    ]

    @property
    def doc_type(self) -> str: return '法规'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        # [P1 修复] overlap=0：法律条文有严格独立性，第一条与第二条的内容必须完全
        # 隔离。任何 overlap 都是跨条文语义污染（第一条的尾句拼到第二条头部，
        # RAG 会返回“法条一+二”的杂交内容）。CoarseChunker._get_overlap 已
        # 有条文边界判断，但 overlap=0 从源头彻底关闭更可靠。
        return ChunkCfg(max_chunk_size=400, min_chunk_size=80,
                        target_chunk_size=280, overlap_size=0)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class NoticeDocStrategy(DocTypeStrategy):
    """
    通知/公告/批复类文档。
    识别特征：明确受文对象行（各XXX：）+ 固定套语（特此通知/请照此执行）。
    overlap=50：受文对象行不参与跨段合并，适中 overlap。
    """
    _PATTERNS = [
        r'各[乡镇市县局组单位]',            # 受文对象行（各乡镇／各单位如下）
        r'现将.*通知如下', r'请.*照此执行',
        r'特此通知', r'特此公告', r'特此批复',
        # [增强] 政府通知常用语式
        r'印发给你们', r'现印发', r'批转', r'转发',
        r'请认真贯彻执行', r'请认真组织学习',
        r'函', r'情况请及时报告',
    ]

    @property
    def doc_type(self) -> str: return '通知'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=350, min_chunk_size=100,
                        target_chunk_size=280, overlap_size=50)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class ReportDocStrategy(DocTypeStrategy):
    """
    工作报告/年度总结/情况汇报类文档。
    识别特征：散文式段落密集，'一是/二是'列举，'存在问题/下一步打算'结构。
    overlap=80：相邻段落上下文关联最强，需最大化语境注入。
    """
    _PATTERNS = [
        r'工作报告', r'年度总结', r'情况汇报', r'工作情况',
        r'一是.*二是', r'存在问题', r'下一步',
        # [增强] 政府工作报告特有结构词
        r'政府工作报告', r'在.*大会上', r'请予审议',
        r'回顾', r'总结', r'主要工作', r'主要成绩',
        r'工作回顾', r'年度目标工作完成',
    ]

    @property
    def doc_type(self) -> str: return '报告'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=500, min_chunk_size=150,
                        target_chunk_size=380, overlap_size=80)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class NewsDocStrategy(DocTypeStrategy):
    """
    新闻通稿/政务信息类文档。
    识别特征：倒金字塔结构（导语最重要），有明确时间+地点+人物要素。
    overlap=0：新闻段落各自独立，不需要跨段语境。
    """
    _PATTERNS = [
        # 传统新闻媒体特征
        r'记者.*报道', r'据悉', r'新华社',
        r'\d{4}年\d{1,2}月\d{1,2}日.*讯', r'本报讯',
        # [补强] 政务信息/政府网站新闻常用格式（无传统媒体特征）
        r'\d{4}年\d{1,2}月\d{1,2}日[\uff0c,]',  # 日期开头的政务资讯
        r'(市委|市政府|省委|国务院).{2,20}召开',   # 政府会议报道
        r'(表示|指出|强调)[，：]',        # 领导讲话通稿
        r'(记者|通讯员)从.{2,15}获悉',    # 特派记者表述
        r'政务信息',
    ]

    @property
    def doc_type(self) -> str: return '新闻'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=300, min_chunk_size=50,
                        target_chunk_size=200, overlap_size=0)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class MeetingMinutesStrategy(DocTypeStrategy):
    """
    会议纪要类文档。
    识别特征：与会人员名单 + '决定如下'/'形成以下决议'锚定词。
    overlap=0：每条决议项完全独立，不需要跨决议语境。
    """
    _PATTERNS = [
        r'会议纪要', r'与会人员', r'参会人员',
        r'决定如下[：:]', r'形成以下决议', r'议定事项',
    ]

    @property
    def doc_type(self) -> str: return '会议纪要'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=400, min_chunk_size=80,
                        target_chunk_size=300, overlap_size=0)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class PoliceDocStrategy(DocTypeStrategy):
    """
    公安/执法/案件/司法鉴定类文书。
    识别特征：
      - 执法动词（受案/立案/侦查/询问/传唤）
      - 当事人/嫌疑人描述
      - 法律依据引用（依据《XX法》第X条）
      - 公安机构标识（派出所/公安局/边防）
    关键参数设计：
      max_chunk_size=300 : 案情要素（人/时/地/事）应保持原子化，不宜跨事项合并
      overlap_size=0     : 不同当事人或案件步骤信息绝对独立，严禁跨案情 overlap
      min_chunk_size=60  : 执法步骤（"1.受案 经审查符合立案条件"）天然较短，不强制合并
    """
    _PATTERNS = [
        r'(受案|立案|侦查|审查|移送|归案)',
        r'(嫌疑人|当事人|被告人|受害人|证人)',
        r'(询问笔录|讯问笔录|到案经过|案情经过)',
        r'依据《.{2,15}法》',
        r'(派出所|公安局|公安分局|边防|警务)',
        r'(违反.*条款|依法处以|予以拘留|警告处罚)',
    ]

    @property
    def doc_type(self) -> str: return '公安文书'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=300, min_chunk_size=60,
                        target_chunk_size=200, overlap_size=0)

    def match_score(self, sample: str) -> float:
        # [加权策略] 执法动词命中给双倍权重（首屏出现概率高，更具区分度）
        # 普通命中正常权重，归一化到 [0, 1]
        total_weight = len(self._PATTERNS) * 2  # 每条模式最高2分
        score = 0
        header = sample[:500]  # 首屏（执法文书文号/案由通常在前500字）
        for p in self._PATTERNS:
            if re.search(p, header):
                score += 2   # 首屏命中：双倍
            elif re.search(p, sample):
                score += 1   # 全文命中：单倍
        return min(score / total_weight, 1.0)



class AnnouncementDocStrategy(DocTypeStrategy):
    """
    通告/公告/令/决定类文档。
    识别特征：「特此通告」「通告如下」「令第X号」「现公告如下」等套语。
    与 NoticeDocStrategy 的区别：
      - 通知（Notice）有明确受文对象（各XXX：），属于上下级行文；
      - 通告（Announcement）面向不特定公众，无固定受文对象，侧重禁令/告知。
    overlap=0：每条禁令/规定相互独立，严禁跨条款语义渗漏。
    """
    _PATTERNS = [
        r'特此通告',
        r'(通告|公告)如下[：:]?',
        r'(令|决定)\s*第\s*\d+\s*号',
        r'现(公告|通告)如下',
        r'依据.*予以通告',
        r'(违反|违法).*依法(处以|处罚)',
    ]

    @property
    def doc_type(self) -> str: return '通告'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        # max=300：通告条款简短精炼，不应跨条合并
        # min=50：禁令类条款天然短小，允许独立入库
        # overlap=0：跨条款语义隔离，第一条禁令不能污染第二条
        return ChunkCfg(max_chunk_size=300, min_chunk_size=50,
                        target_chunk_size=200, overlap_size=0)

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class PublicNoticeDocStrategy(DocTypeStrategy):
    """
    公示类文档（候选人公示/行政许可公示/招标公示/评优公示）。
    识别特征：「予以公示」「公示期为X天」「公示名单如下」「公示期间有异议」。
    关键参数设计：
      max_chunk_size=250 : 每条公示项（姓名+简历+拟任职位）通常100-200字，不应跨人合并
      min_chunk_size=40  : 允许短公示项（如仅有姓名+职位的简版公示）独立入库
      overlap_size=0     : 不同人的公示信息绝对独立，严禁跨人 overlap（隐私隔离）
    """
    _PATTERNS = [
        r'予以?公示',
        r'现将.*公示',
        r'公示期(为|：|:)\s*\d+',
        r'公示(名单|结果|内容)(如下|：)',
        r'公示期间.*有异议',
        r'(拟提拔|拟任|拟聘|拟录用).*公示',
    ]

    @property
    def doc_type(self) -> str: return '公示'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(
            max_chunk_size=250, min_chunk_size=40,
            target_chunk_size=180, overlap_size=0,
            # [C2] 人员信息边界触发词：遇到"姓名："/"拟任："等行时强制 flush，
            # 保证每位候选人/被公示人的信息独立成 chunk，防止多人信息被合并。
            # 根因：每行仅 20-30 字，远低于 min_chunk_size，_merge_pass1 会
            # 把多人信息强制向后合并，产生"张三+李四"混合 chunk，违反隐私隔离。
            person_boundary_words=[
                "姓名：", "姓名:", "拟任：", "拟任:", "拟提拔：", "拟提拔:",
                "拟聘：", "拟聘:", "拟录用：", "拟录用:", "人员姓名：",
            ]
        )

    def match_score(self, sample: str) -> float:
        return self._count_hits(sample, self._PATTERNS)


class DefaultDocStrategy(DocTypeStrategy):
    """
    通用兜底策略（Registry 无命中时自动使用）。
    参数保守均衡，适合无明显特征的混合型文档。
    match_score 永远返回 0.0，不参与竞争，只作为 Registry 最后防线。
    """

    @property
    def doc_type(self) -> str: return '通用'

    @property
    def chunk_cfg(self) -> ChunkCfg:
        return ChunkCfg(max_chunk_size=500, min_chunk_size=100,
                        target_chunk_size=350, overlap_size=50)

    def match_score(self, sample: str) -> float:
        return 0.0
