<script setup lang="ts">
import { NModal, NCard, NForm, NFormItem, NInput, NButton, NSpace, useMessage } from 'naive-ui'
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { put, errorText } from '../api/client'

const props = defineProps<{ show: boolean; userId: number | null; title?: string }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>()
const { t } = useI18n()
const message = useMessage()

const password = ref('')
const confirm = ref('')
const error = ref('')
const saving = ref(false)

watch(() => props.show, (v) => {
  if (v) { password.value = ''; confirm.value = ''; error.value = '' }
})

async function save() {
  if (password.value.length < 6) { error.value = t('users.passwordTooShort'); return }
  if (password.value !== confirm.value) { error.value = t('users.passwordMismatch'); return }
  saving.value = true
  try {
    const r = await put<{ message: string }>(`/api/admin/users/${props.userId}/password`, { password: password.value })
    message.success(r.message)
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal :show="show" @update:show="(v: boolean) => emit('update:show', v)">
    <NCard :title="title ?? $t('users.changePassword')" style="width: 420px" :bordered="false" role="dialog">
      <NForm @submit.prevent="save">
        <NFormItem :label="$t('users.newPassword')">
          <NInput v-model:value="password" type="password" show-password-on="click" :placeholder="$t('users.passwordPlaceholder')" />
        </NFormItem>
        <NFormItem :label="$t('users.confirmPassword')" :feedback="error" :validation-status="error ? 'error' : undefined">
          <NInput v-model:value="confirm" type="password" show-password-on="click" @keyup.enter="save" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="emit('update:show', false)">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="save">{{ $t('common.save') }}</NButton>
        </NSpace>
      </template>
    </NCard>
  </NModal>
</template>
