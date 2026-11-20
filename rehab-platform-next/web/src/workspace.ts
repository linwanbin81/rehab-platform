import { targetValid, type Exercise, type Repetition, type Side, type Target } from './motion.ts'

export type Role = 'ADMIN' | 'GUIDE' | 'TRAINEE'
export type Actor = { id: number; username: string; displayName: string; role: Role; enabled?: boolean }
export type Trainee = {
  id: number
  username: string
  displayName: string
  guideId: number | null
  side: Side | null
  baseline: string | null
  enabled?: boolean
}

export type PlanItem = {
  id: number
  exercise: Exercise
  side: Side
  mode: 'observe' | 'target'
  sets: number
  reps: number
  target: Target | null
}
export type CameraAssignment = { token: string; planTitle: string; item: PlanItem; setNumber: number; traineeId?: number; username?: string }
export type Plan = { id: number; title: string; traineeId: number; traineeName: string; scheduledDate: string; items: PlanItem[] }
export type PlanDraftItem = Omit<PlanItem, 'id'>
export type PlanDraft = { title: string; traineeId: number | null; scheduledDate: string; items: PlanDraftItem[] }

export type Report = {
  id: number
  planItemId: number
  setNumber: number
  exercise: Exercise
  side: Side
  mode: PlanItem['mode']
  target: Target | null
  createdAt: string
  rulesVersion: string
  validFrames: number
  totalFrames: number
  interruptedAttempts: number
  duration: number
  repetitions: Repetition[]
}
export type ReviewStatus = 'PENDING' | 'IN_REVIEW' | 'FINALIZED'
export type ReviewMetric = 'amplitude' | 'tempo' | 'hold'
export type ReviewDecision = 'CONFIRMED' | 'DISMISSED'
export type DeviationEvent = { repetition: number; metric: ReviewMetric }
export type EventReview = DeviationEvent & { decision: ReviewDecision; note: string }
export type Review = { status: ReviewStatus; note: string; events: EventReview[] }
export type ReportSummary = Pick<Report, 'id' | 'planItemId' | 'setNumber' | 'exercise' | 'side' | 'mode' | 'createdAt'> & {
  traineeId: number
  traineeName: string
  planTitle?: string
  repetitionCount: number
  reviewStatus: ReviewStatus
}

export const WORKSPACE_TOKEN_KEY = 'rehab.workspace.token'
export const ROLE_LABEL: Record<Role, string> = { ADMIN: '管理员', GUIDE: '指导者', TRAINEE: '训练者' }
export const SIDE_LABEL: Record<Side, string> = { LEFT: '左侧', RIGHT: '右侧' }
export const MODE_LABEL: Record<PlanItem['mode'], string> = { observe: '仅观察', target: '目标比较' }
export const REVIEW_STATUS_LABEL: Record<ReviewStatus, string> = { PENDING: '待复核', IN_REVIEW: '复核中', FINALIZED: '已完成复核' }
export const METRIC_LABEL: Record<ReviewMetric, string> = { amplitude: '幅度', tempo: '节奏', hold: '保持' }
export const DECISION_LABEL: Record<ReviewDecision, string> = { CONFIRMED: '确认偏离', DISMISSED: '驳回偏离' }
export const TARGET_FIELDS = [
  { key: 'angleMin', label: '最小幅度（°）' },
  { key: 'angleMax', label: '最大幅度（°）' },
  { key: 'cycleMin', label: '最短循环（秒）' },
  { key: 'cycleMax', label: '最长循环（秒）' },
  { key: 'hold', label: '最低连续保持（秒）' },
] as const satisfies readonly { key: keyof Target; label: string }[]

export function buildPlanPayload(draft: PlanDraft) {
  const title = draft.title.trim()
  const traineeId = draft.traineeId
  if (traineeId === null || !Number.isSafeInteger(traineeId) || traineeId < 1) throw new Error('请选择训练者。')
  if (!title || title.length > 100) throw new Error('请填写计划名称，最多 100 字。')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.scheduledDate)) throw new Error('请选择计划日期。')
  if (!draft.items.length || draft.items.length > 20) throw new Error('请添加 1 至 20 个训练动作。')
  const items = draft.items.map(({ exercise, side, mode, sets, reps, target }, index) => {
    if (!Number.isInteger(sets) || sets < 1 || sets > 20 || !Number.isInteger(reps) || reps < 1 || reps > 100) {
      throw new Error(`第 ${index + 1} 个动作的组数须为 1–20 的整数，每组次数须为 1–100 的整数。`)
    }
    if (mode === 'target' && (!target || !targetValid(target))) {
      throw new Error(`请检查第 ${index + 1} 个动作的幅度、循环时长和保持时长。`)
    }
    return { exercise, side, mode, sets, reps, target: mode === 'target' ? target : null }
  })
  return { title, traineeId, scheduledDate: draft.scheduledDate, items }
}

export function deviationEvents(repetitions: Repetition[]): DeviationEvent[] {
  return repetitions.flatMap((repetition, index) =>
    (Object.keys(METRIC_LABEL) as ReviewMetric[])
      .filter(metric => repetition.checks[metric] === false)
      .map(metric => ({ repetition: index + 1, metric })),
  )
}

export function reviewDraftsSaved(drafts: readonly (DeviationEvent & { decision: string; note: string })[], saved: readonly EventReview[]) {
  return drafts.every(draft => saved.some(event => event.repetition === draft.repetition && event.metric === draft.metric &&
    event.decision === draft.decision && event.note === draft.note.trim()))
}

export class WorkspaceError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

export async function workspaceRequest<T>(path: string, token?: string, method = 'GET', body?: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new Error('暂时无法连接训练服务，请稍后重试。')
  }
  if (response.status === 204) return undefined as T
  const result = await response.json().catch(() => null)
  if (!response.ok) throw new WorkspaceError(result?.message || `请求失败（${response.status}），请重试。`, response.status)
  if (result === null) throw new Error('未能读取训练服务的响应，请重试。')
  return result as T
}
