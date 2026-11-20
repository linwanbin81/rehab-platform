<script lang="ts">
export type { CameraAssignment, PlanItem } from './workspace.ts'
</script>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { EXERCISES, type Exercise, type Side, type Target } from './motion.ts'
import {
  buildPlanPayload, MODE_LABEL, REVIEW_STATUS_LABEL, ROLE_LABEL, SIDE_LABEL, TARGET_FIELDS,
  WORKSPACE_TOKEN_KEY, WorkspaceError, workspaceRequest,
  type Actor, type CameraAssignment, type Plan, type PlanDraft, type PlanDraftItem, type PlanItem,
  type ReportSummary, type ReviewStatus, type Role, type Trainee,
} from './workspace.ts'
import WorkspaceReport from './WorkspaceReport.vue'
import './workspace.css'

const props = defineProps<{ recentReportId?: number }>()
const emit = defineEmits<{ start: [assignment: CameraAssignment] }>()

type Panel = 'trainees' | 'plans' | 'reports' | 'accounts'
type Resource<T> = { data: T[]; loading: boolean; ready: boolean; error: string }

const panels: { key: Panel; label: string; roles: readonly Role[] }[] = [
  { key: 'accounts', label: '账号管理', roles: ['ADMIN'] },
  { key: 'trainees', label: '训练者', roles: ['ADMIN', 'GUIDE'] },
  { key: 'plans', label: '训练计划', roles: ['GUIDE', 'TRAINEE'] },
  { key: 'reports', label: '训练报告', roles: ['ADMIN', 'GUIDE', 'TRAINEE'] },
]
const introductions: Record<Role, string> = {
  ADMIN: '管理账号和指导关系，查看训练档案与报告。',
  GUIDE: '登记基线、发布训练计划并复核训练报告。',
  TRAINEE: '按计划进入摄像头跟练，查看记录与指导意见。',
}
const demos = [
  { username: 'trainee', label: '训练者' }, { username: 'teacher', label: '指导者' },
  { username: 'admin', label: '管理员' }, { username: 'trainee2', label: '训练者 2' },
  { username: 'teacher2', label: '指导者 2' },
]
const exerciseOptions = Object.entries(EXERCISES) as [Exercise, (typeof EXERCISES)[Exercise]][]
const dateFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
const token = ref(sessionStorage.getItem(WORKSPACE_TOKEN_KEY) ?? '')
const actor = ref<Actor | null>(null)
const activePanel = ref<Panel>('plans')
const checkingSession = ref(!!token.value)
const loginPending = ref(false)
const credentials = reactive({ username: '', password: '' })
const authError = ref('')
const actionMessage = ref('')
const actionError = ref('')
const actionPending = ref(false)
const trainees = reactive<Resource<Trainee>>({ data: [], loading: false, ready: false, error: '' })
const guides = reactive<Resource<Actor>>({ data: [], loading: false, ready: false, error: '' })
const plans = reactive<Resource<Plan>>({ data: [], loading: false, ready: false, error: '' })
const reports = reactive<Resource<ReportSummary>>({ data: [], loading: false, ready: false, error: '' })
const selectedTraineeId = ref<number | null>(null)
const selectedTrainee = computed(() => trainees.data.find(trainee => trainee.id === selectedTraineeId.value))
const eligibleTrainees = computed(() => trainees.data.filter(trainee => trainee.enabled !== false))
const assignGuideId = ref<number | null>(null)
const baseline = reactive<{ side: Side; note: string }>({ side: 'RIGHT', note: '' })
const planDraft = reactive<PlanDraft>({ traineeId: null, title: '', scheduledDate: localDate(), items: [] })
const account = reactive<{ username: string; password: string; displayName: string; role: 'GUIDE' | 'TRAINEE'; guideId: number | null }>({
  username: '', password: '', displayName: '', role: 'TRAINEE', guideId: null,
})
const selectedSets = reactive<Record<number, number>>({})
const selectedReportId = ref<number | null>(null)
const selectedReport = computed(() => reports.data.find(report => report.id === selectedReportId.value))
const reviewFilter = ref<ReviewStatus | 'ALL'>('ALL')
const shownReports = computed(() => reports.data.filter(report => reviewFilter.value === 'ALL' || report.reviewStatus === reviewFilter.value))
const recentReportOpened = ref<number | null>(null)
let mounted = true
const availablePanels = computed(() => panels.filter(panel => actor.value && panel.roles.includes(actor.value.role)))

