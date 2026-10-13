<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NSelect } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { post } from '../api/client'
import type { AuditLog } from '../api/types'
import { fmtTime } from '../utils/format'

const { t } = useI18n()
const EVENTS = ['LOGIN_SUCCESS', 'LOGIN_FAIL', 'LOGOUT', 'TOKEN_USED', 'TOKEN_FAIL', 'API_WRITE', 'SESSION_REVOKED', 'MUST_CHANGE_BLOCKED', 'CSRF_BLOCKED']

const eventType = ref<string>('')
const rows = ref<AuditLog[]>([])
const loading = ref(false)
const eventOptions = computed(() => [{ label: t('audit.allEvents'), value: '' }, ...EVENTS.map((e) => ({ label: e, value: e }))])

async function load() {
  loading.value = true
  try {
    const body: Record<string, unknown> = { limit: 200 }
    if (eventType.value) body.eventType = eventType.value
    rows.value = await post<AuditLog[]>('/api/admin/audit-logs', body)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

const columns = computed<DataTableColumns<AuditLog>>(() => [
  { title: t('audit.time'), key: 'ts', width: 170, render: (a) => fmtTime(a.ts) },
  { title: t('audit.event'), key: 'eventType', width: 170, render: (a) => h('code', a.eventType) },
  { title: t('audit.actor'), key: 'actor', render: (a) => a.actor || '—' },
  { title: t('audit.sourceIp'), key: 'sourceIp', render: (a) => a.sourceIp || '—' },
  { title: t('audit.method'), key: 'method', width: 80, render: (a) => a.method || '—' },
  { title: t('audit.path'), key: 'path', ellipsis: { tooltip: true }, render: (a) => h('span', { class: 'mono' }, a.path || '—') },
  { title: t('audit.status'), key: 'status', width: 70 },
  { title: t('audit.detail'), key: 'detail', ellipsis: { tooltip: true }, render: (a) => a.detail || '' },
])
</script>

<template>
  <NCard :title="$t('audit.title')">
    <NSpace style="margin-bottom: 12px">
      <NSelect v-model:value="eventType" :options="eventOptions" style="width: 220px" @update:value="load" />
      <NButton type="primary" @click="load">{{ $t('audit.query') }}</NButton>
    </NSpace>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(a: AuditLog) => a.id" :bordered="false"
                :pagination="{ pageSize: 50 }" />
  </NCard>
</template>
