import { reactive } from 'vue'
import { get } from './api/client'
import type { User } from './api/types'

/** 目前登入者（null 代表尚未確認或未登入） */
export const auth = reactive<{ user: User | null }>({ user: null })

/** 向後端確認登入狀態；未登入時拋出 ApiError(401) */
export async function loadMe(): Promise<User> {
  auth.user = await get<User>('/api/auth/me')
  return auth.user
}

export function clearAuth() {
  auth.user = null
}