function localDate() {
  const today = new Date()
  const twoDigits = (value: number) => String(value).padStart(2, '0')
  return [today.getFullYear(), twoDigits(today.getMonth() + 1), twoDigits(today.getDate())].join('-')
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateFormat.format(date)
}

function messageFor(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请重试。'
}

function selectDemo(username: string) {
  credentials.username = username
  credentials.password = 'Demo123!'
  authError.value = ''
}

function clearSession(message = '') {
  if (sessionStorage.getItem(WORKSPACE_TOKEN_KEY) === token.value) sessionStorage.removeItem(WORKSPACE_TOKEN_KEY)
  token.value = ''
  actor.value = null
  credentials.password = ''
  authError.value = message
  actionError.value = ''
  actionMessage.value = ''
  selectedTraineeId.value = null
  selectedReportId.value = null
  recentReportOpened.value = null
  reviewFilter.value = 'ALL'
  baseline.note = ''
  assignGuideId.value = null
  Object.assign(account, { username: '', password: '', displayName: '', role: 'TRAINEE', guideId: null })
  Object.assign(planDraft, { traineeId: null, title: '', scheduledDate: localDate(), items: [] })
  for (const key of Object.keys(selectedSets)) delete selectedSets[Number(key)]
  for (const resource of [trainees, guides, plans, reports]) {
    Object.assign(resource, { data: [], loading: false, ready: false, error: '' })
  }
}

function expireSession(expiredToken: string) {
  if (mounted && expiredToken === token.value) clearSession('登录已失效，请重新登录。')
}

function activate(member: Actor) {
  actor.value = member
  activePanel.value = availablePanels.value[0]!.key
  void refreshWorkspace()
}

async function restoreSession() {
  if (!token.value) return
  const currentToken = token.value
  checkingSession.value = true
  authError.value = ''
  try {
    activate(await workspaceRequest<Actor>('/workspace/me', currentToken))
  } catch (error) {
    if (error instanceof WorkspaceError && error.status === 401) expireSession(currentToken)
    else authError.value = messageFor(error)
  } finally {
    checkingSession.value = false
  }
}

async function login() {
  if (loginPending.value) return
  loginPending.value = true
  authError.value = ''
  try {
    const result = await workspaceRequest<{ token: string; actor: Actor }>('/workspace/login', undefined, 'POST', {
      username: credentials.username.trim(), password: credentials.password,
    })
    if (!mounted) return
    token.value = result.token
    sessionStorage.setItem(WORKSPACE_TOKEN_KEY, result.token)
    credentials.password = ''
    activate(result.actor)
  } catch (error) {
    if (mounted) authError.value = messageFor(error)
  } finally {
    loginPending.value = false
  }
}

async function logout() {
  if (actionPending.value) return
  actionPending.value = true
  actionError.value = ''
  try {
    await workspaceRequest<void>('/workspace/logout', token.value, 'POST')
    if (mounted) clearSession()
  } catch (error) {
    if (!mounted) return
    if (error instanceof WorkspaceError && error.status === 401) clearSession()
    else actionError.value = messageFor(error)
  } finally {
    actionPending.value = false
  }
}

async function load<T>(resource: Resource<T>, path: string) {
  const currentToken = token.value
  if (!currentToken || resource.loading) return
  resource.loading = true
  resource.error = ''
  try {
    const result = await workspaceRequest<T[]>(path, currentToken)
    if (token.value !== currentToken) return
    resource.data = result
    resource.ready = true
  } catch (error) {
    if (token.value !== currentToken) return
    if (error instanceof WorkspaceError && error.status === 401) expireSession(currentToken)
    else resource.error = messageFor(error)
  } finally {
    if (token.value === currentToken) resource.loading = false
  }
}

function loadTrainees() { return load(trainees, '/workspace/trainees') }
function loadGuides() { return load(guides, '/workspace/guides') }
function loadPlans() { return load(plans, '/plans') }
function loadReports() { return load(reports, '/observations') }

async function refreshWorkspace() {
  const role = actor.value?.role
  await Promise.all([
    loadReports(),
    role === 'TRAINEE' ? null : loadTrainees(),
    role === 'ADMIN' ? loadGuides() : loadPlans(),
  ])
}

function navigate(panel: Panel) {
  activePanel.value = panel
  actionMessage.value = ''
  actionError.value = ''
  selectedReportId.value = null
}

