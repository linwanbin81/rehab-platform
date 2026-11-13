// Mirrors algorithm/clinical_analysis.py for immediate feedback. Python recomputes
// the authoritative results from submitted landmarks; UI counts are provisional.
export const PHASE = { CALIBRATING: '保持舒适的起始姿势约 1 秒', UNRELIABLE: '识别不可靠，暂停计数', READY: '准备：缓慢开始动作', RAISE: '抬起 / 屈曲', HOLD: '目标区间保持', RETURN: '缓慢返回起始姿势' }
const rad = d => d * Math.PI / 180
const wrap = d => ((d + 180) % 360 + 360) % 360 - 180
const median = a => { const s = [...a].sort((x,y) => x-y); return (s[Math.floor((s.length-1)/2)] + s[Math.floor(s.length/2)]) / 2 }
export function angle(a,b,c) {
  const v = [a[0]-b[0], a[1]-b[1]], w = [c[0]-b[0], c[1]-b[1]]
  const n = Math.hypot(...v)*Math.hypot(...w)
  return n < 1e-8 ? null : Math.acos(Math.max(-1,Math.min(1,(v[0]*w[0]+v[1]*w[1])/n)))/rad(1)
}
export function feature(frame,width,height,code,side) {
  const points = frame.landmarks || [], invalid = { valid:false, angle:null, trunk:null, visibility:0, reason:'未识别到完整人体关键点' }
  if (frame.pose_count > 1) return {...invalid,reason:'检测到多人，请确保画面中只有训练者本人'}
  if (points.length !== 33 || frame.pose_count !== 1) return invalid
  const [s,e,w,h] = side === 'RIGHT' ? [12,14,16,24] : [11,13,15,23], ids = [s,e,w,h]
  if (points.some(p => ['x','y','visibility'].some(k => !Number.isFinite(p[k])))) return invalid
  if (points.some(p => p.visibility<0 || p.visibility>1 || !Number.isFinite(p.presence??1) || (p.presence??1)<0 || (p.presence??1)>1)) return invalid
  const confidence = Math.min(...ids.map(i => Math.min(points[i].visibility, points[i].presence ?? 1)))
  if (confidence < .65) return {...invalid, visibility:confidence, reason:'肩肘腕髋可见性不足，请调整机位或衣着'}
  if (ids.some(i => ['x','y'].some(k => points[i][k]<.01 || points[i][k]>.99))) return {...invalid,visibility:confidence,reason:'关键关节接近画面边缘或出画'}
  const p = i => [points[i].x*width,points[i].y*height]
  const dist = (a,b) => Math.hypot(a[0]-b[0],a[1]-b[1])
  if (dist(p(s),p(h))<20) return {...invalid,visibility:confidence,reason:'躯干关键点投影异常，请调整机位'}
  if (dist(p(s),p(e))<12 || dist(p(e),p(w))<10) return {...invalid,visibility:confidence,reason:'手臂投影过短，请调整机位'}
  return {valid:true,angle:code==='elbow_flexion'?180-angle(p(s),p(e),p(w)):angle(p(h),p(s),p(e)),trunk:Math.atan2(p(s)[0]-p(h)[0],p(h)[1]-p(s)[1])/rad(1),visibility:confidence,reason:''}
}
export class CycleObserver {
  constructor(item,evaluate=false) {
    this.item=item;this.evaluate=evaluate;this.calibration=[];this.baseline=null;this.trunkBase=null
    this.lastTime=null;this.smooth=null;this.lastValid=null;this.current=null;this.phase='CALIBRATING';this.results=[]
    this.attempts=0;this.interruptions=0;this.returnSince=null;this.restSince=null;this.maxHold=0;this.holdSince=null
  }
  resetGap() {
    if(this.current){this.attempts++;this.interruptions++}
    this.current=null;this.baseline=null;this.trunkBase=null;this.calibration=[];this.smooth=null;this.returnSince=null;this.restSince=null;this.holdSince=null
  }
  update(time,f) {
    const dt=this.lastTime===null?0:time-this.lastTime;this.lastTime=time
    if(!f.valid){this.phase='UNRELIABLE';this.holdSince=null;this.returnSince=null;this.restSince=null;if(this.baseline===null)this.calibration=[];if(this.lastValid!==null&&time-this.lastValid>.8)this.resetGap();return null}
    if(this.lastValid!==null&&time-this.lastValid>.8)this.resetGap()
    this.lastValid=time;this.smooth=this.smooth===null?f.angle:this.smooth+(1-Math.exp(-Math.min(dt,.2)/.12))*(f.angle-this.smooth)
    const value=this.smooth
    if(this.baseline===null){
      this.calibration.push([time,value,f.trunk]);this.calibration=this.calibration.filter(x=>time-x[0]<=1.2);this.phase='CALIBRATING'
      if(this.calibration.length>=6&&this.calibration.at(-1)[0]-this.calibration[0][0]>=.7){const values=this.calibration.map(x=>x[1]);if(Math.max(...values)-Math.min(...values)<=6){this.baseline=median(values);this.trunkBase=median(this.calibration.map(x=>x[2]));this.phase='READY'}}
      return null
    }
    const trunk=Math.abs(wrap(f.trunk-this.trunkBase))
    if(!this.current){this.phase='READY';if(value>=this.baseline+12){this.current={start:time,peak:value,peakTime:time,trunk};this.maxHold=0;this.holdSince=null;this.returnSince=null;this.restSince=null;this.phase='RAISE'};return null}
    const c=this.current
    if(time-c.start>30){this.attempts++;this.current=null;this.baseline=null;this.calibration=[];this.phase='CALIBRATING';return null}
    if(value>c.peak){c.peak=value;c.peakTime=time}c.trunk=Math.max(c.trunk,trunk)
    const inTarget=Number(this.item.target_min)<=value&&value<=Number(this.item.target_max)
    if(inTarget&&this.evaluate){if(this.holdSince===null)this.holdSince=time;this.maxHold=Math.max(this.maxHold,time-this.holdSince)}else this.holdSince=null
    if(c.peak-value>=6){if(this.returnSince===null)this.returnSince=time;if(time-this.returnSince>=.15)this.phase='RETURN'}else{this.returnSince=null;this.phase=this.evaluate&&inTarget?'HOLD':'RAISE'}
    if(this.phase==='RETURN'&&value<=this.baseline+6){
      if(this.restSince===null)this.restSince=time
      if(time-this.restSince>=.2&&time-c.start>=.6){
        const duration=time-c.start,amplitude=Number(this.item.target_min)<=c.peak&&c.peak<=Number(this.item.target_max),tempo=Number(this.item.min_duration)<=duration&&duration<=Number(this.item.max_duration),hold=this.maxHold>=Math.max(0,Number(this.item.hold_seconds)-.15)
        const events=[]
        if(this.evaluate&&!amplitude)events.push('峰值未在个体目标区间，请指导者复核')
        if(this.evaluate&&!tempo)events.push('循环耗时与个体计划节奏不一致')
        if(this.evaluate&&!hold)events.push('未观察到满足计划时长的连续保持')
        if(c.trunk>Number(this.item.trunk_limit))events.push('观察到躯干投影偏移，请指导者复核')
        const result={peak:c.peak,duration,events};this.results.push(result);this.current=null;this.phase='READY';return result
      }
    }else this.restSince=null
    return null
  }
}
