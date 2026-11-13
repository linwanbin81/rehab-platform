<script setup>
import { computed } from 'vue'
const props = defineProps({ angle: { default: 35 }, trunk: { default: 0 }, code: { default: 'shoulder_flexion' }, compact: Boolean })
const rad = computed(() => Number(props.angle) * Math.PI / 180)
const elbow = computed(() => props.code === 'elbow_flexion' ? { x: 222, y: 202 } : { x: 206 + Math.sin(rad.value) * 68, y: 135 + Math.cos(rad.value) * 68 })
const wrist = computed(() => props.code === 'elbow_flexion' ? { x: 222 + Math.sin(rad.value + .15) * 65, y: 202 + Math.cos(rad.value + .15) * 65 } : { x: 206 + Math.sin(rad.value) * 132, y: 135 + Math.cos(rad.value) * 132 })
const points = computed(() => [[160, 80], [114, 135], [206, 135], [102, 205], [95, 270], [135, 275], [185, 275], [130, 350], [190, 350], [elbow.value.x, elbow.value.y], [wrist.value.x, wrist.value.y]])
</script>
<template>
  <svg viewBox="0 0 360 410" class="pose-svg" aria-label="模拟人体骨架示意，不是真实摄像头识别结果">
    <defs><linearGradient id="body-fill" x1="0" x2="1"><stop stop-color="#c3ddd8"/><stop offset="1" stop-color="#e9f2ed"/></linearGradient></defs>
    <g stroke="#dfe9e5" stroke-width="1"><path d="M30 80H330M30 160H330M30 240H330M30 320H330M80 30V380M160 30V380M240 30V380M320 30V380"/></g>
    <ellipse cx="160" cy="382" rx="88" ry="8" fill="#dce8e1"/>
    <g :transform="`rotate(${-trunk} 160 275)`">
      <circle cx="160" cy="76" r="29" fill="url(#body-fill)"/>
      <path d="M125 118 Q160 105 197 118 L205 170 185 278 135 278 112 168Z" fill="url(#body-fill)"/>
      <path d="M138 275 L130 350 M183 275 L190 350 M114 135 L102 205 L95 270" stroke="#c9ddd4" stroke-width="27" stroke-linecap="round" fill="none"/>
      <path :d="`M206 135 L${elbow.x} ${elbow.y} L${wrist.x} ${wrist.y}`" stroke="#c9ddd4" stroke-width="25" stroke-linecap="round" fill="none"/>
      <g stroke="#2a8b74" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" fill="none">
        <path d="M114 135 L206 135 M114 135 L135 275 L185 275 L206 135 M114 135 L102 205 L95 270 M135 275 L130 350 M185 275 L190 350"/>
        <path :d="`M206 135 L${elbow.x} ${elbow.y} L${wrist.x} ${wrist.y}`" stroke="#e39b48" stroke-width="4"/>
        <path d="M160 106V135" stroke-dasharray="4 5"/>
      </g>
      <g v-for="(point, i) in points" :key="i"><circle :cx="point[0]" :cy="point[1]" r="6" :fill="i > 8 ? '#e39b48' : '#2a8b74'" stroke="white" stroke-width="2"/></g>
    </g>
    <g v-if="!compact"><rect x="12" y="15" width="105" height="27" rx="7" fill="white"/><text x="25" y="33" fill="#41665e" font-size="12">模拟骨架 · 演示</text></g>
  </svg>
</template>