function chooseTrainee(trainee: Trainee) {
  selectedTraineeId.value = trainee.id
  baseline.side = trainee.side ?? 'RIGHT'
  baseline.note = trainee.baseline ?? ''
  assignGuideId.value = trainee.guideId
  actionError.value = ''
  actionMessage.value = ''
}

function guideName(guideId: number | null) {
  if (!guideId) return '未分配'
  return guides.data.find(guide => guide.id === guideId)?.displayName ?? '指导者 #' + guideId
}

async function mutate(path: string, method: string, body: unknown, success: string, refresh: () => Promise<unknown>) {
  if (actionPending.value) return false
  const currentToken = token.value
  actionPending.value = true
  actionError.value = ''
  actionMessage.value = ''
  try {
    await workspaceRequest(path, currentToken, method, body)
    if (token.value !== currentToken) return false
    actionMessage.value = success
    await refresh()
    return token.value === currentToken
  } catch (error) {
    if (token.value !== currentToken) return false
    if (error instanceof WorkspaceError && error.status === 401) expireSession(currentToken)
    else actionError.value = messageFor(error)
    return false
  } finally {
    actionPending.value = false
  }
}

function saveBaseline() {
  if (selectedTrainee.value) {
    void mutate('/workspace/trainees/' + selectedTrainee.value.id + '/baseline', 'PUT', { ...baseline }, '已保存基线。', loadTrainees)
  }
}

function assignGuide() {
  if (selectedTrainee.value && assignGuideId.value) {
    void mutate('/workspace/trainees/' + selectedTrainee.value.id + '/guide', 'PUT', { guideId: assignGuideId.value }, '已更新指导关系。', loadTrainees)
  }
}

async function createAccount() {
  const { username, password, displayName, role, guideId } = account
  const saved = await mutate('/workspace/accounts', 'POST', {
    username: username.trim(), password, displayName: displayName.trim(), role,
    ...(role === 'TRAINEE' && guideId !== null ? { guideId } : {}),
  }, '已创建账号。', () => Promise.all([loadTrainees(), loadGuides()]))
  if (saved) Object.assign(account, { username: '', password: '', displayName: '', role: 'TRAINEE', guideId: null })
}

function toggleAccount(member: Actor | Trainee) {
  const enabled = member.enabled === false
  void mutate('/workspace/accounts/' + member.id, 'PATCH', { enabled },
    enabled ? '已启用账号。' : '已停用账号。', () => Promise.all([loadTrainees(), loadGuides()]))
}

function emptyTarget(): Target {
  return { angleMin: NaN, angleMax: NaN, cycleMin: NaN, cycleMax: NaN, hold: NaN }
}

function addPlanItem() {
  if (planDraft.items.length >= 20) return
  const side = trainees.data.find(trainee => trainee.id === planDraft.traineeId)?.side ?? 'RIGHT'
  planDraft.items.push({ exercise: 'elbow_flexion', side, mode: 'observe', sets: 1, reps: 5, target: emptyTarget() })
}

function planFor(trainee: Trainee) {
  const started = planDraft.traineeId !== null
  planDraft.traineeId = trainee.id
  if (!started && planDraft.items[0]) planDraft.items[0].side = trainee.side ?? 'RIGHT'
  navigate('plans')
}

function setTarget(item: PlanDraftItem, key: keyof Target, event: Event) {
  if (item.target) item.target[key] = (event.target as HTMLInputElement).valueAsNumber
}

async function publishPlan() {
  if (actionPending.value) return
  try {
    const payload = buildPlanPayload(planDraft)
    const saved = await mutate('/plans', 'POST', payload, '已发布计划，训练者现在可以开练。', loadPlans)
    if (saved) {
      planDraft.title = ''
      planDraft.items = []
      addPlanItem()
    }
  } catch (error) {
    actionMessage.value = ''
    actionError.value = messageFor(error)
  }
}

const reportBySet = computed(() => {
  const result = new Map<number, Map<number, ReportSummary>>()
  for (const report of reports.data) {
    const itemReports = result.get(report.planItemId) ?? new Map<number, ReportSummary>()
    const previous = itemReports.get(report.setNumber)
    if (!previous || report.id > previous.id) itemReports.set(report.setNumber, report)
    result.set(report.planItemId, itemReports)
  }
  return result
})

