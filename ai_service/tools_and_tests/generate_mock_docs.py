import os
import random
import datetime
import difflib
import string
from pathlib import Path

# 尝试导入所需库
try:
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.shared import Pt, RGBColor
    DOCX_AVAILABLE = True
except ImportError:
    DOCX_AVAILABLE = False
    print("警告：python-docx未安装，将无法生成docx文件。")

try:
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer
    from reportlab.lib.units import mm
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.lib.fonts import addMapping
    PDF_AVAILABLE = False  # 稍后检查字体
except ImportError:
    PDF_AVAILABLE = False
    print("警告：reportlab未安装，将无法生成pdf文件。")

try:
    import win32com.client
    WIN32_AVAILABLE = True
except ImportError:
    WIN32_AVAILABLE = False
    print("警告：pywin32未安装，将无法生成真正的doc文件（将使用docx替代）。")

# 配置目标目录
TARGET_DIR = r"C:\Users\liyz\Desktop\tmp"
Path(TARGET_DIR).mkdir(parents=True, exist_ok=True)

# 随机种子（可选）
random.seed(42)  # 注释掉以获得真正随机

# ================== 数据模板 ==================
# 单位列表
UNITS = [
    "北京市教育局", "上海市财政局", "广东省发展和改革委员会",
    "江苏省人力资源和社会保障厅", "浙江省卫生健康委员会",
    "安徽省住房和城乡建设厅", "福建省交通运输厅", "江西省水利厅",
    "山东省农业农村厅", "河南省商务厅", "湖北省文化和旅游厅",
    "湖南省生态环境厅", "广东省市场监督管理局", "广西壮族自治区税务局",
    "海南省公安厅", "重庆市司法局", "四川省民政厅",
    "贵州省民族宗教事务委员会", "云南省审计厅", "陕西省统计局"
]

# 上级单位
SUPERIOR_UNITS = [
    "国务院", "省政府", "市政府", "教育部", "财政部", "发改委",
    "人力资源和社会保障部", "卫生健康委", "住建部", "交通运输部"
]

# 文种
DOC_TYPES = ["通知", "报告", "请示", "批复", "函", "纪要", "意见", "决定", "通报", "规定"]

# 事项（主题）
TOPICS = [
    "2023年度工作总结", "2024年预算编制", "安全生产检查", "环境保护督查",
    "教育改革试点", "财政专项资金管理", "人才引进计划", "科技创新项目",
    "乡村振兴战略", "疫情防控措施", "营商环境优化", "数字化转型",
    "法治政府建设", "民生实事工程", "重大项目推进", "招商引资工作",
    "信访维稳工作", "食品药品安全监管", "防汛抗旱工作", "森林防火"
]

# 会议名称
MEETINGS = [
    "市政府常务会议", "全省经济工作会议", "全国教育大会", "安全生产电视电话会议",
    "环保督察反馈会", "财政工作座谈会", "人才工作领导小组会议", "科技创新大会"
]

# 领导小组名称
LEADER_GROUPS = [
    "工作领导小组", "专项工作组", "协调小组", "指挥部", "办公室"
]

# 负责人姓氏
SURNAMES = ["张", "王", "李", "刘", "陈", "杨", "赵", "黄", "周", "吴"]
NAMES = ["伟", "强", "丽", "敏", "静", "涛", "军", "勇", "杰", "婷"]

# 数字
def random_num(min=1, max=100):
    return str(random.randint(min, max))

def random_date(start_year=2020, end_year=2024):
    year = random.randint(start_year, end_year)
    month = random.randint(1, 12)
    day = random.randint(1, 28)  # 避免2月问题
    return f"{year}年{month}月{day}日"

