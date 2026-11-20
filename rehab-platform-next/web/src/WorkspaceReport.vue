<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { EXERCISES } from './motion.ts'
import {
  DECISION_LABEL, deviationEvents, reviewDraftsSaved, METRIC_LABEL, MODE_LABEL, REVIEW_STATUS_LABEL, SIDE_LABEL,
  WorkspaceError, workspaceRequest, type DeviationEvent, type Report, type ReportSummary,
  type Review, type ReviewDecision, type ReviewStatus,
} from './workspace.ts'

const props = defineProps<{ reportId: number; summary?: ReportSummary; token: string; canReview: boolean }>()
const emit = defineEmits<{ back: []; saved: [id: number, status: ReviewStatus]; expired: [token: string] }>()

type EventDraft = DeviationEvent & { decision: ReviewDecision | ''; note: string }
const report = ref<Report | null>(null)
const review = ref<Review | null>(null)
const reportError = ref('')
const reviewError = ref('')
const savingError = ref('')
const savedMessage = ref('')
const loadingReport = ref(true)
const loadingReview = ref(true)
const saving = ref(false)
const eventRows = ref<EventDraft[]>([])
const overallNote = ref('')
const path = computed(() => '/observations/' + props.reportId)
const canEdit = computed(() => props.canReview && !!review.value && review.value.status !== 'FINALIZED')
const canFinalize = computed(() => !!overallNote.value.trim() && reviewDraftsSaved(eventRows.value, review.value?.events ?? []))
const heading = computed(() => props.summary?.planTitle || (report.value && EXERCISES[report.value.exercise].title))

function matches(left: DeviationEvent, right: DeviationEvent) {
  return left.repetition === right.repetition && left.metric === right.metric
}

function storedDecision(event: DeviationEvent) {
  return review.value?.events.find(candidate => matches(candidate, event))
}

function loadDrafts() {
  if (!report.value || !review.value) return
  overallNote.value = review.value.note
  eventRows.value = deviationEvents(report.value.repetitions).map(event => {
    const saved = storedDecision(event)
    return { ...event, decision: saved?.decision ?? '', note: saved?.note ?? '' }
  })
}

function errorText(error: unknown) {
  if (error instanceof WorkspaceError && error.status === 401) emit('expired', props.token)
  return error instanceof Error ? error.message : '操作失败，请重试。'
}

async function loadReport() {
  loadingReport.value = true
  reportError.value = ''
  try {
    report.value = await workspaceRequest<Report>(path.value, props.token)
    loadDrafts()
  } catch (error) {
    reportError.value = errorText(error)
  } finally {
    loadingReport.value = false
  }
}

async function loadReview() {
  loadingReview.value = true
  reviewError.value = ''
  try {
    review.value = await workspaceRequest<Review>(path.value + '/review', props.token)
    loadDrafts()
  } catch (error) {
    reviewError.value = errorText(error)
  } finally {
    loadingReview.value = false
  }
}

async function saveReview(endpoint: string, body: unknown, message: string) {
  if (!canEdit.value || saving.value) return
  savingError.value = ''
  savedMessage.value = ''
  saving.value = true
  try {
    const updated = await workspaceRequest<Review>(path.value + endpoint, props.token, 'POST', body)
    review.value = updated
    emit('saved', props.reportId, updated.status)
    savedMessage.value = message
  } catch (error) {
    savingError.value = errorText(error)
  } finally {
    saving.value = false
  }
}

function saveEvent({ repetition, metric, decision, note }: EventDraft) {
  if (decision) void saveReview('/event-reviews', { repetition, metric, decision, note }, '已保存该项复核。')
}

function saveOverall(finalize: boolean) {
  if (!finalize || canFinalize.value) {
    void saveReview('/review', { note: overallNote.value, finalize }, finalize ? '已完成复核。' : '已保存总体意见。')
  }
}

function checkLabel(value: boolean | null) {
  return value === null ? '未比较' : value ? '符合目标' : '偏离目标'
}

onMounted(() => {
  void loadReport()
  void loadReview()
})
</script>

