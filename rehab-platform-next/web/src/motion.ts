export type Exercise = 'shoulder_flexion' | 'shoulder_abduction' | 'elbow_flexion'
export type Side = 'LEFT' | 'RIGHT'
export type Point = { x: number; y: number; visibility: number; presence?: number }
export type Frame = { time: number; width: number; height: number; poses: number; points: Point[] }
export type Target = { angleMin: number; angleMax: number; cycleMin: number; cycleMax: number; hold: number }
type Checks = { amplitude: boolean | null; tempo: boolean | null; hold: boolean | null }
export type Repetition = { peak: number; seconds: number; holdSeconds: number; start: number; end: number; checks: Checks }
export type MotionState = {
  angle: number | null
  phase: 'calibrating' | 'ready' | 'raising' | 'returning' | 'unreliable'
  reason?: string
}

export const EXERCISES: Record<Exercise, { title: string; direction: string; action: string; source: string }> = {
  shoulder_flexion: {
    title: '肩前屈', direction: '侧面机位', action: '训练侧朝向摄像头。手臂从体侧向前抬起，再缓慢回到起始位置。',
    source: 'https://orthoinfo.aaos.org/en/recovery/shoulder-surgery-exercise-guide/',
  },
  shoulder_abduction: {
    title: '肩外展', direction: '正面机位', action: '面向摄像头。手臂从体侧向身体侧方抬起，再缓慢放回体侧。',
    source: 'https://orthoinfo.aaos.org/en/recovery/shoulder-surgery-exercise-guide/',
  },
  elbow_flexion: {
    title: '肘屈伸', direction: '侧面机位', action: '训练侧朝向摄像头。上臂贴近体侧，缓慢弯曲肘部，再缓慢伸回起始位置。',
    source: 'https://orthoinfo.aaos.org/en/recovery/rotator-cuff-and-shoulder-conditioning-program/',
  },
}

const JOINTS = { LEFT: [11, 13, 15, 23], RIGHT: [12, 14, 16, 24] } as const
const INVALID = { phase: 'unreliable' as const, angle: null }
const GAP = .8

export function resumeOffset(lastFrameTime: number | undefined, wallSeconds: number, offset: number) {
  if (lastFrameTime === undefined) return offset
  // Guarantee the analyzer sees a gap even when the pause lasted less than its occlusion threshold.
  return Math.max(offset, lastFrameTime + GAP + .1 - wallSeconds)
}

function visible(point: Point | undefined) {
  return point && Math.min(point.visibility, point.presence ?? 1) >= .65 &&
    point.x >= .01 && point.x <= .99 && point.y >= .01 && point.y <= .99
}

export function measure(frame: Frame, exercise: Exercise, side: Side): MotionState {
  if (frame.poses !== 1 || frame.points.length !== 33) {
    return { ...INVALID, reason: frame.poses > 1 ? '请确保画面中只有训练者' : '请让肩、肘、手腕和髋部完整进入画面' }
  }
  const [shoulder, elbow, wrist, hip] = JOINTS[side].map(index => frame.points[index])
  if (![shoulder, elbow, wrist, hip].every(visible)) return { ...INVALID, reason: '关节被遮挡或接近画面边缘' }

  const vector = (from: Point, to: Point) => [
    (to.x - from.x) * frame.width,
    (to.y - from.y) * frame.height,
  ] as const
  const upperArm = vector(shoulder, elbow)
  const forearm = vector(elbow, wrist)
  const torso = vector(shoulder, hip)
  if (Math.hypot(...upperArm) < 12 || Math.hypot(...forearm) < 10 || Math.hypot(...torso) < 20) {
    return { ...INVALID, reason: '关节在画面里的投影太短，请调整机位或站远一点再试' }
  }

  const elbowMode = exercise === 'elbow_flexion'
  const first = elbowMode ? [-upperArm[0], -upperArm[1]] : torso
  const second = elbowMode ? forearm : upperArm
  const cosine = (first[0] * second[0] + first[1] * second[1]) / (Math.hypot(...first) * Math.hypot(...second))
  const degrees = Math.acos(Math.max(-1, Math.min(1, cosine))) * 180 / Math.PI
  return { angle: elbowMode ? 180 - degrees : degrees, phase: 'ready' }
}

