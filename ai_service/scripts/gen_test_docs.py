import os
import random
import sys
import subprocess
from datetime import datetime
from docx import Document
from docx.shared import Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

# 尝试导入 FPDF 并处理中文字体
try:
    from fpdf import FPDF
except ImportError:
    print("正在安装 fpdf2...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "fpdf2"])
    from fpdf import FPDF

# 业务语料增强
SENTENCE_TEMPLATES = [
    "关于{topic}的{action}已经进入{stage}阶段。",
    "针对{issue}问题，各部门必须落细落实{policy}，确保{goal}。",
    "在{tech}技术的赋能下，政务效率预计提升{percent}%。",
    "我们要构建以{core}为核心的治理体系，筑牢{security}防线。",
    "数字化转型不是简单的技术堆砌，而是{concept}的深度变革。",
    "会议强调，{dept}应在{time}前完成对{task}的全面摸排。",
    "根据《{law}》相关规定，对{behavior}行为将采取{punish}措施。",
    "我们要深刻认识到{topic}在提升政府治理现代化水平中的关键作用。",
    "各单位要加强数据全要素生命周期管理，建立健全{policy}相关机制。"
]

VOCAB = {
    "topic": ["数字政府云原生架构", "政务大模型私有化部署", "跨省通办业务协同", "非结构化数据治理", "政务数据要素流通", "智能检索中枢系统"],
    "action": ["专项调研", "试点建设", "全面推广", "效能评估", "深度重构", "全量替换"],
    "stage": ["关键攻坚", "平稳运行", "迭代优化", "收尾验收", "全面提质", "精细化管理"],
    "issue": ["基层减负不足", "数据共享壁垒", "系统响应延迟", "安全审计缺失", "跨部门协同脱节", "业务标准不统一"],
    "policy": ["‘五个一’原则", "全生命周期管控方案", "分级分类保护制度", "动态容错机制", "零信任安全架构", "标准化接入规范"],
    "goal": ["业务不中断", "全过程追溯", "数据零泄露", "群众获得感提升", "资源集约化利用", "响应速度大幅提升"],
    "tech": ["RAG向量检索", "Cross-Encoder重排", "混合kNN搜索", "多模态识别", "大模型意图理解", "知识增强推理"],
    "percent": ["15", "25", "40", "60", "80", "95"],
    "core": ["智能中台", "共性底座", "算力集群", "安全堡垒", "数据湖仓", "应用支撑平台"],
    "security": ["主权安全", "隐私计算", "内生安全", "链路可控", "数据底座安全", "关键基础设施保护"],
    "concept": ["服务理念", "组织架构", "资源配置", "管理模式", "运行机制", "评价体系"],
    "dept": ["综合协调组", "技术保障部", "数据运管中心", "办公室", "数字化转型领导小组", "政策研究室"],
    "time": ["本月底", "下季度中旬", "年底前", "今年上半年", "本周内", "近期"],
    "task": ["存量文档向量化", "系统漏洞修复", "用户权限审计", "多租户逻辑验证", "索引权重精细调优", "数据脱敏流程审计"],
    "law": ["数据安全法", "网络安全法", "政务信息公开条例", "数字政府十四五规划", "电子签名法", "关键信息基础设施保护条例"],
    "behavior": ["私自留存敏感数据", "违规调用接口", "系统停机维护不报备", "越权访问业务数据", "明文传输政务信息"],
    "punish": ["严肃处理", "限期整改", "全系统通报", "计入绩效考核", "依法追究责任", "列入负面清单"]
}

def generate_sentence():
    template = random.choice(SENTENCE_TEMPLATES)
    placeholders = {key: random.choice(values) for key, values in VOCAB.items()}
    return template.format(**placeholders)

def generate_document_content():
    content = []
    # 至少 30 个随机生成的句子，确保总字数稳超 500
    for _ in range(random.randint(30, 40)):
        content.append(generate_sentence())
    
    # 随机组合成 5-6 个段落
    random.shuffle(content)
    full_text = ""
    chunk_size = len(content) // 5
    for i in range(0, len(content), chunk_size):
        full_text += "".join(content[i:i+chunk_size]) + "\n\n"
    return full_text

def get_doc_info():
    dept = random.choice(["博扬智能化政务办公室", "政务数据中心", "数字转型专家组", "综合管理调研室", "数字化政务发展委员会"])
    year = 2024 + random.randint(0, 2)
    doc_no = f"博政办发〔{year}〕第{random.randint(1001, 9999)}号"
    date = f"{year}年{random.randint(1,12)}月{random.randint(1,28)}日"
    
    topics = ["数字政府建设", "语义搜索优化", "大规模向量入库", "政务大模型应用", "全链路安全审计", "混合云架构升级"]
    action_verbs = ["实施方案", "指导意见", "调研报告", "自查通知", "会议纪要", "评估简报"]
    title = f"关于{random.choice(topics)}的{random.choice(action_verbs)}"
    
    return {
        "header": "★ 内部资料 · 严禁外传 ★",
        "red_header": "博扬智能化政务办公室文件",
        "doc_no": doc_no,
        "title": title,
        "content": generate_document_content(),
        "dept": dept,
        "date": date
    }

def save_docx(path, info):
    doc = Document()
    # 红头样式
    p_header = doc.add_paragraph()
    p_header.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_header = p_header.add_run(info['red_header'])
    run_header.font.size = Pt(22)
    run_header.font.color.rgb = RGBColor(255, 0, 0) # 红色
    run_header.bold = True

    p_no = doc.add_paragraph()
    p_no.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_no = p_no.add_run(info['doc_no'])
    run_no.font.size = Pt(12)

    doc.add_heading(info['title'], level=1).alignment = WD_ALIGN_PARAGRAPH.CENTER
    
    p_content = doc.add_paragraph()
    p_content.add_run(info['content'])
    
    p_footer = doc.add_paragraph()
    p_footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    p_footer.add_run(f"\n{info['dept']}\n{info['date']}")
    
    doc.save(path)

def save_txt(path, info):
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"{info['header']}\n\n")
        f.write(f"{info['red_header']}\n")
        f.write(f"{info['doc_no']}\n\n")
        f.write(f"标题：{info['title']}\n\n")
        f.write(f"{info['content']}\n")
        f.write(f"\n落款单位：{info['dept']}\n")
        f.write(f"发文日期：{info['date']}\n")

