export type AngleSample = { time: number; angle: number | null }
type ObservedAngle = { time: number; angle: number }

export function angleSegments(samples: readonly AngleSample[]): ObservedAngle[][] {
  const segments: ObservedAngle[][] = []
  let segment: ObservedAngle[] | undefined
  for (const sample of samples) {
    if (sample.angle === null) {
      segment = undefined
      continue
    }
    const previous = segment?.at(-1)
    if (!segment || (previous && sample.time - previous.time > .5)) {
      segment = []
      segments.push(segment)
    }
    segment.push({ time: sample.time, angle: sample.angle })
  }
  return segments
}
