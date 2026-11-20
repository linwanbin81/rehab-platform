import assert from 'node:assert/strict'
import test from 'node:test'
import { cameraSettings, observationRequest } from '../src/plan-camera.ts'

const target = { angleMin: 80, angleMax: 100, cycleMin: 1.5, cycleMax: 6, hold: .8 }
const assignment = {
  token: 'workspace-token', planTitle: '肘屈伸观察', setNumber: 2,
  item: { id: 35, exercise: 'elbow_flexion', side: 'LEFT', mode: 'target', sets: 3, reps: 8, target },
} as const

test('assigned camera settings use the prescribed exercise, side and goals', () => {
  const settings = cameraSettings(assignment)
  assert.deepEqual(settings, { exercise: 'elbow_flexion', side: 'LEFT', mode: 'target', target })
  assert.ok(settings.target)
  settings.target.angleMin = 50
  assert.equal(assignment.item.target.angleMin, 80)
})

test('saving an assigned set includes credentials and the exact plan item and set', () => {
  const request = observationRequest(cameraSettings(assignment), [], 'capture-key', assignment)
  assert.equal(request.headers.Authorization, 'Bearer workspace-token')
  assert.equal(request.headers['Idempotency-Key'], 'capture-key')
  assert.deepEqual(JSON.parse(request.body), {
    exercise: 'elbow_flexion', side: 'LEFT', mode: 'target', target, frames: [], planItemId: 35, setNumber: 2,
  })
})

test('trial camera saves with no private plan or account association', () => {
  const request = observationRequest(cameraSettings(), [], 'trial-key')
  assert.equal(request.headers.Authorization, undefined)
  assert.deepEqual(JSON.parse(request.body), {
    exercise: 'elbow_flexion', side: 'RIGHT', mode: 'observe', target: null, frames: [],
  })
})
