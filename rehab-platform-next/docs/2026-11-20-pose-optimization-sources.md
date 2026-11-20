# 姿态系统优化：官方资料核对

核对日期：2026-11-20。范围仅为 Google MediaPipe 与 Microsoft ONNX Runtime 官方资料；本文记录事实和工程建议，不代表已完成性能实测或动作质量验证。

## 官方事实

| 核实项 | 官方说明 | 来源 |
| --- | --- | --- |
| Web 推理执行方式 | Web Tasks 同步执行；实时摄像头使用 `VIDEO` 配合 `requestAnimationFrame`，只处理最新视频帧，繁忙时跳过中间帧。API 的 callback 重载也会同步等待 callback 返回，不能把 callback 当成异步推理。 | [Google Web 指南][G1]、[PoseLandmarker API][G2] |
| 模型选择 | 官方提供 Lite、Full、Heavy 三种模型包，由人体检测和姿态关键点模型组成，输出估计的 33 个三维身体关键点；模型面向设备端实时健身应用优化。文档示例采用 Lite 和 GPU delegate，但未承诺任意设备上的速度或临床准确度。 | [Google 模型与 Web 指南][G1] |
| 坐标含义 | `landmarks` 的 x/y 分别按图像宽/高归一化；`worldLandmarks` 为以髋部中点为原点、单位为米的三维估计，附带 visibility/presence。两者都不是相机标定或测量精度保证。 | [Google 输出与模型说明][G1] |
| 人数设置 | `numPoses` 是最大检测人数，默认 1。它不是多人闯入检测的独立保证。 | [Google 配置说明][G1] |
| ONNX 浏览器推理 | `onnxruntime-web` 可以在浏览器加载 ONNX 模型；模型可从 PyTorch/TensorFlow 等导出。可使用 WASM 或 GPU 执行提供程序，但 WebGPU 等只支持部分算子，浏览器支持也有差异。 | [ORT Web 工作流][O1]、[支持环境][O2] |
| ONNX Worker 与线程限制 | WASM proxy Worker 默认关闭，启用后可将计算移出主线程；该 proxy 不能用于 WebGPU，且受 CSP 限制。WebGPU 若放在 Worker 中，应在 Worker 内初始化运行时。WASM 多线程还要求浏览器支持及 `crossOriginIsolated`，单独增加 `numThreads` 不够。 | [ORT 环境与会话选项][O3] |

Google 的旧入口 `ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/web_js` 在核对时重定向至 [G1]，故引用当前落地页。当前页面明确同步执行；下述将 MediaPipe 放入 Worker 的方案是工程建议，不声称当前指南提供了本项目可直接套用的完整 Worker 实现。

## 本项目适用判断与建议

以下为代码观察及工程判断，与上面的官方事实分开：

1. **先减少界面阻塞，再比较模型。** 当前 `web/src/pose.ts` 使用 CPU + Full，`CameraTraining.vue` 在主线程每至少 100 ms 调用一次 `detectForVideo`。节流限制调用频率，但单次同步调用仍占用主线程。建议先记录推理耗时 P50/P95、实际分析帧率、长任务和可用帧比例，再做 Worker 原型，采用“最多一帧处理中、完成后取最新帧”，避免排队产生陈旧反馈。Worker 主要改善界面响应，不能承诺缩短模型本身的计算时间。Worker 的视频帧传输、OffscreenCanvas/GPU 支持、内存释放仍需在目标浏览器验证。
2. **Lite/Full 和 CPU/GPU 应通过同一素材对比。** 保留 Full 基线，在低性能设备尝试 Lite，测量漏检、抖动、计数错误及动作峰值偏差。不能引用其他设备或旧版模型延迟作为本项目结果。不要直接把 `numPoses: 2` 改成 1：本项目会暂停评价多人画面，限制成 1 可能让第二个人被截断而失去此保护。
3. **worldLandmarks 值得评估，但不等于单目深度真值。** 当前仅保存二维 x/y 并计算投影角度，已做图像宽高比例修正与时间相关平滑。建议在新数据版本中额外保留 world 三维坐标、置信度、模型版本及机位信息，对比二维与三维角度的稳定性。官方将三维输出描述为“估计”；单相机遮挡、视角和深度歧义的影响仍需通过实测检验，不能仅凭单位是米就输出“真实关节活动度”或临床精度结论。
4. **健身适配不等于已经识别八段锦或康复质量。** 官方说明的是通用人体关键点和实时健身适用方向，并未给本项目提供八段锦分式、动作规范分数或康复效果标签。建议保持“姿态估计 → 动作分段/计数 → 动作专项评价”三层；新增动作需要定义可观测指标、正确及错误示例与验收集。三维关键点仍不足以直接观测疼痛、肌力或呼吸配合等指标。
5. **训练动作模型与替换姿态底座应分开。** 当前加载预训练 `.task` 文件，工程规则没有进行神经网络训练。若需要从数据学习动作类别/阶段或错误类型，可先收集带专家标签的关键点序列，按人员划分训练/验证/测试集，训练小型时序模型，再导出 ONNX，在浏览器测量兼容性和延迟。此路线是项目建议；ORT 官方确认的是模型导出与浏览器运行能力，不证明具体动作模型已经可靠。重训底层姿态模型则需要额外的关键点标注、数据和训练管线，不能通过改几个 MediaPipe 推理参数实现。

## 来源

[G1]: https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker?tab=Web
[G2]: https://developers.google.com/edge/api/mediapipe/js/tasks-vision.poselandmarker
[O1]: https://onnxruntime.ai/docs/tutorials/web/
[O2]: https://onnxruntime.ai/docs/get-started/with-javascript/web.html
[O3]: https://onnxruntime.ai/docs/tutorials/web/env-flags-and-session-options.html

- Google：[Pose Landmarker：Web、输出、配置与模型][G1]；[JavaScript API][G2]。
- Microsoft ONNX Runtime：[Web 工作流][O1]；[浏览器支持环境][O2]；[环境与会话选项][O3]。
