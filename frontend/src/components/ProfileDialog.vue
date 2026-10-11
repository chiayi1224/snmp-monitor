<script setup lang="ts">
import {
  NModal, NCard, NForm, NFormItem, NInput, NSelect, NCheckbox, NButton, NSpace, NAlert, NTable, NText, NTag, useMessage,
} from 'naive-ui'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { post, put, errorText } from '../api/client'
import type { Device, PreviewResult, Profile, ProfileMetric } from '../api/types'
import { KIND_PARAMS, KINDS } from '../utils/profile'
import { fmtNum } from '../utils/format'

const props = defineProps<{ show: boolean; profile: Profile | null }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>()
const { t } = useI18n()
const message = useMessage()

const form = reactive({ name: '', matchPrefix: '', description: '', metrics: [] as ProfileMetric[] })
const readonly = computed(() => !!props.profile?.builtin)
const devices = ref<Device[]>([])
const previewDevice = ref<string | null>(null)
const previewResults = ref<PreviewResult[] | null>(null)
const previewError = ref('')
const previewing = ref(false)
const saving = ref(false)

watch(() => props.show, async (v) => {
  if (!v) return
  const p = props.profile
  form.name = p ? (p.builtin ? p.displayName : p.name) : ''
  form.matchPrefix = p?.matchPrefix ?? ''
  form.description = p ? (p.builtin ? p.displayDescription : p.description) : ''
  form.metrics = p ? p.metrics.map((m) => ({ ...m, params: { ...m.params } }))
    : [{ metricKey: 'cpu', kind: 'VALUE', params: {}, unit: '%', enabled: true }]
  previewResults.value = null
  previewError.value = ''
  devices.value = await post<Device[]>('/api/v1/devices').catch(() => [])
  previewDevice.value = devices.value[0]?.name ?? null
})

const title = computed(() => !props.profile ? t('profiles.addTitle') : readonly.value ? t('profiles.viewTitle') : t('profiles.editTitle'))
const kindOptions = computed(() => KINDS.map((k) => ({ label: k, value: k })))
const deviceOptions = computed(() => devices.value.map((d) => ({ label: t('common.withNote', { main: d.name, note: d.host }), value: d.name })))

function addMetric() {
  form.metrics.push({ metricKey: '', kind: 'VALUE', params: {}, unit: '', enabled: true })
}

function body() {
  return {
    name: form.name.trim(), matchPrefix: form.matchPrefix.trim(), description: form.description.trim(),
    metrics: form.metrics.map((m) => ({
      metricKey: m.metricKey.trim(), kind: m.kind, unit: (m.unit ?? '').trim(), enabled: m.enabled,
      params: Object.fromEntries(Object.entries(m.params).map(([k, v]) => [k, String(v ?? '').trim()]).filter(([, v]) => v)),
    })),
  }
}

async function save() {
  const b = body()
  if (!b.name) { message.warning(t('profiles.nameRequired')); return }
  saving.value = true
  try {
    const r = props.profile
      ? await put<{ message: string }>(`/api/admin/profiles/${props.profile.id}`, b)
      : await post<{ message: string }>('/api/admin/profiles', b)
    message.success(r.message)
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    saving.value = false
  }
}

async function preview() {
  if (!previewDevice.value) { message.warning(t('profiles.noDevice')); return }
  previewing.value = true
  previewError.value = ''
  previewResults.value = null
  try {
    const r = await post<{ results: PreviewResult[] }>('/api/admin/profiles/preview', { deviceName: previewDevice.value, profile: body() })
    previewResults.value = r.results
  } catch (e) {
    previewError.value = errorText(e)
  } finally {
    previewing.value = false
  }
}
</script>

