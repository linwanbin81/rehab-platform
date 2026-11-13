from pathlib import Path
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parent
OUT = ROOT / '交付文档'
OUT.mkdir(exist_ok=True)
doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.top_margin, sec.bottom_margin = Cm(1.9), Cm(1.8)
sec.left_margin, sec.right_margin = Cm(2.1), Cm(2.1)
sec.footer_distance = Cm(.8)
for grid in sec._sectPr.findall(qn('w:docGrid')):
    sec._sectPr.remove(grid)
for sty in doc.styles:
    for border in sty.element.findall('.//' + qn('w:pBdr')):
        border.getparent().remove(border)

def style_font(style, size, bold=False):
    style.font.name = 'Microsoft YaHei'
    style.font.size = Pt(size)
    style.font.bold = bold
    style.font.color.rgb = RGBColor(0, 0, 0)
    rf = style.element.get_or_add_rPr().get_or_add_rFonts()
    for key in ('ascii', 'hAnsi', 'eastAsia'):
        rf.set(qn('w:' + key), 'Microsoft YaHei')
    for key in ('asciiTheme', 'hAnsiTheme', 'eastAsiaTheme', 'cstheme'):
        rf.attrib.pop(qn('w:' + key), None)
    snap = OxmlElement('w:snapToGrid')
    snap.set(qn('w:val'), '0')
    style.element.get_or_add_pPr().append(snap)

style_font(doc.styles['Normal'], 10.5)
normal = doc.styles['Normal'].paragraph_format
normal.line_spacing = Pt(16.2)
normal.space_after = Pt(6)
normal.widow_control = True
style_font(doc.styles['Title'], 18, True)
doc.styles['Title'].paragraph_format.space_after = Pt(8)
doc.styles['Title'].paragraph_format.line_spacing = Pt(24)
for name, size in [('Heading 1', 13), ('Heading 2', 11)]:
    style_font(doc.styles[name], size, True)
    f = doc.styles[name].paragraph_format
    f.space_before, f.space_after = Pt(10), Pt(5)
    f.keep_with_next = True
    f.line_spacing = Pt(19)

def p(text, boldlead=None):
    para = doc.add_paragraph()
    if boldlead and text.startswith(boldlead):
        para.add_run(boldlead).bold = True
        para.add_run(text[len(boldlead):])
    else:
        para.add_run(text)
    return para

def h(text, level=1):
    return doc.add_paragraph(text, f'Heading {level}')

def table(headers, rows, widths):
    t = doc.add_table(rows=1, cols=len(headers))
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for col, width in zip(t.columns, widths):
        col.width = Cm(width)
    for c, width, txt in zip(t.rows[0].cells, widths, headers):
        c.width = Cm(width)
        c.text = txt
    repeat = OxmlElement('w:tblHeader')
    t.rows[0]._tr.get_or_add_trPr().append(repeat)
    for row in rows:
        cells = t.add_row().cells
        for c, width, txt in zip(cells, widths, row):
            c.width = Cm(width)
            c.text = txt
    for ri, row in enumerate(t.rows):
        trpr = row._tr.get_or_add_trPr()
        cant = OxmlElement('w:cantSplit')
        trpr.append(cant)
        for ci, c in enumerate(row.cells):
            c.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            pr = c._tc.get_or_add_tcPr()
            borders = OxmlElement('w:tcBorders')
            for edge in ('top','left','bottom','right'):
                el = OxmlElement('w:' + edge)
                for k, v in [('val','single'),('sz','4'),('color','D9D9D9')]:
                    el.set(qn('w:' + k), v)
                borders.append(el)
            pr.append(borders)
            margins = OxmlElement('w:tcMar')
            for edge, value in [('top',75),('bottom',75),('left',95),('right',95)]:
                el = OxmlElement('w:' + edge)
                el.set(qn('w:w'), str(value))
                el.set(qn('w:type'), 'dxa')
                margins.append(el)
            pr.append(margins)
            if ri == 0:
                sh = OxmlElement('w:shd')
                sh.set(qn('w:fill'), 'E8EEF3')
                pr.append(sh)
            for para in c.paragraphs:
                para.paragraph_format.space_after = Pt(0)
                para.paragraph_format.line_spacing = Pt(14.2)
                if ci == 0:
                    para.alignment = WD_ALIGN_PARAGRAPH.CENTER
                for run in para.runs:
                    run.font.size = Pt(9.5)
                    if ri == 0:
                        run.bold = True
    doc.add_paragraph().paragraph_format.space_after = Pt(0)
    return t

