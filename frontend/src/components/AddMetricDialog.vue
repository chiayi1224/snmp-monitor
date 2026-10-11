<script setup lang="ts">
import { NModal, NForm, NFormItem, NInput, NSelect, NButton, NSpace, NText, NAlert, useMessage } from 'naive-ui'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { get, post, put, errorText } from '../api/client'
import type { Profile, ScanOid } from '../api/types'

const props = defineProps<{ show: boolean; oid: ScanOid | null }>()
const emit = defineEmits<{ 'update:show': [boolean] }>()
const { t } = useI18n()
const message = useMessage()

const profiles = ref<Profile[]>([])
const form = reactive({ profile: 'new', newName: '', oid: '', metricKey: '', kind: 'VALUE', scale: '', unit: '' })
const saving = ref(false)

watch(() => props.show, async (v) => {
  if (!v || !props.oid) return
  profiles.value = (await get<Profile[]>('/api/admin/profiles').catch(() => [])).filter((p) => !p.builtin)
  Object.assign(form, { profile: profiles.value.length ? String(profiles.value[0].id) : 'new', newName: '',
    oid: props.oid.oid, metricKey: '', kind: 'VALUE', scale: '', unit: '' })
})

// 表格欄位平均：預設帶入去掉最後一段 index 的欄位 OID
watch(() => form.kind, (k) => {
  if (!props.oid) return
  form.oid = k === 'TABLE_AVERAGE' ? props.oid.oid.replace(/\.\d+$/, '') : props.oid.oid
})

const profileOptions = computed(() => [
  ...profiles.value.map((p) => ({ label: p.name, value: String(p.id) })),
  { label: t('addMetric.newProfile'), value: 'new' },
])
const kindOptions = computed(() => ['VALUE', 'HUNDRED_MINUS', 'TABLE_AVERAGE'].map((k) => ({ label: t('common.withNote', { main: k, note: t(`profiles.kinds.${k}`) }), value: k })))

async function save() {
  const metric = {
    metricKey: form.metricKey.trim(), kind: form.kind, unit: form.unit.trim(), enabled: true,
    params: (form.kind === 'TABLE_AVERAGE' ? { column: form.oid.trim() } : { oid: form.oid.trim() }) as Record<string, string>,
  }
  if (form.kind === 'VALUE' && form.scale.trim()) metric.params.scale = form.scale.trim()
  if (!metric.metricKey) { message.warning(t('addMetric.keyRequired')); return }
  saving.value = true
  try {
    if (form.profile === 'new') {
      if (!form.newName.trim()) { message.warning(t('addMetric.newNameRequired')); return }
      await post('/api/admin/profiles', { name: form.newName.trim(), metrics: [metric] })
    } else {
      const p = profiles.value.find((x) => String(x.id) === form.profile)!
      if (p.metrics.some((m) => m.metricKey === metric.metricKey)) {
        message.warning(t('addMetric.exists', { profile: p.name, key: metric.metricKey }))
        return
      }
      await put(`/api/admin/profiles/${p.id}`, { name: p.name, description: p.description, matchPrefix: p.matchPrefix,
        metrics: [...p.metrics, metric] })
    }
    message.success(t('addMetric.added', { key: metric.metricKey }))
    emit('update:show', false)
  } catch (e) {
    message.error(errorText(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal :show="show" preset="card" :title="$t('addMetric.title')" style="width: 480px" @update:show="(v: boolean) => emit('update:show', v)">
    <NForm label-placement="top">
      <NFormItem label="OID">
        <NInput v-model:value="form.oid" class="mono" />
        <template #feedback><NText depth="3" class="mono">{{ $t('common.labelPrefix', { label: oid?.type ?? '' }) }}{{ oid?.value }}</NText></template>
      </NFormItem>
      <NFormItem :label="$t('addMetric.profile')">
        <NSelect v-model:value="form.profile" :options="profileOptions" />
      </NFormItem>
      <NFormItem v-if="form.profile === 'new'" :label="$t('addMetric.newName') + ' *'">
        <NInput v-model:value="form.newName" maxlength="64" />
      </NFormItem>
      <NFormItem :label="$t('addMetric.key') + ' *'">
        <NInput v-model:value="form.metricKey" :placeholder="$t('addMetric.keyPlaceholder')" data-test="am-key" />
      </NFormItem>
      <NFormItem :label="$t('profiles.kind')">
        <NSelect v-model:value="form.kind" :options="kindOptions" />
      </NFormItem>
      <NFormItem v-if="form.kind === 'VALUE'" :label="$t('addMetric.scale')">
        <NInput v-model:value="form.scale" :placeholder="$t('addMetric.scalePlaceholder')" />
      </NFormItem>
      <NFormItem :label="$t('profiles.unit')">
        <NInput v-model:value="form.unit" :placeholder="$t('addMetric.unitPlaceholder')" />
      </NFormItem>
      <NAlert v-if="!profiles.length" type="info" :show-icon="false">{{ $t('addMetric.noCustomProfile') }}</NAlert>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="emit('update:show', false)">{{ $t('common.cancel') }}</NButton>
        <NButton type="primary" :loading="saving" data-test="am-save" @click="save">{{ $t('addMetric.add') }}</NButton>
      </NSpace>
    </template>
  </NModal>
</template>
