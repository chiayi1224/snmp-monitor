<script setup lang="ts">
import {
  NCard, NDataTable, NSelect, NButton, NSpace, NInputNumber, NAutoComplete, NAlert, NModal, NCheckboxGroup, NCheckbox,
  NText, useMessage, useDialog,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import SeverityTag from '../components/SeverityTag.vue'
import { get, post, put, del, errorText } from '../api/client'
import type { Device, Threshold } from '../api/types'
import { fmtTime, isPercentMetric } from '../utils/format'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const devices = ref<Device[]>([])
const counts = ref<Record<string, number>>({})
const deviceName = ref<string | null>(null)
const rows = ref<Threshold[]>([])
const availableMetrics = ref<string[]>([])
const loading = ref(false)

const device = computed(() => devices.value.find((d) => d.name === deviceName.value))
const deviceOptions = computed(() => devices.value.map((d) => ({
  label: t('thresholds.deviceOption', { name: d.name, n: counts.value[d.name] ?? 0 }), value: d.name,
})))
const directionOptions = computed(() => [
  { label: t('thresholds.high'), value: 'HIGH' },
  { label: t('thresholds.low'), value: 'LOW' },
])

async function loadDevices() {
  const [list, c] = await Promise.all([
    post<Device[]>('/api/v1/devices').catch(() => [] as Device[]),
    get<{ counts: Record<string, number> }>('/api/admin/device-thresholds').catch(() => ({ counts: {} })),
  ])
  devices.value = list
  counts.value = c.counts
  if (!list.some((d) => d.name === deviceName.value)) deviceName.value = list[0]?.name ?? null
  await loadThresholds()
}

async function loadThresholds() {
  if (!deviceName.value) { rows.value = []; return }
  loading.value = true
  try {
    const r = await get<{ thresholds: Threshold[]; availableMetrics: string[] }>(
      `/api/admin/device-thresholds?device=${encodeURIComponent(deviceName.value)}`)
    rows.value = r.thresholds
    availableMetrics.value = r.availableMetrics
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(loadDevices)

function metricLabel(name: string) {
  if (name === 'disk') return t('thresholds.diskDefault')
  if (name.startsWith('disk:')) return t('thresholds.diskOverride')
  return ''
}

async function save(row: Threshold) {
  try {
    const r = await put<{ message: string }>('/api/admin/device-thresholds', {
      device: deviceName.value, metricName: row.metricName, direction: row.direction,
      warning: row.warning, critical: row.critical, rearmMargin: row.rearmMargin ?? 0,
      triggerCount: row.triggerCount, rearmCount: row.rearmCount,
    })
    message.success(r.message)
    await loadDevices()
  } catch (e) {
    message.error(errorText(e))
  }
}

function remove(row: Threshold) {
  dialog.warning({
    title: t('common.confirmDelete'),
    content: t('thresholds.deleteConfirm', { metric: row.metricName }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        const r = await del<{ message: string }>(`/api/admin/device-thresholds/${row.id}`)
        message.success(r.message)
        await loadDevices()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

const columns = computed<DataTableColumns<Threshold>>(() => [
  {
    title: t('thresholds.metric'), key: 'metricName', width: 220,
    render: (r) => h('div', [h('strong', r.metricName), metricLabel(r.metricName)
      ? h(NText, { depth: 3, style: 'font-size:12px;margin-left:6px' }, () => metricLabel(r.metricName)) : null]),
  },
  {
    title: t('thresholds.direction'), key: 'direction', width: 200,
    render: (r) => h(NSelect, {
      value: r.direction, options: directionOptions.value, size: 'small', disabled: isPercentMetric(r.metricName),
      'onUpdate:value': (v: 'HIGH' | 'LOW') => { r.direction = v },
    }),
  },
  ...(['warning', 'critical'] as const).map((field) => ({
    title: t(`severity.${field.toUpperCase()}`), key: field, width: 130,
    render: (r: Threshold) => h(NInputNumber, {
      value: r[field], size: 'small', clearable: true, placeholder: '—', showButton: false,
      status: field === 'critical' ? 'error' : 'warning',
      'onUpdate:value': (v: number | null) => { r[field] = v },
    }, { suffix: isPercentMetric(r.metricName) ? () => '%' : undefined }),
  })),
  {
    title: t('thresholds.margin'), key: 'rearmMargin', width: 100,
    render: (r) => h(NInputNumber, { value: r.rearmMargin, size: 'small', min: 0, showButton: false,
      'onUpdate:value': (v: number | null) => { r.rearmMargin = v ?? 0 } }),
  },
  ...(['triggerCount', 'rearmCount'] as const).map((field) => ({
    title: t(`thresholds.${field}`), key: field, width: 90,
    render: (r: Threshold) => h(NInputNumber, { value: r[field], size: 'small', min: 1, showButton: false,
      'onUpdate:value': (v: number | null) => { r[field] = v ?? 1 } }),
  })),
  { title: t('thresholds.updated'), key: 'updated', width: 170, render: (r) => h(NText, { depth: 3, style: 'font-size:12px' }, () => fmtTime(r.updated)) },
  {
    title: t('common.actions'), key: 'actions', width: 150, fixed: 'right',
    render: (r) => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => save(r) }, () => t('common.save')),
      h(NButton, { size: 'small', type: 'error', ghost: true, onClick: () => remove(r) }, () => t('common.delete')),
    ]),
  },
])

// ─── 新增 ─────────────────────────────────────────────────────
const blank = () => ({ metricName: '', direction: 'HIGH' as 'HIGH' | 'LOW', warning: null as number | null,
  critical: null as number | null, rearmMargin: 5, triggerCount: 3, rearmCount: 2 })
const form = ref(blank())
const newIsPercent = computed(() => isPercentMetric(form.value.metricName.trim()))
const metricSuggestions = computed(() => {
  const set = new Set(rows.value.map((r) => r.metricName))
  return availableMetrics.value.filter((k) => !set.has(k) && k.includes(form.value.metricName.trim()))
    .map((k) => ({ label: k, value: k }))
})

async function create() {
  const metricName = form.value.metricName.trim()
  if (!metricName) { message.warning(t('thresholds.metricRequired')); return }
  if (form.value.warning == null && form.value.critical == null) { message.warning(t('thresholds.levelRequired')); return }
  try {
    const r = await put<{ message: string }>('/api/admin/device-thresholds', {
      device: deviceName.value, ...form.value, metricName,
      direction: newIsPercent.value ? 'HIGH' : form.value.direction,
    })
    message.success(r.message)
    form.value = blank()
    await loadDevices()
  } catch (e) {
    message.error(errorText(e))
  }
}

// ─── 套用到其他設備 ───────────────────────────────────────────
const showCopy = ref(false)
const copyTargets = ref<string[]>([])
const otherDevices = computed(() => devices.value.filter((d) => d.name !== deviceName.value))

function openCopy() {
  copyTargets.value = []
  showCopy.value = true
}

async function applyCopy() {
  if (!copyTargets.value.length) { message.warning(t('thresholds.copyTargetRequired')); return }
  try {
    const r = await post<{ message: string }>('/api/admin/device-thresholds/copy', { from: deviceName.value, to: copyTargets.value })
    message.success(r.message)
    showCopy.value = false
    await loadDevices()
  } catch (e) {
    message.error(errorText(e))
  }
}
</script>

<template>
  <div>
    <NAlert type="info" :show-icon="false" style="margin-bottom: 16px">{{ $t('thresholds.intro') }}</NAlert>
    <NCard>
      <NSpace align="center" style="margin-bottom: 12px">
        <span>{{ $t('thresholds.device') }}</span>
        <NSelect v-model:value="deviceName" :options="deviceOptions" style="width: 280px" :placeholder="$t('thresholds.noDevices')"
                 filterable data-test="th-device" @update:value="loadThresholds" />
        <NButton :disabled="!deviceName" data-test="th-copy" @click="openCopy">{{ $t('thresholds.copy') }}</NButton>
        <NText v-if="device" depth="3">
          {{ $t('thresholds.unreachable') }}
          <SeverityTag v-if="device.unreachableSeverity" :severity="device.unreachableSeverity" />
          <template v-if="device.unreachableSeverity">{{ $t('thresholds.unreachableCount', { n: device.unreachableCount }) }}</template>
          <template v-else>{{ $t('thresholds.unreachableOff') }}</template>
          {{ $t('common.parenthesized', { text: $t('thresholds.unreachableHint') }) }}
        </NText>
      </NSpace>
      <NAlert v-if="deviceName && !loading && !rows.length" type="warning" :show-icon="false" style="margin-bottom: 12px">
        {{ $t('thresholds.empty') }}
      </NAlert>
      <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(r: Threshold) => r.id" :bordered="false"
                  :scroll-x="1350" />
    </NCard>

    <NCard :title="$t('thresholds.addTitle')" style="margin-top: 16px">
      <NSpace align="end" :wrap="true">
        <div>
          <div class="muted">{{ $t('thresholds.metric') }} *</div>
          <NAutoComplete v-model:value="form.metricName" :options="metricSuggestions" style="width: 220px"
                         :placeholder="$t('thresholds.metricPlaceholder')" :get-show="() => true" data-test="th-new-metric" />
        </div>
        <div>
          <div class="muted">{{ $t('thresholds.direction') }}</div>
          <NSelect v-model:value="form.direction" :options="directionOptions" style="width: 200px" :disabled="newIsPercent" />
        </div>
        <div>
          <div class="muted">{{ $t('severity.WARNING') }}</div>
          <NInputNumber v-model:value="form.warning" style="width: 110px" clearable :show-button="false" status="warning" placeholder="—" data-test="th-new-warning" />
        </div>
        <div>
          <div class="muted">{{ $t('severity.CRITICAL') }}</div>
          <NInputNumber v-model:value="form.critical" style="width: 110px" clearable :show-button="false" status="error" placeholder="—" data-test="th-new-critical" />
        </div>
        <div>
          <div class="muted">{{ $t('thresholds.margin') }}</div>
          <NInputNumber v-model:value="form.rearmMargin" style="width: 90px" :min="0" :show-button="false" />
        </div>
        <div>
          <div class="muted">{{ $t('thresholds.triggerCount') }}</div>
          <NInputNumber v-model:value="form.triggerCount" style="width: 80px" :min="1" :show-button="false" />
        </div>
        <div>
          <div class="muted">{{ $t('thresholds.rearmCount') }}</div>
          <NInputNumber v-model:value="form.rearmCount" style="width: 80px" :min="1" :show-button="false" />
        </div>
        <NButton type="primary" :disabled="!deviceName" data-test="th-new-submit" @click="create">{{ $t('common.add') }}</NButton>
      </NSpace>
      <div class="muted" style="margin-top: 8px">
        {{ (newIsPercent || form.direction === 'HIGH') ? $t('thresholds.hintHigh') : $t('thresholds.hintLow') }}
      </div>
    </NCard>

    <NModal v-model:show="showCopy" preset="card" :title="$t('thresholds.copyTitle')" style="width: 440px">
      <p style="margin-top: 0">{{ $t('thresholds.copyFrom', { name: deviceName, n: rows.length }) }}</p>
      <div style="max-height: 260px; overflow: auto; border: 1px solid #eee; border-radius: 4px; padding: 8px">
        <NCheckboxGroup v-model:value="copyTargets">
          <NSpace vertical>
            <NCheckbox v-for="d in otherDevices" :key="d.name" :value="d.name" :label="d.name" />
          </NSpace>
        </NCheckboxGroup>
        <NText v-if="!otherDevices.length" depth="3">{{ $t('thresholds.noOtherDevices') }}</NText>
      </div>
      <p class="muted">{{ $t('thresholds.copyHint') }}</p>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showCopy = false">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" data-test="th-copy-apply" @click="applyCopy">{{ $t('thresholds.apply') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>
