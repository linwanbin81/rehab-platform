import { createHash } from 'node:crypto'
import { copyFile, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const manifest = JSON.parse(await readFile(new URL('public/models/manifest.json', root), 'utf8'))
const packageWasm = new URL('node_modules/@mediapipe/tasks-vision/wasm/', root)
const publicWasm = new URL('public/wasm/', root)
const model = new URL(`public/models/${manifest.file}`, root)
const localReference = new URL(`../../rehab-platform/web/public/models/${manifest.file}`, root)

await mkdir(publicWasm, { recursive: true })
await Promise.all((await readdir(packageWasm)).map(name => copyFile(new URL(name, packageWasm), new URL(name, publicWasm))))

const installedModel = await readFile(model).catch(error => {
  if (error.code !== 'ENOENT') throw error
})
let bytes = installedModel
if (!bytes) {
  bytes = await readFile(localReference).catch(error => {
    if (error.code !== 'ENOENT') throw error
  })
}
if (!bytes) {
  const response = await fetch(manifest.source, { signal: AbortSignal.timeout(60_000) })
  if (!response.ok) throw new Error(`模型下载失败（HTTP ${response.status}）`)
  bytes = Buffer.from(await response.arrayBuffer())
}
const digest = createHash('sha256').update(bytes).digest('hex')
if (bytes.length !== manifest.bytes || digest !== manifest.sha256) throw new Error('姿态模型完整性校验失败')
if (!installedModel) await writeFile(model, bytes)