function beginPlan(plan: Plan, item: PlanItem) {
  if (!token.value || actor.value?.role !== 'TRAINEE') return
  emit('start', { token: token.value, planTitle: plan.title, item, setNumber: selectedSets[item.id] ?? 1,
    traineeId: plan.traineeId, username: actor.value.username })
}

function openReport(id: number) {
  navigate('reports')
  selectedReportId.value = id
}

function savedReview(id: number, status: ReviewStatus) {
  const summary = reports.data.find(report => report.id === id)
  if (summary) summary.reviewStatus = status
}

watch(selectedTrainee, trainee => {
  if (!trainee && trainees.ready && trainees.data[0]) chooseTrainee(trainees.data[0])
})
watch(() => trainees.data, records => {
  if (records[0] && !selectedTrainee.value) chooseTrainee(records[0])
})
watch([() => props.recentReportId, () => reports.data], ([id, summaries]) => {
  if (id && recentReportOpened.value !== id && actor.value?.role === 'TRAINEE' && summaries.some(report => report.id === id)) {
    recentReportOpened.value = id
    openReport(id)
  }
})

onMounted(() => {
  addPlanItem()
  void restoreSession()
})
onUnmounted(() => { mounted = false })
watch(() => actor.value?.role, role => {
  if (role === 'GUIDE' && !planDraft.items.length) addPlanItem()
})
</script>

