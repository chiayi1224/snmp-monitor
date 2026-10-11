<script setup lang="ts">
import { NCard, NDataTable, NSelect, NCheckbox, NButton, NSpace, NText } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import SeverityTag from '../components/SeverityTag.vue'
import { post } from '../api/client'
import type { AlertRecord, Device } from '../api/types'
import { fmtAlertValue, fmtTime } from '../utils/format'

const { t } = useI18n()

const severity = ref<string | null>(null)
const device = ref<string | null>(null)
const activeOnly = ref(false)
const devices = ref<Device[]>([])
const rows = ref<AlertRecord[]>([])
const loading = ref(false)

const severityOptions = computed(() => [
  { label: t('alerts.allSeverities'), value: '' },
  { label: t('severity.CRITICAL'), value: 'CRITICAL' },
  { label: t('severity.WARNING'), value: 'WARNING' },
])
const deviceOptions = computed(() => [
  { label: t('alerts.allDevices'), value: '' },
  ...devices.value.map((d) => ({ label: d.name, value: d.name })),
])

async function load() {
  loading.value = true
  try {
    const query: Record<string, unknown> = { limit: 200, active: activeOnly.value }
    if (severity.value) query.severity = severity.value
    if (device.value) query.device = device.value
    rows.value = await post<AlertRecord[]>('/api/v1/alerts', query)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  devices.value = await post<Device[]>('/api/v1/devices').catch(() => [])
  load()
})

const columns = computed<DataTableColumns<AlertRecord>>(() => [
  { title: t('alerts.time'), key: 'ts', width: 170, render: (r) => fmtTime(r.ts) },
  { title: t('alerts.device'), key: 'deviceName' },
  {
    title: t('alerts.metric'), key: 'metricName',
    render: (r) => r.metricName === 'device_unreachable'
      ? h('div', [h('div', r.metricName), h(NText, { depth: 3, style: 'font-size:12px' }, () => t('alerts.unreachable'))])
      : r.metricName,
  },
  { title: t('alerts.severity'), key: 'severity', width: 100, render: (r) => h(SeverityTag, { severity: r.severity }) },
  {
    title: t('alerts.event'), key: 'eventType', width: 180,
    render: (r) => h(NText, {
      type: r.eventType === 'RESOLVED' ? 'success' : r.severity === 'CRITICAL' ? 'error' : 'warning', strong: true,
    }, () => t('alerts.eventFormat', { code: r.eventType, label: t(`event.${r.eventType}`) })),
  },
  { title: t('alerts.value'), key: 'value', width: 100, render: (r) => (noValue(r) ? '—' : fmtAlertValue(r.metricName, r.value)) },
  { title: t('alerts.threshold'), key: 'thresholdVal', width: 110, render: (r) => (noValue(r) ? '—' : fmtAlertValue(r.metricName, r.thresholdVal)) },
  { title: t('alerts.message'), key: 'message', ellipsis: { tooltip: true } },
])

// 連線中斷與 Trap 告警沒有量測值
function noValue(r: AlertRecord) {
  return r.metricName === 'device_unreachable' || (r.value === 0 && r.thresholdVal === 0)
}
</script>

<template>
  <NCard :title="activeOnly ? $t('alerts.activeTitle') : $t('alerts.title')">
    <NSpace style="margin-bottom: 12px" align="center">
      <NSelect v-model:value="severity" :options="severityOptions" style="width: 150px" :placeholder="$t('alerts.allSeverities')"
               data-test="alert-severity" @update:value="load" />
      <NSelect v-model:value="device" :options="deviceOptions" style="width: 200px" :placeholder="$t('alerts.allDevices')"
               filterable data-test="alert-device" @update:value="load" />
      <NCheckbox v-model:checked="activeOnly" data-test="alert-active" @update:checked="load">{{ $t('alerts.activeOnly') }}</NCheckbox>
      <NButton @click="load">{{ $t('common.refresh') }}</NButton>
    </NSpace>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(r: AlertRecord) => r.id" :bordered="false"
                :pagination="{ pageSize: 50 }" />
  </NCard>
</template>
