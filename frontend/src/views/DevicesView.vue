<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NTag, NText, useMessage, useDialog } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DeviceDialog from '../components/DeviceDialog.vue'
import { get, post, errorText } from '../api/client'
import type { Device } from '../api/types'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const rows = ref<Device[]>([])
const counts = ref<Record<string, number>>({})
const loading = ref(false)
const showDialog = ref(false)
const editing = ref<Device | null>(null)

async function load() {
  loading.value = true
  try {
    const [list, c] = await Promise.all([
      post<Device[]>('/api/v1/devices'),
      get<{ counts: Record<string, number> }>('/api/admin/device-thresholds').catch(() => ({ counts: {} })),
    ])
    rows.value = list
    counts.value = c.counts
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openDialog(d: Device | null) {
  editing.value = d
  showDialog.value = true
}

function remove(d: Device) {
  dialog.warning({
    title: t('common.confirmDelete'),
    content: t('devices.deleteConfirm', { name: d.name }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        const r = await post<{ message: string }>(`/api/admin/devices/${encodeURIComponent(d.name)}`, { action: 'delete' })
        message.success(r.message)
        load()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

const columns = computed<DataTableColumns<Device>>(() => [
  { title: t('devices.name'), key: 'name', render: (d) => h('strong', d.name) },
  { title: t('devices.host'), key: 'host' },
  { title: t('devices.port'), key: 'port', width: 80 },
  {
    title: 'SNMP', key: 'snmpVersion', width: 200,
    render: (d) => h('span', [`v${d.snmpVersion || '2c'} `, d.snmpVersion === '3'
      ? h('code', d.v3User) : h(NText, { depth: 3 }, () => t('devices.communitySet'))]),
  },
  {
    title: t('devices.profile'), key: 'profile',
    render: (d) => h('div', [
      h('span', d.appliedProfileDisplayName || '—'),
      d.profileId == null ? h(NTag, { size: 'small', bordered: false, style: 'margin-left:6px' }, () => t('devices.auto')) : null,
      h(NSpace, { size: 4, style: 'margin-top:4px' }, () => [
        counts.value[d.name]
          ? h(NTag, { size: 'small', type: 'info', bordered: false }, () => t('devices.thresholdCount', { n: counts.value[d.name] }))
          : h(NTag, { size: 'small', type: 'warning', bordered: false, title: t('devices.noThresholdHint') }, () => t('devices.noThreshold')),
        d.unreachableSeverity
          ? h(NTag, { size: 'small', bordered: false }, () => t('devices.unreachableTag', { severity: t(`severity.${d.unreachableSeverity}`) }))
          : null,
      ]),
    ]),
  },
  {
    title: t('devices.state'), key: 'enabled', width: 90,
    render: (d) => h(NTag, { size: 'small', type: d.enabled ? 'success' : 'default', bordered: false },
      () => (d.enabled ? t('common.enabled') : t('common.disabled'))),
  },
  {
    title: t('common.actions'), key: 'actions', width: 150,
    render: (d) => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => openDialog(d) }, () => t('common.edit')),
      h(NButton, { size: 'small', type: 'error', ghost: true, onClick: () => remove(d) }, () => t('common.delete')),
    ]),
  },
])
</script>

<template>
  <NCard :title="$t('devices.title')">
    <template #header-extra>
      <NButton type="primary" data-test="dev-add" @click="openDialog(null)">{{ $t('devices.add') }}</NButton>
    </template>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(d: Device) => d.name" :bordered="false" />
    <DeviceDialog v-model:show="showDialog" :device="editing" @saved="load" />
  </NCard>
</template>
