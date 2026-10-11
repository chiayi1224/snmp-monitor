<script setup lang="ts">
import {
  NCard, NDataTable, NButton, NSpace, NSelect, NCheckbox, NInput, NAlert, NTag, NText, NDescriptions, NDescriptionsItem,
  NPagination, useMessage,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AddMetricDialog from '../components/AddMetricDialog.vue'
import { ApiError, get, post, errorText } from '../api/client'
import type { Device, Scan, ScanOid } from '../api/types'
import { fmtTime } from '../utils/format'

const { t } = useI18n()
const message = useMessage()

const PAGE = 100
const devices = ref<Device[]>([])
const deviceName = ref<string | null>(null)
const walk = ref(false)
const subtrees = ref('')
const scans = ref<Scan[]>([])
const scan = ref<Scan | null>(null)
const oids = ref<ScanOid[]>([])
const total = ref(0)
const page = ref(1)
const prefix = ref('')
const keyword = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let showing: number | null = null

const deviceOptions = computed(() => devices.value.map((d) => ({
  label: t('common.withNote', { main: d.name, note: `${d.host} · v${d.snmpVersion || '2c'}` }), value: d.name,
})))
const running = computed(() => scan.value?.status === 'RUNNING')

onMounted(async () => {
  devices.value = await post<Device[]>('/api/v1/devices').catch(() => [])
  deviceName.value = devices.value[0]?.name ?? null
  loadScans(true)
})
onUnmounted(() => clearTimeout(timer))

async function loadScans(showLatest: boolean) {
  if (!deviceName.value) return
  scans.value = await get<Scan[]>(`/api/admin/discovery/scans?device=${encodeURIComponent(deviceName.value)}`).catch(() => [])
  if (showLatest) {
    if (scans.value.length) showScan(scans.value[0].id)
    else { scan.value = null; showing = null }
  }
}

async function start() {
  if (!deviceName.value) { message.warning(t('discovery.noDevice')); return }
  const list = subtrees.value.split(',').map((s) => s.trim()).filter(Boolean)
  try {
    const r = await post<{ scanId: number }>('/api/admin/discovery/scans', { deviceName: deviceName.value, walk: walk.value, subtrees: list })
    showScan(r.scanId)
  } catch (e) {
    // 已有進行中的掃描：直接顯示那一次
    const id = e instanceof ApiError && e.status === 409 ? (e.data as { scanId?: number })?.scanId : undefined
    if (id) showScan(id)
    else message.error(errorText(e))
  }
}

async function showScan(id: number) {
  clearTimeout(timer)
  showing = id
  try {
    const s = await get<Scan>(`/api/admin/discovery/scans/${id}`)
    if (showing !== id) return   // 使用者已切換到其他掃描
    scan.value = s
    if (s.status === 'RUNNING') {
      timer = setTimeout(() => showScan(id), 2000)
      return
    }
    page.value = 1
    loadOids()
    loadScans(false)
  } catch (e) {
    message.error(errorText(e))
  }
}

async function loadOids() {
  if (!scan.value) return
  const params = new URLSearchParams({ offset: String((page.value - 1) * PAGE), limit: String(PAGE) })
  if (prefix.value.trim()) params.set('prefix', prefix.value.trim())
  if (keyword.value.trim()) params.set('q', keyword.value.trim())
  try {
    const r = await get<{ total: number; items: ScanOid[] }>(`/api/admin/discovery/scans/${scan.value.id}/oids?${params}`)
    oids.value = r.items
    total.value = r.total
  } catch (e) {
    message.error(errorText(e))
  }
}

const statusPanel = computed(() => {
  const s = scan.value
  if (!s) return { type: 'default' as const, text: deviceName.value ? t('discovery.noScan') : t('discovery.hint') }
  if (s.status === 'RUNNING') return { type: 'info' as const, text: t('discovery.running', { id: s.id, progress: s.progress || '' }) }
  if (s.status === 'FAILED') return { type: 'error' as const, text: t('discovery.failed', { id: s.id, error: s.error || t('discovery.unknownError') }) }
  return { type: 'success' as const, text: t('discovery.completed', { id: s.id, time: fmtTime(s.finished), n: s.oidCount })
    + (s.truncated ? t('discovery.truncatedSuffix') : '') }
})

// ─── 加入範本 ─────────────────────────────────────────────────
const showAdd = ref(false)
const addOid = ref<ScanOid | null>(null)

const oidColumns = computed<DataTableColumns<ScanOid>>(() => [
  { title: 'OID', key: 'oid', width: '36%', render: (o) => h('span', { class: 'mono' }, o.oid) },
  { title: t('discovery.type'), key: 'type', width: 130 },
  { title: t('discovery.value'), key: 'value', render: (o) => h('span', { class: 'mono' }, o.value) },
  {
    title: '', key: 'actions', width: 110,
    render: (o) => h(NButton, { size: 'small', onClick: () => { addOid.value = o; showAdd.value = true } }, () => t('discovery.addToProfile')),
  },
])

const probeColumns = computed<DataTableColumns<NonNullable<Scan['probes']>[number]>>(() => [
  { title: t('discovery.probeItem'), key: 'name', width: 180 },
  { title: 'MIB', key: 'mib', width: 220, render: (p) => h('span', { class: 'mono' }, p.mib) },
  {
    title: t('discovery.supported'), key: 'supported', width: 90,
    render: (p) => h(NTag, { size: 'small', type: p.supported ? 'success' : 'default', bordered: false },
      () => (p.supported ? t('discovery.yes') : t('discovery.no'))),
  },
  {
    title: t('discovery.samples'), key: 'samples',
    render: (p) => p.samples?.length
      ? h('ul', { style: 'margin:0;padding-left:16px;font-size:12px;color:#555' }, p.samples.map((x) => h('li', x)))
      : h(NText, { depth: 3 }, () => p.error || '—'),
  },
])

const scanColumns = computed<DataTableColumns<Scan>>(() => [
  { title: '#', key: 'id', width: 60 },
  { title: t('discovery.started'), key: 'started', render: (s) => fmtTime(s.started) },
  {
    title: t('discovery.status'), key: 'status',
    render: (s) => t(`discovery.statuses.${s.status}`) + (s.truncated ? t('discovery.truncated') : ''),
  },
  { title: t('discovery.oidCount'), key: 'oidCount' },
  {
    title: t('common.actions'), key: 'actions', width: 100,
    render: (s) => h(NButton, { size: 'small', type: 'primary', onClick: () => showScan(s.id) }, () => t('common.view')),
  },
])
</script>

<template>
  <div>
    <NCard>
      <NSpace align="center" :wrap="true">
        <NSelect v-model:value="deviceName" :options="deviceOptions" style="width: 320px" filterable
                 :placeholder="$t('discovery.noDevice')" data-test="disc-device" @update:value="loadScans(true)" />
        <NCheckbox v-model:checked="walk" data-test="disc-walk">{{ $t('discovery.walk') }}</NCheckbox>
        <NInput v-model:value="subtrees" style="width: 340px" :placeholder="$t('discovery.subtreesPlaceholder')" />
        <NButton type="primary" :disabled="running" data-test="disc-start" @click="start">{{ $t('discovery.start') }}</NButton>
      </NSpace>
      <NAlert :type="statusPanel.type" :show-icon="false" style="margin-top: 12px" data-test="disc-status">{{ statusPanel.text }}</NAlert>
    </NCard>

    <template v-if="scan && scan.status !== 'RUNNING'">
      <NCard :title="$t('discovery.sysInfo')" class="page-section">
        <NDescriptions :column="1" label-placement="left" bordered size="small">
          <NDescriptionsItem :label="$t('discovery.vendor')">{{ scan.vendor ?? '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="sysName"><span class="mono">{{ scan.sysName ?? '—' }}</span></NDescriptionsItem>
          <NDescriptionsItem label="sysObjectID"><span class="mono">{{ scan.sysObjectId ?? '—' }}</span></NDescriptionsItem>
          <NDescriptionsItem label="sysUpTime"><span class="mono">{{ scan.sysUpTime ?? '—' }}</span></NDescriptionsItem>
          <NDescriptionsItem label="sysDescr"><span class="mono">{{ scan.sysDescr ?? '—' }}</span></NDescriptionsItem>
        </NDescriptions>
      </NCard>
      <NCard :title="$t('discovery.probes')" class="page-section">
        <NDataTable :columns="probeColumns" :data="scan.probes ?? []" :bordered="false" :row-key="(p: { key: string }) => p.key" />
      </NCard>
      <NCard :title="$t('discovery.oidList', { n: total })" class="page-section">
        <NSpace style="margin-bottom: 12px">
          <NInput v-model:value="prefix" style="width: 260px" :placeholder="$t('discovery.prefixPlaceholder')" @keyup.enter="page = 1; loadOids()" />
          <NInput v-model:value="keyword" style="width: 200px" :placeholder="$t('discovery.keywordPlaceholder')" @keyup.enter="page = 1; loadOids()" />
          <NButton type="primary" @click="page = 1; loadOids()">{{ $t('discovery.filter') }}</NButton>
        </NSpace>
        <NDataTable :columns="oidColumns" :data="oids" :bordered="false" :row-key="(o: ScanOid) => o.oid">
          <template #empty><NText depth="3">{{ $t('discovery.noOids') }}</NText></template>
        </NDataTable>
        <NSpace justify="end" style="margin-top: 12px">
          <NPagination v-model:page="page" :item-count="total" :page-size="PAGE" @update:page="loadOids" />
        </NSpace>
      </NCard>
    </template>

    <NCard :title="$t('discovery.history')" class="page-section">
      <NDataTable :columns="scanColumns" :data="scans" :bordered="false" :row-key="(s: Scan) => s.id" />
    </NCard>
    <AddMetricDialog v-model:show="showAdd" :oid="addOid" />
  </div>
</template>
