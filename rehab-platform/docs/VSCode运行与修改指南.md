# VS Code 运行、目录与重要修改位置（0.2）

本指南对应工作区的 `rehab-platform`，`交付文档/rehab-platform` 是第一版存档。请在 VS Code 打开当前 `rehab-platform` 文件夹，避免修改旧副本。

## 直接运行

本机已安装依赖和构建结果。终端使用 PowerShell：

```powershell
.\start.ps1 -SkipBuild -NoBrowser
```

访问 <http://127.0.0.1:8080/>，训练者 `trainee`、指导者 `teacher`、管理员 `admin`，密码均为 `Demo123!`。摄像头版默认仅记录动作；进入计划后再开启摄像头。普通摄像头版本无需开发板、无需 Python checkpoint，也不需要额外 pip 包。

从源码重新构建：

```powershell
.\stop.ps1
.\start.ps1 -NoBrowser
```

Windows 需要先停止运行中的 jar 再打包。脚本自动检查启动依赖，Maven 放在工作区 `.tools`，前端构建前校验模型并准备 WASM。首次构建和 npm 安装需要网络。PowerShell 执行策略受限时，可用已提供的 `.cmd` 启动文件或 VS Code 任务。

“终端 → 运行任务”有已有构建启动、停止、重新构建、前端开发、Python 服务和规则测试。重新构建任务先执行停止任务。

## 开发时开三个终端

终端一，在项目根目录：

```powershell
python algorithm/service.py
```

终端二，先从项目根目录进入 server：

```powershell
cd server
java -Dfile.encoding=UTF-8 -jar target/rehab-platform-0.1.0.jar
```

工作目录必须是 server，以使用同一个 H2 文件。终端三，在 web：

```powershell
cd web
npm ci
npm run dev
```

开发页面 <http://127.0.0.1:5173/>，API 代理到 8080；Python 在 8001。不要同时用一键脚本和终端启动两套相同端口服务。前端热更新无需重启 Java；Java 或打包资源改变后需要重新构建。

## 目录与该改哪里

| 目标 | 文件 | 修改思路 |
| --- | --- | --- |
| 摄像头、采样、组切换、暂停和保存 | web/src/components/CameraTraining.vue | 摄像头预览与正式采集分开；不要把缺失帧填成正常；失败时保留采集供重试 |
| 即时特征与计数 | web/src/observation.js | 平滑、校准、完整循环、遮挡重置；同时维护 Python，并运行状态机一致性检查 |
| 服务端真实分析与观察报告 | algorithm/clinical_analysis.py | Python 从上传关键点重新计算；浏览器传来的次数不作为权威结果 |
| API、权限及事务 | server/.../Api.java、RehabService.java | 不让前端直接写数据库；分析在事务外，组、动作、事件、特征及观察记录在同一事务内保存 |
| SQL 和查询 | server/.../Store.java | MyBatis 注解方法不要用重名重载；保持外键、唯一键和会话行锁 |
| 动作来源与说明 | web/public/exercises.json | 修改可核验的原文对应、适用条件与中文步骤；Maven 同时将它打包到 Java 来源快照中 |
| 模型 | web/public/models/manifest.json、.task | 更新模型摘要、版本与用途；不能把关键点提取模型写成质量分类器 |
| 报告与回放 | ObservationReport.vue、LandmarkReplay.vue、App.vue | NULL 显示“未评价”；真实报告显示上传关键点，历史模拟报告保留来源标识 |
| 数据结构 | server/.../schema.sql、database/01_schema_mysql.sql | 同时维护 H2 和 MySQL；旧库通过增量迁移保留记录 |
| 数据迁移 | ObservationMigration.java、database/03_upgrade_camera_mysql.sql | 根据元数据添加个体时长字段，允许质量和角度缺失；不清空已有训练数据 |
| 硬件 | hardware/esp32_bno055_uart/、docs/硬件采购与烧录.md | 可选采集脚手架，尚未与当前摄像头评价接口连接，先做实物校准和误差验证 |
| 验证结果与后续位置 | TEST_REPORT.md、docs/开发检查点.md | 写清验证方法、实际结果与未测范围，便于继续开发和答辩 |

## 正确配置训练参数

指导者发布或调整计划时可设置每动作的组数、次数、幅度区间、最短和最长完整循环耗时、保持及休息。完整循环耗时包含保持阶段。系统检查最长循环不短于保持时长，参数不能超过状态机的 30 秒循环上限。

默认仅观察不会使用这些参数宣布质量达标。目标比较需要训练者声明已与指导者确认。报告保留当时的计划、参数、模型与动作来源版本；改后续计划不改旧报告。

## 数据库切换

默认 H2 文件 `server/data/rehab.mv.db`。不要在运行时覆盖或删除它。17 张表可以通过管理员“数据库概览”查看计数。MySQL 首次使用执行 `database/01_schema_mysql.sql`；旧库先备份并执行 `03_upgrade_camera_mysql.sql`。然后设置：

```powershell
$env:DB_USER = '你的账号'
$env:DB_PASSWORD = '你的密码'
.\start.ps1 -SkipBuild -MySql -NoBrowser
```

连接 URL 可用 `DB_URL` 配置。H2 与 MySQL 数据互不迁移；本次没有真实 MySQL 实例验证。

## 修改后怎么验证

```powershell
python -m unittest discover -s algorithm -p 'test_*.py' -v
python tests/observer_parity.py
python tests/camera_check.py
python tests/transaction_check.py
```

后两项需要本地算法服务和可用 Java；使用隔离内存库和测试端口，不把合成数据写进当前演示库。`tests/smoke_test.py` 若单独执行会向默认演示库新增测试记录，日常优先运行隔离的 `camera_check.py`。

还需由真人完成机位、左右侧、遮挡、计数、停止不适流程和报告回放试验。通过合成数据测试不能宣称医学精度已验证。
