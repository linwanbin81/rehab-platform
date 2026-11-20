<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import AngleTrace from './AngleTrace.vue'
import type { AngleSample } from './angle-trace.ts'
import { cameraError, openVideo, stopVideo } from './camera.ts'
import { drawPose, openPoseLandmarker } from './pose.ts'
import { createObserver, EXERCISES, measure, resumeOffset, targetValid } from './motion.ts'
import type { Frame, MotionState, Point, Repetition, Target } from './motion.ts'
import { cameraSettings, observationRequest } from './plan-camera.ts'
import { WORKSPACE_TOKEN_KEY, workspaceRequest, type Actor, type CameraAssignment } from './workspace.ts'

type CameraState = 'idle' | 'requesting' | 'streaming' | 'error'
type PoseState = 'idle' | 'loading' | 'ready' | 'error'
type Stage = 'idle' | 'recording' | 'paused' | 'finished'
type Report = { id: number; repetitions: Repetition[]; validFrames: number; totalFrames: number }

const props = defineProps<{ assignment?: CameraAssignment }>()
const emit = defineEmits<{ saved: [id: number]; nextSet: []; reauthenticated: [token: string] }>()
const initial = cameraSettings(props.assignment)

const PHASE: Record<MotionState['phase'], string> = {
  calibrating: '请保持起始姿势约一秒', ready: '准备好，可以开始动作',
  raising: '正在抬臂或屈肘', returning: '缓慢返回起始姿势', unreliable: '请调整位置',
}
const cameraMessages: Record<CameraState, string> = {
  idle: '摄像头未开启', requesting: '正在请求摄像头，请留意浏览器权限提示',
  streaming: '摄像头已开启', error: '摄像头未能开启',
}

const video = ref<HTMLVideoElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
const camera = ref<CameraState>('idle')
const pose = ref<PoseState>('idle')
const cameraProblem = ref('')
const poseProblem = ref('')
const poses = ref(0)
const devices = ref<MediaDeviceInfo[]>([])
const deviceId = ref('')
const mirror = ref(true)
const exercise = ref(initial.exercise)
const side = ref(initial.side)
const mode = ref(initial.mode)
const target = ref<Target>(initial.target ?? { angleMin: NaN, angleMax: NaN, cycleMin: NaN, cycleMax: NaN, hold: NaN })
const stage = ref<Stage>('idle')
const reading = ref<MotionState | null>(null)
const repetitions = ref<Repetition[]>([])
const totalFrames = ref(0)
const validFrames = ref(0)
const angleSamples = shallowRef<AngleSample[]>([])
const captureLimitReached = ref(false)
const trainMessage = ref('')
const report = ref<Report | null>(null)
const saveProblem = ref('')
const saving = ref(false)
const captureToken = ref(props.assignment?.token ?? '')
const loginRequired = ref(false)
const reauthUsername = ref(props.assignment?.username ?? '')
const reauthPassword = ref('')
const authenticating = ref(false)
const summaryPanel = ref<HTMLElement | null>(null)
const currentExercise = computed(() => EXERCISES[exercise.value])
const configLocked = computed(() => Boolean(props.assignment) || stage.value !== 'idle')
const hasUnsaved = computed(() => totalFrames.value > 0 && !report.value)
defineExpose({ hasUnsaved })
const configValid = computed(() => mode.value === 'observe' || targetValid(target.value))
const last = computed(() => repetitions.value.at(-1))
const angleBand = computed<[number, number] | undefined>(() => mode.value === 'target' && configValid.value
  ? [target.value.angleMin, target.value.angleMax] : undefined)