footer = sec.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = footer.add_run()
field = OxmlElement('w:fldSimple')
field.set(qn('w:instr'), 'PAGE')
run._r.addnext(field)

title = doc.add_paragraph('融合视觉姿态感知与动作质量评估的\n智能康复训练辅助平台设计与实现', 'Title')
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
sub = p('选题说明与实施规划')
sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
sub.paragraph_format.space_after = Pt(10)

h('一 课题概述与建设目标')
p('本课题拟开发面向肩肘上肢训练的康复训练辅助平台，以训练管理、动作质量分析和训练反馈为主线，面向训练者和指导者提供完整业务流程。训练者根据计划观看动作示范，使用普通摄像头跟练；系统提取人体关键点，完成动作计数、幅度与节奏分析，形成可回放的训练报告；指导者复核问题记录，并据此调整后续训练计划。手势控制花朵开合游戏作为附加互动训练模块。')
p('项目定位为课程研究原型，验证动作观察、辅助反馈和管理流程，不输出诊断或康复疗效结论。实际训练动作、目标范围与训练安排由专业人员确定；系统展示的角度为指定机位下的视觉估计值。')
h('二 业务流程与功能范围')
p('指导者建立档案 → 记录训练基线 → 制定并发布计划 → 训练者跟练 → 系统分析与反馈 → 生成报告与回放 → 指导者复核 → 调整下一阶段计划。')
table(['使用端', '主要功能', '形成的业务结果'], [
    ['训练者端', '查看今日计划、动作说明和示范视频；摄像头跟练、暂停和结束训练。', '会话、组记录、单次动作结果。'],
    ['训练者端', '查看次数、幅度、节奏提示、角度曲线、历史报告及问题回放。', '可追溯的训练过程与统计。'],
    ['指导者端', '管理负责的训练者，记录基线，设置次数、目标幅度、保持及休息时间。', '可发布、可执行的训练计划。'],
    ['指导者端', '查看报告，确认或驳回问题标注，补充反馈并调整后续计划。', '复核记录、反馈与计划版本。'],
    ['管理功能', '维护账号权限、动作模板、示范资源及规则版本。', '后台维护能力与数据访问控制。'],
    ['辅助游戏', '识别手掌开合或捏合，驱动花朵动画，记录时长和完成情况。', '关联训练者或训练会话的游戏记录。'],
], [2.0, 8.3, 6.5])
p('实施边界：业务界面重点建设训练者端和指导者端；管理员仅提供必要维护功能。优先做深实时训练、动作分析、报告回放和指导者复核。', '实施边界：')

