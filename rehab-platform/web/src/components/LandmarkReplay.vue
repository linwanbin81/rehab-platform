<script setup>
import { computed } from 'vue'
const props=defineProps({sample:Object})
const edges=[[11,12],[11,13],[13,15],[12,14],[14,16],[11,23],[12,24],[23,24],[23,25],[24,26],[25,27],[26,28]]
const points=computed(()=>props.sample?.phase==='UNRELIABLE'?[]:props.sample?.landmarks||[])
const visible=i=>points.value[i]&&points.value[i].visibility>=.65
const viewBox=computed(()=>`0 0 ${props.sample?.frame_width||640} ${props.sample?.frame_height||480}`)
const p=i=>({x:points.value[i].x*(props.sample?.frame_width||640),y:points.value[i].y*(props.sample?.frame_height||480)})
</script>
<template><div class="landmark-replay"><svg :viewBox="viewBox" aria-label="本次采集的人体关键点回放"><g v-for="([a,b],i) in edges" :key="i"><line v-if="visible(a)&&visible(b)" :x1="p(a).x" :y1="p(a).y" :x2="p(b).x" :y2="p(b).y" stroke="#57c5a6" stroke-width="4"/></g><template v-for="(point,i) in points" :key="i"><circle v-if="visible(i)" :cx="p(i).x" :cy="p(i).y" r="4" fill="#f1be70"/></template></svg><p>{{points.length?'真实采集关键点 · 未保存原始视频':'当前帧无可靠关键点'}}</p></div></template>
