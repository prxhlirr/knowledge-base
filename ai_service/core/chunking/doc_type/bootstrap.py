"""
全局策略注册（项目启动时执行一次）。

==========================================================================
★ 在整个分片系统中，这是唯一需要维护的配置文件 ★

新增文档类型时，只需：
  Step 1: 在 strategies.py 新建策略类（继承 DocTypeStrategy）
  Step 2: 在此文件追加 .register(NewStrategy())
Bootstrap 以上的所有代码（registry.py / strategy.py / 各 chunker）永不修改。
==========================================================================
"""
from .registry import DocTypeRegistry
from .strategies import (
    LegalDocStrategy,
    NoticeDocStrategy,
    ReportDocStrategy,
    NewsDocStrategy,
    MeetingMinutesStrategy,
    PoliceDocStrategy,
    AnnouncementDocStrategy,    # [缺陷1 修复] 通告/令/决定类
    PublicNoticeDocStrategy,     # [缺陷1 修复] 公示类（候选人/行政许可/招标）
)

# 全局单例，对外暴露此对象
# 新增类型只在此处追加 .register()，Bootstrap 以上的代码永不修改
doc_type_registry = (
    DocTypeRegistry()
    .register(LegalDocStrategy())
    .register(NoticeDocStrategy())
    .register(AnnouncementDocStrategy())  # [缺陷1] 通告（面向公众禁令/告知，无固定受文对象）
    .register(PublicNoticeDocStrategy())  # [缺陷1] 公示（候选人/行政许可/招标，条目独立性极强）
    .register(ReportDocStrategy())
    .register(NewsDocStrategy())
    .register(MeetingMinutesStrategy())
    .register(PoliceDocStrategy())        # 公安/执法/案件文书专项策略
    # ↓ 未来新增类型只在此处追加，其余代码零修改
    # .register(BidDocStrategy())
    # .register(ContractDocStrategy())
)