export function targetValid(target: Target) {
  return Object.values(target).every(Number.isFinite) &&
    target.angleMin >= 0 && target.angleMin < target.angleMax && target.angleMax <= 180 &&
    target.cycleMin >= .6 && target.cycleMin <= target.cycleMax && target.cycleMax <= 30 &&
    target.hold >= 0 && target.hold <= Math.min(target.cycleMax, 10)
}

type Candidate = { start: number; peak: number; holdStart: number | null; longestHold: number; returnSince: number | null; restSince: number | null }

export function createObserver(exercise: Exercise, side: Side, target: Target | null) {
  const repetitions: Repetition[] = []
  let calibration: { time: number; angle: number }[] = []
  let baseline: number | null = null
  let smoothed: number | null = null
  let lastTime: number | null = null
  let lastValid: number | null = null
  let candidate: Candidate | null = null
  let interruptedAttempts = 0

  function reset() {
    if (candidate) interruptedAttempts++
    candidate = null
    calibration = []
    baseline = null
    smoothed = null
  }

  function push(frame: Frame): MotionState {
    const time = frame.time
    const elapsed = lastTime === null ? 0 : time - lastTime
    lastTime = time
    const sample = measure(frame, exercise, side)
    if (lastValid !== null && time - lastValid > GAP) reset()
    if (sample.angle === null) {
      if (candidate) {
        candidate.holdStart = null
        candidate.returnSince = null
        candidate.restSince = null
      }
      if (baseline === null) calibration = []
      return sample
    }
    lastValid = time
    smoothed = smoothed === null ? sample.angle : smoothed + (1 - Math.exp(-Math.min(elapsed, .2) / .12)) * (sample.angle - smoothed)
    const angle = smoothed

    if (baseline === null) {
      calibration.push({ time, angle })
      calibration = calibration.filter(point => time - point.time <= 1.2)
      if (calibration.length >= 6 && time - calibration[0].time >= .7) {
        const angles = calibration.map(point => point.angle).sort((a, b) => a - b)
        if (angles.at(-1)! - angles[0] <= 6) {
          baseline = (angles[Math.floor((angles.length - 1) / 2)] + angles[Math.floor(angles.length / 2)]) / 2
        }
      }
      return { angle, phase: baseline === null ? 'calibrating' : 'ready' }
    }

    if (!candidate) {
      if (angle < baseline + 12) return { angle, phase: 'ready' }
      candidate = { start: time, peak: angle, holdStart: null, longestHold: 0, returnSince: null, restSince: null }
      return { angle, phase: 'raising' }
    }

    if (time - candidate.start > 30) {
      reset()
      return { angle, phase: 'calibrating' }
    }
    candidate.peak = Math.max(candidate.peak, angle)
    if (target && angle >= target.angleMin && angle <= target.angleMax) {
      candidate.holdStart ??= time
      candidate.longestHold = Math.max(candidate.longestHold, time - candidate.holdStart)
    } else {
      candidate.holdStart = null
    }

    if (candidate.peak - angle >= 6) candidate.returnSince ??= time
    else candidate.returnSince = null
    const returning = candidate.returnSince !== null && time - candidate.returnSince >= .15
    const seconds = time - candidate.start
    if (returning && angle <= baseline + 6) {
      candidate.restSince ??= time
      if (time - candidate.restSince >= .2 && seconds >= .6) {
        const { peak, longestHold: holdSeconds, start } = candidate
        repetitions.push({ peak, seconds, holdSeconds, start, end: time, checks: {
          amplitude: target ? peak >= target.angleMin && peak <= target.angleMax : null,
          tempo: target ? seconds >= target.cycleMin && seconds <= target.cycleMax : null,
          hold: target ? target.hold === 0 || (holdSeconds > 0 && holdSeconds >= target.hold - .15) : null,
        } })
        candidate = null
        return { angle, phase: 'ready' }
      }
    } else {
      candidate.restSince = null
    }
    return { angle, phase: returning ? 'returning' : 'raising' }
  }

  return { push, repetitions, get interruptedAttempts() { return interruptedAttempts } }
}
