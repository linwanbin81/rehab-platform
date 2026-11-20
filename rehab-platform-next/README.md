# 舒展 · 康复训练辅助平台

当前版本连接「账号与指导关系 → 基线登记 → 发布计划 → 摄像头跟练 → 逐组报告 → 指导者复核」。支持肩前屈、肩外展、肘屈伸，显示视频骨架、投影角度曲线、动作阶段、次数和目标分项反馈。

## 启动

安装 Node.js 24、Java 17 和 Maven，配置 JAVA_HOME 和 PATH。在本目录的 Windows PowerShell 执行：

```powershell
.\start.ps1 -NoBrowser
```

执行策略阻止脚本时可以执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -NoBrowser
```

使用 Chrome 或 Edge 打开 <http://127.0.0.1:8081/>。API 端口为 8082，数据保存在 `server/data/rehab-next.mv.db`，Flyway 自动初始化数据库。构建自动准备官方姿态模型和 WASM，不需要把这些缓存提交 Git。

脚本支持工作区已有 `.tools` 中的便携 JDK 和 Maven；其他电脑使用已安装的工具，也可设置 `REHAB_JAVA17_HOME`。源码更新后启动脚本检查构建时间；强制重建使用 `.\start.ps1 -NoBrowser -Rebuild`，重建后刷新浏览器。

## 计划训练与复核

所有演示账号密码均为 `Demo123!`。

| 账号 | 角色与归属 |
| --- | --- |
| `teacher` | 指导者，负责 `trainee` |
| `trainee` | 训练者，可读取自己的计划与报告 |
| `admin` | 管理员，可创建、启停账号及调整指导关系 |
| `teacher2` | 第二位指导者，负责 `trainee2` |
| `trainee2` | 第二位训练者，用于数据隔离验证 |

1. 顶部打开「训练工作台」，指导者登录后登记基线，在「训练计划」选择动作、训练侧、日期、组数、次数并发布。
2. 目标比较模式需要指导者填写个人幅度、循环耗时和保持要求；仅观察模式只记录动作数据。系统未提供临床推荐阈值。
3. 训练者登录后选计划中的动作和组，进入摄像头跟练。允许摄像头后先显示视频，再加载模型。确认骨架后点击「开始训练」，保持舒适起始姿势完成校准。
4. 完整抬起或屈肘并返回起始位置后计一次；「结束并保存」提交关键点供 Java 复算。有后续组时可进入下一组，也可返回报告。
5. 指导者查看报告，对失败分项确认或驳回并保存，填写总体意见后单独点击「完成复核」。训练者可读取结果。

演示库预置肘屈伸观察计划，可直接开练。顶部「摄像头试练」支持不绑定计划的观察。登录失效时当前页保留采集，可用原训练者账号重登后继续保存；关闭或刷新会丢失尚未保存的内存帧。原始视频只在浏览器预览，上传内容为关键点。

## 开发与验证

当前技术栈为 Vue 3、TypeScript、MediaPipe、Canvas、Java 17、Spring Boot、Spring JDBC、Flyway 和 H2 文件数据库。开发时分别运行 `server/start.ps1` 和 `web/` 下的 `npm.cmd run dev`。

- 前端规则及生命周期测试：`web/` 下执行 `npm.cmd test`。
- 类型及生产构建：`web/` 下执行 `npm.cmd run build`。
- Java HTTP 与数据库测试：`server/` 下执行 `mvn.cmd test`。
- [浏览器回归说明](web/e2e/README.md)：虚拟摄像头输入、真实姿态模型和隔离内存数据库，不代表真人识别精度结论。

[本阶段功能、目录、数据库与开发记录](docs/2026-11-20-workspace-training.md) · [算法优化依据](docs/2026-11-20-pose-optimization-sources.md)

## 当前边界

每组分别保存报告，尚无整次训练编排、刷新恢复、骨架回放、计划版本调整和整体计划完成率。MySQL、MyBatis、自训练 PyTorch 分类器和花朵游戏尚未在新版接入或验收。二维投影与个人目标比较用于辅助观察，不能据此判断临床诊断或康复疗效。
