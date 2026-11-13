# 舒展 · 康复训练辅助平台 0.2

选题：《融合视觉姿态感知与动作质量评估的智能康复训练辅助平台设计与实现》。

当前主线为**摄像头动作观察 → 个体目标反馈 → 报告与关键点回放 → 指导者复核 → 调整后续计划**。支持肩前屈、肩外展、肘屈伸，动作说明分别链接 AAOS / ASES 原文。Java 负责业务、权限、事务和数据库；Python 对真实采集的关键点重新计算动作结果。

## 在这台电脑运行

打开 <http://127.0.0.1:8080/>。如未启动，双击 `启动平台.cmd`。

| 身份 | 账号 | 密码 |
| --- | --- | --- |
| 训练者 | trainee | Demo123! |
| 指导者 | teacher | Demo123! |
| 管理员 | admin | Demo123! |

VS Code 请打开 **rehab-platform** 文件夹，阅读 [运行与修改指南](docs/VSCode运行与修改指南.md)。修改后执行：

```powershell
.\stop.ps1
.\start.ps1 -NoBrowser
```

先停止再构建，避免 Windows 锁定正在运行的 jar。只有启动已有构建时才用 `-SkipBuild`。启动脚本不会停止其他项目进程。

## 体验顺序

1. 使用训练者账号，进入“我的训练计划”，点击一个已发布计划。
2. 选择动作组、训练侧，阅读机位要求、动作步骤及官方图示。默认“仅记录动作”，不判定幅度或节奏是否达标。
3. 如需比较个体目标，先与指导者核对目标幅度、完整循环耗时、保持时长，再切换方式并勾选确认。这是训练者声明，系统没有核验临床处方。
4. 点击“开启摄像头”，在浏览器允许本机网站使用摄像头。预览不计数；点击“开始本组”，稳定舒适起始姿势约 1 秒，再做动作。
5. 本组完成后点击“结束本组”，按计划休息并选择下一组。也可暂停；长时间遮挡或暂停后需要重新稳定起始姿势。疼痛或不适时停止。
6. 选填训练后自报疼痛、结束原因，点击“结束并保存报告”。未做的组保留为未采集，不补成已完成。每次最多 6000 帧、15 组，约 8Hz 采样。
7. 报告包含可靠观测时间、逐动作统计、个体目标快照、来源、模型和规则版本。点击问题定位关键点回放；支持 CSV 及浏览器“打印 / 保存 PDF”。只保存关键点，不保存原始视频。
8. 切换指导者查看报告，确认或驳回提示、添加反馈、调整下一版本计划。个体循环耗时可以配置，包含保持阶段。

历史模拟计划和报告保留。报告标注 `SIMULATED` 或 `CAMERA` 来源；模拟接口保留供开发回归使用，训练页面已改为摄像头采集。旧计划的文字说明不会自动改写，训练页以新版有来源的动作目录为准。

## 已完成与实际边界

- 三角色权限、指导关系、计划与版本管理、真实关系数据库事务；默认 H2 文件库，可切换 MySQL。
- 本地 MediaPipe Pose Landmarker Full 预训练权重，33 个关键点及可见性；本地 WASM，无须运行时访问 CDN。
- 几何角度、平滑、起始校准、完整循环计数、遮挡处理及分项反馈；浏览器即时结果由 Python 在保存时复算。
- 仅观察模式的质量字段为 NULL，缺失角度为 NULL，不用零或“正常”补齐。躯干偏移是待复核事件，不宣称躯干稳定已达标。
- 每次保存计划快照、模型信息、来源版本、关键点证据和人工意见。
- 花朵互动保留为辅助模块，目前仍为鼠标 / 键盘输入。
- **尚无经过独立标注训练与验证的 PyTorch 康复质量分类器**。下载的模型负责提取姿态，不提供医疗诊断或疗效评分。
- 尚未完成真人摄像头计数精度实验、临床测量验证及实物 IMU 联调。测试结果不能当作临床准确率。

