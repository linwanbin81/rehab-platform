type VideoDevice = Pick<MediaDevices, 'getUserMedia'>

export async function openVideo(video: HTMLVideoElement, media: VideoDevice, deviceId = '', signal?: AbortSignal): Promise<MediaStream> {
  signal?.throwIfAborted()
  const stream = await media.getUserMedia({
    audio: false,
    video: {
      width: { ideal: 960 },
      height: { ideal: 540 },
      ...(deviceId ? { deviceId: { exact: deviceId } } : { facingMode: { ideal: 'user' } }),
    },
  })
  const cancel = () => stopVideo(video, stream)
  try {
    signal?.throwIfAborted()
    signal?.addEventListener('abort', cancel, { once: true })
    video.srcObject = stream
    await video.play()
    signal?.throwIfAborted()
    return stream
  } catch (error) {
    cancel()
    throw error
  } finally {
    signal?.removeEventListener('abort', cancel)
  }
}

export function stopVideo(video: HTMLVideoElement, stream: MediaStream) {
  stream.getTracks().forEach(track => track.stop())
  if (video.srcObject === stream) video.srcObject = null
}

export function cameraError(error: unknown): string {
  const name = error instanceof Error ? error.name : ''
  switch (name) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
    case 'SecurityError':
      return '摄像头权限未开启。请在浏览器地址栏允许摄像头，并在 Windows「隐私和安全性 → 摄像头」中允许桌面应用访问，然后重试。'
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return '未找到摄像头设备。请确认设备已连接、启用且物理遮挡盖已打开。'
    case 'NotReadableError':
    case 'TrackStartError':
      return '浏览器无法读取摄像头。请关闭正在占用设备的相机软件或其他标签页后重试。'
    case 'OverconstrainedError':
      return '选中的摄像头已不可用。请选择系统默认摄像头或其他设备。'
    default:
      return `打开摄像头失败${error instanceof Error && error.message ? `（${error.message}）` : ''}。请在 Edge 或 Chrome 中重试。`
  }
}
