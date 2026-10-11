<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NTag, NModal, NForm, NFormItem, NInput, NDatePicker, NAlert, useMessage, useDialog } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { get, post, errorText } from '../api/client'
import type { ApiToken } from '../api/types'
import { fmtDate, fmtTime } from '../utils/format'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const rows = ref<ApiToken[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await get<ApiToken[]>('/api/admin/tokens')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const showCreate = ref(false)
const description = ref('')
const expires = ref<number | null>(null)
const created = ref('')

function openCreate() {
  description.value = ''
  expires.value = null
  showCreate.value = true
}

async function create() {
  if (!description.value.trim()) { message.warning(t('tokens.descriptionRequired')); return }
  const expiresAt = expires.value ? new Date(expires.value).toISOString().substring(0, 10) : null
  try {
    const r = await post<{ token: string }>('/api/admin/tokens', { description: description.value.trim(), expiresAt })
    showCreate.value = false
    created.value = r.token
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(created.value)
    message.success(t('tokens.copied'))
  } catch {
    message.warning(t('tokens.copyFailed'))
  }
}

function revoke(tk: ApiToken) {
  dialog.warning({
    title: t('tokens.revoke'), content: t('tokens.revokeConfirm', { name: tk.description }),
    positiveText: t('tokens.revoke'), negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        message.success((await post<{ message: string }>(`/api/admin/tokens/${tk.id}`, { action: 'delete' })).message)
        load()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

const columns = computed<DataTableColumns<ApiToken>>(() => [
  { title: t('tokens.description'), key: 'description' },
  {
    title: t('tokens.state'), key: 'enabled',
    render: (tk) => h(NTag, { size: 'small', type: tk.enabled ? 'success' : 'default', bordered: false }, () => (tk.enabled ? t('common.enabled') : t('common.disabled'))),
  },
  { title: t('tokens.expires'), key: 'expiresAt', render: (tk) => (tk.expiresAt ? fmtDate(tk.expiresAt) : t('tokens.never')) },
  { title: t('tokens.lastUsed'), key: 'lastUsedAt', render: (tk) => fmtTime(tk.lastUsedAt) },
  { title: t('tokens.created'), key: 'createdAt', render: (tk) => fmtTime(tk.createdAt) },
  {
    title: t('common.actions'), key: 'actions', width: 100,
    render: (tk) => h(NButton, { size: 'small', type: 'error', ghost: true, onClick: () => revoke(tk) }, () => t('tokens.revoke')),
  },
])
</script>

<template>
  <NCard :title="$t('tokens.title')">
    <template #header-extra>
      <NButton type="primary" data-test="token-add" @click="openCreate">{{ $t('tokens.add') }}</NButton>
    </template>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(tk: ApiToken) => tk.id" :bordered="false" />

    <NModal v-model:show="showCreate" preset="card" :title="$t('tokens.add')" style="width: 420px">
      <NForm label-placement="top">
        <NFormItem :label="$t('tokens.description') + ' *'">
          <NInput v-model:value="description" :placeholder="$t('tokens.descriptionPlaceholder')" data-test="token-desc" />
        </NFormItem>
        <NFormItem :label="$t('tokens.expiresOptional')">
          <NDatePicker v-model:value="expires" type="date" clearable style="width: 100%" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showCreate = false">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" data-test="token-create" @click="create">{{ $t('tokens.create') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal :show="!!created" preset="card" :title="$t('tokens.createdTitle')" style="width: 520px" :mask-closable="false"
            @update:show="(v: boolean) => { if (!v) created = '' }">
      <NAlert type="warning" :show-icon="false" style="margin-bottom: 12px">{{ $t('tokens.createdHint') }}</NAlert>
      <NInput :value="created" readonly class="mono" data-test="token-value" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="copyToken">{{ $t('tokens.copy') }}</NButton>
          <NButton type="primary" @click="created = ''">{{ $t('common.close') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NCard>
</template>
