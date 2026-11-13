<script setup>
import { ref, computed, onBeforeUnmount } from 'vue'
import { FilesetResolver, PoseLandmarker } from '@mediapipe/tasks-vision'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { CycleObserver, feature, PHASE } from '../observation'
import Chart from './Chart.vue'
const props=defineProps({plan:Object,catalog:Object})
const emit=defineEmits(['completed'])
const video=ref(null),canvas=ref(null),loading=ref(false),ready=ref(false),recording=ref(false),saving=ref(false),error=ref('')
const side=ref('RIGHT'),mode=ref('OBSERVATION'),confirmed=ref(false),selection=ref(''),captures=ref([]),current=ref(null)
const reading=ref(null),phase=ref('CALIBRATING'),count=ref(0),feedback=ref(''),plot=ref([]),session=ref(null),saved=ref(null)
const painBefore=ref(null),painAfter=ref(null),stopReason=ref('完成本次观察'),elapsed=ref(0),restRemaining=ref(0)
let detector,stream,raf,observer,epoch,disposed=false,lastFrame=-1,lastVideoTime=-1,noticeAt=0,restUntil=0
const slots=computed(()=>props.plan.items.flatMap(i=>Array.from({length:i.sets},(_,n)=>({item:i,set:n+1,key:`${i.id}:${n+1}`}))))
selection.value=slots.value[0]?.key||''
const slot=computed(()=>slots.value.find(s=>s.key===selection.value))
const reference=computed(()=>props.catalog?.exercises.find(e=>e.code===slot.value?.item.code))
const hasUnsaved=computed(()=>!saved.value&&(!!session.value||captures.value.length>0||!!current.value?.frames.length))
defineExpose({hasUnsaved})
const totalFrames=()=>captures.value.reduce((n,c)=>n+c.frames.length,0)+(current.value?.frames.length||0)
const chart=computed(()=>({animation:false,grid:{left:50,right:20,top:25,bottom:35},tooltip:{trigger:'axis'},xAxis:{type:'value',name:'秒'},yAxis:{type:'value',name:'二维投影 °',min:0,max:180},series:[{type:'line',showSymbol:false,connectNulls:false,data:plot.value,lineStyle:{color:'#27866e',width:2}}]}))
function closeCamera(){cancelAnimationFrame(raf);stream?.getTracks().forEach(t=>t.stop());stream=null;ready.value=false;if(video.value)video.value.srcObject=null;if(canvas.value)canvas.value.getContext('2d').clearRect(0,0,canvas.value.width,canvas.value.height)}
async function openCamera(){
  loading.value=true;error.value=''
  try{
    if(!window.isSecureContext||!navigator.mediaDevices?.getUserMedia)throw new Error('摄像头需要 localhost 或 HTTPS；请在本机使用 127.0.0.1 地址')
    if(!detector){
      const response=await fetch('/models/pose_landmarker_full.task');if(!response.ok)throw new Error('本地姿态模型缺失，请重新构建')
      const buffer=await response.arrayBuffer(),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',buffer))).map(x=>x.toString(16).padStart(2,'0')).join('')
      const manifest=await fetch('/models/manifest.json').then(r=>r.json());if(hash!==manifest.sha256)throw new Error('姿态模型校验失败')
      const files=await FilesetResolver.forVisionTasks('/wasm')
      const options={baseOptions:{modelAssetBuffer:new Uint8Array(buffer),delegate:'GPU'},runningMode:'VIDEO',numPoses:2,minPoseDetectionConfidence:.6,minPosePresenceConfidence:.6,minTrackingConfidence:.6}
      try{detector=await PoseLandmarker.createFromOptions(files,options)}catch{options.baseOptions.delegate='CPU';detector=await PoseLandmarker.createFromOptions(files,options)}
    }
    if(disposed)return
    const acquired=await navigator.mediaDevices.getUserMedia({video:{width:{ideal:640},height:{ideal:480},facingMode:'user'},audio:false})
    if(disposed){acquired.getTracks().forEach(t=>t.stop());return}
    stream=acquired;video.value.srcObject=stream;await video.value.play();ready.value=true;lastFrame=-1;lastVideoTime=-1
    for(const track of stream.getVideoTracks())track.onended=()=>{recording.value=false;closeCamera();error.value='摄像头已断开；当前采集仍保留，可重新连接或保存'}
    raf=requestAnimationFrame(tick)
  }catch(e){closeCamera();error.value=({NotAllowedError:'摄像头权限被拒绝，请在浏览器地址栏允许摄像头',NotFoundError:'未找到摄像头，请连接 USB 摄像头',NotReadableError:'摄像头可能正被其他程序占用'})[e.name]||e.message}
  finally{loading.value=false;if(disposed){detector?.close();detector=null}}
}
function draw(points){
  const c=canvas.value;if(!c||!video.value)return;c.width=video.value.videoWidth;c.height=video.value.videoHeight
  const ctx=c.getContext('2d');ctx.clearRect(0,0,c.width,c.height)
  const edges=[[11,12],[11,13],[13,15],[12,14],[14,16],[11,23],[12,24],[23,24],[23,25],[24,26]]
  ctx.lineWidth=3;ctx.strokeStyle='#58dfbc';ctx.fillStyle='#ffc66e'
  for(const[a,b]of edges){if(!points[a]||!points[b]||Math.min(points[a].visibility,points[b].visibility)<.65)continue;ctx.beginPath();ctx.moveTo(points[a].x*c.width,points[a].y*c.height);ctx.lineTo(points[b].x*c.width,points[b].y*c.height);ctx.stroke()}
  for(const p of points){if(p.visibility<.65)continue;ctx.beginPath();ctx.arc(p.x*c.width,p.y*c.height,4,0,Math.PI*2);ctx.fill()}
}
function tick(now){
  if(disposed||!ready.value)return
  restRemaining.value=Math.max(0,Math.ceil((restUntil-now)/1000))
  if(now-lastFrame>=125&&video.value?.readyState>=2&&video.value.currentTime!==lastVideoTime){
    lastFrame=now;lastVideoTime=video.value.currentTime
    try{
      const result=detector.detectForVideo(video.value,now)
      const quantize=x=>Number(x.toFixed(6))
      const points=(result.landmarks[0]||[]).map(p=>({x:quantize(p.x),y:quantize(p.y),visibility:quantize(p.visibility??0),presence:quantize(p.presence??1)}))
      draw(points)
      const frame={time:epoch===undefined?0:Math.round((now-epoch)/1000*10000)/10000,pose_count:result.landmarks.length,landmarks:points}
      const f=feature(frame,video.value.videoWidth,video.value.videoHeight,slot.value.item.code,side.value)
      reading.value=f
      if(recording.value&&current.value){
        if(totalFrames()>=6000||frame.time>=3599){recording.value=false;error.value='已达到采集上限，请保存本次报告后再开始新的会话'}
        else{
          current.value.frames.push(frame);const rep=observer.update(frame.time,f);phase.value=observer.phase;count.value=observer.results.length;elapsed.value=frame.time
          if(plot.value.length&&frame.time-plot.value.at(-1)[0]>.5)plot.value.push([frame.time-.0001,null]);plot.value.push([frame.time,f.valid?observer.smooth:null]);if(plot.value.length>500)plot.value.shift()
          if(!f.valid)feedback.value=f.reason
          else if(rep&&now-noticeAt>1500){feedback.value=rep.events.join('；')||'已观察到一个完整循环';noticeAt=now}
          else if(phase.value==='CALIBRATING')feedback.value='保持舒适的起始姿势稳定约 1 秒，再开始动作'
        }
      }
    }catch(e){recording.value=false;error.value=`识别停止：${e.message}。采集仍保留，可保存或重新连接`;closeCamera();return}
  }
  raf=requestAnimationFrame(tick)
}
async function startSet(){
  if(!ready.value||!slot.value||saved.value)return
  if(mode.value==='TARGET'&&!confirmed.value)return ElMessage.warning('请先与指导者核对目标，并勾选确认')
  if(restRemaining.value>0)return
  loading.value=true
  try{
    if(!session.value){session.value=await api('/sessions',{method:'POST',body:{plan_id:props.plan.id,data_source:'CAMERA'}});epoch=performance.now()}
    if(disposed)return
    if(!current.value){
      if(captures.value.some(c=>`${c.item_id}:${c.set_index}`===selection.value))return ElMessage.warning('该组已经采集，请选择下一组')
      current.value={item_id:slot.value.item.id,set_index:slot.value.set,side:side.value,width:video.value.videoWidth,height:video.value.videoHeight,frames:[]}
      observer=new CycleObserver(slot.value.item,mode.value==='TARGET');count.value=0;feedback.value='';phase.value='CALIBRATING'
    }
    recording.value=true
  }catch(e){error.value=e.message}finally{loading.value=false}
}
function finishSet(){
  recording.value=false
  if(current.value?.frames.length){captures.value.push(current.value);restUntil=performance.now()+Number(slot.value.item.rest_seconds)*1000}
  current.value=null;count.value=0
  const next=slots.value.find(s=>!captures.value.some(c=>`${c.item_id}:${c.set_index}`===s.key));if(next)selection.value=next.key
}
async function save(){
  if(!session.value||totalFrames()<2)return ElMessage.warning('请先采集至少两帧真实动作数据')
  recording.value=false;saving.value=true;error.value=''
  if(current.value?.frames.length){captures.value.push(current.value);current.value=null}
  try{
    const manifest=await fetch('/models/manifest.json').then(r=>r.json())
    const result=await api(`/sessions/${session.value.id}/capture`,{method:'POST',body:{captures:captures.value,evaluation_mode:mode.value,target_confirmed:confirmed.value,subjective:{pain_before:painBefore.value,pain_after:painAfter.value},stop_reason:stopReason.value,model_sha256:manifest.sha256}})
    saved.value=result;closeCamera();emit('completed',result)
  }catch(e){error.value=`保存失败，采集数据仍保留在此页面：${e.message}`}finally{saving.value=false}
}
function beforeUnload(e){if(hasUnsaved.value){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',beforeUnload)
document.addEventListener('visibilitychange',onVisibility)
function onVisibility(){if(document.hidden&&recording.value){recording.value=false;feedback.value='页面转入后台，采集已暂停；返回后点击继续并重新稳定起始姿势'}}
onBeforeUnmount(()=>{disposed=true;closeCamera();if(!loading.value)detector?.close();window.removeEventListener('beforeunload',beforeUnload);document.removeEventListener('visibilitychange',onVisibility)})
</script>
<template>
  <div class="section-note"><span>◉</span><p>真实摄像头观察 · 关键点模型在本机运行，仅提交关键点供分析与回放，不保存原始视频。出现疼痛加重或不适时停止，并联系指导者。</p></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="camera-alert"/>
  <div class="camera-setup panel">
    <label>本组动作<el-select v-model="selection" :disabled="!!current||saving||!!saved"><el-option v-for="s in slots" :key="s.key" :label="`${s.item.name} · 第 ${s.set} 组${captures.some(c=>`${c.item_id}:${c.set_index}`===s.key)?'（已采集）':''}`" :value="s.key" :disabled="captures.some(c=>`${c.item_id}:${c.set_index}`===s.key)"/></el-select></label>
    <label>训练侧<el-select v-model="side" :disabled="!!current||saving||!!saved"><el-option label="右侧" value="RIGHT"/><el-option label="左侧" value="LEFT"/></el-select></label>
    <label>反馈方式<el-select v-model="mode" :disabled="!!session"><el-option label="仅记录动作（默认）" value="OBSERVATION"/><el-option label="与个体计划目标比较" value="TARGET"/></el-select></label>
    <el-checkbox v-if="mode==='TARGET'" v-model="confirmed" :disabled="!!session">我已与指导者确认本计划的幅度、节奏与保持要求</el-checkbox>
  </div>
  <div class="training-grid camera-grid">
    <section class="panel instruction-panel"><span class="mini-label">01 / 动作与依据</span><h3>{{reference?.name||slot?.item.name}}</h3><p class="camera-view">{{reference?.view}}</p><ol class="motion-steps"><li v-for="step in reference?.steps" :key="step">{{step}}</li></ol><p class="mini-note">{{reference?.measurement}}</p><p>{{slot?.item.sets}} 组 × {{slot?.item.repetitions}} 次 · 组间休息 {{slot?.item.rest_seconds}} 秒</p><p v-if="mode==='TARGET'">个体计划：{{slot?.item.target_min}}–{{slot?.item.target_max}}° · 完整循环 {{slot?.item.min_duration}}–{{slot?.item.max_duration}} 秒 · 保持 {{slot?.item.hold_seconds}} 秒</p><p v-else class="muted">仅观察模式：记录次数与投影角度，不判定幅度、节奏或保持是否达标。</p><a v-if="reference" :href="reference.source.url" target="_blank" rel="noopener noreferrer">查看官方动作图示与说明 ↗</a><small class="source-label">{{reference?.source.title}} · {{reference?.source.section}}</small><p class="mini-note">{{reference?.caution}}</p></section>
    <section class="camera-stage"><div class="stage-label">{{recording?'● 采集中':ready?'摄像头预览 · 未采集':'等待开启摄像头'}}<span>POSE LANDMARKER FULL</span></div><div class="camera-viewport"><video ref="video" muted playsinline/><canvas ref="canvas"/><div v-if="!ready" class="camera-placeholder"><span>◉</span><p>肩、肘、腕、髋完整入画<br>按照左侧说明调整机位</p></div></div><div class="camera-footer"><span>{{recording?PHASE[phase]:'预览不产生动作计数'}}</span><span>{{elapsed.toFixed(1)}} 秒</span></div></section>
    <section class="panel feedback-panel"><span class="mini-label">02 / 观察反馈</span><div class="rep-number">{{count}}<span>/ {{slot?.item.repetitions}}</span></div><p class="muted">本组暂计循环 · 保存后由服务端复算</p><div class="angle-reading"><span>当前投影角度</span><b>{{reading?.valid?reading.angle.toFixed(1):'—'}}<small>°</small></b></div><p>关键关节可见性 {{reading?.visibility!=null?(reading.visibility*100).toFixed(0)+'%':'—'}}</p><div class="feedback-note" :class="{warning:reading&&!reading.valid}"><b>{{recording?PHASE[phase]:'请按本人获准的范围练习'}}</b><p>{{feedback||'每组先稳定起始姿势，再完成抬起和返回。遮挡时暂停评价，较长遮挡后重新校准。'}}</p></div><p class="muted">已采集 {{captures.length}} 组 · {{totalFrames()}} / 6000 帧</p></section>
  </div>
  <section class="panel"><div class="panel-heading"><div><h3>实时投影角度曲线</h3><p>最近 500 帧；缺失观测保留断点。所有组的完整数据在报告中回放。</p></div><div class="button-row"><el-button v-if="!ready&&!saved" :loading="loading" @click="openCamera">开启摄像头</el-button><el-button v-if="ready&&!recording&&!saved" type="primary" :disabled="saving||restRemaining>0" :loading="loading" @click="startSet">{{restRemaining>0?`组间休息 ${restRemaining} 秒`:current?'继续采集':'开始本组'}}</el-button><el-button v-if="recording" @click="recording=false">暂停采集</el-button><el-button v-if="current&&!saved" :disabled="saving" @click="finishSet">结束本组</el-button><el-button v-if="ready&&!recording" :disabled="loading" @click="closeCamera">关闭摄像头</el-button></div></div><Chart :option="chart" height="230px"/></section>
  <section class="panel camera-save"><h3>结束记录并生成报告</h3><p>疼痛为本人自报，0 表示无痛，10 表示最严重；可以不填写。系统不根据该数值自动制定处方。</p><div class="camera-subjective"><label>训练前疼痛（可选）<el-input-number v-model="painBefore" :min="0" :max="10" :step="1" :disabled="!!session"/></label><label>训练后疼痛（可选）<el-input-number v-model="painAfter" :min="0" :max="10" :step="1" :disabled="saving||!!saved"/></label><label>结束原因<el-select v-model="stopReason" :disabled="saving||!!saved"><el-option v-for="reason in ['完成本次观察','疼痛或不适，主动停止','疲劳，主动停止','识别不稳定，结束记录','其他原因']" :key="reason" :label="reason" :value="reason"/></el-select></label><el-button type="primary" :loading="saving" :disabled="!hasUnsaved" @click="save">结束并保存报告</el-button></div></section>
</template>
