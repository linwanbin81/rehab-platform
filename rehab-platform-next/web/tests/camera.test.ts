import assert from 'node:assert/strict'
import test from 'node:test'
import * as camera from '../src/camera.ts'

test('attaches the browser stream and starts video playback before returning', async () => {
  const tracks: MediaTrackConstraints[] = []
  const stream = { getVideoTracks: () => [{}], getTracks: () => [{ stop() {} }] } as unknown as MediaStream
  const element = {
    srcObject: null as MediaStream | null,
    async play() { assert.equal(element.srcObject, stream) },
  }
  const video = element as unknown as HTMLVideoElement
  const media = {
    async getUserMedia(constraints: MediaStreamConstraints) {
      assert.equal(constraints.audio, false)
      tracks.push(constraints.video as MediaTrackConstraints)
      return stream
    },
  }
  assert.equal(await camera.openVideo(video, media, 'device-2'), stream)
  assert.deepEqual(tracks[0].deviceId, { exact: 'device-2' })
})

test('stops a camera track if the video cannot play', async () => {
  let stopped = false
  const stream = { getTracks: () => [{ stop() { stopped = true } }] } as unknown as MediaStream
  const video = {
    srcObject: null,
    async play() { throw new Error('preview failed') },
  } as unknown as HTMLVideoElement
  await assert.rejects(camera.openVideo(video, { async getUserMedia() { return stream } }), /preview failed/)
  assert.equal(stopped, true)
  assert.equal(video.srcObject, null)
})

test('a cancelled permission request cannot replace a newer live preview', async () => {
  const cancelled = new AbortController()
  let stopped = false
  const oldStream = { getTracks: () => [{ stop() { stopped = true } }] } as unknown as MediaStream
  const newStream = {} as MediaStream
  const element = { srcObject: newStream, async play() {} } as unknown as HTMLVideoElement
  let resolveOld!: (value: MediaStream) => void
  const pending = new Promise<MediaStream>(resolve => { resolveOld = resolve })
  const opening = camera.openVideo(element, { getUserMedia: () => pending }, '', cancelled.signal)
  cancelled.abort()
  resolveOld(oldStream)
  await assert.rejects(opening, { name: 'AbortError' })
  assert.equal(element.srcObject, newStream)
  assert.equal(stopped, true)
})

test('explains reasons camera permission can fail', () => {
  assert.match(camera.cameraError(new DOMException('', 'NotAllowedError')), /权限/)
  assert.match(camera.cameraError(new DOMException('', 'NotReadableError')), /占用|读取/)
  assert.match(camera.cameraError(new DOMException('', 'NotFoundError')), /设备/)
})
