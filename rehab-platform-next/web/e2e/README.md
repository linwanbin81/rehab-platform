# 摄像头浏览器回归

先在 `server/` 执行 `./start.ps1 -BuildOnly` 构建 Java 服务。在 `web/` 安装依赖并启动本地开发服务器，再运行以下命令。生成测试流需要 Python 和 Pillow，浏览器测试使用项目的 Playwright 依赖及本机 Chrome 或 Edge。

```powershell
python e2e/prepare-video.py
node --test e2e/camera.test.mjs
```

如需使用其他本机端口、浏览器或 Java 17 可执行文件，可设置 `REHAB_E2E_URL`、`REHAB_CHROME` 或 `REHAB_E2E_JAVA`。默认页面端口为 `8081`，测试用 Java 服务运行在系统分配的临时端口，使用内存数据库。

若需要针对另一份构建产物运行，例如 Windows 正在使用默认 JAR，可用 `REHAB_E2E_JAR` 指定待测 JAR 的绝对路径。

测试给 Chromium 提供固定视频源，通过应用实际调用 `getUserMedia`，并运行真实 MediaPipe WASM 和姿态模型。测试验证视频播放、骨架绘制和关闭释放，检查模型下载挂起及返回 404 时预览继续播放，检查权限拒绝后重新授权可恢复，最后从页面完成采集与保存，再通过 Java 接口读取同一份报告。测试使用虚拟摄像头，也不衡量真人动作识别准确率。

业务回归还覆盖：训练者选计划第一组进入摄像头、撤销当前登录后原页重新登录并保存、下一组使用新登录、返回工作台查看报告，以及报告数据隔离。指导者回归从页面登记基线、发布个人目标计划，对独立测试库内的合成动作结果逐项复核；未保存的分项改动会阻止最终提交。最后测试退出请求延迟返回时，已卸载的旧页面不能清除新的登录。

每项持久化测试启动独立的 Java 临时端口及 H2 内存库，并把页面 API 请求路由到该实例。不会在正常演示库中创建测试账号、计划或报告。页面应使用最新构建，可在项目根运行 `./start.ps1 -NoBrowser -Rebuild`；单独构建 API 后仍需构建或启动前端。

测试画面来自 MediaPipe 官方示例。脚本校验 JPEG 的 SHA-256，再把固定画面转换成 Chromium 虚拟摄像头支持的 YUV4MPEG2 视频。下载转码结果存放在已忽略的 `.fixtures/`，不随项目分发。[MediaPipe 图像来源](https://storage.googleapis.com/mediapipe-assets/pose.jpg)