# ================== 段落生成函数 ==================
def generate_opening():
    templates = [
        "根据{superior}《{file_name}》（{doc_no}）要求，结合我市实际，现就{topic}通知如下：",
        "为了{purpose}，经{meeting}研究同意，现将{topic}有关事项通知如下：",
        "按照{meeting}精神，现就{topic}提出如下意见：",
        "{superior}印发了《{file_name}》，现转发给你们，请结合实际认真贯彻落实。",
        "为深入贯彻落实{policy}，进一步推动{work}，现就有关事项通知如下："
    ]
    template = random.choice(templates)
    superior = random.choice(SUPERIOR_UNITS)
    file_name = f"关于{random.choice(TOPICS)}的{random.choice(DOC_TYPES)}"
    doc_no = f"{random.choice(['国发','省发','市发'])}〔{random.randint(2020,2024)}〕{random.randint(1,99)}号"
    topic = random.choice(TOPICS)
    purpose = random.choice(["加强管理", "提高效率", "规范流程", "促进发展", "保障安全"])
    meeting = random.choice(MEETINGS)
    policy = random.choice(["新发展理念", "高质量发展要求", "国家战略", "上级部署"])
    work = random.choice(TOPICS)
    return template.format(superior=superior, file_name=file_name, doc_no=doc_no,
                           topic=topic, purpose=purpose, meeting=meeting,
                           policy=policy, work=work)

def generate_body_paragraph():
    templates = [
        "一、提高思想认识。{importance}各部门要充分认识{topic}的重要意义，切实增强责任感和紧迫感。",
        "二、明确工作重点。{key_points}要围绕{target}，突出{area}，抓好{key_link}。",
        "三、加强组织领导。成立{leader_group}，由{leader}担任组长，{members}为成员。各部门要密切配合，形成合力。",
        "四、强化责任落实。{responsibility}对工作不力、进展缓慢的单位将予以通报批评。",
        "五、严格时间节点。{time_limit}各相关单位务必于{deadline}前完成阶段性任务。",
        "六、加大宣传力度。{publicity}充分利用各类媒体，营造良好氛围。",
        "七、完善保障措施。{support}在资金、人员等方面给予优先保障。",
        "八、建立长效机制。{mechanism}定期召开协调会议，研究解决突出问题。"
    ]
    template = random.choice(templates)
    importance = random.choice(["这是当前一项紧迫任务，", "这是关系全局的重要工作，", "这是推动发展的关键举措，", ""])
    topic = random.choice(TOPICS)
    key_points = random.choice(["要抓住重点环节，", "要聚焦突出问题，", "要明确主攻方向，", ""])
    target = random.choice(["年度目标", "预期效果", "高质量发展", "群众满意"])
    area = random.choice(["重点领域", "关键环节", "薄弱环节", "核心业务"])
    key_link = random.choice(["政策落地", "项目实施", "监督检查", "考核评估"])
    leader_group = random.choice(LEADER_GROUPS)
    leader = random.choice(SURNAMES) + random.choice(NAMES)
    members = random.choice(["相关职能部门", "各镇（街）", "业务科室", "下属单位"])
    responsibility = random.choice(["建立责任清单，", "签订责任书，", "实行网格化管理，", ""])
    time_limit = random.choice(["倒排工期，", "挂图作战，", "明确时间表，", ""])
    deadline = random_date()
    publicity = random.choice(["及时报道进展，", "推广典型经验，", "加强政策解读，", ""])
    support = random.choice(["加大财政投入，", "优化资源配置，", "加强业务指导，", ""])
    mechanism = random.choice(["完善联席会议制度，", "建立信息共享平台，", "健全考核机制，", ""])
    return template.format(importance=importance, topic=topic, key_points=key_points,
                           target=target, area=area, key_link=key_link,
                           leader_group=leader_group, leader=leader, members=members,
                           responsibility=responsibility, time_limit=time_limit,
                           deadline=deadline, publicity=publicity, support=support,
                           mechanism=mechanism)

def generate_closing():
    templates = [
        "请各单位于{date}前将落实情况报{unit}。",
        "特此通知。",
        "以上请示，妥否，请批示。",
        "请认真贯彻执行。",
        "此复。",
        "请结合本地实际，抓好落实。"
    ]
    template = random.choice(templates)
    date = random_date()
    unit = random.choice(UNITS)
    return template.format(date=date, unit=unit)

