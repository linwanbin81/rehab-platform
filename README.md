# 智能康复训练辅助平台

《融合视觉姿态感知与动作质量评估的智能康复训练辅助平台设计与实现》

当前代码位于 `rehab-platform-next/`，支持指导者发布训练计划、训练者摄像头跟练、MediaPipe 骨架识别与角度反馈、逐组报告和人工复核。动作范围为肩前屈、肩外展、肘屈伸。

## 文档入口

- [安装、启动与演示账号](rehab-platform-next/README.md)
- [功能、目录结构、数据库关系和开发记录](rehab-platform-next/docs/2026-11-20-workspace-training.md)
- [姿态识别优化依据](rehab-platform-next/docs/2026-11-20-pose-optimization-sources.md)
- [整体重建设计](docs/superpowers/specs/2026-11-20-rehab-platform-next-design.md)

## 本地启动

安装 Node.js 24、JDK 17 和 Maven，并配置 `JAVA_HOME` 与 `PATH`。在 Windows PowerShell 执行：

```powershell
cd rehab-platform-next
.\start.ps1 -NoBrowser
```

如果本机 PowerShell 执行策略阻止脚本，可以执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -NoBrowser
```

使用 Chrome 或 Edge 打开 <http://127.0.0.1:8081/>，允许摄像头权限。Java API 使用 `8082` 端口。构建会下载姿态模型和 WASM，数据库首次启动时自动初始化。

点击顶部「训练工作台」，使用以下演示账号，密码均为 `Demo123!`：

| 账号 | 角色 |
| --- | --- |
| `teacher` | 指导者，负责 `trainee` |
| `trainee` | 训练者 |
| `admin` | 管理员 |
| `teacher2`、`trainee2` | 第二组指导关系，用于数据隔离验证 |

## 技术与范围

当前实现使用 Vue 3、TypeScript、MediaPipe、Canvas、Java 17、Spring Boot、Spring JDBC、Flyway 与 H2 文件数据库。数据及缓存不随仓库分发。

目前按组保存训练报告。整次训练编排、断网与刷新恢复、骨架回放、MySQL 部署验收、自训练动作质量分类器和花朵游戏仍为后续工作。反馈是动作观察及个人目标比较，不是临床诊断或疗效结论。

旧版可在 Git 历史及 `backup/before-next-20261120` 分支查看。
