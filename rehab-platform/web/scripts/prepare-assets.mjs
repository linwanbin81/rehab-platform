import { mkdir, copyFile, readdir, readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
const root = new URL('../', import.meta.url)
const target = new URL('public/wasm/', root)
await mkdir(target, { recursive: true })
for (const name of await readdir(new URL('node_modules/@mediapipe/tasks-vision/wasm/', root))) {
  if (/\.(js|wasm)$/.test(name)) await copyFile(new URL(`node_modules/@mediapipe/tasks-vision/wasm/${name}`, root), new URL(name, target))
}
const manifest = JSON.parse(await readFile(new URL('public/models/manifest.json', root), 'utf8'))
const model = await readFile(new URL(`public/models/${manifest.task}`, root)).catch(() => { throw new Error('姿态模型缺失，请按 docs/模型与动作依据.md 下载') })
if (model.length !== manifest.bytes || createHash('sha256').update(model).digest('hex') !== manifest.sha256) throw new Error('姿态模型校验失败')
console.log(`Local model verified; WASM prepared: ${fileURLToPath(target)}`)