# ================== 生成完整公文文本 ==================
def generate_document_text(index):
    """生成一份公文的完整文本，返回 (标题, 全文)"""
    # 随机选择单位、文种、主题
    unit = random.choice(UNITS)
    doc_type = random.choice(DOC_TYPES)
    topic = random.choice(TOPICS)
    # 构造红头、文号、标题
    red_head = f"{unit}文件"
    doc_no = f"{random.choice(['X发','X办发','X政发'])}〔{random.randint(2020,2024)}〕{random.randint(1,999)}号"
    title = f"关于{topic}的{doc_type}"
    
    # 正文段落
    paragraphs = []
    # 开头段
    paragraphs.append(generate_opening())
    # 主体段：3-6段
    body_count = random.randint(3, 6)
    for _ in range(body_count):
        paragraphs.append(generate_body_paragraph())
    # 结尾段
    paragraphs.append(generate_closing())
    
    # 组装全文
    full_text = red_head + "\n" + doc_no + "\n" + title + "\n\n"
    for para in paragraphs:
        full_text += para + "\n\n"
    
    # 统计字数（中文字符）
    # 简单统计：去除空白后统计汉字和标点
    char_count = len([c for c in full_text if '\u4e00' <= c <= '\u9fff' or c in '，。；：！“”【】（）'])
    # 如果字数不足500，再添加一个段落
    while char_count < 500:
        extra = generate_body_paragraph()
        full_text += extra + "\n\n"
        char_count = len([c for c in full_text if '\u4e00' <= c <= '\u9fff' or c in '，。；：！“”【】（）'])
    
    return title, full_text

# ================== 相似度检查 ==================
def similarity(a, b):
    """计算两个字符串的相似度（0-1）"""
    return difflib.SequenceMatcher(None, a, b).ratio()

def is_too_similar(new_text, existing_texts, threshold=0.8):
    """检查新文本是否与已有任何文本相似度过高"""
    for text in existing_texts:
        if similarity(new_text, text) > threshold:
            return True
    return False

# ================== 生成100份不重复的文本 ==================
print("开始生成100份公文文本...")
documents = []  # 存储 (标题, 全文)
titles = []
texts = []
max_attempts = 200
for i in range(100):
    attempts = 0
    while attempts < max_attempts:
        title, text = generate_document_text(i)
        if not is_too_similar(text, texts, 0.8):
            documents.append((title, text))
            titles.append(title)
            texts.append(text)
            print(f"已生成第 {len(documents)} 份")
            break
        attempts += 1
    else:
        # 如果尝试多次仍失败，强制接受
        print(f"警告：第 {i+1} 份文本经过多次尝试仍无法满足相似度要求，强制接受")
        documents.append((title, text))
        titles.append(title)
        texts.append(text)
print("文本生成完成。")

# ================== 保存文件 ==================
# 确定格式分配
total = 30
txt_count = 0
doc_count = 0
docx_count = 0
pdf_count = 30
# 打乱顺序，使格式随机分布
formats = ['txt'] * txt_count + ['doc'] * doc_count + ['docx'] * docx_count + ['pdf'] * pdf_count
random.shuffle(formats)

# 保存函数
def save_txt(content, path):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

def save_docx(content, path):
    if not DOCX_AVAILABLE:
        print(f"无法生成docx: {path}，请安装python-docx")
        return False
    doc = Document()
    # 解析内容行（简单处理，假设按行分割）
    lines = content.split('\n')
    # 红头（第一行）
    if lines:
        p = doc.add_paragraph()
        run = p.add_run(lines[0])
        run.font.color.rgb = RGBColor(255, 0, 0)
        run.font.size = Pt(22)
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    # 文号（第二行）
    if len(lines) > 1:
        p = doc.add_paragraph()
        run = p.add_run(lines[1])
        run.font.size = Pt(14)
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    # 标题（第三行）
    if len(lines) > 2:
        p = doc.add_paragraph()
        run = p.add_run(lines[2])
        run.font.size = Pt(16)
        run.bold = True
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    # 正文（从第四行开始）
    for line in lines[3:]:
        if line.strip():
            p = doc.add_paragraph(line.strip())
            p.paragraph_format.first_line_indent = Pt(24)  # 首行缩进
    doc.save(path)
    return True