<template>
  <NModal :show="show" @update:show="(v: boolean) => emit('update:show', v)">
    <NCard :title="title" style="width: 980px; max-width: 96vw" :bordered="false" role="dialog">
      <NAlert v-if="readonly" type="info" :show-icon="false" style="margin-bottom: 12px">{{ $t('profiles.readonlyHint') }}</NAlert>
      <NForm label-placement="top" :disabled="readonly">
        <NSpace :wrap="false" style="width: 100%" :item-style="{ flex: 1 }">
          <NFormItem :label="$t('profiles.name') + ' *'">
            <NInput v-model:value="form.name" maxlength="64" data-test="pf-name" />
          </NFormItem>
          <NFormItem :label="$t('profiles.prefix')">
            <NInput v-model:value="form.matchPrefix" class="mono" :placeholder="$t('profiles.prefixPlaceholder')" />
          </NFormItem>
        </NSpace>
        <NFormItem :label="$t('profiles.description')">
          <NInput v-model:value="form.description" />
        </NFormItem>
      </NForm>

      <NTable size="small" :single-line="false">
        <thead>
          <tr>
            <th style="width: 150px">{{ $t('profiles.metricKey') }}</th>
            <th style="width: 220px">{{ $t('profiles.kind') }}</th>
            <th>{{ $t('profiles.paramsHeader') }}</th>
            <th style="width: 80px">{{ $t('profiles.unit') }}</th>
            <th style="width: 50px">{{ $t('profiles.enabled') }}</th>
            <th style="width: 50px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(m, i) in form.metrics" :key="i">
            <td><NInput v-model:value="m.metricKey" size="small" :disabled="readonly" /></td>
            <td>
              <NSelect v-model:value="m.kind" :options="kindOptions" size="small" :disabled="readonly" />
              <NText depth="3" style="font-size: 11px">{{ $t(`profiles.kinds.${m.kind}`) }}</NText>
            </td>
            <td>
              <div v-for="[key, optional] in KIND_PARAMS[m.kind] ?? []" :key="key"
                   style="display: flex; gap: 6px; align-items: center; margin: 2px 0">
                <span style="width: 130px; flex-shrink: 0; font-size: 12px; color: #666">{{ $t(`profiles.params.${key}`) }}{{ optional ? '' : ' *' }}</span>
                <NInput v-model:value="m.params[key]" size="small" class="mono" :disabled="readonly"
                        :placeholder="key === 'first' ? $t('profiles.firstPlaceholder') : ''" />
              </div>
              <NText v-if="!(KIND_PARAMS[m.kind] ?? []).length" depth="3" style="font-size: 12px">{{ $t('profiles.noParams') }}</NText>
            </td>
            <td><NInput v-model:value="m.unit" size="small" :disabled="readonly" /></td>
            <td style="text-align: center"><NCheckbox v-model:checked="m.enabled" :disabled="readonly" /></td>
            <td><NButton v-if="!readonly" size="tiny" type="error" ghost @click="form.metrics.splice(i, 1)">✕</NButton></td>
          </tr>
        </tbody>
      </NTable>
      <NSpace justify="space-between" align="center" style="margin-top: 8px">
        <NText depth="3" style="font-size: 12px">{{ $t('profiles.metricHint') }}</NText>
        <NButton v-if="!readonly" size="small" @click="addMetric">{{ $t('profiles.addMetric') }}</NButton>
      </NSpace>

      <NCard size="small" embedded style="margin-top: 12px">
        <NSpace align="center">
          <span>{{ $t('profiles.preview') }}</span>
          <NSelect v-model:value="previewDevice" :options="deviceOptions" style="width: 260px" :placeholder="$t('profiles.noDevice')" />
          <NButton type="primary" size="small" :loading="previewing" data-test="pf-preview" @click="preview">{{ $t('profiles.runPreview') }}</NButton>
          <NText depth="3" style="font-size: 12px">{{ $t('profiles.previewHint') }}</NText>
        </NSpace>
        <NAlert v-if="previewError" type="error" :show-icon="false" style="margin-top: 8px">{{ previewError }}</NAlert>
        <NTable v-if="previewResults" size="small" style="margin-top: 8px" data-test="pf-preview-result">
          <tbody>
            <tr v-for="r in previewResults" :key="r.metricKey">
              <td style="width: 160px">
                <strong>{{ r.metricKey }}</strong>
                <NTag v-if="!r.enabled" size="tiny" :bordered="false" style="margin-left: 4px">{{ $t('common.disabled') }}</NTag>
              </td>
              <td>
                <template v-if="r.ok">
                  <div v-for="(v, j) in r.values" :key="j">
                    <span v-if="v.instance != null" class="mono">{{ $t('common.labelPrefix', { label: v.instance }) }}</span><strong>{{ fmtNum(v.value) }}</strong> {{ r.unit }}
                  </div>
                  <NText v-if="!r.values?.length" depth="3">{{ $t('profiles.noRows') }}</NText>
                </template>
                <NText v-else type="error">{{ r.error }}</NText>
              </td>
            </tr>
            <tr v-if="!previewResults.length"><td><NText depth="3">{{ $t('profiles.noMetrics') }}</NText></td></tr>
          </tbody>
        </NTable>
      </NCard>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="emit('update:show', false)">{{ readonly ? $t('common.close') : $t('common.cancel') }}</NButton>
          <NButton v-if="!readonly" type="primary" :loading="saving" data-test="pf-save" @click="save">{{ $t('common.save') }}</NButton>
        </NSpace>
      </template>
    </NCard>
  </NModal>
</template>