def save_pdf(path, info):
    """生成带中文字体的 PDF"""
    pdf = FPDF()
    # 加载系统字体 (微软雅黑常规)
    font_path = "C:/Windows/Fonts/msyh.ttc"
    if not os.path.exists(font_path):
        # 兜底：如果 msyh.ttc 不存在，尝试 msyh.ttf
        font_path = "C:/Windows/Fonts/msyh.ttf"
        
    if os.path.exists(font_path):
        pdf.add_font("msyh", "", font_path)
        pdf.set_font("msyh", size=11)
    else:
        # 实在没有中文字体则退回 helvetica (会导致乱码，但防止崩溃)
        pdf.set_font("helvetica", size=11)

    pdf.add_page()
    
    # 红色红头
    pdf.set_text_color(255, 0, 0)
    pdf.set_font("msyh" if os.path.exists(font_path) else "helvetica", style="", size=20)
    pdf.cell(0, 20, txt=info['red_header'], ln=True, align='C')
    
    # 文号
    pdf.set_text_color(0, 0, 0)
    pdf.set_font("msyh" if os.path.exists(font_path) else "helvetica", size=12)
    pdf.cell(0, 10, txt=info['doc_no'], ln=True, align='C')
    pdf.ln(5)
    
    # 标题
    pdf.set_font("msyh" if os.path.exists(font_path) else "helvetica", size=16)
    pdf.cell(0, 15, txt=info['title'], ln=True, align='C')
    pdf.ln(5)
    
    # 正文
    pdf.set_font("msyh" if os.path.exists(font_path) else "helvetica", size=11)
    pdf.multi_cell(0, 8, txt=info['content'])
    pdf.ln(10)
    
    # 落款
    pdf.cell(0, 10, txt=info['dept'], ln=True, align='R')
    pdf.cell(0, 10, txt=info['date'], ln=True, align='R')
    
    pdf.output(path)

def main():
    target_dir = "mock_data"
    if not os.path.exists(target_dir):
        os.makedirs(target_dir)
        
    print(f"🚀 开始生成全格式中文测试集 (512 份)...")
    
    # 分配格式：pdf, docx, txt (各占 1/3)
    # 对于 .doc，由 .docx 替代或通过 txt 改名模拟旧系统漏洞测试
    for i in range(512):
        info = get_doc_info()
        rem = i % 4
        if rem == 0:
            file_name = f"gov_chinese_doc_{i+1}.docx"
            save_docx(os.path.join(target_dir, file_name), info)
        elif rem == 1:
            file_name = f"gov_chinese_doc_{i+1}.txt"
            save_txt(os.path.join(target_dir, file_name), info)
        elif rem == 2:
            file_name = f"gov_chinese_doc_{i+1}.pdf"
            save_pdf(os.path.join(target_dir, file_name), info)
        else:
            # 模拟旧版 .doc (解析器应能识别其内容)
            file_name = f"gov_chinese_legacy_{i+1}.doc"
            save_txt(os.path.join(target_dir, file_name), info)
            
        if (i+1) % 50 == 0:
            print(f"  [+] 累计产出: {i+1} 份")

    print("\n✅ 中文测试集生成任务圆满完成。")

if __name__ == "__main__":
    main()
