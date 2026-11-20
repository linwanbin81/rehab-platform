import type { Frame, Point, Side } from './motion.ts'

const BONES = [
  [11, 12], [11, 13], [13, 15], [12, 14], [14, 16],
  [11, 23], [12, 24], [23, 24], [23, 25], [25, 27], [24, 26], [26, 28],
]

export async function openPoseLandmarker() {
  const modelResponse = await fetch('/models/pose_landmarker_full.task', { signal: AbortSignal.timeout(20_000) })
  if (!modelResponse.ok) throw new Error(`模型文件请求失败（HTTP ${modelResponse.status}）`)
  const modelAssetBuffer = new Uint8Array(await modelResponse.arrayBuffer())
  const { FilesetResolver, PoseLandmarker } = await import('@mediapipe/tasks-vision')
  const files = await FilesetResolver.forVisionTasks('/wasm')
  return PoseLandmarker.createFromOptions(files, {
    baseOptions: { modelAssetBuffer, delegate: 'CPU' },
    runningMode: 'VIDEO',
    numPoses: 2,
    minPoseDetectionConfidence: .55,
    minPosePresenceConfidence: .55,
    minTrackingConfidence: .55,
  })
}

export function drawPose(canvas: HTMLCanvasElement, frame: Frame, side: Side) {
  if (canvas.width !== frame.width) canvas.width = frame.width
  if (canvas.height !== frame.height) canvas.height = frame.height
  const context = canvas.getContext('2d')
  if (!context) return
  context.clearRect(0, 0, canvas.width, canvas.height)
  if (frame.poses !== 1) return

  const active = new Set(side === 'LEFT' ? [11, 13, 15, 23] : [12, 14, 16, 24])
  const pixel = (point: Point): [number, number] => [point.x * canvas.width, point.y * canvas.height]
  const visible = (point: Point | undefined): point is Point => !!point && point.visibility >= .5 && (point.presence ?? 1) >= .5
  context.lineWidth = Math.max(3, canvas.width / 195)
  context.lineCap = 'round'
  for (const [a, b] of BONES) {
    const first = frame.points[a], second = frame.points[b]
    if (!visible(first) || !visible(second)) continue
    context.strokeStyle = active.has(a) && active.has(b) ? '#7affca' : '#eef9f7bc'
    context.beginPath()
    context.moveTo(...pixel(first))
    context.lineTo(...pixel(second))
    context.stroke()
  }
  for (const i of [11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28]) {
    const point = frame.points[i]
    if (!visible(point)) continue
    context.fillStyle = active.has(i) ? '#7affca' : '#eaf9f2'
    context.beginPath()
    context.arc(...pixel(point), Math.max(4, canvas.width / 150), 0, 2 * Math.PI)
    context.fill()
  }
}
