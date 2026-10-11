import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { auth, clearAuth, loadMe } from './auth'
import { onAuthError } from './api/client'

export const MENU_ROUTES = [
  'status', 'devices', 'discovery', 'profiles', 'traps', 'thresholds',
  'alerts', 'users', 'tokens', 'audit', 'settings',
] as const

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  { path: '/change-password', name: 'change-password', component: () => import('./views/ChangePasswordView.vue') },
  {
    path: '/',
    component: () => import('./components/AppLayout.vue'),
    redirect: { name: 'status' },
    children: [
      { path: 'status', name: 'status', component: () => import('./views/StatusView.vue') },
      { path: 'devices', name: 'devices', component: () => import('./views/DevicesView.vue') },
      { path: 'discovery', name: 'discovery', component: () => import('./views/DiscoveryView.vue') },
      { path: 'profiles', name: 'profiles', component: () => import('./views/ProfilesView.vue') },
      { path: 'traps', name: 'traps', component: () => import('./views/TrapsView.vue') },
      { path: 'thresholds', name: 'thresholds', component: () => import('./views/ThresholdsView.vue') },
      { path: 'alerts', name: 'alerts', component: () => import('./views/AlertsView.vue') },
      { path: 'users', name: 'users', component: () => import('./views/UsersView.vue') },
      { path: 'tokens', name: 'tokens', component: () => import('./views/TokensView.vue') },
      { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue') },
      { path: 'settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'status' } },
]

export const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!auth.user) {
    try {
      await loadMe()
    } catch {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  // 需改密碼時只能進修改密碼畫面（後端 AuthFilter 同樣在伺服器端把關）
  if (auth.user?.mustChange && to.name !== 'change-password') return { name: 'change-password' }
  if (!auth.user?.mustChange && to.name === 'change-password') return { name: 'status' }
  return true
})

// 任何 API 回 401（登入失效、被管理員撤銷）→ 回登入畫面；403 且需改密碼 → 修改密碼畫面
onAuthError((status) => {
  const current = router.currentRoute.value
  if (status === 401) {
    clearAuth()
    if (current.name !== 'login') router.push({ name: 'login', query: { redirect: current.fullPath } })
  } else if (status === 403 && auth.user?.mustChange && current.name !== 'change-password') {
    router.push({ name: 'change-password' })
  }
})
