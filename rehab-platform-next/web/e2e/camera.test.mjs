import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { chromium } from 'playwright'

const base = new URL(process.env.REHAB_E2E_URL ?? 'http://127.0.0.1:8081/')
assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname), 'Use a local application URL')

const fixture = fileURLToPath(new URL('./.fixtures/pose.y4m', import.meta.url))
const browserPath = process.env.REHAB_CHROME ?? [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(existsSync)
const timeout = 45_000

async function openTestCamera(t, bypassPermission = true) {
  assert.ok(existsSync(fixture), 'Generate the mock camera video with python e2e/prepare-video.py')
  const browser = await chromium.launch({
    executablePath: browserPath,
    args: [
      ...(bypassPermission ? ['--use-fake-ui-for-media-stream'] : []),
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-video-capture=${fixture}`,
    ],
  })
  t.after(() => browser.close())
  const page = await browser.newPage()
  page.setDefaultTimeout(timeout)
  return page
}

async function seekLivePreview(page) {
  await page.getByRole('button', { name: '开启摄像头', exact: true }).click()
  const video = page.getByTestId('camera-video')
  await video.waitFor({ state: 'visible' })
  await page.waitForFunction(() => {
    const video = document.querySelector('[data-testid="camera-video"]')
    return video?.videoWidth > 0 && !video.paused && video.currentTime > 0 &&
      video.srcObject?.getVideoTracks().some(track => track.readyState === 'live')
  })
  assert.equal(await page.getByTestId('camera-status').getAttribute('data-state'), 'streaming')
  return video
}

async function waitForPaintedPose(page) {
  await page.waitForFunction(() => {
    const status = document.querySelector('[data-testid="pose-status"]')
    if (status?.getAttribute('data-state') !== 'ready' || status.getAttribute('data-pose-count') !== '1') {
      return false
    }
    const canvas = document.querySelector('[data-testid="pose-overlay"]')
    if (!canvas?.width || !canvas.height) return false
    const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data
    for (let index = 3; index < rgba.length; index += 4) {
      if (rgba[index] > 0) return true
    }
    return false
  })
}

test('camera displays video, draws real MediaPipe landmarks, and releases the stream on close', { timeout: 70_000 }, async t => {
  const page = await openTestCamera(t)
  const successfulAssets = new Set()
  page.on('response', response => {
    if (response.ok()) successfulAssets.add(new URL(response.url()).pathname)
  })
  await page.goto(base.href)
  const video = await seekLivePreview(page)
  await waitForPaintedPose(page)
  assert.ok(successfulAssets.has('/models/pose_landmarker_full.task'), 'The configured real model was fetched')
  assert.ok([...successfulAssets].some(path => /^\/wasm\/.+\.wasm$/.test(path)), 'The MediaPipe WASM was fetched')

  const track = await video.evaluateHandle(element => element.srcObject.getVideoTracks()[0])
  await page.getByRole('button', { name: '关闭摄像头', exact: true }).click()
  await page.waitForFunction(stoppedTrack => stoppedTrack.readyState === 'ended', track)
  await track.dispose()
})

test('camera preview starts while the pose model is pending and stays live after a 404', { timeout: 70_000 }, async t => {
  const page = await openTestCamera(t)
  const modelRequest = Promise.withResolvers()
  await page.route('**/models/pose_landmarker_full.task*', async route => {
    await modelRequest.promise
    await route.fulfill({ status: 404 })
  })
  let video
  try {
    await page.goto(base.href)
    video = await seekLivePreview(page)
  } finally {
    modelRequest.resolve()
  }
  await page.locator('[data-testid="pose-status"][data-state="error"]').waitFor({ state: 'visible' })
  assert.equal(await page.getByTestId('camera-status').getAttribute('data-state'), 'streaming')
  assert.equal(await video.evaluate(element => element.srcObject.getVideoTracks()[0].readyState), 'live')
  const time = await video.evaluate(element => element.currentTime)
  await page.waitForFunction(initial => {
    return document.querySelector('[data-testid="camera-video"]').currentTime > initial + 0.1
  }, time)
})

test('browser permission refusal shows a retry path that works after camera permission is granted', { timeout: 70_000 }, async t => {
  const page = await openTestCamera(t, false)
  const session = await page.context().newCDPSession(page)
  const { targetInfo } = await session.send('Target.getTargetInfo')
  const setPermission = setting => session.send('Browser.setPermission', {
    permission: { name: 'camera' },
    setting,
    origin: base.origin,
    browserContextId: targetInfo.browserContextId,
  })

  await setPermission('denied')
  await page.goto(base.href)
  await page.getByRole('button', { name: '开启摄像头', exact: true }).click()
  await page.locator('[data-testid="camera-status"][data-state="error"]').waitFor({ state: 'visible' })
  assert.match(await page.getByRole('alert').innerText(), /摄像头权限/)
  assert.equal(await page.getByTestId('camera-video').evaluate(video => video.srcObject), null)
  assert.equal(await page.getByTestId('pose-status').getAttribute('data-state'), 'idle')

  await setPermission('granted')
  await seekLivePreview(page)
})

async function startTestApi(t) {
  const server = new URL('../../server/', import.meta.url)
  const jar = process.env.REHAB_E2E_JAR ?? fileURLToPath(new URL('target/rehab-next-server-0.1.0.jar', server))
  assert.ok(existsSync(jar), 'Build the Java API before running the browser tests')
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  const localJava = fileURLToPath(new URL(`../../../.tools/jdk-17.0.18-lite/bin/${javaName}`, import.meta.url))
  const configuredJdk = process.env.REHAB_JAVA17_HOME ?? process.env.JAVA_HOME
  const java = process.env.REHAB_E2E_JAVA ?? (existsSync(localJava)
    ? localJava
    : configuredJdk ? `${configuredJdk}/bin/${javaName}` : javaName)
  const processHandle = spawn(java, ['-Dfile.encoding=UTF-8', '-jar', jar,
    '--server.address=127.0.0.1', '--server.port=0',
    '--spring.datasource.url=jdbc:h2:mem:browser_camera;DB_CLOSE_DELAY=-1'],
  { cwd: fileURLToPath(server), windowsHide: true })
  t.after(() => {
    if (processHandle.exitCode !== null || processHandle.signalCode !== null || !processHandle.pid) return
    return new Promise(resolve => {
      processHandle.once('exit', resolve)
      processHandle.kill()
    })
  })
  return new Promise((resolve, reject) => {
    let output = ''
    const timer = setTimeout(() => reject(new Error(`Isolated Java API startup timed out\n${output}`)), 30_000)
    const complete = callback => value => { clearTimeout(timer); callback(value) }
    processHandle.once('error', complete(reject))
    processHandle.once('exit', code => complete(reject)(new Error(`Isolated Java API exited ${code}\n${output}`)))
    const capture = chunk => {
      output += chunk.toString('utf8')
      const port = output.match(/Tomcat started on port (\d+) \(http\)/)?.[1]
      if (port) complete(resolve)(`http://127.0.0.1:${port}`)
    }
    processHandle.stdout.on('data', capture)
    processHandle.stderr.on('data', capture)
  })
}

test('browser capture saves a server-recomputed observation in an isolated database', { timeout: 90_000 }, async t => {
  const api = await startTestApi(t)
  const page = await openTestCamera(t)
  await page.route('**/api/v1/observations', async route => {
    const response = await route.fetch({ url: `${api}/api/v1/observations` })
    await route.fulfill({ response })
  })

  await page.goto(base.href)
  const video = await seekLivePreview(page)
  await waitForPaintedPose(page)
  await page.getByRole('button', { name: '开始训练', exact: true }).click()
  const start = await video.evaluate(element => element.currentTime)
  await page.waitForFunction(initial => {
    return document.querySelector('[data-testid="camera-video"]').currentTime > initial + 1.0
  }, start)

  const create = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/observations' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '结束并保存', exact: true }).click()
  const saved = await create
  assert.equal(saved.status(), 201, await saved.text())
  const report = await saved.json()
  assert.ok(Number.isInteger(report.id) && report.id > 0)
  assert.ok(report.totalFrames >= 2, 'Several runtime model frames were saved')
  assert.ok(report.validFrames > 0, 'The Java analyzer accepted observable pose frames')
  assert.equal(report.repetitions.length, 0, 'A static camera subject did not count as movement')
  await page.getByText(`已保存到数据库 · 记录 #${report.id}`, { exact: true }).waitFor({ state: 'visible' })

  const restored = await fetch(`${api}/api/v1/observations/${report.id}`)
  assert.equal(restored.status, 200)
  assert.deepEqual(await restored.json(), report)
})

test('trainee opens an assigned set in the camera and the assigned guide can finalize its saved report', { timeout: 90_000 }, async t => {
  const api = await startTestApi(t)
  const page = await openTestCamera(t)
  await page.route('**/api/v1/**', async route => {
    const requestUrl = new URL(route.request().url())
    const response = await route.fetch({ url: `${api}${requestUrl.pathname}${requestUrl.search}` })
    await route.fulfill({ response })
  })
  await page.goto(base.href)
  await page.getByRole('button', { name: '训练工作台', exact: true }).click({ timeout: 7000 })

  async function apiRequest(path, token, settings = {}) {
    return fetch(`${api}/api/v1${path}`, {
      ...settings,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(settings.body ? { 'Content-Type': 'application/json' } : {}) },
    })
  }
  async function login(username) {
    const response = await apiRequest('/workspace/login', null, { method: 'POST', body: JSON.stringify({ username, password: 'Demo123!' }) })
    assert.equal(response.status, 200, await response.clone().text())
    return response.json()
  }
  const guide = await login('teacher')
  const outsider = await login('teacher2')
  const trainee = await login('trainee')
  const plan = {
    title: '联调计划 · 肘屈伸', traineeId: trainee.actor.id, scheduledDate: '2026-11-20',
    items: [{ exercise: 'elbow_flexion', side: 'RIGHT', mode: 'observe', sets: 2, reps: 3, target: null }],
  }
  const published = await apiRequest('/plans', guide.token, { method: 'POST', body: JSON.stringify(plan) })
  assert.equal(published.status, 201, await published.clone().text())
  const createdPlan = await published.json()
  assert.ok(createdPlan.items[0].id)

  await page.getByLabel('用户名', { exact: true }).fill('trainee')
  await page.getByLabel('密码', { exact: true }).fill('Demo123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '训练计划', exact: true }).click()
  const item = page.locator(`[data-plan-id="${createdPlan.id}"] [data-plan-item-id="${createdPlan.items[0].id}"]`)
  await item.getByLabel('选择训练组').selectOption('1')
  await item.getByRole('button', { name: '进入摄像头跟练', exact: true }).click()
  await page.getByText(/联调计划 · 肘屈伸 · 第 1 \/ 2 组/).waitFor()
  assert.equal(await page.locator('#exercise').inputValue(), 'elbow_flexion')
  assert.equal(await page.locator('#exercise').isDisabled(), true)

  const video = await seekLivePreview(page)
  await waitForPaintedPose(page)
  await page.getByRole('button', { name: '开始训练', exact: true }).click()
  const start = await video.evaluate(element => element.currentTime)
  await page.waitForFunction(initial => document.querySelector('video').currentTime > initial + 1, start)

  const browserToken = await page.evaluate(() => sessionStorage.getItem('rehab.workspace.token'))
  const revoked = await apiRequest('/workspace/logout', browserToken, { method: 'POST' })
  assert.equal(revoked.status, 204)
  const create = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/observations' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '结束并保存', exact: true }).click()
  const expired = await create
  assert.equal(expired.status(), 401)
  const reauthenticate = page.getByRole('form', { name: '恢复训练登录' })
  await reauthenticate.getByLabel('用户名', { exact: true }).fill('trainee')
  await reauthenticate.getByLabel('密码', { exact: true }).fill('Demo123!')
  const retry = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/observations' && response.request().method() === 'POST')
  await reauthenticate.getByRole('button', { name: '登录并重试保存', exact: true }).click()
  const saved = await retry
  assert.equal(saved.status(), 201, await saved.text())
  const report = await saved.json()
  assert.equal(report.planItemId, createdPlan.items[0].id)
  assert.equal(report.setNumber, 1)
  assert.ok(report.totalFrames > 0)

  await page.getByRole('button', { name: '进入下一组', exact: true }).click()
  await page.getByText(/联调计划 · 肘屈伸 · 第 2 \/ 2 组/).waitFor()
  await seekLivePreview(page)
  await waitForPaintedPose(page)
  await page.getByRole('button', { name: '开始训练', exact: true }).click()
  const secondStart = await page.getByTestId('camera-video').evaluate(element => element.currentTime)
  await page.waitForFunction(initial => document.querySelector('[data-testid="camera-video"]').currentTime > initial + 1, secondStart)
  const secondSave = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/observations' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '结束并保存', exact: true }).click()
  const secondSaved = await secondSave
  assert.equal(secondSaved.status(), 201, 'The next set must use the renewed session without another login')
  assert.equal((await secondSaved.json()).setNumber, 2)

  await page.getByRole('button', { name: '训练工作台', exact: true }).click()
  await page.getByRole('heading', { name: '训练报告详情', exact: true }).waitFor()
  const reportPath = `/observations/${report.id}`
  const guideReport = await apiRequest(reportPath, guide.token)
  assert.equal(guideReport.status, 200)
  assert.equal((await guideReport.json()).planItemId, createdPlan.items[0].id)
  assert.ok([403, 404].includes((await apiRequest(reportPath, outsider.token)).status))
  assert.ok([401, 404].includes((await apiRequest(reportPath)).status))

  const reviewed = await apiRequest(`${reportPath}/review`, guide.token, {
    method: 'POST', body: JSON.stringify({ note: '已核对本组画面与采集信息。', finalize: true }),
  })
  assert.equal(reviewed.status, 200, await reviewed.clone().text())
  assert.equal((await reviewed.json()).status, 'FINALIZED')
  const refreshedTrainee = await login('trainee')
  const seenByTrainee = await apiRequest(`${reportPath}/review`, refreshedTrainee.token)
  assert.equal(seenByTrainee.status, 200)
  assert.equal((await seenByTrainee.json()).status, 'FINALIZED')
})

test('guide publishes a personal plan and reviews target deviations through the workspace UI', { timeout: 90_000 }, async t => {
  const api = await startTestApi(t)
  const page = await openTestCamera(t)
  await page.route('**/api/v1/**', async route => {
    const requestUrl = new URL(route.request().url())
    const response = await route.fetch({ url: `${api}${requestUrl.pathname}${requestUrl.search}` })
    await route.fulfill({ response })
  })
  await page.goto(base.href)
  await page.getByRole('button', { name: '训练工作台', exact: true }).click()
  await page.getByLabel('用户名', { exact: true }).fill('teacher')
  await page.getByLabel('密码', { exact: true }).fill('Demo123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '指导者工作台', exact: true }).waitFor()
  await page.getByRole('button', { name: '训练者', exact: true }).click()
  await page.getByLabel('训练侧').selectOption('RIGHT')
  await page.getByLabel('基线备注', { exact: true }).fill('浏览器流程验证：右侧，侧面机位。')
  const baselineSaved = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/baseline') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存基线', exact: true }).click()
  assert.equal((await baselineSaved).status(), 204)

  await page.getByRole('button', { name: '训练计划', exact: true }).click()
  await page.getByLabel('计划名称', { exact: true }).fill('浏览器流程验证计划')
  await page.getByLabel('训练者').selectOption({ index: 1 })
  await page.getByLabel('计划日期', { exact: true }).fill('2026-11-20')
  await page.locator('.ws-plan-editor').getByLabel('动作', { exact: false }).selectOption('shoulder_flexion')
  await page.getByLabel('训练侧').selectOption('RIGHT')
  await page.getByLabel('组数', { exact: true }).fill('1')
  await page.getByLabel('每组次数', { exact: true }).fill('1')
  await page.getByLabel('记录方式').selectOption('target')
  for (const [label, value] of [['最小幅度（°）', '130'], ['最大幅度（°）', '150'],
    ['最短循环（秒）', '1'], ['最长循环（秒）', '12'], ['最低连续保持（秒）', '0.1']]) {
    await page.getByLabel(label, { exact: true }).fill(value)
  }
  const published = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/plans' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '发布计划', exact: true }).click()
  const publishedResponse = await published
  assert.equal(publishedResponse.status(), 201, await publishedResponse.text())
  const plan = await publishedResponse.json()
  await page.locator(`[data-plan-id="${plan.id}"]`).waitFor()

  // Synthetic geometry isolates the review workflow from recognition accuracy.
  const login = await fetch(`${api}/api/v1/workspace/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'trainee', password: 'Demo123!' }) })
  const trainee = await login.json()
  const frames = Array.from({ length: 51 }, (_, index) => {
    const angle = index < 12 ? 0 : index < 21 ? (index - 12) * 10 : index < 30 ? 90 : index < 39 ? (39 - index) * 10 : 0
    const rotation = angle * Math.PI / 180
    const point = (x, y) => ({ x: x / 640, y: y / 480, visibility: 1 })
    const points = Array.from({ length: 33 }, () => point(320, 240))
    points[12] = point(320, 150)
    points[24] = point(320, 330)
    points[14] = point(320 + 70 * Math.sin(rotation), 150 + 70 * Math.cos(rotation))
    points[16] = point(320 + 140 * Math.sin(rotation), 150 + 140 * Math.cos(rotation))
    return { time: index / 10, width: 640, height: 480, poses: 1, points }
  })
  const saved = await fetch(`${api}/api/v1/observations`, { method: 'POST',
    headers: { Authorization: `Bearer ${trainee.token}`, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ planItemId: plan.items[0].id, setNumber: 1, frames }) })
  assert.equal(saved.status, 201, await saved.clone().text())
  const report = await saved.json()
  assert.equal(report.repetitions.length, 1)
  assert.equal(report.repetitions[0].checks.amplitude, false)

  await page.getByRole('button', { name: '训练报告', exact: true }).click()
  await page.getByRole('button', { name: '刷新报告', exact: true }).click()
  await page.locator(`tr[data-report-id="${report.id}"]`).getByRole('button', { name: '查看报告', exact: true }).click()
  await page.getByRole('heading', { name: '训练报告详情', exact: true }).waitFor()
  await page.getByLabel('总体意见', { exact: true }).fill('流程测试，参数仅用于验证分项复核。')
  const finalize = page.getByRole('button', { name: '完成复核', exact: true })
  assert.equal(await finalize.isDisabled(), true, 'Unreviewed events prevent finalization')
  const events = page.locator('.ws-review-item')
  assert.ok(await events.count() > 0)
  for (const event of await events.all()) {
    await event.getByLabel('复核结论').selectOption('CONFIRMED')
    await event.getByLabel('分项备注', { exact: true }).fill('已核对分项数值。')
    const reviewSaved = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/event-reviews') && response.request().method() === 'POST')
    await event.getByRole('button', { name: '保存分项复核', exact: true }).click()
    assert.equal((await reviewSaved).status(), 200)
  }
  await page.waitForFunction(() => ![...document.querySelectorAll('button')].find(button => button.textContent === '完成复核')?.disabled)
  const first = events.first()
  await first.getByLabel('分项备注', { exact: true }).fill('修改后尚未保存的备注。')
  assert.equal(await finalize.isDisabled(), true, 'Unsaved draft edits prevent finalization')
  const resaved = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/event-reviews') && response.request().method() === 'POST')
  await first.getByRole('button', { name: '保存分项复核', exact: true }).click()
  assert.equal((await resaved).status(), 200)
  const finalized = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/review') && response.request().method() === 'POST')
  await finalize.click()
  const result = await finalized
  assert.equal(result.status(), 200, await result.text())
  assert.equal((await result.json()).status, 'FINALIZED')
  await page.getByText('已完成复核。', { exact: true }).waitFor()
  assert.equal(await page.getByLabel('总体意见', { exact: true }).count(), 0)
})

test('a delayed logout from a closed workspace cannot erase a newer login', { timeout: 90_000 }, async t => {
  const api = await startTestApi(t)
  const page = await openTestCamera(t)
  const logoutReceived = Promise.withResolvers()
  const releaseLogout = Promise.withResolvers()
  await page.route('**/api/v1/**', async route => {
    const requestUrl = new URL(route.request().url())
    const response = await route.fetch({ url: `${api}${requestUrl.pathname}${requestUrl.search}` })
    if (requestUrl.pathname === '/api/v1/workspace/logout') {
      logoutReceived.resolve()
      await releaseLogout.promise
    }
    await route.fulfill({ response })
  })
  async function login(username) {
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('Demo123!')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('heading', { name: '指导者工作台', exact: true }).waitFor()
  }
  try {
    await page.goto(base.href)
    await page.getByRole('button', { name: '训练工作台', exact: true }).click()
    await login('teacher')
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await logoutReceived.promise
    await page.getByRole('button', { name: '摄像头试练', exact: true }).click()
    await page.getByRole('button', { name: '训练工作台', exact: true }).click()
    await login('teacher2')
    const token = await page.evaluate(() => sessionStorage.getItem('rehab.workspace.token'))
    const logoutFinished = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/workspace/logout')
    releaseLogout.resolve()
    await logoutFinished
    await page.reload()
    await page.getByRole('heading', { name: '指导者工作台', exact: true }).waitFor()
    assert.equal(await page.evaluate(() => sessionStorage.getItem('rehab.workspace.token')), token)
    await page.locator('.ws-identity').getByText('teacher2', { exact: true }).waitFor()
  } finally {
    releaseLogout.resolve()
  }
})