const resultLabel = (value: boolean | null) => value === null ? '未比较' : value ? '符合目标' : '偏离目标'
const recentChecks = computed(() => last.value ? [
  { label: '幅度', value: last.value.checks.amplitude, measurement: `${last.value.peak.toFixed(1)}°` },
  { label: '节奏', value: last.value.checks.tempo, measurement: `${last.value.seconds.toFixed(1)} 秒` },
  { label: '保持', value: last.value.checks.hold, measurement: last.value.checks.hold === null ? '—' : `${last.value.holdSeconds.toFixed(1)} 秒` },
] : [])
const poseMessage = computed(() => {
  if (pose.value === 'idle') return '姿态识别未启动'
  if (pose.value === 'loading') return '正在加载姿态模型'
  if (pose.value === 'error') return '姿态模型未能运行'
  if (poses.value === 0) return '识别就绪 · 等待人体进入画面'
  if (poses.value > 1) return '识别到多人 · 请只保留训练者'
  return '识别到人体 · 骨架实时叠加'
})

let stream: MediaStream | null = null
let detector: Awaited<ReturnType<typeof openPoseLandmarker>> | null = null
let opening: AbortController | null = null
let poseGeneration = 0
let animation = 0
let captureStart = 0
let captureOffset = 0
let captureId = ''
let frames: Frame[] = []
let observer: ReturnType<typeof createObserver> | null = null

async function listDevices() {
  if (!navigator.mediaDevices?.enumerateDevices) return
  try {
    devices.value = (await navigator.mediaDevices.enumerateDevices()).filter(device => device.kind === 'videoinput' && device.deviceId)
    if (deviceId.value && !devices.value.some(device => device.deviceId === deviceId.value)) deviceId.value = ''
  } catch { devices.value = [] }
}

function releasePose() {
  poseGeneration++
  cancelAnimationFrame(animation)
  detector?.close()
  detector = null
  poses.value = 0
  pose.value = 'idle'
  const surface = canvas.value
  surface?.getContext('2d')?.clearRect(0, 0, surface.width, surface.height)
}

function releaseCamera() {
  opening?.abort()
  opening = null
  releasePose()
  if (stream) {
    if (video.value) stopVideo(video.value, stream)
    else stream.getTracks().forEach(track => track.stop())
  }
  stream = null
  camera.value = 'idle'
  reading.value = null
  if (stage.value === 'recording') stage.value = 'paused'
}

async function startCamera() {
  const element = video.value
  if (!element || camera.value === 'requesting' || camera.value === 'streaming') return
  cameraProblem.value = ''
  poseProblem.value = ''
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    camera.value = 'error'
    cameraProblem.value = '当前页面无法调用摄像头。请直接用 Edge 或 Chrome 打开 http://127.0.0.1:8081，无需使用应用内预览。'
    return
  }

  const request = new AbortController()
  opening = request
  camera.value = 'requesting'
  try {
    const acquired = await openVideo(element, navigator.mediaDevices, deviceId.value, request.signal)
    if (request.signal.aborted) {
      stopVideo(element, acquired)
      return
    }
    stream = acquired
    camera.value = 'streaming'
    for (const track of stream.getVideoTracks()) {
      track.addEventListener('ended', () => {
        if (stream !== acquired) return
        releaseCamera()
        camera.value = 'error'
        cameraProblem.value = '摄像头已断开，已采集的动作仍可保存。重新开启后可继续。'
      }, { once: true })
    }
    // The preview is already playing. A model failure cannot stop the camera.
    void listDevices()
    await startPose()
  } catch (error) {
    if (request.signal.aborted) return
    camera.value = 'error'
    cameraProblem.value = cameraError(error)
    if (error instanceof Error && error.name === 'OverconstrainedError') deviceId.value = ''
  } finally {
    if (opening === request) opening = null
  }
}

async function startPose() {
  if (camera.value !== 'streaming' || pose.value === 'loading') return
  releasePose()
  const generation = poseGeneration
  poseProblem.value = ''
  pose.value = 'loading'
  try {
    const model = await openPoseLandmarker()
    if (generation !== poseGeneration) {
      model.close()
      return
    }
    detector = model
    pose.value = 'ready'
    runFrames()
  } catch (error) {
    if (generation !== poseGeneration) return
    pose.value = 'error'
    poseProblem.value = `姿态识别启动失败，摄像头预览仍可用。${error instanceof Error ? error.message : ''}`
  }
}

