<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NTag, NAlert, useMessage, useDialog } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ProfileDialog from '../components/ProfileDialog.vue'
import { get, post, del, errorText } from '../api/client'
import type { Profile } from '../api/types'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const rows = ref<Profile[]>([])
const loading = ref(false)
const showDialog = ref(false)
const editing = ref<Profile | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await get<Profile[]>('/api/admin/profiles')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

function open(p: Profile | null) {
  editing.value = p
  showDialog.value = true
}

async function copy(p: Profile) {
  try {
    const r = await post<{ message: string; id: number }>(`/api/admin/profiles/${p.id}/copy`)
    message.success(r.message)
    await load()
    const created = rows.value.find((x) => x.id === r.id)
    if (created) open(created)
  } catch (e) {
    message.error(errorText(e))
  }
}

function remove(p: Profile) {
  dialog.warning({
    title: t('common.confirmDelete'),
    content: t('profiles.deleteConfirm', { name: p.displayName }) + (p.deviceCount ? '\n' + t('profiles.deleteInUse', { n: p.deviceCount }) : ''),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        message.success((await del<{ message: string }>(`/api/admin/profiles/${p.id}`)).message)
        load()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

const columns = computed<DataTableColumns<Profile>>(() => [
  {
    title: t('profiles.name'), key: 'name',
    render: (p) => h('div', [
      h('strong', p.displayName),
      p.builtin ? h(NTag, { size: 'small', type: 'info', bordered: false, style: 'margin-left:6px' }, () => t('profiles.builtin')) : null,
      p.displayDescription ? h('div', { class: 'muted' }, p.displayDescription) : null,
    ]),
  },
  { title: t('profiles.prefix'), key: 'matchPrefix', render: (p) => h('span', { class: 'mono' }, p.matchPrefix || '—') },
  {
    title: t('profiles.metrics'), key: 'metrics',
    render: (p) => p.metrics.length ? h(NSpace, { size: 4 }, () => p.metrics.map((m) =>
      h(NTag, { size: 'small', type: m.enabled ? 'success' : 'default', bordered: false, title: m.kind }, () => m.metricKey)))
      : '—',
  },
  { title: t('profiles.deviceCount'), key: 'deviceCount', width: 100 },
  {
    title: t('common.actions'), key: 'actions', width: 220,
    render: (p) => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => open(p) }, () => (p.builtin ? t('common.view') : t('common.edit'))),
      h(NButton, { size: 'small', onClick: () => copy(p) }, () => t('profiles.copy')),
      p.builtin ? null : h(NButton, { size: 'small', type: 'error', ghost: true, onClick: () => remove(p) }, () => t('common.delete')),
    ]),
  },
])
</script>

<template>
  <div>
    <NAlert type="info" :show-icon="false" style="margin-bottom: 16px">{{ $t('profiles.intro') }}</NAlert>
    <NCard :title="$t('profiles.title')">
      <template #header-extra>
        <NButton type="primary" data-test="pf-add" @click="open(null)">{{ $t('profiles.add') }}</NButton>
      </template>
      <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(p: Profile) => p.id" :bordered="false" />
    </NCard>
    <ProfileDialog v-model:show="showDialog" :profile="editing" @saved="load" />
  </div>
</template>
