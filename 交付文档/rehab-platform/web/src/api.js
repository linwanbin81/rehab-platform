import { ElMessage } from 'element-plus'
export const token = () => localStorage.getItem('rehab-token') || ''
export async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(token() ? { Authorization: `Bearer ${token()}` } : {}), ...options.headers },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    if (response.status === 401 && path !== '/login') { localStorage.removeItem('rehab-token'); window.dispatchEvent(new Event('rehab-unauthorized')) }
    const error = new Error(data.message || `请求失败 (${response.status})`)
    ElMessage.error(error.message)
    throw error
  }
  return data
}
export async function exportReport(id) {
  const response = await fetch(`/api/sessions/${id}/export`, { headers: { Authorization: `Bearer ${token()}` } })
  if (!response.ok) { ElMessage.error('导出失败'); return }
  const url = URL.createObjectURL(await response.blob())
  const a = document.createElement('a'); a.href = url; a.download = `训练报告-${id}.csv`; a.click(); URL.revokeObjectURL(url)
}