<template>
  <section class="card ws-section" aria-labelledby="ws-report-heading" :data-report-id="reportId">
    <div class="ws-section-heading">
      <h2 id="ws-report-heading">训练报告详情</h2>
      <button class="secondary small" type="button" @click="emit('back')">返回报告列表</button>
    </div>
    <p v-if="loadingReport" class="ws-state" role="status">正在读取报告…</p>
    <p v-if="reportError" class="callout error" role="alert">
      {{ reportError }} <button class="secondary small" type="button" @click="loadReport">重试</button>
    </p>

    <template v-if="report">
      <div class="ws-report-summary">
        <div>
          <h3>{{ heading }}</h3>
          <p>{{ EXERCISES[report.exercise].title }} · {{ SIDE_LABEL[report.side] }} · 第 {{ report.setNumber }} 组 · {{ MODE_LABEL[report.mode] }}</p>
        </div>
        <span v-if="review" class="ws-tag" :data-status="review.status">{{ REVIEW_STATUS_LABEL[review.status] }}</span>
      </div>
      <dl class="ws-metrics">
        <div><dt>完整循环</dt><dd>{{ report.repetitions.length }} 次</dd></div>
        <div><dt>可用帧</dt><dd>{{ report.validFrames }} / {{ report.totalFrames }}</dd></div>
        <div><dt>中断动作</dt><dd>{{ report.interruptedAttempts }} 次</dd></div>
        <div><dt>采集用时</dt><dd>{{ report.duration.toFixed(1) }} 秒</dd></div>
      </dl>
      <p v-if="report.target" class="muted">本组目标幅度 {{ report.target.angleMin }}–{{ report.target.angleMax }}° · 循环 {{ report.target.cycleMin }}–{{ report.target.cycleMax }} 秒 · 保持至少 {{ report.target.hold }} 秒</p>
      <p v-if="!report.repetitions.length" class="ws-state">{{ report.validFrames ? '本组未记录到完整动作循环。' : '本组缺少可用姿态帧。' }}</p>
      <div v-else class="report-table-wrap">
        <table class="report-table ws-results-table">
          <thead><tr><th>动作次数</th><th>幅度</th><th>节奏</th><th>保持</th><th>时间段</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in report.repetitions" :key="index">
              <td>第 {{ index + 1 }} 次</td>
              <td>{{ row.peak.toFixed(1) }}° <small>{{ checkLabel(row.checks.amplitude) }}</small></td>
              <td>{{ row.seconds.toFixed(1) }} 秒 <small>{{ checkLabel(row.checks.tempo) }}</small></td>
              <td>{{ row.checks.hold === null ? '—' : row.holdSeconds.toFixed(1) + ' 秒' }} <small>{{ checkLabel(row.checks.hold) }}</small></td>
              <td>{{ row.start.toFixed(1) }}–{{ row.end.toFixed(1) }} 秒</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted">幅度为当前机位的图像平面投影角度。分析规则 {{ report.rulesVersion }}。</p>
    </template>
  </section>

  <section v-if="report" class="card ws-section" aria-labelledby="ws-review-heading">
    <div class="ws-section-heading">
      <h2 id="ws-review-heading">指导复核</h2>
      <span v-if="review" class="ws-tag" :data-status="review.status">{{ REVIEW_STATUS_LABEL[review.status] }}</span>
    </div>
    <p v-if="loadingReview" class="ws-state" role="status">正在加载复核…</p>
    <p v-if="reviewError" class="callout error" role="alert">
      {{ reviewError }} <button class="secondary small" type="button" @click="loadReview">重试</button>
    </p>
    <template v-if="review">
      <p v-if="savingError" class="callout error" role="alert">{{ savingError }}</p>
      <p v-if="savedMessage" class="callout good" role="status">{{ savedMessage }}</p>
      <p v-if="!eventRows.length" class="ws-state">本组没有待复核的目标偏离。</p>
      <div v-for="event in eventRows" :key="event.repetition + '-' + event.metric" class="ws-review-item">
        <h3>第 {{ event.repetition }} 次 · {{ METRIC_LABEL[event.metric] }}</h3>
        <p class="muted">{{ storedDecision(event) ? DECISION_LABEL[storedDecision(event)!.decision] : '待人工复核' }}</p>
        <form v-if="canEdit" class="ws-review-form" @submit.prevent="saveEvent(event)">
          <label class="field">复核结论
            <select v-model="event.decision" required>
              <option disabled value="">请选择结论</option>
              <option v-for="(label, value) in DECISION_LABEL" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <label class="field">分项备注 <input v-model="event.note" maxlength="1000" placeholder="可补充可见情况或判断依据"></label>
          <button class="secondary small" :disabled="saving || !event.decision">保存分项复核</button>
        </form>
        <p v-else-if="storedDecision(event)?.note" class="ws-review-note">{{ storedDecision(event)!.note }}</p>
      </div>

      <form v-if="canEdit" class="ws-divider ws-form" @submit.prevent="saveOverall(false)">
        <label class="field">总体意见 <textarea v-model="overallNote" maxlength="1000" rows="4" placeholder="记录本组观察和后续练习建议"></textarea></label>
        <p v-if="!canFinalize" class="muted">请填写总体意见，并保存每项复核结论后再完成复核。</p>
        <div class="actions">
          <button class="secondary" :disabled="saving || !overallNote.trim()">保存总体意见</button>
          <button class="primary" type="button" :disabled="saving || !canFinalize" @click="saveOverall(true)">完成复核</button>
        </div>
      </form>
      <div v-else class="ws-divider">
        <h3>总体意见</h3>
        <p v-if="review.note" class="ws-review-note">{{ review.note }}</p>
        <p v-else class="muted">暂无指导意见。</p>
      </div>
    </template>
  </section>
</template>
