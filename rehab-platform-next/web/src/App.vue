<script setup lang="ts">
import { ref, shallowRef } from 'vue'
import CameraTraining from './CameraTraining.vue'
import Workspace from './Workspace.vue'
import type { CameraAssignment } from './workspace.ts'

const tabKey = 'rehab.workspace.active-view'
const view = ref<'camera' | 'workspace'>(sessionStorage.getItem(tabKey) === 'workspace' ? 'workspace' : 'camera')
const assignment = shallowRef<CameraAssignment>()
const camera = ref<InstanceType<typeof CameraTraining>>()
const recentReportId = ref<number>()
const message = ref('')

function openWorkspace() {
  if (camera.value?.hasUnsaved) {
    message.value = '本组仍有未保存的采集。请先结束并保存，或取消本组再返回工作台。'
    return
  }
  view.value = 'workspace'
  assignment.value = undefined
  message.value = ''
  sessionStorage.setItem(tabKey, 'workspace')
}

function openTrial() {
  if (camera.value?.hasUnsaved) {
    message.value = '请先结束并保存当前采集，或取消本组。'
    return
  }
  view.value = 'camera'
  assignment.value = undefined
  message.value = ''
  sessionStorage.removeItem(tabKey)
}

function startAssigned(selected: CameraAssignment) {
  assignment.value = selected
  view.value = 'camera'
  message.value = ''
  window.scrollTo(0, 0)
}

function nextSet() {
  const current = assignment.value
  if (!current || current.setNumber >= current.item.sets) return
  startAssigned({ ...current, setNumber: current.setNumber + 1 })
}

function rememberReport(id: number) {
  if (assignment.value) recentReportId.value = id
  message.value = ''
}

function renewAssignment(token: string) {
  if (assignment.value) assignment.value = { ...assignment.value, token }
}
</script>

<template>
  <header class="site-header">
    <div class="header-inner">
      <div class="identity"><span class="brand-mark" aria-hidden="true">✳</span><span>舒展 <small>上肢训练</small></span></div>
      <nav class="app-nav" aria-label="平台导航">
        <button class="secondary small" :aria-current="view === 'workspace' ? 'page' : undefined" @click="openWorkspace">训练工作台</button>
        <button class="secondary small" :aria-current="view === 'camera' && !assignment ? 'page' : undefined" @click="openTrial">摄像头试练</button>
      </nav>
    </div>
  </header>
  <div v-if="message" class="workspace navigation-message" role="status">{{ message }}</div>
  <Workspace v-if="view === 'workspace'" :recent-report-id="recentReportId" @start="startAssigned" />
  <CameraTraining v-else ref="camera" :key="assignment ? `${assignment.item.id}:${assignment.setNumber}` : 'trial'"
    :assignment="assignment" @saved="rememberReport" @next-set="nextSet" @reauthenticated="renewAssignment" />
</template>