doc.add_page_break()
h('三 技术路线与系统分工')
p('总体采用浏览器、Java 业务后端与 Python 模型服务协作的结构。浏览器通过 MediaPipe 提取姿态并展示实时反馈；Spring Boot 负责业务和结果管理；PyTorch 用于训练片段质量模型，训练完成后由 Python 服务提供推理接口。')
table(['技术层', '选用技术', '具体职责'], [
    ['前端', 'Vue 3、Element Plus、ECharts', '角色页面、计划与报告、摄像头画面、骨架叠加、角度曲线及回放时间轴。'],
    ['姿态与手势', 'MediaPipe Pose Landmarker\nMediaPipe Hand Landmarker', 'Pose 提取肩肘腕髋等关键点及可见性；Hand 单独用于花朵游戏手部交互。'],
    ['业务后端', 'Java、Spring Boot、MyBatis', '认证授权、指导关系、计划发布、会话管理、结果校验、复核、统计和报告导出。'],
    ['模型服务', 'Python、PyTorch、FastAPI', '模型训练、特征处理与动作片段推理；输出预测标签、概率及模型版本。'],
    ['数据存储', 'MySQL 与文件目录', '关系数据与结构化结果入库；示范资源、模型文件及特征数据文件按需存储。'],
], [2.0, 5.1, 9.7])
p('实时画面和骨架在浏览器中叠加；关键点与特征按时间戳批量上传，动作片段结束后调用模型，避免逐帧请求模型接口。前端依据同一规则配置即时提示，Java 对上传特征复算并形成持久化结果。规则输出与模型输出分别保存，便于对照与人工复核。')
h('四 三个动作的设计')
p('先实现单侧动作，左右侧通过配置复用；采用固定机位和统一示范。各动作共用关键点处理与计数框架，评价参数由计划绑定的规则版本确定。')
table(['动作', '建议机位与主要特征', '计数与反馈重点'], [
    ['肩关节前屈抬臂', '侧面；肩—肘连线与肩—髋躯干方向的投影夹角。', '下垂→抬起→返回；目标幅度、阶段耗时、可见的躯干前后偏移。'],
    ['肩关节外展抬臂', '正面；上臂与躯干的投影夹角、肩髋中心线变化。', '起始→外展→返回；目标幅度、节奏、可见的躯干侧倾。'],
    ['肘关节屈伸', '侧面；肩—肘—腕三点夹角及上臂相对躯干的位置。', '伸展→屈曲→伸展；屈伸幅度、完整循环、节奏与上臂位置变化。'],
], [3.0, 6.6, 7.2])
p('开始前完成机位检查和短时起始姿势校准。目标区间、阶段时长、可见性阈值及偏移阈值均配置化，并在预实验后固定。单目视频仅评价当前视角可观察的动作，不将投影角度直接等同于临床关节活动度。')

doc.add_page_break()
h('五 动作分析与回放的实现')
p('1 关键点处理：按时间戳采样，对肩、肘、腕、髋等关键点进行可见性与出画检查；以躯干中心及尺度归一化，对位置或角度做平滑。对长时间间断重新校准，避免在无可靠观测时继续计数。', '1 关键点处理：')
p('2 动作状态机：实现“起始—运动—目标区间—返回”阶段转换；以不同进入、退出阈值及最短持续时间抑制抖动。仅在完成规定阶段后计数，记录未完成尝试；短暂遮挡时冻结评价，恢复后先确认当前阶段，超时则丢弃本次未完成循环。', '2 动作状态机：')
p('3 分项质量评价：分别记录幅度达标、动作完整性、阶段耗时、目标区间保持时间及可观察的躯干偏移。识别可靠性不足时标为“无法评价”，不计入有效评价分母。提示加入持续时间、冷却时间和优先级，避免重复刷屏。', '3 分项质量评价：')
p('4 证据与复核：保存单次动作起止时间、峰值角度、阶段耗时和问题事件；特征与骨架统一使用会话相对时间。点击事件跳转到对应骨架和角度曲线，显示触发规则；指导者确认、驳回或补充备注，保留系统原始判断和人工复核记录。', '4 证据与复核：')
h('六 动作质量模型与实验安排')
p('模型任务：对完整动作片段进行辅助质量分类。输入采用角度、相对位移、速度及阶段耗时等特征；先训练小型 MLP，样本量允许时再比较 1D CNN。各动作可分别建模，共用训练与推理流程。输出以幅度未达预设区间、节奏偏差等多标签为主，不预测疾病或疗效。', '模型任务：')
p('数据与标注：先小规模试采，检查机位和标签可区分性，再按可招募情况采集约 12～20 名志愿者、约 600～1200 个动作循环，作为初步目标而非已完成数据。健康志愿者在舒适范围内演示任务；每类包含达标与预设偏差样本。编写标注说明，对部分样本进行双人复核；无法可靠判断的片段单独标记。规则自动标签只能辅助预标注，测试标签需独立复核。', '数据与标注：')
p('实验设计：按参与者隔离训练、验证和测试集，同一人的所有场次进入同一集合；仅用训练集拟合归一化参数，在验证集选模型与阈值，测试集留到最终评价。记录计数绝对误差、分标签 Precision／Recall／F1、Macro F1、标签混淆矩阵、有效跟踪比例及反馈延迟，比较规则方法与模型方法。样本不足时报告探索性结果及局限。', '实验设计：')
h('七 数据组织与版本追溯')
p('建议围绕 16 类核心数据建表：用户、指导关系、训练基线、动作模板、规则版本、训练计划、计划明细、训练会话、组记录、单次动作结果、问题事件、特征数据索引、指导复核、计划调整、模型版本、游戏记录。初期角色可作为用户属性，报告由结果聚合生成。')
p('会话固定关联计划版本、规则版本、特征格式版本和模型版本；已发布计划发生调整时生成新版本。默认保存关键点和特征，不保存原始摄像头视频；模型采集另行取得参与者同意。所有查询由后端校验本人或指导关系，骨架数据同样按训练记录保护。')