def save_doc(content, path):
    """尝试用win32com生成真正的doc，否则用docx替代"""
    if WIN32_AVAILABLE:
        try:
            word = win32com.client.Dispatch("Word.Application")
            word.Visible = False
            doc = word.Documents.Add()
            # 添加内容（需要处理格式，这里简化，只添加文本）
            selection = word.Selection
            selection.TypeText(content)
            # 设置红头颜色（需要更复杂的操作，这里略）
            doc.SaveAs(path, FileFormat=0)  # 0 = wdFormatDocument
            doc.Close()
            word.Quit()
            return True
        except Exception as e:
            print(f"win32com保存doc失败: {e}，尝试使用docx替代")
            # 保存为docx并改名
            docx_path = path.replace('.doc', '.docx')
            if save_docx(content, docx_path):
                os.rename(docx_path, path)
                return True
            return False
    else:
        # 无win32com，直接保存为docx并改名
        docx_path = path.replace('.doc', '.docx')
        if save_docx(content, docx_path):
            os.rename(docx_path, path)
            return True
        return False

def save_pdf(content, path):
    if not PDF_AVAILABLE:
        print(f"无法生成pdf: {path}，请安装reportlab并配置中文字体")
        return False
    try:
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
        font_name = 'STSong-Light'
        
        doc = SimpleDocTemplate(path, pagesize=A4)
        styles = getSampleStyleSheet()
        # 创建自定义样式
        style_normal = ParagraphStyle(
            'Normal',
            parent=styles['Normal'],
            fontName=font_name,
            fontSize=12,
            leading=16,
            firstLineIndent=24,
            spaceAfter=6
        )
        style_red = ParagraphStyle(
            'RedHead',
            parent=styles['Normal'],
            fontName=font_name,
            fontSize=22,
            leading=28,
            alignment=1,  # 居中
            textColor='red'
        )
        style_center = ParagraphStyle(
            'Center',
            parent=styles['Normal'],
            fontName=font_name,
            fontSize=14,
            alignment=1,
            spaceAfter=6
        )
        style_title = ParagraphStyle(
            'Title',
            parent=styles['Normal'],
            fontName=font_name,
            fontSize=16,
            alignment=1,
            bold=True,
            spaceAfter=12
        )
        
        story = []
        lines = content.split('\n')
        # 红头
        if lines:
            story.append(Paragraph(lines[0], style_red))
            story.append(Spacer(1, 6*mm))
        # 文号
        if len(lines) > 1:
            story.append(Paragraph(lines[1], style_center))
            story.append(Spacer(1, 6*mm))
        # 标题
        if len(lines) > 2:
            story.append(Paragraph(lines[2], style_title))
            story.append(Spacer(1, 6*mm))
        # 正文
        for line in lines[3:]:
            if line.strip():
                story.append(Paragraph(line.strip(), style_normal))
                story.append(Spacer(1, 2*mm))
        doc.build(story)
        return True
    except Exception as e:
        print(f"生成pdf失败: {e}")
        return False

# 循环生成文件
print("开始生成文件...")
for idx, ((title, text), fmt) in enumerate(zip(documents, formats)):
    # 构造文件名，去除非法字符
    safe_title = "".join(c for c in title if c not in r'\/:*?"<>|').strip()
    if not safe_title:
        safe_title = f"公文_{idx+1:03d}"
    filename = f"{safe_title}_{idx+1:03d}.{fmt}"
    filepath = os.path.join(TARGET_DIR, filename)
    
    if fmt == 'txt':
        save_txt(text, filepath)
        print(f"已生成txt: {filename}")
    elif fmt == 'doc':
        if save_doc(text, filepath):
            print(f"已生成doc: {filename}")
        else:
            print(f"生成doc失败: {filename}")
    elif fmt == 'docx':
        if save_docx(text, filepath):
            print(f"已生成docx: {filename}")
        else:
            print(f"生成docx失败: {filename}")
    elif fmt == 'pdf':
        if save_pdf(text, filepath):
            print(f"已生成pdf: {filename}")
        else:
            print(f"生成pdf失败: {filename}")

print("所有文件生成完毕！")