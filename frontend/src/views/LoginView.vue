<script setup lang="ts">
import { NCard, NForm, NFormItem, NInput, NButton, NAlert } from 'naive-ui'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LocaleSwitcher from '../components/LocaleSwitcher.vue'
import { api, errorText } from '../api/client'
import { loadMe } from '../auth'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function login() {
  if (loading.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = t('login.required'); return }
  loading.value = true
  try {
    await api('POST', '/api/auth/login', { username: username.value.trim(), password: password.value })
    const me = await loadMe()
    if (me.mustChange) { router.replace({ name: 'change-password' }); return }
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/status'
    router.replace(redirect === '/login' ? '/status' : redirect)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div style="min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1a1a2e">
    <NCard style="width: 360px" :bordered="false">
      <template #header>
        <div style="text-align: center; font-size: 20px">📡 {{ $t('app.title') }}</div>
      </template>
      <template #header-extra><LocaleSwitcher /></template>
      <NForm @submit.prevent="login">
        <NFormItem :label="$t('login.username')">
          <NInput v-model:value="username" placeholder="admin" autofocus data-test="login-username" />
        </NFormItem>
        <NFormItem :label="$t('login.password')">
          <NInput v-model:value="password" type="password" show-password-on="click" data-test="login-password" />
        </NFormItem>
        <NAlert v-if="error" type="error" :show-icon="false" style="margin-bottom: 12px" data-test="login-error">{{ error }}</NAlert>
        <!-- 送出按鈕觸發表單 submit（含在輸入框按 Enter），不另綁 click，避免重複送出 -->
        <NButton type="primary" block :loading="loading" attr-type="submit" data-test="login-submit">
          {{ $t('login.submit') }}
        </NButton>
      </NForm>
    </NCard>
  </div>
</template>
