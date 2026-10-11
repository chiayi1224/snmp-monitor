<script setup lang="ts">
import { NCard, NDataTable, NTooltip, NSpace, NText } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PctBar from '../components/PctBar.vue'
import SeverityTag from '../components/SeverityTag.vue'
import { post } from '../api/client'
import type { AlertRecord, DeviceStatus, Severity } from '../api/types'
import { fmtNum, fmtTime } from '../utils/format'

const { t } = useI18n()

interface Row extends DeviceStatus { noData?: boolean; worst?: { severity: Severity; metrics: string[] } }

const rows = ref<Row[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

async function load() {
  loading.value = true
  try {
    const [status, active] = await Promise.all([
      post<DeviceStatus[]>('/api/v1/status').catch(() => [] as DeviceStatus[]),
      post<AlertRecord[]>('/api/v1/alerts', { active: true, limit: 1000 }).catch(() => [] as AlertRecord[]),
    ])
    // 每台設備目前未解除告警中最高的等級
    const worst = new Map<string, { severity: Severity; metrics: string[] }>()
    for (const a of active) {
      const w = worst.get(a.deviceName) ?? { severity: 'WARNING' as Severity, metrics: [] }
      if (a.severity === 'CRITICAL') w.severity = 'CRITICAL'
      w.metrics.push(t('common.withNote', { main: a.metricName, note: t(`severity.${a.severity}`) }))
      worst.set(a.deviceName, w)
    }
    const out: Row[] = status.map((d) => ({ ...d, worst: worst.get(d.deviceName) }))
    // 有未解除告警但沒有任何指標數據的設備（例如一開始就連不上）也要列出
    for (const [name, w] of worst) {
      if (!out.some((d) => d.deviceName === name)) out.push({ deviceName: name, noData: true, worst: w })
    }
    rows.value = out
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 30_000)
})
onUnmounted(() => clearInterval(timer))

const columns = computed<DataTableColumns<Row>>(() => [
  {
    title: t('status.device'), key: 'deviceName', width: 220,
    render: (r) => h('div', [
      h('strong', r.deviceName),
      r.worst ? h(NTooltip, null, {
        trigger: () => h(NSpace, { size: 4, align: 'center', style: 'margin-top:4px' }, () => [
          h(SeverityTag, { severity: r.worst!.severity }),
          h(NText, { depth: 3, style: 'font-size:12px' }, () => t('status.alertCount', { n: r.worst!.metrics.length })),
        ]),
        default: () => t('status.unresolved', { list: r.worst!.metrics.join(t('common.listSep')) }),
      }) : null,
    ]),
  },
  {
    title: t('status.cpu'), key: 'cpu',
    render: (r) => r.noData ? h(NText, { depth: 3 }, () => t('status.noMetrics')) : r.cpu ? h(PctBar, { pct: r.cpu.usedPct }) : '—',
    colSpan: (r) => (r.noData ? 4 : 1),
  },
  { title: t('status.memory'), key: 'memory', render: (r) => (r.memory ? h(PctBar, { pct: r.memory.usedPct }) : '—') },
  {
    title: t('status.disk'), key: 'disks',
    render: (r) => r.disks?.length
      ? h('div', r.disks.map((d) => h('div', { style: 'display:flex;gap:8px;align-items:center' }, [
          h('span', { class: 'mono', style: 'min-width:60px' }, d.mountPoint), h(PctBar, { pct: d.usedPct }),
        ])))
      : '—',
  },
  {
    title: t('status.custom'), key: 'custom',
    render: (r) => r.custom?.length
      ? h('div', r.custom.map((c) => h('div', { title: fmtTime(c.ts) }, [
          t('common.labelPrefix', { label: c.metricKey }), h('strong', fmtNum(c.value)), ` ${c.unit}`,
        ])))
      : '—',
  },
])
</script>

<template>
  <NCard :title="$t('status.title')">
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(r: Row) => r.deviceName"
                :bordered="false" />
  </NCard>
</template>
