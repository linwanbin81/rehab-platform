import assert from 'node:assert/strict'
import test from 'node:test'
import { angleSegments } from '../src/angle-trace.ts'

test('the live angle trace leaves gaps for values the camera could not observe', () => {
  const first = { time: 1, angle: 32 }
  const afterOcclusion = { time: 1.2, angle: 43 }
  const afterPause = { time: 2.1, angle: 64 }
  const next = { time: 2.2, angle: 66 }
  assert.deepEqual(angleSegments([first, { time: 1.1, angle: null }, afterOcclusion, afterPause, next]),
    [[first], [afterOcclusion], [afterPause, next]])
})
