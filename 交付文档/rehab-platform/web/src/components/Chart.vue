<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { init, use } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, MarkLineComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart, BarChart, GridComponent, TooltipComponent, MarkLineComponent, LegendComponent, CanvasRenderer])
const props = defineProps({ option: Object, height: { default: '260px' } })
const root = ref(); let chart; let observer
onMounted(() => { chart = init(root.value); chart.setOption(props.option || {}); observer = new ResizeObserver(() => chart?.resize()); observer.observe(root.value) })
watch(() => props.option, value => chart?.setOption(value || {}, true), { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="root" :style="{ width: '100%', height }" role="img" aria-label="训练数据图表"></div></template>