<template>
  <main class="workspace rehab-workspace">
    <p v-if="checkingSession" class="ws-state" role="status">正在恢复登录…</p>

    <div v-else-if="!actor" class="ws-entry">
      <section class="ws-entry-intro" aria-labelledby="workspace-login-heading">
        <h1 id="workspace-login-heading">训练工作台</h1>
        <p>从训练计划进入摄像头跟练，保存每组记录并查看指导反馈。</p>
        <div class="ws-demo">
          <h2>演示账号</h2>
          <p>选择角色填入演示账号。</p>
          <div class="actions">
            <button v-for="demo in demos" :key="demo.username" type="button" class="secondary small" :disabled="loginPending" @click="selectDemo(demo.username)">
              {{ demo.label }}
            </button>
          </div>
        </div>
      </section>

      <form class="card ws-login-card" @submit.prevent="login">
        <h2>账号登录</h2>
        <label class="field">用户名 <input v-model="credentials.username" name="username" autocomplete="username" maxlength="64" required></label>
        <label class="field">密码 <input v-model="credentials.password" name="password" type="password" autocomplete="current-password" maxlength="128" required></label>
        <p v-if="authError" class="callout error" role="alert">{{ authError }}</p>
        <button class="primary" :disabled="loginPending">{{ loginPending ? '登录中…' : '登录' }}</button>
      </form>
    </div>

    <template v-else>
      <header class="ws-heading">
        <div>
          <h1>{{ ROLE_LABEL[actor.role] }}工作台</h1>
          <p>{{ introductions[actor.role] }}</p>
        </div>
        <div class="ws-identity">
          <div><strong>{{ actor.displayName }}</strong><small>{{ actor.username }}</small></div>
          <button class="secondary small" type="button" :disabled="actionPending" @click="logout">退出登录</button>
        </div>
      </header>

      <nav class="ws-nav" aria-label="工作台功能">
        <button v-for="panel in availablePanels" :key="panel.key" type="button" :aria-pressed="activePanel === panel.key" @click="navigate(panel.key)">
          {{ panel.label }}
        </button>
      </nav>
      <p v-if="actionError" class="callout error" role="alert">{{ actionError }}</p>
      <p v-if="actionMessage" class="callout good" role="status">{{ actionMessage }}</p>

      <div v-if="activePanel === 'trainees'" class="ws-grid">
        <section class="card ws-section">
          <h2>训练者档案</h2>
          <p v-if="trainees.loading" class="muted" role="status">正在加载训练者…</p>
          <p v-if="trainees.error" class="callout error" role="alert">
            {{ trainees.error }} <button class="secondary small" type="button" @click="loadTrainees">重试</button>
          </p>
          <p v-else-if="trainees.ready && !trainees.data.length" class="ws-state">暂无训练者。</p>
          <div v-if="trainees.data.length" class="ws-people">
            <button v-for="trainee in trainees.data" :key="trainee.id" class="ws-person" type="button"
              :aria-pressed="selectedTraineeId === trainee.id" @click="chooseTrainee(trainee)">
              <span><strong>{{ trainee.displayName }}</strong><small>{{ trainee.username }}</small></span>
              <span class="ws-tag">{{ trainee.side ? SIDE_LABEL[trainee.side] : '待登记' }}</span>
            </button>
          </div>
        </section>

        <section v-if="selectedTrainee" class="card ws-section">
          <div class="ws-section-heading">
            <h2>{{ selectedTrainee.displayName }}的基线</h2>
            <button v-if="actor.role === 'GUIDE' && selectedTrainee.enabled !== false" class="secondary small" type="button" @click="planFor(selectedTrainee)">为该训练者创建计划</button>
          </div>
          <p v-if="actor.role === 'ADMIN'" class="muted">当前指导者 · {{ guideName(selectedTrainee.guideId) }}</p>
          <p v-if="selectedTrainee.side === null" class="muted">尚未登记基线。</p>
          <form class="ws-form" @submit.prevent="saveBaseline">
            <label class="field">训练侧
              <select v-model="baseline.side"><option value="LEFT">左侧</option><option value="RIGHT">右侧</option></select>
            </label>
            <label class="field">基线备注 <textarea v-model="baseline.note" rows="4" maxlength="1000" placeholder="记录起始状态和观察条件"></textarea></label>
            <div><button class="primary" :disabled="actionPending">保存基线</button></div>
          </form>
          <form v-if="actor.role === 'ADMIN'" class="ws-divider ws-form" @submit.prevent="assignGuide">
            <label class="field">分配指导者
              <select v-model="assignGuideId" required>
                <option :value="null" disabled>请选择指导者</option>
                <option v-for="guide in guides.data" :key="guide.id" :value="guide.id" :disabled="guide.enabled === false">{{ guide.displayName }}</option>
              </select>
            </label>
            <p v-if="guides.error" class="muted">指导者名单加载失败。 <button class="secondary small" type="button" @click="loadGuides">重试</button></p>
            <div><button class="secondary" :disabled="actionPending || !assignGuideId || assignGuideId === selectedTrainee.guideId">保存分配</button></div>
          </form>
        </section>
      </div>

      <div v-else-if="activePanel === 'plans'" class="ws-stack">
        <section v-if="actor.role === 'GUIDE'" class="card ws-section">
          <h2>发布训练计划</h2>
          <p v-if="trainees.loading" class="muted" role="status">正在加载训练者…</p>
          <p v-if="trainees.error" class="callout error" role="alert">
            {{ trainees.error }} <button class="secondary small" type="button" @click="loadTrainees">重试</button>
          </p>
          <p v-else-if="trainees.ready && !eligibleTrainees.length" class="ws-state">暂无可分配计划的训练者。</p>
          <form v-if="eligibleTrainees.length" class="ws-form" @submit.prevent="publishPlan">
            <div class="ws-fields ws-fields-plan">
              <label class="field">计划名称 <input v-model="planDraft.title" maxlength="100" required></label>
              <label class="field">训练者
                <select v-model="planDraft.traineeId" required>
                  <option :value="null" disabled>请选择训练者</option>
                  <option v-for="trainee in eligibleTrainees" :key="trainee.id" :value="trainee.id">{{ trainee.displayName }}</option>
                </select>
              </label>
              <label class="field">计划日期 <input v-model="planDraft.scheduledDate" type="date" required></label>
            </div>

            <fieldset v-for="(item, index) in planDraft.items" :key="index" class="ws-plan-editor">
              <legend>动作 {{ index + 1 }}</legend>
              <div class="ws-fields ws-fields-item">
                <label class="field">动作
                  <select v-model="item.exercise">
                    <option v-for="[key, exercise] in exerciseOptions" :key="key" :value="key">{{ exercise.title }}</option>
                  </select>
                </label>
                <label class="field">训练侧
                  <select v-model="item.side"><option value="LEFT">左侧</option><option value="RIGHT">右侧</option></select>
                </label>
                <label class="field">组数 <input v-model.number="item.sets" type="number" min="1" max="20" required></label>
                <label class="field">每组次数 <input v-model.number="item.reps" type="number" min="1" max="100" required></label>
                <label class="field">记录方式
                  <select v-model="item.mode"><option value="observe">仅观察</option><option value="target">目标比较</option></select>
                </label>
              </div>
              <div v-if="item.mode === 'target' && item.target" class="ws-target-editor">
                <div class="ws-fields ws-fields-item">
                  <label v-for="field in TARGET_FIELDS" :key="field.key" class="field">{{ field.label }}
                    <input type="number" min="0" step="any" :value="Number.isFinite(item.target[field.key]) ? item.target[field.key] : ''" required @input="setTarget(item, field.key, $event)">
                  </label>
                </div>
                <p class="muted">幅度 0–180°，单次循环 0.6–30 秒。最低保持时长最多 10 秒，且不得超过最长循环时长。</p>
              </div>
              <button v-if="planDraft.items.length > 1" class="secondary small" type="button" :aria-label="'移除动作 ' + (index + 1)" @click="planDraft.items.splice(index, 1)">移除</button>
            </fieldset>
            <div class="actions">
              <button class="secondary" type="button" :disabled="actionPending || planDraft.items.length >= 20" @click="addPlanItem">添加动作</button>
              <button class="primary" :disabled="actionPending">发布计划</button>
            </div>
          </form>
        </section>

        <section class="ws-section ws-plan-section" aria-labelledby="published-plans">
          <div class="ws-section-heading">
            <h2 id="published-plans">{{ actor.role === 'TRAINEE' ? '我的训练计划' : '已发布计划' }}</h2>
            <button class="secondary small" type="button" :disabled="plans.loading" @click="loadPlans">刷新计划</button>
          </div>
          <p v-if="plans.loading" class="ws-state" role="status">正在加载计划…</p>
          <p v-if="plans.error" class="callout error" role="alert">{{ plans.error }}</p>
          <p v-else-if="plans.ready && !plans.data.length" class="ws-state">{{ actor.role === 'TRAINEE' ? '指导者尚未发布训练计划。' : '暂无已发布计划。' }}</p>
          <p v-if="actor.role === 'TRAINEE' && reports.error" class="callout error" role="alert">
            训练记录暂时无法加载。 <button class="secondary small" type="button" @click="loadReports">重试记录</button>
          </p>
          <article v-for="plan in plans.data" :key="plan.id" :data-plan-id="plan.id" class="card ws-plan-card">
            <div class="ws-section-heading">
              <h3>{{ plan.title }}</h3>
              <span class="muted"><template v-if="actor.role === 'GUIDE'">{{ plan.traineeName }} · </template>{{ plan.scheduledDate }}</span>
            </div>
            <div v-for="item in plan.items" :key="item.id" :data-plan-item-id="item.id" class="ws-plan-item">
              <div>
                <div class="ws-item-title"><strong>{{ EXERCISES[item.exercise].title }}</strong><span class="ws-tag">{{ MODE_LABEL[item.mode] }}</span></div>
                <p class="muted">{{ SIDE_LABEL[item.side] }} · {{ item.sets }} 组，每组 {{ item.reps }} 次 · {{ EXERCISES[item.exercise].direction }}</p>
                <p v-if="item.target" class="muted">目标幅度 {{ item.target.angleMin }}–{{ item.target.angleMax }}° · 循环 {{ item.target.cycleMin }}–{{ item.target.cycleMax }} 秒 · 保持至少 {{ item.target.hold }} 秒</p>
              </div>
              <div v-if="actor.role === 'TRAINEE'" class="ws-train-actions">
                <label class="field">选择训练组
                  <select :value="selectedSets[item.id] ?? 1" @change="selectedSets[item.id] = Number(($event.target as HTMLSelectElement).value)">
                    <option v-for="number in item.sets" :key="number" :value="number">
                      第 {{ number }} 组{{ reportBySet.get(item.id)?.has(number) ? ' · 已保存记录' : '' }}
                    </option>
                  </select>
                </label>
                <div class="actions">
                  <button class="primary small" type="button" @click="beginPlan(plan, item)">进入摄像头跟练</button>
                  <button v-if="reportBySet.get(item.id)?.has(selectedSets[item.id] ?? 1)" class="secondary small" type="button"
                    @click="openReport(reportBySet.get(item.id)!.get(selectedSets[item.id] ?? 1)!.id)">查看该组报告</button>
                </div>
              </div>
            </div>
          </article>
        </section>
      </div>

      <div v-else-if="activePanel === 'reports'" class="ws-stack">
        <WorkspaceReport v-if="selectedReportId !== null" :key="selectedReportId" :report-id="selectedReportId" :summary="selectedReport"
          :token="token" :can-review="actor.role === 'GUIDE'" @back="selectedReportId = null" @saved="savedReview" @expired="expireSession" />
        <section v-else class="card ws-section">
          <div class="ws-section-heading">
            <h2>{{ actor.role === 'TRAINEE' ? '我的训练报告' : '训练报告' }}</h2>
            <div class="actions ws-report-filters">
              <label class="ws-inline-field">复核状态
                <select v-model="reviewFilter">
                  <option value="ALL">全部</option>
                  <option v-for="(label, value) in REVIEW_STATUS_LABEL" :key="value" :value="value">{{ label }}</option>
                </select>
              </label>
              <button class="secondary small" type="button" :disabled="reports.loading" @click="loadReports">刷新报告</button>
            </div>
          </div>
          <p v-if="reports.loading" class="ws-state" role="status">正在加载报告…</p>
          <p v-if="reports.error" class="callout error" role="alert">{{ reports.error }}</p>
          <p v-else-if="reports.ready && !shownReports.length" class="ws-state">{{ reports.data.length ? '没有符合筛选条件的报告。' : '暂无已保存的计划训练报告。' }}</p>
          <div v-if="shownReports.length" class="report-table-wrap">
            <table class="report-table ws-reports-table">
              <thead><tr><th>计划与动作</th><th>保存时间</th><th>完整次数</th><th>复核状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="report in shownReports" :key="report.id" :data-report-id="report.id">
                  <td><strong>{{ report.planTitle || EXERCISES[report.exercise].title }}</strong>
                    <small><template v-if="actor.role !== 'TRAINEE'">{{ report.traineeName }} · </template>{{ EXERCISES[report.exercise].title }} · {{ SIDE_LABEL[report.side] }} · 第 {{ report.setNumber }} 组</small>
                  </td>
                  <td>{{ formatDate(report.createdAt) }}</td><td>{{ report.repetitionCount }}</td>
                  <td><span class="ws-tag" :data-status="report.reviewStatus">{{ REVIEW_STATUS_LABEL[report.reviewStatus] }}</span></td>
                  <td><button class="secondary small" type="button" @click="openReport(report.id)">查看报告</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <div v-else-if="activePanel === 'accounts'" class="ws-grid">
        <section class="card ws-section">
          <h2>创建账号</h2>
          <form class="ws-form" @submit.prevent="createAccount">
            <label class="field">角色
              <select v-model="account.role"><option value="TRAINEE">训练者</option><option value="GUIDE">指导者</option></select>
            </label>
            <label class="field">姓名 <input v-model="account.displayName" maxlength="100" required></label>
            <label class="field">用户名
              <input v-model="account.username" maxlength="64" pattern="[A-Za-z0-9._\-]+" title="字母、数字、句点、下划线或连字符" autocomplete="off" required>
            </label>
            <label class="field">初始密码 <input v-model="account.password" type="password" minlength="8" maxlength="128" autocomplete="new-password" required></label>
            <label v-if="account.role === 'TRAINEE'" class="field">指导者
              <select v-model="account.guideId">
                <option :value="null">暂不分配</option>
                <option v-for="guide in guides.data" :key="guide.id" :value="guide.id" :disabled="guide.enabled === false">{{ guide.displayName }}</option>
              </select>
            </label>
            <div><button class="primary" :disabled="actionPending">创建账号</button></div>
          </form>
        </section>
        <section class="card ws-section">
          <h2>训练者与指导者</h2>
          <p v-if="guides.loading || trainees.loading" class="muted" role="status">正在加载账号…</p>
          <p v-if="guides.error || trainees.error" class="callout error" role="alert">
            {{ guides.error || trainees.error }}
            <button class="secondary small" type="button" @click="refreshWorkspace">重试</button>
          </p>
          <p v-else-if="guides.ready && trainees.ready && !guides.data.length && !trainees.data.length" class="ws-state">暂无账号。</p>
          <div class="ws-account-list">
            <div v-for="member in [...guides.data, ...trainees.data]" :key="member.id" class="ws-account-row">
              <span><strong>{{ member.displayName }}</strong><small>{{ member.username }} · {{ 'role' in member ? '指导者' : '训练者' }}</small></span>
              <span class="ws-tag">{{ member.enabled === false ? '已停用' : '已启用' }}</span>
              <button class="secondary small" type="button" :disabled="actionPending" :aria-label="(member.enabled === false ? '启用 ' : '停用 ') + member.displayName" @click="toggleAccount(member)">
                {{ member.enabled === false ? '启用' : '停用' }}
              </button>
            </div>
          </div>
        </section>
      </div>
    </template>
  </main>
</template>
