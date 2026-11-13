import { readFileSync } from 'node:fs'
import { CycleObserver, feature } from '../web/src/observation.js'
const fixtures=JSON.parse(readFileSync(0,'utf8'))
console.log(JSON.stringify(fixtures.map(({item,frames,evaluate})=>{
  const observer=new CycleObserver(item,evaluate)
  return frames.map(frame=>{
    const f=feature(frame,640,480,item.code,'RIGHT');observer.update(frame.time,f)
    return {valid:f.valid,angle:f.angle,phase:observer.phase,count:observer.results.length}
  })
})))