function asPoint(point: { x: number; y: number; visibility: number; presence?: number }): Point {
  const rounded = (value: number) => Number(value.toFixed(6))
  return { x: rounded(point.x), y: rounded(point.y), visibility: rounded(point.visibility ?? 0),
    ...(Number.isFinite(point.presence) ? { presence: rounded(point.presence!) } : {}) }
}

function runFrames() {
  let lastFrame = -Infinity
  let lastVideoTime = -1
  function tick(now: number) {
    const element = video.value
    if (!detector || !element || camera.value !== 'streaming') return
    try {
      if (now - lastFrame >= 100 && element.readyState >= 2 && element.currentTime !== lastVideoTime) {
        lastFrame = now
        lastVideoTime = element.currentTime
        const result = detector.detectForVideo(element, now)
        poses.value = result.landmarks.length
        const frame: Frame = {
          time: captureStart ? Number(((now - captureStart) / 1000 + captureOffset).toFixed(4)) : 0,
          width: element.videoWidth, height: element.videoHeight,
          poses: poses.value, points: poses.value === 1 ? result.landmarks[0].map(asPoint) : [],
        }
        if (canvas.value) drawPose(canvas.value, frame, side.value)
        if (stage.value === 'recording' && observer && frame.time >= 0) {
          if (frames.length >= 1000 || frame.time >= 3600) {
            stage.value = 'paused'
            captureLimitReached.value = true
            trainMessage.value = '已达到本组采集上限，可以结束并保存结果。'
          } else {
            frames.push(frame)
            totalFrames.value++
            reading.value = observer.push(frame)
            angleSamples.value = [...angleSamples.value.filter(sample => frame.time - sample.time <= 20),
              { time: frame.time, angle: reading.value.angle }]
            if (reading.value.angle !== null) validFrames.value++
            if (repetitions.value.length !== observer.repetitions.length) repetitions.value = [...observer.repetitions]
          }
        } else if (stage.value === 'idle') reading.value = measure(frame, exercise.value, side.value)
      }
      animation = requestAnimationFrame(tick)
    } catch (error) {
      releasePose()
      if (stage.value === 'recording') stage.value = 'paused'
      pose.value = 'error'
      poseProblem.value = `识别过程中断，摄像头预览仍可用。${error instanceof Error ? error.message : ''}`
    }
  }
  animation = requestAnimationFrame(tick)
}

function startTraining() {
  if (pose.value !== 'ready' || !configValid.value || captureLimitReached.value) return
  const now = performance.now()
  if (stage.value === 'idle') {
    captureStart = now
    captureId = crypto.randomUUID()
    observer = createObserver(exercise.value, side.value, mode.value === 'target' ? { ...target.value } : null)
    reading.value = null
  } else {
    captureOffset = resumeOffset(frames.at(-1)?.time, (now - captureStart) / 1000, captureOffset)
    if ((now - captureStart) / 1000 + captureOffset >= 3600) {
      captureLimitReached.value = true
      trainMessage.value = '本组训练的时间已达上限，可以结束并保存结果。'
      return
    }
  }
  trainMessage.value = ''
  stage.value = 'recording'
}

function clearTraining() {
  stage.value = 'idle'
  reading.value = null
  repetitions.value = []
  validFrames.value = 0
  angleSamples.value = []
  totalFrames.value = 0
  captureLimitReached.value = false
  frames = []
  observer = null
  captureStart = 0
  captureOffset = 0
  captureId = ''
  report.value = null
  saveProblem.value = ''
  loginRequired.value = false
  trainMessage.value = ''
}

async function finishTraining() {
  stage.value = 'finished'
  await nextTick()
  summaryPanel.value?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  await saveReport()
}

