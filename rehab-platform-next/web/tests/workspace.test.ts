import assert from 'node:assert/strict'
import test from 'node:test'
import { buildPlanPayload, deviationEvents, reviewDraftsSaved, type PlanDraft, type Report } from '../src/workspace.ts'

const target = { angleMin: 75, angleMax: 110, cycleMin: 2, cycleMax: 5, hold: 1 }
const draft: PlanDraft = {
  traineeId: 42,
  title: ' 上肢训练 ',
  scheduledDate: '2026-11-20',
  items: [
    { exercise: 'shoulder_flexion', side: 'LEFT', mode: 'observe', sets: 2, reps: 4, target },
    { exercise: 'elbow_flexion', side: 'RIGHT', mode: 'target', sets: 1, reps: 6, target },
  ],
}

test('publishes all rows in order and excludes stale targets from observation mode', () => {
  assert.deepEqual(buildPlanPayload(draft), {
    traineeId: 42,
    title: '上肢训练',
    scheduledDate: '2026-11-20',
    items: [
      { exercise: 'shoulder_flexion', side: 'LEFT', mode: 'observe', sets: 2, reps: 4, target: null },
      { exercise: 'elbow_flexion', side: 'RIGHT', mode: 'target', sets: 1, reps: 6, target },
    ],
  })
})

test('rejects missing, reversed or nonnumeric targets before publishing', () => {
  for (const invalid of [null, { ...target, angleMin: 120 }, { ...target, hold: NaN }]) {
    assert.throws(() => buildPlanPayload({
      ...draft,
      items: [{ ...draft.items[1]!, target: invalid }],
    }), /第 1 个动作/)
  }
})

test('rejects zero or fractional group and repetition counts before publishing', () => {
  for (const invalid of [{ sets: 0 }, { sets: 1.5 }, { reps: 101 }]) {
    assert.throws(() => buildPlanPayload({
      ...draft,
      items: [{ ...draft.items[0]!, ...invalid }],
    }), /第 1 个动作/)
  }
})

test('review rows include only compared failures and keep one-based repetition numbers', () => {
  const repetitions: Report['repetitions'] = [
    { start: 1, end: 4, peak: 101, seconds: 3, holdSeconds: 1, checks: { amplitude: true, tempo: true, hold: true } },
    { start: 5, end: 10, peak: 54, seconds: 5, holdSeconds: 0, checks: { amplitude: false, tempo: true, hold: null } },
    { start: 11, end: 13, peak: 101, seconds: 2, holdSeconds: 0.5, checks: { amplitude: true, tempo: false, hold: false } },
  ]
  assert.deepEqual(deviationEvents(repetitions), [
    { repetition: 2, metric: 'amplitude' }, { repetition: 3, metric: 'tempo' }, { repetition: 3, metric: 'hold' },
  ])
})

test('finalizing requires the latest event decisions and notes to be saved', () => {
  const saved = [{ repetition: 1, metric: 'amplitude' as const, decision: 'CONFIRMED' as const, note: '已核对' }]
  assert.equal(reviewDraftsSaved([{ ...saved[0] }], saved), true)
  assert.equal(reviewDraftsSaved([{ ...saved[0], decision: 'DISMISSED' }], saved), false)
  assert.equal(reviewDraftsSaved([{ ...saved[0], note: '新的意见' }], saved), false)
  assert.equal(reviewDraftsSaved(saved, []), false)
  assert.equal(reviewDraftsSaved([{ ...saved[0], note: ' 已核对 ' }], saved), true)
})
