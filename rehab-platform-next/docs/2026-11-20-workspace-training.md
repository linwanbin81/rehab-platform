# 计划训练与指导复核：功能和开发记录

记录日期：2026-11-20。适用于 `rehab-platform-next/`。本次交付是整体重建设计中的一个阶段，保存单位为一组训练。

## 可演示流程

指导者登记基线并发布多动作计划；训练者选动作和组进入摄像头，看到实时骨架、角度及反馈；结束后 Java 复算并生成该组报告；指导者逐项复核目标偏离，保存总体意见并单独提交最终结论；训练者查看反馈。

默认打开摄像头试练页，顶部可切换训练工作台。初始账号、启动方式见项目 README。第二组指导关系用于验证互相不能访问训练记录。试练不绑定个人计划，报告可凭编号读取；正式计划报告要求登录，并检查训练者或当前指导关系。

## 本阶段功能边界

| 功能 | 已实现行为 | 后续工作 |
| --- | --- | --- |
| 账号与权限 | 三角色；管理员创建训练者/指导者、启停及分配；密码加盐哈希，8 小时登录，退出和停用撤销会话 | 密码修改、更多部署配置 |
| 档案与基线 | 训练侧、观察备注；数据库追加基线版本；新计划关联当时基线 | 页面查看旧基线、量化基线流程 |
| 计划 | 直接发布；多个动作、左右侧、组次、日期；目标比较配置幅度、节奏及保持 | 草稿预览、休息安排、发布版本替代和调整说明 |
| 摄像头 | 视频先开启，模型随后加载；骨架、实时角度曲线、校准、阶段、完整循环、暂停与恢复 | 更多机位验收、设备适配和性能测量 |
| 保存 | 服务端以计划配置复算，防止客户端改目标；同一采集标识重试不重复入库；登录失效可原页重登保存，下一组沿用新令牌 | IndexedDB 刷新恢复、分块上传、整会话状态 |
| 报告 | 逐组记录，显示每次幅度、耗时、保持、缺失可用帧及规则版本；无完整循环也明确显示 | 事件时间轴、骨架回放、可比较历史统计及导出 |
| 复核 | 对幅度/节奏/保持的失败分项确认或驳回；编辑未保存分项时禁止完成；总体意见单独保存或最终提交；数据库追加复核历史 | 历史查看、完成后的补充反馈、从报告调整计划 |

目标范围为空时不输出达标结论。关键关节遮挡、多人体、出画或投影过短时暂停可靠评价，缺失角度不作为 0°。计数是从起始、抬起到返回的完整循环，不按画面帧数计数。计划的期望次数用于展示，当前不按次数自动停止，也没有整计划完成率。

目前使用官方 MediaPipe Full 姿态模型及工程规则，没有自训练动作质量权重。二维机位不能覆盖所有动作代偿，没有实现临床疗效判定或可观察躯干偏移专项规则。动作说明附 AAOS 参考链接；尚无本地真人示范视频库。

## 目录与修改入口

```text
rehab-platform-next/
├─ start.ps1                     # 检查构建时间、启动 8081 / 8082
├─ README.md                     # 安装、账号和使用流程
├─ docs/                         # 本阶段记录
├─ web/
│  ├─ src/
│  │  ├─ App.vue                 # 导航、计划任务和下一组
│  │  ├─ Workspace.vue           # 三角色业务工作台
│  │  ├─ WorkspaceReport.vue     # 报告与复核表单
│  │  ├─ workspace.ts            # 类型、请求、计划及复核校验
│  │  ├─ CameraTraining.vue      # 摄像头采集、反馈、保存和恢复登录
│  │  ├─ camera.ts / pose.ts     # 设备流与真实模型推理、骨架绘制
│  │  ├─ motion.ts               # 角度、平滑、校准、计数和目标评价
│  │  ├─ plan-camera.ts          # 计划参数、请求标识与鉴权头
│  │  └─ AngleTrace.vue          # 角度曲线；不可见帧显示为断点
│  ├─ tests/                     # 规则和请求契约测试
│  └─ e2e/                       # 浏览器与独立数据库流程验证
└─ server/
   ├─ src/main/java/edu/course/rehabnext/
   │  ├─ WorkspaceAuth.java      # 密码、令牌和角色
   │  ├─ WorkspaceProfiles.java  # 账号、指导关系、基线
   │  ├─ WorkspacePlans.java     # 计划校验、事务发布及查询
   │  ├─ WorkspaceController.java
   │  ├─ Observation*.java       # 采集、复算、持久化及复核接口
   │  └─ MotionAnalysis.java     # Java 动作分析
   ├─ src/main/resources/db/migration/
   ├─ src/test/                  # 真实 HTTP 控制器、关系及越权测试
   └─ data/                      # 本机 H2 数据文件（不提交）
```

