<script setup lang="ts">
import { NCard, NForm, NFormItem, NInput, NButton, NAlert, NSpace, NText } from 'naive-ui'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LocaleSwitcher from '../components/LocaleSwitcher.vue'
import { put, post, errorText } from '../api/client'
import { auth, clearAuth, loadMe } from '../auth'

const { t } = useI18n()
const router = useRouter()

const password = ref('')
const confirm = ref('')
const error = ref('')
const saving = ref(false)

// 規則與升級前的改密碼頁相同
const rules = computed(() => [
  { key: 'length', ok: password.value.length >= 8 },
  { key: 'upper', ok: /[A-Z]/.test(password.value) },
  { key: 'number', ok: /[0-9]/.test(password.value) },
  { key: 'notDefault', ok: password.value.length > 0 && password.value !== 'admin' },
])
const valid = computed(() => rules.value.every((r) => r.ok) && password.value === confirm.value)

async function submit() {
  error.value = ''
  if (password.value !== confirm.value) { error.value = t('users.passwordMismatch'); return }
  if (!auth.user) { error.value = t('changePassword.noUser'); return }
  saving.value = true
  try {
    await put(`/api/admin/users/${auth.user.id}/password`, { password: password.value })
    await loadMe()
    router.replace({ name: 'status' })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}

async function logout() {
  try { await post('/api/auth/logout') } catch { /* ignore */ }
  clearAuth()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div style="min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1a1a2e">
    <NCard style="width: 420px" :bordered="false" :title="$t('changePassword.title')">
      <template #header-extra><LocaleSwitcher /></template>
      <p style="margin-top: 0; color: #666">{{ $t('changePassword.intro', { user: auth.user?.username ?? '' }) }}</p>
      <NForm @submit.prevent="submit">
        <NFormItem :label="$t('users.newPassword')">
          <NInput v-model:value="password" type="password" show-password-on="click" data-test="cp-new" />
        </NFormItem>
        <NFormItem :label="$t('users.confirmPassword')">
          <NInput v-model:value="confirm" type="password" show-password-on="click" data-test="cp-confirm" @keyup.enter="valid && submit()" />
        </NFormItem>
      </NForm>
      <div style="margin-bottom: 12px">
        <div v-for="r in rules" :key="r.key">
          <NText :type="r.ok ? 'success' : 'error'">{{ r.ok ? '✓' : '✗' }} {{ $t(`changePassword.rules.${r.key}`) }}</NText>
        </div>
      </div>
      <NAlert v-if="error" type="error" :show-icon="false" style="margin-bottom: 12px">{{ error }}</NAlert>
      <NSpace justify="space-between">
        <NButton quaternary @click="logout">{{ $t('app.logout') }}</NButton>
        <NButton type="primary" :disabled="!valid" :loading="saving" data-test="cp-submit" @click="submit">{{ $t('changePassword.submit') }}</NButton>
      </NSpace>
    </NCard>
  </div>
</template>