doc.add_page_break()
h('八 开发顺序与阶段交付')
p('以下按 10 周规划，可根据课程期限压缩。第一阶段使用明确标记的模拟训练数据，先验证完整业务；模拟数据不进入模型实验或最终性能结论。')
table(['时间', '实施内容', '阶段验收'], [
    ['第 1 周', '确认三动作定义、机位和标签；绘制原型、数据库关系和接口清单；完成姿态试采。', '能稳定取得目标关节；范围、状态和数据结构明确。'],
    ['第 2～3 周', '搭建前后端和 MySQL；实现权限、指导关系、动作库、基线与计划发布。', '指导者发布计划，训练者只看到自己的任务。'],
    ['第 4 周', '用模拟特征完成会话、组记录、报告、事件定位、复核和计划调整。', '一条计划从发布到执行、复核及下一版本完整贯通。'],
    ['第 5～6 周', '接入真实姿态；先实现肘屈伸，再扩展两种抬臂；完成计数、遮挡处理和曲线。', '三动作分别支持达标演示、预设偏差与遮挡测试。'],
    ['第 7～8 周', '整理采集标注、冻结数据划分；训练 MLP，接入片段推理并完成规则对比。', '交付数据说明、训练脚本、模型文件和独立测试结果。'],
    ['第 9 周', '完善真实数据的骨架回放、复核留痕、阶段统计和报告导出；加入花朵游戏。', '问题事件准确定位；复核和计划调整可追溯。'],
    ['第 10 周', '完成流程联调、权限与异常检查、性能测量、文档整理和答辩演示。', '系统可部署，关键流程可复现，结果与限制表述一致。'],
], [2.1, 8.2, 6.5])
h('九 验收重点与预期成果')
p('业务验收：两类业务角色完成计划、跟练、报告、复核和调整流程；越权查看被拒绝；暂停、结束、重复上传等情况不造成重复记录。计划完成率按已完成任务／应完成任务统计，并明确日期口径。', '业务验收：')
p('识别验收：以人工标记次数和事件时间点为参照，分动作报告计数误差、提示效果与反馈延迟；测试正常、幅度未达目标、节奏偏差、短暂遮挡和出画情形。识别中断不产生虚假计数，报告区分有效动作、未完成尝试与无法评价片段。', '识别验收：')
p('成果交付：Vue 前端、Spring Boot 后端、Python 模型服务及部署说明；数据库结构和初始化数据；三个动作的版本化评价配置；采集标注说明、训练与测试脚本、模型及实验结果；可回放报告、指导复核记录和一个花朵互动游戏。', '成果交付：')
p('答辩演示：指导者发布计划 → 训练者完成动作并演示一次预设偏差 → 系统生成报告 → 点击问题查看骨架与曲线 → 指导者复核并发布调整后的计划 → 训练者进入花朵游戏。', '答辩演示：')
p('课题重点：以完整训练流程体现软件工程工作量，以可解释的动作规则、片段质量模型及可复核证据体现技术深度。', '课题重点：')

doc.core_properties.title = '融合视觉姿态感知与动作质量评估的智能康复训练辅助平台设计与实现'
doc.core_properties.subject = '选题说明与实施规划'
doc.core_properties.author = ''
doc.core_properties.keywords = '康复训练,姿态感知,动作质量评估,Spring Boot,Vue,MediaPipe,PyTorch'
target = OUT / '康复训练辅助平台_选题说明与实施规划.docx'
doc.save(target)
print(target)