改界面和表单优先从对应 Vue 页面入手，改输入规则同步修改 TypeScript 类型、Java 校验及 HTTP 测试。动作算法变更需同步 `motion.ts` 与 `MotionAnalysis.java`，复算不能直接信任浏览器的最终次数或分数。数据库升级新增 V5 等迁移，已执行过的 V1–V4 不再改写。后续替换模型时，应补存模型标识、权重版本和机位配置，使旧报告可解释。

## 数据库关系

当前共 11 张业务表，另有 Flyway 版本表。

| 表 | 用途与关联 |
| --- | --- |
| `workspace_account` | 三角色账号及密码哈希 |
| `workspace_login_session` | 令牌摘要、到期时间，关联账号 |
| `workspace_trainee` | 训练者档案及当前指导者 |
| `workspace_assignment` | 指导关系变更记录及操作者 |
| `workspace_baseline` | 基线追加记录及作者 |
| `workspace_plan` | 训练者、作者、基线、日期 |
| `workspace_plan_item` | 计划中的动作、侧别、组次、目标和规则版本 |
| `training_observation` | 一组采集；关联计划明细、组号与训练者，保存关键点、使用目标和汇总 |
| `observation_repetition` | 每次完整循环，复合主键为报告与动作序号 |
| `observation_event_review` | 某次动作某个失败分项的确认/驳回历史 |
| `observation_report_review` | 总体意见及报告复核状态历史 |

同一保存请求使用稳定的 Idempotency-Key，内容相同返回原报告，内容冲突返回错误。指导关系变化后，读取权限按照当前关系计算，原始作者仍保留。训练记录和复核分开，保存总体意见只进入复核中；所有失败项已处理且有总体意见才能最终完成。

关键点现在以 JSON 存入报告，适合当前小规模演示；长时训练应升级为采集批次、独立组表与采集块表。目前数据库为 H2，MySQL 配置与迁移兼容尚未验收；服务端使用 Spring JDBC，尚未接入 MyBatis。

## 当前接口

以下路径均以 `/api/v1` 开头。登录后使用 `Authorization: Bearer <token>`。

| 方法和路径 | 用途 |
| --- | --- |
| `POST /workspace/login`、`GET /workspace/me`、`POST /workspace/logout` | 登录、当前身份、退出 |
| `GET /workspace/trainees`、`GET /workspace/guides` | 可管理训练者、指导者名单 |
| `POST /workspace/accounts`、`PATCH /workspace/accounts/{id}` | 创建、启停账号 |
| `PUT /workspace/trainees/{id}/guide` | 调整指导关系 |
| `PUT /workspace/trainees/{id}/baseline` | 追加基线 |
| `GET /plans`、`POST /plans` | 查询可见计划、事务发布计划和明细 |
| `POST /observations` | 关键点复算与保存；计划采集提交 planItemId、setNumber、frames |
| `GET /observations`、`GET /observations/{id}` | 可见计划报告列表、报告详情 |
| `GET /observations/{id}/review` | 当前人工复核结果 |
| `POST /observations/{id}/event-reviews` | 保存 repetition、metric、decision、note |
| `POST /observations/{id}/review` | 保存 note；finalize=true 时尝试完成复核 |

## 验证证据与后续优先级

前端有规则、生命周期和请求契约测试；后端测试验证数据库入库、读取、防重、归属、停用、基线及多动作发布。浏览器测试调用真实 getUserMedia、MediaPipe WASM 和模型，输入为官方示例图转换的视频；报告复核使用显式合成几何序列，只进入隔离内存数据库。浏览器自动化不证明真人识别准确率，下一阶段应按三个动作、左右侧、不同机位和遮挡场景做真人验收与误差记录。

1. 增加训练会话、组状态及计划完成判据，支持整次训练结束与取消。
2. 增加 IndexedDB 待上传批次与恢复流程，解决刷新、短时断网。
3. 为报告增加可定位事件、骨架时间轴与规则依据；补充参考资料和授权真人演示。
4. 增加计划版本调整、统计和报告导出；完成 MySQL 与关系约束验收。
5. 采集按人或独立场次划分的数据，训练 PyTorch 质量分类器并与规则基线对比。
6. 最后接入花朵辅助游戏和可选 ESP32/BNO055 数据源。

本阶段未实现的内容不得在课程总文档中写成已完成。整体重建设计和分阶段清单位于工作区 `docs/superpowers/`。