async function saveReport() {
  if (saving.value || report.value) return
  if (!frames.length) {
    saveProblem.value = '没有采集到帧，未生成训练记录。可以重新开始或检查摄像头。'
    return
  }
  saving.value = true
  saveProblem.value = ''
  try {
    const assignment = props.assignment ? { ...props.assignment, token: captureToken.value } : undefined
    const response = await fetch('/api/v1/observations', {
      ...observationRequest({ exercise: exercise.value, side: side.value, mode: mode.value, target: target.value }, frames, captureId, assignment),
      signal: AbortSignal.timeout(20_000),
    })
    if (response.status === 401 && assignment) {
      loginRequired.value = true
      saveProblem.value = '登录已失效，采集数据仍保留。请用原训练者账号登录后继续保存。'
      return
    }
    if (!response.ok) throw new Error((await response.json().catch(() => null))?.message ?? `HTTP ${response.status}`)
    const result = await response.json() as Report
    if (!Number.isInteger(result.id) || !Array.isArray(result.repetitions)) throw new Error('服务返回了无效报告')
    report.value = result
    repetitions.value = result.repetitions
    validFrames.value = result.validFrames
    emit('saved', result.id)
  } catch (error) {
    saveProblem.value = `保存失败，采集数据仍保留，可重试。${error instanceof Error ? error.message : ''}`
  } finally { saving.value = false }
}

async function reauthenticate() {
  if (authenticating.value || !props.assignment) return
  authenticating.value = true
  try {
    const result = await workspaceRequest<{ token: string; actor: Actor }>('/workspace/login', undefined, 'POST', {
      username: reauthUsername.value.trim(), password: reauthPassword.value,
    })
    if (result.actor.role !== 'TRAINEE' || (props.assignment.traineeId && result.actor.id !== props.assignment.traineeId)) {
      await workspaceRequest('/workspace/logout', result.token, 'POST').catch(() => undefined)
      throw new Error('请登录本计划所属的训练者账号。')
    }
    captureToken.value = result.token
    sessionStorage.setItem(WORKSPACE_TOKEN_KEY, result.token)
    emit('reauthenticated', result.token)
    reauthPassword.value = ''
    loginRequired.value = false
    await saveReport()
  } catch (error) {
    saveProblem.value = error instanceof Error ? error.message : '重新登录失败，请重试。'
  } finally { authenticating.value = false }
}

const stopOnHidden = () => {
  if (document.hidden && stage.value === 'recording') {
    stage.value = 'paused'
    trainMessage.value = '页面进入后台，采集已暂停。返回后可继续。'
  }
}

