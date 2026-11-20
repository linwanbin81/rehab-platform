import type { Exercise, Frame, Side, Target } from './motion.ts'
import type { CameraAssignment } from './workspace.ts'

export type CameraSettings = {
  exercise: Exercise
  side: Side
  mode: 'observe' | 'target'
  target: Target | null
}

export function cameraSettings(assignment?: CameraAssignment): CameraSettings {
  if (!assignment) return { exercise: 'elbow_flexion', side: 'RIGHT', mode: 'observe', target: null }
  const { exercise, side, mode, target } = assignment.item
  return { exercise, side, mode, target: target ? { ...target } : null }
}

export function observationRequest(settings: CameraSettings, frames: Frame[], captureId: string, assignment?: CameraAssignment) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Idempotency-Key': captureId }
  const payload = { ...settings, target: settings.mode === 'target' ? settings.target : null, frames }
  if (assignment) headers.Authorization = `Bearer ${assignment.token}`
  return {
    method: 'POST',
    headers,
    body: JSON.stringify(assignment ? { ...payload, planItemId: assignment.item.id, setNumber: assignment.setNumber } : payload),
  }
}
