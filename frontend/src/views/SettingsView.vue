<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NInput, NInputNumber, NText, NGrid, NGi, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { get, put, errorText } from '../api/client'
import type { Setting } from '../api/types'

const { t } = useI18n()
const message = useMessage()

const RETENTION = ['retention_metrics_days', 'retention_alerts_days', 'retention_audit_days'] as const
const retention = reactive<Record<string, number | null>>({})
const others = ref<Setting[]>([])

async function load() {
  try {
    const all = await get<Record<string, Setting>>('/api/admin/settings')
    for (const k of RETENTION) retention[k] = all[k] ? Number(all[k].value) : null
    others.value = Object.entries(all).filter(([k]) => !(RETENTION as readonly string[]).includes(k))
      .map(([k, v]) => ({ ...v, key: k }))
  } catch (e) {
    message.error(errorText(e))
  }
}
onMounted(load)

async function saveRetention() {
  try {
    for (const k of RETENTION) {
      await put(`/api/admin/settings/${k}`, { value: String(retention[k] ?? '') })
    }
    message.success(t('settings.retentionSaved'))
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}

async function save(s: Setting) {
  try {
    const r = await put<{ message: string }>(`/api/admin/settings/${encodeURIComponent(s.key)}`, { value: s.value.trim() })
    message.success(r.message)
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}

const columns = computed<DataTableColumns<Setting>>(() => [
  { title: t('settings.key'), key: 'key', width: 220, render: (s) => h('span', { class: 'mono' }, s.key) },
  { title: t('settings.description'), key: 'description', render: (s) => h(NText, { depth: 3 }, () => s.description) },
  {
    title: t('settings.value'), key: 'value', width: 260,
    render: (s) => h(NInput, { value: s.value, size: 'small', type: s.key.includes('password') ? 'password' : 'text',
      showPasswordOn: 'click', 'onUpdate:value': (v: string) => { s.value = v } }),
  },
  { title: '', key: 'actions', width: 90, render: (s) => h(NButton, { size: 'small', type: 'primary', onClick: () => save(s) }, () => t('common.save')) },
])
</script>

<template>
  <div>
    <NCard :title="$t('settings.retentionTitle')">
      <NGrid :cols="3" :x-gap="16" :y-gap="12" responsive="screen" item-responsive>
        <NGi v-for="k in RETENTION" :key="k" span="3 m:1">
          <div style="font-weight: 600">{{ $t(`settings.retention.${k}.label`) }}</div>
          <NInputNumber v-model:value="retention[k]" :min="0" :max="36500" style="width: 160px; margin: 6px 0" :data-test="`ret-${k}`">
            <template #suffix>{{ $t('settings.days') }}</template>
          </NInputNumber>
          <NText depth="3" style="font-size: 12px; display: block">{{ $t(`settings.retention.${k}.hint`) }}</NText>
        </NGi>
      </NGrid>
      <NSpace justify="space-between" align="center" style="margin-top: 12px">
        <NText depth="3" style="font-size: 12px">{{ $t('settings.retentionHint') }}</NText>
        <NButton type="primary" data-test="ret-save" @click="saveRetention">{{ $t('settings.saveRetention') }}</NButton>
      </NSpace>
    </NCard>
    <NCard :title="$t('settings.otherTitle')" class="page-section">
      <NDataTable :columns="columns" :data="others" :row-key="(s: Setting) => s.key" :bordered="false" />
    </NCard>
  </div>
</template>
