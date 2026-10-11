<script setup lang="ts">
import {
  NCard, NDataTable, NButton, NSpace, NTag, NModal, NForm, NFormItem, NInput, NSelect, NSwitch, NText, NAlert,
  useMessage, useDialog,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import SeverityTag from '../components/SeverityTag.vue'
import DeviceDialog from '../components/DeviceDialog.vue'
import { get, post, put, del, errorText } from '../api/client'
import type { TrapMapping, UnknownSource, UnknownTrap } from '../api/types'
import { fmtTime } from '../utils/format'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const mappings = ref<TrapMapping[]>([])
const unknownOids = ref<UnknownTrap[]>([])
const unknownSources = ref<UnknownSource[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [m, u] = await Promise.all([
      get<TrapMapping[]>('/api/admin/trap-mappings'),
      get<{ unknownOids: UnknownTrap[]; unknownSources: UnknownSource[] }>('/api/admin/unknown-traps')
        .catch(() => ({ unknownOids: [], unknownSources: [] })),
    ])
    mappings.value = m
    unknownOids.value = u.unknownOids ?? []
    unknownSources.value = u.unknownSources ?? []
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ─── 對應對話框 ───────────────────────────────────────────────
const showMapping = ref(false)
const form = reactive({ id: null as number | null, fromUnknownId: null as number | null, metricName: '',
  triggeredOid: '', resolvedOid: '', description: '', severity: 'CRITICAL', enabled: true })
const mappingTitle = computed(() => form.id ? t('traps.editTitle') : form.fromUnknownId ? t('traps.fromUnknownTitle') : t('traps.addTitle'))
const severityOptions = computed(() => [
  { label: t('severity.CRITICAL'), value: 'CRITICAL' },
  { label: t('severity.WARNING'), value: 'WARNING' },
])

function openMapping(m: TrapMapping | null, fromUnknown: UnknownTrap | null = null) {
  Object.assign(form, {
    id: m?.id ?? null, fromUnknownId: fromUnknown?.id ?? null, metricName: m?.metricName ?? '',
    triggeredOid: m?.triggeredOid ?? fromUnknown?.trapOid ?? '', resolvedOid: m?.resolvedOid ?? '',
    description: m?.description ?? '', severity: m?.severity ?? 'CRITICAL', enabled: m?.enabled ?? true,
  })
  showMapping.value = true
}

async function saveMapping() {
  if (!form.metricName.trim() || !form.triggeredOid.trim()) { message.warning(t('traps.required')); return }
  const body: Record<string, unknown> = {
    metricName: form.metricName.trim(), triggeredOid: form.triggeredOid.trim(), resolvedOid: form.resolvedOid.trim(),
    description: form.description.trim(), severity: form.severity, enabled: form.enabled,
  }
  if (form.fromUnknownId) body.fromUnknownId = form.fromUnknownId
  try {
    const r = form.id
      ? await put<{ message: string }>(`/api/admin/trap-mappings/${form.id}`, body)
      : await post<{ message: string }>('/api/admin/trap-mappings', body)
    message.success(r.message)
    showMapping.value = false
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}

function confirmDelete(content: string, action: () => Promise<{ message: string }>) {
  dialog.warning({
    title: t('common.confirmDelete'), content, positiveText: t('common.delete'), negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        message.success((await action()).message)
        load()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

// ─── 未登錄來源新增為設備 ─────────────────────────────────────
const showDevice = ref(false)
const presetHost = ref('')
function addDevice(ip: string) {
  presetHost.value = ip
  showDevice.value = true
}

const mappingColumns = computed<DataTableColumns<TrapMapping>>(() => [
  { title: t('traps.metric'), key: 'metricName', render: (m) => h('strong', m.metricName) },
  { title: t('traps.severity'), key: 'severity', width: 100, render: (m) => h(SeverityTag, { severity: m.severity }) },
  { title: t('traps.triggeredOid'), key: 'triggeredOid', render: (m) => h('span', { class: 'mono' }, m.triggeredOid) },
  { title: t('traps.resolvedOid'), key: 'resolvedOid', render: (m) => h('span', { class: 'mono' }, m.resolvedOid || '—') },
  { title: t('traps.description'), key: 'description' },
  {
    title: t('traps.state'), key: 'enabled', width: 90,
    render: (m) => h(NTag, { size: 'small', type: m.enabled ? 'success' : 'default', bordered: false },
      () => (m.enabled ? t('common.enabled') : t('common.disabled'))),
  },
  {
    title: t('common.actions'), key: 'actions', width: 150,
    render: (m) => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => openMapping(m) }, () => t('common.edit')),
      h(NButton, { size: 'small', type: 'error', ghost: true,
        onClick: () => confirmDelete(t('traps.deleteConfirm', { name: m.metricName }), () => del(`/api/admin/trap-mappings/${m.id}`)) },
        () => t('common.delete')),
    ]),
  },
])

const unknownColumns = computed<DataTableColumns<UnknownTrap>>(() => [
  { title: 'Trap OID', key: 'trapOid', render: (u) => h('span', { class: 'mono' }, u.trapOid) },
  {
    title: t('traps.lastSource'), key: 'deviceName',
    render: (u) => h('div', [u.deviceName, u.deviceName !== u.sourceIp ? h('div', { class: 'muted' }, u.sourceIp) : null]),
  },
  { title: t('traps.count'), key: 'count', width: 80 },
  { title: t('traps.lastSeen'), key: 'lastSeen', width: 170, render: (u) => fmtTime(u.lastSeen) },
  { title: 'VarBind', key: 'varbinds', ellipsis: { tooltip: true }, render: (u) => h('span', { class: 'mono' }, u.varbinds || '—') },
  {
    title: t('common.actions'), key: 'actions', width: 240,
    render: (u) => h(NSpace, { size: 6, wrap: false }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => openMapping(null, u) }, () => t('traps.createMapping')),
      h(NButton, { size: 'small', type: 'error', ghost: true,
        onClick: () => confirmDelete(t('traps.deleteUnknownConfirm'), () => del(`/api/admin/unknown-traps/oids/${u.id}`)) },
        () => t('common.delete')),
    ]),
  },
])

