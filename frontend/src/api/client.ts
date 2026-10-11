import { currentLocale, t } from '../i18n'

/** API 錯誤：message 為後端依目前語言產生的訊息（網路錯誤時為前端的翻譯） */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly data?: unknown,
  ) {
    super(message)
  }
}

type Handler = (status: number) => void
let authHandler: Handler | null = null

/** 401（登入失效）與 403（需先修改密碼）時的處理，由路由註冊 */
export function onAuthError(handler: Handler) {
  authHandler = handler
}

export async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Accept-Language': currentLocale.value }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, t('common.networkError'))
  }

  const text = await res.text()
  let data: unknown = undefined
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }

  if ((res.status === 401 || res.status === 403) && path !== '/api/auth/login') authHandler?.(res.status)
  if (!res.ok) {
    const message =
      data && typeof data === 'object' && 'error' in data && typeof (data as { error: unknown }).error === 'string'
        ? (data as { error: string }).error
        : t('common.requestFailed', { status: res.status })
    throw new ApiError(res.status, message, data)
  }
  return data as T
}

export const get = <T = unknown>(path: string) => api<T>('GET', path)
export const post = <T = unknown>(path: string, body?: unknown) => api<T>('POST', path, body ?? {})
export const put = <T = unknown>(path: string, body?: unknown) => api<T>('PUT', path, body ?? {})
export const del = <T = unknown>(path: string) => api<T>('DELETE', path)

/** 錯誤訊息文字 */
export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
