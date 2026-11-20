import assert from 'node:assert/strict'
import test from 'node:test'
import { createObserver, measure, resumeOffset, type Frame, type Point } from '../src/motion.ts'

function frame(time: number, angle: number, visibility = .96, poses = 1): Frame {
  const points: Point[] = Array.from({ length: 33 }, () => ({ x: .5, y: .5, visibility }))
  points[12] = { x: .5, y: .35, visibility }
  points[14] = { x: .5, y: .5, visibility }
  points[24] = { x: .5, y: .7, visibility }
  const radians = angle * Math.PI / 180
  points[16] = { x: .5 + .12 * Math.sin(radians) * .75, y: .5 + .12 * Math.cos(radians), visibility }
  return { time, width: 640, height: 480, poses, points }
}

const target = { angleMin: 85, angleMax: 110, cycleMin: 1, cycleMax: 5, hold: .35 }

test('uses image aspect ratio and returns a right angle for a bent elbow', () => {
  assert.ok(Math.abs(measure(frame(0, 90), 'elbow_flexion', 'RIGHT').angle! - 90) < .01)
  assert.ok(Math.abs(measure(frame(0, 45), 'elbow_flexion', 'RIGHT').angle! - 45) < .01)
})

test('counts only after returning and gives separate actual target checks', () => {
  for (const [peak, amplitude] of [[95, true], [65, false]] as const) {
    const observer = createObserver('elbow_flexion', 'RIGHT', target)
    for (let i = 0; i < 14; i++) observer.push(frame(i / 10, 0))
    for (let i = 14; i < 30; i++) observer.push(frame(i / 10, peak))
    assert.equal(observer.repetitions.length, 0)
    for (let i = 30; i < 45; i++) observer.push(frame(i / 10, 0))
    assert.equal(observer.repetitions.length, 1)
    assert.deepEqual(observer.repetitions[0].checks, { amplitude, tempo: true, hold: amplitude })
  }
})

test('an occlusion does not become a return movement or a zero angle', () => {
  const observer = createObserver('elbow_flexion', 'RIGHT', target)
  for (let i = 0; i < 13; i++) observer.push(frame(i / 10, 0))
  for (let i = 13; i < 24; i++) observer.push(frame(i / 10, 95))
  for (let i = 24; i < 39; i++) {
    const state = observer.push(frame(i / 10, 0, .1))
    assert.equal(state.angle, null)
  }
  for (let i = 39; i < 56; i++) observer.push(frame(i / 10, 0))
  assert.equal(observer.repetitions.length, 0)
  assert.equal(measure(frame(0, 40, .95, 2), 'elbow_flexion', 'RIGHT').angle, null)
})

test('holding requires an observed stay in range even for a short positive target', () => {
  const observer = createObserver('elbow_flexion', 'RIGHT', { ...target, hold: .1 })
  for (let i = 0; i < 14; i++) observer.push(frame(i / 10, 0))
  for (let i = 14; i < 30; i++) observer.push(frame(i / 10, 65))
  for (let i = 30; i < 45; i++) observer.push(frame(i / 10, 0))
  assert.equal(observer.repetitions.length, 1)
  assert.equal(observer.repetitions[0].holdSeconds, 0)
  assert.equal(observer.repetitions[0].checks.hold, false)
})

test('resuming after a short pause forces re-calibration without counting an interrupted repetition', () => {
  const observer = createObserver('elbow_flexion', 'RIGHT', target)
  for (let i = 0; i < 10; i++) observer.push(frame(i / 10, 0))
  for (let i = 10; i < 21; i++) observer.push(frame(i / 10, 95))
  const offset = resumeOffset(2, 2.01, 0)
  const afterPause = observer.push(frame(2.01 + offset, 0))
  assert.equal(afterPause.phase, 'calibrating')
  assert.equal(observer.interruptedAttempts, 1)
  assert.equal(observer.repetitions.length, 0)
  assert.equal(resumeOffset(2, 302, 0), 0)
})
