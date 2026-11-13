# 康复训练辅助平台 GitHub 参考项目与复用方案

核查日期：2026 年 11 月 6 日。

建议以若依 Java 后端和 Vue 3 前端构建业务底座，以 MediaPipe 官方 Web 示例构建视觉接入层，再参考康复项目的动作规则、训练页面和研究代码。此次筛选没有核实到一个同时满足现有技术栈、完整业务闭环、三个肩肘动作及质量模型要求的成熟现成系统。

本次检查包括 GitHub 仓库元数据、README、目录和下列指定源码，尚未在本机安装、启动或复现实验。最近推送时间用于说明仓库活动，不等同于最新正式版本或质量保证；星标数量仅作背景信息。

## 推荐清单

| 项目 | 适合承担的部分 | 许可证 | 仓库活动与限制 |
| --- | --- | --- | --- |
| [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue) 与 [RuoYi-Vue3](https://github.com/yangzongzhuan/RuoYi-Vue3) | 最优先的业务底座。复用认证、角色菜单、操作日志、MyBatis 数据访问和代码生成；新增康复业务模块。 | 两者 MIT | 前端约 6802 星；后端最近推送 2026-08-26，前端 2026-09-16。注意 Vue3 仓库是前端，须配套后端。 |
| [MediaPipe Samples Web](https://github.com/google-ai-edge/mediapipe-samples-web) | 最优先的浏览器识别基础。参考 Pose、Hand、Canvas 叠加和 Worker 推理。 | Apache-2.0 | Google 官方示例，最近推送 2026-11-06；约 107 星。视觉基础组件不包含康复评价规则。 |
| [Kkriver smart-rehabilitation-system](https://github.com/Kkriver/smart-rehabilitation-system) | 最接近本项目流程的轻量页面参考。包含指导者任务、训练者任务、摄像头训练及记录页面。 | MIT | 最近推送 2025-10-13；0 星。Next.js 原型，数据用 localStorage，认证模拟，不能承担真实多用户后端。 |
| [sing1ee my-pose](https://github.com/sing1ee/my-pose) | 浏览器姿态对比、躯干方向归一化、多种姿态相似度与骨架可视化参考。 | MIT | 最近推送 2026-05-20；约 20 星。Next.js 与 TensorFlow.js，算法函数可适配到 Vue，但需要转换关键点数据结构。 |
| [Smart Physiotherapy Assistant](https://github.com/Yashaswini-Umesh-0304/Smart-Physiotherapy-Assistant) | 肩外展、肘屈伸规则、起始姿势检测、暂停、提示和 CSV 日志参考。 | MIT | 最近推送 2025-11-24；1 星。Python 单文件演示，使用旧 MediaPipe Solutions API；评分与缺失值处理必须重写。 |
| [RehabExerAssess](https://github.com/Kelly510/RehabExerAssess) | PyTorch 动作质量分类实验参考。具备预处理、GCN 模型、训练、推理、结果绘图和混淆矩阵。 | MIT | TNSRE 2023 论文官方实现，最近推送 2025-07-27；约 13 星。原环境 Python 3.7 / PyTorch 1.8，需单独适配。 |
| [Sports2D](https://github.com/davidpagnon/Sports2D) | 关节角度、轨迹、短缺口插值、滤波和结果导出的工程参考；可做离线比较工具。 | BSD-3-Clause | 最近推送 2026-11-05；约 344 星。Python 运动分析工具，当前代码使用 RTMLib 等依赖；与浏览器 MediaPipe 的骨架和算法不完全相同。 |

## 已核查的源码与重要限制

### 若依

- [Vue3 package.json](https://github.com/yangzongzhuan/RuoYi-Vue3/blob/master/package.json) 确认包含 Vue 3、Element Plus、ECharts、Pinia 和 Vite。
- [Spring Boot 3 分支 pom.xml](https://github.com/yangzongzhuan/RuoYi-Vue/blob/springboot3/pom.xml) 确认 Java 17、Spring Boot 3 和 MyBatis。建议课程项目先选该分支并固定提交；默认 master 已转到 Spring Boot 4，不能默认按旧版环境安装。
- 通用角色与部门数据范围不能直接代替“指导者只能查看自己负责的训练者”的业务关系校验。需新增指导关系，并在查询和详情接口统一检查。

### MediaPipe 官方 Web 示例

- [姿态页面逻辑](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/src/tasks/pose-landmarker.ts)
- [姿态 Worker](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/src/workers/pose-landmarker.worker.ts)
- [手部页面逻辑](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/src/tasks/hand-landmarker.ts)
- [姿态端到端测试](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/tests/pose-landmarker.spec.ts)

适配时封装为 Vue 的摄像头组件与视觉服务，分别管理模型加载、推理、骨架绘制及资源释放。手部检测用于游戏，人体姿态检测用于肩肘训练。

### 两个相似演示项目

[Kkriver 实时训练页面](https://github.com/Kkriver/smart-rehabilitation-system/blob/main/src/app/patient/training/live/page.tsx) 实际仅依据手腕是否高于肩部切换状态，并在抬起时加一；保存的 accuracy 按次数占十次任务的比例计算。因此可以参考页面组织和流程，不能把该指标作为动作质量准确率。须补充完整循环计数、幅度、节奏、置信度门控和服务器持久化。

[Smart Physiotherapy Assistant 的 main.py](https://github.com/Yashaswini-Umesh-0304/Smart-Physiotherapy-Assistant/blob/main/main.py) 确有肩外展和肘屈伸规则，但 calculate_rep_score 和 log_failed_rep 使用随机数生成分值；calc_angle 在可见性不足时返回 180 度，与真实伸直姿势混淆。不能沿用这两种做法，应保存可解释的分项指标，并为无法评价的观测使用独立状态。现有阈值只是演示代码设置，不应作为患者处方。

### 浏览器特征与训练模型

- [my-pose 方向归一化](https://github.com/sing1ee/my-pose/blob/main/app/lib/normPose.ts) 与 [多策略相似度](https://github.com/sing1ee/my-pose/blob/main/app/lib/simPose.ts) 可提供实现思路；与标准姿态相似并不自动等于达到本次计划要求。
- [RehabExerAssess 训练代码](https://github.com/Kelly510/RehabExerAssess/blob/main/train.py) 采用正确与错误二分类；若项目要求输出“幅度不足、节奏偏差”等标签，必须另行设计多标签输出和标注数据。
- 该研究代码处理的骨架关节数和来源包括 Kinect、Vicon 及其他数据，并非直接适配 MediaPipe 33 点。需要定义关节映射、坐标、采样、归一化和数据划分；不能直接加载权重就宣称适用于本系统。
- 自建实验必须保留独立测试集，按参与者隔离，不能直接把训练中用于选模型的验证结果作为最终测试结果。
- [Sports2D process.py](https://github.com/davidpagnon/Sports2D/blob/main/Sports2D/process.py) 可参考时间序列处理，但离线使用未来帧的处理不能直接照搬为实时反馈。

## 其他发现及取舍

- [Visolus](https://github.com/dngvmnh/Visolus)：Apache-2.0，包含 [肘屈伸与 DTW 示例](https://github.com/dngvmnh/Visolus/blob/main/src/virtual_assistance/elbow_flexion_extension_dtw.py)，可作扩展参考；最近推送 2024-09-28，整体为实验脚本，不优先作为平台底座。
- [RehabFlow](https://github.com/YaokaiZhang/RehabFlow)：MIT，有患者与医生工作区、训练会话及 FastAPI 源码，最近推送 2026-11-05；但还依赖 Next.js、PostgreSQL、Redis、Qdrant 和智能体相关服务，超出现有范围，建议只参考业务组织。
- [PhysioGo FYP-Demo](https://github.com/ak-abdullah/FYP-Demo)：README 描述了相近流程，但本次默认分支目录核查只见 README 和界面截图，未见其描述的前后端源码，不作为可复用基础。
- [hyperphysio](https://github.com/aleksamarusic/hyperphysio)：本次默认分支目录核查只见 README，不作为已提供完整训练代码的推荐项。

## 落地方式

建议代码按三个部分组织：

```text
rehab-platform/
  web/          若依 Vue3 前端，增加训练者工作区与实时跟练页
  server/       若依 Spring Boot 3 后端，新增康复业务模块
  algorithm/    自建特征处理、标注、PyTorch 训练与推理服务
```

1. 先运行若依前后端及 MySQL、Redis，验证训练者与指导者角色；再加入档案、动作库、计划和模拟训练结果，贯通报告与复核。
2. 独立运行官方 Pose Web 示例，验证本机浏览器、摄像头、WASM 与模型加载；先接一个肘屈伸动作，再扩展两个肩部动作。
3. 参考相似项目重写版本化状态机，加入可见性门控、平滑、阶段时长、完整循环和分项反馈。上传时间序列，完成事件回放。
4. 先建立规则和小型 MLP 基线；再按进度参考 RehabExerAssess 比较 GCN。公开研究数据与自采 MediaPipe 数据分开报告。
5. 最后接官方 Hand 示例，实现捏合或手掌开合控制花朵，并保存游戏记录。

核心自主工作仍是肩肘动作规则、质量模型与独立实验、计划及会话数据设计、问题证据回放、指导复核与版本追溯。复用代码保留原许可证和版权声明，在设计说明中列明来源与修改范围；数据、视频与模型权重另外核查各自使用条款。

## 按数据库课程要求调整的方案

允许 Python 承担全部视觉算法与实时反馈，Java 承担核心业务和数据库访问。课程重点应放在 ER 模型、关系模式、约束、SQL、事务、查询优化和数据权限上。无需为了语言比例把视觉算法改写为 Java。

推荐最终结构为 Vue 页面、Spring Boot 与 MyBatis 业务服务、Python 视觉与模型服务、MySQL 数据库。Python 服务返回动作特征和分析结果；Java 校验会话、用户归属和版本后统一入库，避免两个服务分别修改同一批业务表。算法调用在持久化事务之外完成，结果齐备后使用短事务写入会话、组记录、单次动作和问题事件；网络调用失败与数据库事务回滚分开处理。

若依可复用登录、菜单和通用维护功能；也可以自建较轻的 Spring Boot 后端，明确展示自己实现的 MyBatis 查询及事务。数据库工作量通过真实业务关系和验证体现，不按表的数量判断。

### 新增 Java 与 Python 协作参考 KinetoLive

[KinetoLive](https://github.com/bweleaveu/KinetoLive) 提供 Spring Boot 后端、Python FastAPI 模型服务和数据库实体，原始输入为 ESP32 与 BNO055 传感器数据。它与本项目的采集方式不同，但“Java 管业务与结果持久化，Python 管模型分析”的分工很接近。

本轮继续核查了实际源码，未安装运行：

- [MlServiceClient.java](https://github.com/bweleaveu/KinetoLive/blob/main/backend-spring/src/main/java/ro/licenta/kinetolive/ml/MlServiceClient.java)：Java 通过 HTTP / JSON 调用 Python，处理超时、异常和模型服务健康状态。
- [TherapySessionService.java](https://github.com/bweleaveu/KinetoLive/blob/main/backend-spring/src/main/java/ro/licenta/kinetolive/service/TherapySessionService.java)：训练会话与单次动作结果保存、事务注解、部分查询的数据归属检查。
- [TherapySession.java](https://github.com/bweleaveu/KinetoLive/blob/main/backend-spring/src/main/java/ro/licenta/kinetolive/entity/TherapySession.java)：训练会话关联训练者、目标动作和识别动作，保存起止时间、次数及质量结果。
- [pom.xml](https://github.com/bweleaveu/KinetoLive/blob/main/backend-spring/pom.xml)：实际使用 Java 21、Spring Boot 3、JPA 和 PostgreSQL，并非 MyBatis 与 MySQL。

截至本次核查，GitHub 未识别出许可证，默认分支目录也未见独立 LICENSE 文件。因此将它列为架构与数据关系参考，不作为已经确认可复制改造的开源底座；若要复制源码，需先获得明确授权。它仍属于个人项目，不能由目录完整推断为成熟生产系统。

### 数据库课程应展示的具体成果

| 课程内容 | 本项目中的落点 |
| --- | --- |
| 概念与逻辑设计 | ER 图、角色与指导关系、计划与明细、会话与组、单次动作与事件之间的关系；关键函数依赖及规范化说明。 |
| 完整性约束 | 主外键、必要非空、账号唯一、同组动作序号唯一；禁止产生无归属的动作和事件记录。 |
| 多表查询 | 计划执行详情、指导者负责用户的历史训练、单次动作的问题清单、报告汇总。 |
| 聚合统计 | 计划完成率、有效动作次数、按动作和日期统计的问题分布；区分无法评价与未达标。 |
| 事务一致性 | 一次训练结果涉及多表时统一提交；使用非法明细验证整体回滚；重复提交不重复增加动作结果。 |
| 索引与性能 | 为训练者与起始时间、会话与动作序号等访问路径设计索引；用 EXPLAIN 和有规模的测试数据说明选择理由。 |
| 权限与追溯 | 后端校验本人及指导关系；复核保留原始输出、人工决定和版本，不覆盖历史计划。 |

第一版优先交付数据库设计、建表脚本、典型查询、事务与权限验证，以及能运行的计划到复核闭环；随后完善视觉识别和分类模型。