const sourceColumns = computed<DataTableColumns<UnknownSource>>(() => [
  { title: t('traps.sourceIp'), key: 'sourceIp', render: (u) => h('span', { class: 'mono' }, u.sourceIp) },
  { title: t('traps.lastTrapOid'), key: 'lastTrapOid', render: (u) => h('span', { class: 'mono' }, u.lastTrapOid) },
  { title: t('traps.count'), key: 'count', width: 80 },
  { title: t('traps.lastSeen'), key: 'lastSeen', width: 170, render: (u) => fmtTime(u.lastSeen) },
  {
    title: t('common.actions'), key: 'actions', width: 240,
    render: (u) => h(NSpace, { size: 6, wrap: false }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => addDevice(u.sourceIp) }, () => t('traps.addAsDevice')),
      h(NButton, { size: 'small', type: 'error', ghost: true,
        onClick: () => confirmDelete(t('traps.deleteUnknownConfirm'), () => del(`/api/admin/unknown-traps/sources/${u.id}`)) },
        () => t('common.delete')),
    ]),
  },
])
</script>

<template>
  <div>
    <NCard :title="$t('traps.mappingsTitle')">
      <template #header-extra>
        <NButton type="primary" data-test="trap-add" @click="openMapping(null)">{{ $t('traps.add') }}</NButton>
      </template>
      <NDataTable :columns="mappingColumns" :data="mappings" :loading="loading" :row-key="(m: TrapMapping) => m.id" :bordered="false" />
    </NCard>
    <NCard :title="$t('traps.unknownTitle')" class="page-section">
      <NDataTable :columns="unknownColumns" :data="unknownOids" :row-key="(u: UnknownTrap) => u.id" :bordered="false" />
    </NCard>
    <NCard :title="$t('traps.sourcesTitle')" class="page-section">
      <NDataTable :columns="sourceColumns" :data="unknownSources" :row-key="(u: UnknownSource) => u.id" :bordered="false" />
    </NCard>

    <NModal v-model:show="showMapping" preset="card" :title="mappingTitle" style="width: 520px">
      <NForm label-placement="top">
        <NFormItem :label="$t('traps.metric') + ' *'">
          <NInput v-model:value="form.metricName" :placeholder="$t('traps.metricPlaceholder')" data-test="trap-metric" />
        </NFormItem>
        <NFormItem :label="$t('traps.triggeredOidLabel')">
          <NInput v-model:value="form.triggeredOid" class="mono" data-test="trap-triggered" />
        </NFormItem>
        <NFormItem :label="$t('traps.resolvedOidLabel')">
          <NInput v-model:value="form.resolvedOid" class="mono" />
        </NFormItem>
        <NFormItem :label="$t('traps.severity')">
          <NSelect v-model:value="form.severity" :options="severityOptions" data-test="trap-severity" />
        </NFormItem>
        <NFormItem :label="$t('traps.description')">
          <NInput v-model:value="form.description" />
        </NFormItem>
        <NAlert type="info" :show-icon="false" style="margin-bottom: 12px">{{ $t('traps.metricNameHint') }}</NAlert>
        <NSpace v-if="form.id" align="center">
          <NSwitch v-model:value="form.enabled" /><NText>{{ $t('common.enabled') }}</NText>
        </NSpace>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showMapping = false">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" data-test="trap-save" @click="saveMapping">{{ $t('common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
    <DeviceDialog v-model:show="showDevice" :device="null" :preset-host="presetHost" @saved="load" />
  </div>
</template>