const protectUnsaved = (event: BeforeUnloadEvent) => {
  if (frames.length && !report.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

watch([exercise, side], () => { if (stage.value === 'idle') reading.value = null })
onMounted(() => {
  void listDevices()
  navigator.mediaDevices?.addEventListener('devicechange', listDevices)
  document.addEventListener('visibilitychange', stopOnHidden)
  window.addEventListener('beforeunload', protectUnsaved)
})
onUnmounted(() => {
  navigator.mediaDevices?.removeEventListener('devicechange', listDevices)
  document.removeEventListener('visibilitychange', stopOnHidden)
  window.removeEventListener('beforeunload', protectUnsaved)
  releaseCamera()
})
</script>

<template>
  <main class="workspace">
    <div class="page-heading">
      <h1>摄像头跟练</h1>
      <p v-if="assignment">{{ assignment.planTitle }} · 第 {{ assignment.setNumber }} / {{ assignment.item.sets }} 组 · 本组目标 {{ assignment.item.reps }} 次</p>
      <p>开启摄像头后，按机位要求站好，确认骨架对准身体再开始。</p>
    </div>

    <div class="training-layout">
      <section class="camera-card" aria-label="摄像头训练">
        <div class="camera-title"><h2>实时画面</h2><span class="indicator" data-testid="camera-status" :data-state="camera" aria-live="polite">{{ cameraMessages[camera] }}</span></div>
        <div class="viewport">
          <div class="feed" :class="{ mirror }">
            <video ref="video" data-testid="camera-video" autoplay muted playsinline aria-label="摄像头实时预览"></video>
            <canvas ref="canvas" data-testid="pose-overlay" aria-label="人体关键点骨架"></canvas>
          </div>
          <div v-if="camera !== 'streaming'" class="camera-cover">
            <div class="lens" aria-hidden="true"><span></span></div>
            <p>{{ camera === 'requesting' ? '等待浏览器授权' : '开启摄像头查看实时画面' }}</p>
            <button v-if="camera !== 'requesting'" class="primary" @click="startCamera">开启摄像头</button>
          </div>
        </div>
        <div class="camera-toolbar">
          <div class="indicator" data-testid="pose-status" :data-state="pose" :data-pose-count="poses" aria-live="polite">{{ poseMessage }}</div>
          <div class="camera-options">
            <label><input v-model="mirror" type="checkbox" /> 镜像预览</label>
            <button v-if="camera === 'streaming' || camera === 'requesting'" class="secondary small" @click="releaseCamera">{{ camera === 'requesting' ? '取消开启' : '关闭摄像头' }}</button>
          </div>
        </div>
        <div v-if="cameraProblem" class="callout error" role="alert">{{ cameraProblem }}</div>
        <div v-if="poseProblem" class="callout error" role="alert">{{ poseProblem }} <button class="secondary small" @click="startPose">重试识别</button></div>
        <div v-if="devices.length > 1" class="device-field">
          <label for="camera-device">摄像头</label>
          <select id="camera-device" v-model="deviceId" :disabled="camera === 'streaming' || camera === 'requesting'">
            <option value="">系统默认</option>
            <option v-for="(device, index) in devices" :key="device.deviceId" :value="device.deviceId">{{ device.label || `摄像头 ${index + 1}` }}</option>
          </select>
        </div>
      </section>

      <aside class="settings">
        <section class="card">
          <h2>{{ assignment ? '计划要求' : '本次练习' }}</h2>
          <fieldset :disabled="configLocked">
            <label class="field" for="exercise">训练动作<select id="exercise" v-model="exercise"><option v-for="(item, key) in EXERCISES" :key="key" :value="key">{{ item.title }}</option></select></label>
            <label class="field" for="side">训练侧<select id="side" v-model="side"><option value="RIGHT">右侧</option><option value="LEFT">左侧</option></select></label>
            <label class="field" for="mode">反馈方式<select id="mode" v-model="mode"><option value="observe">仅观察动作</option><option value="target">比较个人目标</option></select></label>
            <template v-if="mode === 'target'">
              <p v-if="!assignment" class="muted">请填指导者给出的幅度范围、单次耗时和保持时间。无需保持时填 0。</p>
              <div class="metric-inputs">
                <label class="field">最小幅度 °<input v-model.number="target.angleMin" type="number" min="0" max="179" /></label>
                <label class="field">最大幅度 °<input v-model.number="target.angleMax" type="number" min="1" max="180" /></label>
                <label class="field">最短耗时 s<input v-model.number="target.cycleMin" type="number" min="0.6" max="30" step="0.1" /></label>
                <label class="field">最长耗时 s<input v-model.number="target.cycleMax" type="number" min="0.6" max="30" step="0.1" /></label>
                <label class="field">目标保持 s<input v-model.number="target.hold" type="number" min="0" max="10" step="0.1" /></label>
              </div>
              <p v-if="!configValid" class="field-error" role="alert">填写有效的幅度、耗时上下限与保持时间后才能开始。</p>
            </template>
          </fieldset>
          <div class="instructions"><strong>{{ currentExercise.direction }}</strong><p>{{ currentExercise.action }}</p><a :href="currentExercise.source" target="_blank" rel="noopener noreferrer">查看 AAOS 动作图示 ↗</a></div>
        </section>
      </aside>
    </div>

    <section class="card results" aria-label="训练反馈">
      <div class="results-heading">
        <h2>训练反馈</h2>
        <div class="actions">
          <button v-if="stage === 'idle'" class="primary" :disabled="pose !== 'ready' || !configValid" @click="startTraining">开始训练</button>
          <button v-if="stage === 'recording'" class="secondary" @click="stage = 'paused'">暂停</button>
          <button v-if="stage === 'paused'" class="secondary" :disabled="pose !== 'ready' || captureLimitReached" @click="startTraining">继续训练</button>
          <button v-if="stage === 'recording' || stage === 'paused'" class="primary" @click="finishTraining">结束并保存</button>
          <button v-if="stage === 'recording' || stage === 'paused'" class="secondary" @click="clearTraining">取消本组</button>
          <button v-if="stage === 'finished' && !assignment" class="secondary" :disabled="saving" @click="clearTraining">开始新一组</button>
          <button v-if="stage === 'finished' && assignment && !report" class="secondary" :disabled="saving" @click="clearTraining">重新采集本组</button>
          <button v-if="report && assignment && assignment.setNumber < assignment.item.sets" class="secondary" @click="emit('nextSet')">进入下一组</button>
        </div>
      </div>
      <div class="readings" aria-live="polite">
        <div><span>当前投影角度</span><strong>{{ reading?.angle == null ? '—' : `${Math.round(reading.angle)}°` }}</strong></div>
        <div><span>完整动作</span><strong>{{ repetitions.length }} <small>次</small></strong></div>
        <div><span>采集阶段</span><strong class="phase">{{ stage === 'idle' ? '待开始' : stage === 'paused' ? '已暂停' : stage === 'finished' ? '已结束' : reading ? PHASE[reading.phase] : '校准起始姿势' }}</strong></div>
      </div>
      <p v-if="trainMessage || reading?.reason" class="callout">{{ trainMessage || reading?.reason }}</p>
      <AngleTrace v-if="angleSamples.length" :samples="angleSamples" :target="angleBand" />
      <div v-if="last" class="recent">
        <h3>最近一次动作</h3>
        <div v-for="check in recentChecks" :key="check.label" class="recent-item">
          <strong>{{ check.label }}</strong><span>{{ check.measurement }}</span>
          <span :class="{ good: check.value === true, attention: check.value === false }">{{ resultLabel(check.value) }}</span>
        </div>
      </div>
      <div v-else class="muted">完成从起始姿势抬臂或屈肘，再回到起始位置后计一次。目标比较会分别反馈幅度、节奏和保持。</div>
    </section>

    <section v-if="stage === 'finished'" ref="summaryPanel" class="card summary" aria-label="本组报告">
      <h2>本组报告</h2>
      <p>已完成 {{ repetitions.length }} 次 · 可靠观测 {{ validFrames }} / {{ totalFrames }} 帧</p>
      <p v-if="validFrames === 0">本次有效观测不足，请调整机位后重试。</p>
      <p v-else-if="!repetitions.length">本组未观察到完整动作循环。</p>
      <div v-if="repetitions.length" class="report-table-wrap">
        <table class="report-table" aria-label="每次动作结果">
          <thead><tr><th scope="col">次数</th><th scope="col">峰值幅度</th><th scope="col">循环耗时</th><th scope="col">保持</th></tr></thead>
          <tbody>
            <tr v-for="(rep, index) in repetitions" :key="rep.start">
              <th scope="row">{{ index + 1 }}</th>
              <td>{{ rep.peak.toFixed(1) }}°<small>{{ resultLabel(rep.checks.amplitude) }}</small></td>
              <td>{{ rep.seconds.toFixed(1) }} 秒<small>{{ resultLabel(rep.checks.tempo) }}</small></td>
              <td>{{ rep.checks.hold === null ? '—' : `${rep.holdSeconds.toFixed(1)} 秒` }}<small>{{ resultLabel(rep.checks.hold) }}</small></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="saving" role="status">正在复算并保存…</p>
      <p v-if="report" class="good" role="status">已保存到数据库 · 记录 #{{ report.id }}</p>
      <p v-if="saveProblem" class="callout error" role="alert">{{ saveProblem }} <button v-if="totalFrames && !loginRequired" class="secondary small" @click="saveReport">重试保存</button></p>
      <form v-if="loginRequired" class="capture-reauth" aria-label="恢复训练登录" @submit.prevent="reauthenticate">
        <label class="field">用户名<input v-model="reauthUsername" autocomplete="username" required /></label>
        <label class="field">密码<input v-model="reauthPassword" type="password" autocomplete="current-password" required /></label>
        <button class="primary" :disabled="authenticating || saving">登录并重试保存</button>
      </form>
    </section>
    <footer>仅在本机显示摄像头画面；结束保存时提交关键点。动作反馈依据当前机位和个人目标。</footer>
  </main>
</template>
