<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { angleSegments, type AngleSample } from './angle-trace.ts'

const props = defineProps<{ samples: AngleSample[]; target?: [number, number] }>()
const surface = ref<HTMLCanvasElement>()
let resize: ResizeObserver | undefined

function draw() {
  const canvas = surface.value
  if (!canvas) return
  const width = canvas.clientWidth
  const height = canvas.clientHeight
  const ratio = window.devicePixelRatio || 1
  if (!width || !height) return
  if (canvas.width !== Math.round(width * ratio)) canvas.width = Math.round(width * ratio)
  if (canvas.height !== Math.round(height * ratio)) canvas.height = Math.round(height * ratio)
  const context = canvas.getContext('2d')
  if (!context) return
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  context.clearRect(0, 0, width, height)

  const left = 40, right = width - 12, top = 12, bottom = height - 26
  const last = props.samples.at(-1)?.time ?? 0
  const start = Math.max(0, last - 20)
  const end = Math.max(20, last)
  const x = (time: number) => left + (right - left) * (time - start) / (end - start)
  const y = (angle: number) => bottom - (bottom - top) * Math.max(0, Math.min(180, angle)) / 180

  if (props.target) {
    const [min, max] = props.target
    context.fillStyle = '#e1f0e5'
    context.fillRect(left, y(max), right - left, Math.max(1, y(min) - y(max)))
  }
  context.font = '12px Segoe UI, sans-serif'
  context.fillStyle = '#526862'
  context.strokeStyle = '#d4e1d9'
  context.lineWidth = 1
  for (const angle of [0, 45, 90, 135, 180]) {
    const line = y(angle)
    context.fillText(`${angle}°`, 1, line + 4)
    context.beginPath()
    context.moveTo(left, line)
    context.lineTo(right, line)
    context.stroke()
  }
  context.fillText(`${start.toFixed(0)} s`, left, height - 8)
  context.textAlign = 'right'
  context.fillText(`${end.toFixed(0)} s`, right, height - 8)
  context.textAlign = 'left'

  context.strokeStyle = context.fillStyle = '#086449'
  context.lineWidth = 2.5
  context.lineCap = 'round'
  context.lineJoin = 'round'
  for (const segment of angleSegments(props.samples.filter(sample => sample.time >= start))) {
    context.beginPath()
    const [first, ...rest] = segment
    if (!rest.length) {
      context.arc(x(first.time), y(first.angle), 2.5, 0, 2 * Math.PI)
      context.fill()
      continue
    }
    context.moveTo(x(first.time), y(first.angle))
    for (const sample of rest) context.lineTo(x(sample.time), y(sample.angle))
    context.stroke()
  }
}

onMounted(() => {
  if (surface.value) {
    resize = new ResizeObserver(draw)
    resize.observe(surface.value)
  }
  draw()
})
onUnmounted(() => resize?.disconnect())
watch(() => [props.samples, props.target], draw, { flush: 'post' })
</script>

<template>
  <section class="angle-trace" aria-label="最近二十秒的投影角度">
    <div class="trace-heading"><h3>实时角度曲线</h3><span v-if="target" class="muted">浅绿区域为目标范围</span></div>
    <canvas ref="surface" aria-label="训练角度随时间变化，缺失观测处断开"></canvas>
  </section>
</template>