## 技术与目录

```text
rehab-platform/
├── web/                     Vue 3 / Element Plus / ECharts
│   ├── src/components/      摄像头训练、观察报告、真实关键点回放
│   ├── src/observation.js   浏览器即时特征与状态机
│   ├── public/exercises.json    动作步骤、来源、适用条件
│   ├── public/models/      预训练 .task 与 SHA-256 清单
│   └── scripts/prepare-assets.mjs   模型校验与本地 WASM 准备
├── server/                  Java 8+ / Spring Boot 2.7 / MyBatis
│   └── src/main/java/edu/course/rehab/
│       ├── Api.java         API、登录与角色入口
│       ├── RehabService.java    权限、参数校验、会话保存与复核
│       ├── Store.java       MyBatis SQL
│       └── ObservationMigration.java  兼容旧库的增量迁移
├── algorithm/
│   ├── service.py           本地 HTTP 服务 /analyze 与 /simulate
│   ├── clinical_analysis.py 关键点几何、状态机、观察报告
│   └── test_observation.py  合成几何测试
├── database/                MySQL 建表、升级与典型查询
├── hardware/                可选 ESP32 双 BNO055 UART 采集脚手架
├── tests/                   跨服务、状态机一致性及事务测试
├── docs/                    运行、依据、硬件与开发检查点
└── .runtime/                本地日志、PID 与数据库备份（不发布）
```

Spring Boot 从 `web/public` 打包动作目录与模型清单，因此修改它们后应重新构建 Java，避免前后端来源版本不一致。

## 数据库

默认文件：`server/data/rehab.mv.db`。已有演示记录保留，升级前备份位于 `.runtime/rehab-before-camera-20261113.mv.db`。备份或恢复数据库前先停止 Java，不能在运行中覆盖文件。

17 张表；新增 `observation_record` 与训练会话一对一，保存观察报告及关键点采集 JSON。可查询的组、动作、事件与特征仍保存在关系表中。原有非空质量字段改为可空以表达“未评价”，计划明细增加个体循环时长。查看 [数据库说明](database/README.md)。

切换 MySQL：安装 MySQL 8，执行 `database/01_schema_mysql.sql`，设置 `DB_USER` / `DB_PASSWORD`，停止后执行 `start.ps1 -MySql`。旧 MySQL 库先执行 `database/03_upgrade_camera_mysql.sql`。本机验证的是 H2，MySQL 实例尚未实测。

## 文档与验证

- [GitHub 首次提交指南](docs/GitHub首次提交指南.md)
- [0.2 功能更新、实现思路与后续优化规划](docs/版本更新记录与后续优化规划-0.2.md)
- [后续开发更新记录模板](docs/开发更新记录模板.md)
- [VS Code 运行、目录与修改思路](docs/VSCode运行与修改指南.md)
- [模型下载、动作依据与评价边界](docs/模型与动作依据.md)
- [硬件采购、接线与烧录](docs/硬件采购与烧录.md)
- [当前检查点与后续任务](docs/开发检查点.md)
- [测试记录](TEST_REPORT.md)

```powershell
python -m unittest discover -s algorithm -p 'test_*.py' -v
python tests/observer_parity.py
# 下两项使用独立内存库和测试端口；先启动本地 Python 算法服务
python tests/camera_check.py
python tests/transaction_check.py
```

依赖：Java 8+、Python 3.10+、Node.js 20+；首次构建需要网络。Maven 和缓存使用工作区 `.tools`。Python 算法服务本身不依赖 pip 包。前端开发：先启动 Java/Python，再在 `web` 执行 `npm ci`、`npm run dev`，访问 5173。

打开失败先查看 `.runtime/java.out.log`、`java.err.log`、`python.err.log`。摄像头需 localhost / 127.0.0.1 或 HTTPS；拒绝权限、被其他程序占用及设备断开都有单独提示。保存失败时采集仍在当前页面内，离开或刷新会丢失尚未保存的内容。
