import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { api, ApiError, onAuthError } from './client'
import { setLocale } from '../i18n'

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn().mockImplementation(() => Promise.resolve(new Response(body === undefined ? '' : JSON.stringify(body), { status })))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('API 用戶端', () => {
  beforeEach(() => setLocale('zh-TW'))
  afterEach(() => vi.unstubAllGlobals())

  it('每個請求帶上目前語言的 Accept-Language，切換語言後跟著改變', async () => {
    const fn = mockFetch(200, { ok: true })
    await api('GET', '/api/info')
    expect(fn.mock.calls[0][1].headers['Accept-Language']).toBe('zh-TW')
    setLocale('en')
    await api('POST', '/api/v1/status', {})
    expect(fn.mock.calls[1][1].headers['Accept-Language']).toBe('en')
    expect(fn.mock.calls[1][1].headers['Content-Type']).toBe('application/json')
  })

  it('錯誤時拋出 ApiError，訊息為後端回傳的 error', async () => {
    mockFetch(400, { error: 'With direction HIGH, Warning must be lower than Critical' })
    await expect(api('PUT', '/api/admin/device-thresholds', {})).rejects.toMatchObject({
      status: 400, message: 'With direction HIGH, Warning must be lower than Critical',
    })
  })

  it('401 時呼叫認證處理（登入請求本身除外）', async () => {
    const handler = vi.fn()
    onAuthError(handler)
    mockFetch(401, { error: 'Not logged in' })
    await expect(api('GET', '/api/admin/devices')).rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledWith(401)

    handler.mockClear()
    mockFetch(401, { error: 'bad' })
    await expect(api('POST', '/api/auth/login', {})).rejects.toBeInstanceOf(ApiError)
    expect(handler).not.toHaveBeenCalled()
  })

  it('網路錯誤時顯示前端的翻譯訊息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    setLocale('en')
    await expect(api('GET', '/api/info')).rejects.toMatchObject({ status: 0, message: expect.stringContaining('Cannot reach the server') })
  })

  it('沒有 error 欄位時使用 HTTP 狀態碼訊息', async () => {
    mockFetch(500, undefined)
    await expect(api('GET', '/api/info')).rejects.toMatchObject({ message: '請求失敗（HTTP 500）' })
  })
})
