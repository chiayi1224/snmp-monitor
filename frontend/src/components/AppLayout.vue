<script setup lang="ts">
import {
  NLayout, NLayoutSider, NLayoutHeader, NLayoutContent, NLayoutFooter, NMenu, NDropdown, NButton, NIcon, NSpace,
  useMessage,
} from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import {
  PulseOutline, ServerOutline, SearchOutline, LayersOutline, NotificationsOutline, SpeedometerOutline,
  WarningOutline, PeopleOutline, KeyOutline, ReaderOutline, SettingsOutline, PersonCircleOutline,
} from '@vicons/ionicons5'
import { computed, h, onMounted, ref } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LocaleSwitcher from './LocaleSwitcher.vue'
import PasswordModal from './PasswordModal.vue'
import { auth, clearAuth } from '../auth'
import { get, post } from '../api/client'
import { MENU_ROUTES } from '../router'
import { currentLocale } from '../i18n'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const message = useMessage()

const ICONS: Record<(typeof MENU_ROUTES)[number], Component> = {
  status: PulseOutline, devices: ServerOutline, discovery: SearchOutline, profiles: LayersOutline,
  traps: NotificationsOutline, thresholds: SpeedometerOutline, alerts: WarningOutline, users: PeopleOutline,
  tokens: KeyOutline, audit: ReaderOutline, settings: SettingsOutline,
}

const menuOptions = computed<MenuOption[]>(() => MENU_ROUTES.map((name) => ({
  key: name,
  label: t(`nav.${name}`),
  icon: () => h(NIcon, null, { default: () => h(ICONS[name]) }),
})))

const collapsed = ref(false)
const activeKey = computed(() => String(route.name ?? 'status'))

const userOptions = computed(() => [
  { key: 'password', label: t('app.changePassword') },
  { key: 'logout', label: t('app.logout') },
])

const showPassword = ref(false)

async function onUserSelect(key: string) {
  if (key === 'password') {
    showPassword.value = true
  } else if (key === 'logout') {
    try {
      await post('/api/auth/logout')
    } catch {
      /* 已失效的 session 也視為登出 */
    }
    clearAuth()
    message.success(t('app.loggedOut'))
    router.push({ name: 'login' })
  }
}

const version = ref('')
onMounted(async () => {
  try {
    const info = await get<{ title: string; version: string; buildTime: string }>('/api/info')
    version.value = `${info.title} v${info.version} · Build ${info.buildTime}`
  } catch {
    version.value = ''
  }
})
</script>

<template>
  <NLayout has-sider style="height: 100vh">
    <NLayoutSider bordered collapse-mode="width" :collapsed-width="64" :width="200" :collapsed="collapsed"
                  show-trigger @collapse="collapsed = true" @expand="collapsed = false"
                  :native-scrollbar="false" style="background: #1a1a2e" inverted>
      <div style="height: 56px; display: flex; align-items: center; justify-content: center; color: #fff; font-weight: 700; font-size: 17px; white-space: nowrap">
        📡<span v-if="!collapsed" style="margin-left: 8px">{{ $t('app.title') }}</span>
      </div>
      <NMenu :options="menuOptions" :value="activeKey" :collapsed="collapsed" :collapsed-width="64" inverted
             @update:value="(key: string) => router.push({ name: key })" />
    </NLayoutSider>
    <NLayout>
      <NLayoutHeader bordered style="height: 56px; display: flex; align-items: center; justify-content: space-between; padding: 0 20px">
        <div style="font-size: 16px; font-weight: 600">{{ $t(`nav.${activeKey}`) }}</div>
        <NSpace align="center">
          <LocaleSwitcher />
          <NDropdown :options="userOptions" trigger="click" @select="onUserSelect">
            <NButton quaternary size="small" data-test="user-menu">
              <template #icon><NIcon><PersonCircleOutline /></NIcon></template>
              {{ auth.user?.username }}
            </NButton>
          </NDropdown>
        </NSpace>
      </NLayoutHeader>
      <NLayoutContent content-style="padding: 20px; max-width: 1400px" :native-scrollbar="false" style="height: calc(100vh - 56px - 36px)">
        <!-- 切換語言時重新掛載頁面，重新向後端取得依新語言產生的文字（告警描述、範本名稱等） -->
        <RouterView :key="currentLocale" />
      </NLayoutContent>
      <NLayoutFooter bordered style="height: 36px; display: flex; align-items: center; justify-content: center; color: #aaa; font-size: 12px">
        {{ version }}
      </NLayoutFooter>
    </NLayout>
    <PasswordModal v-model:show="showPassword" :user-id="auth.user?.id ?? null" />
  </NLayout>
</template>
