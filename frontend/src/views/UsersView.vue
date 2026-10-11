<script setup lang="ts">
import { NCard, NDataTable, NButton, NSpace, NTag, NModal, NForm, NFormItem, NInput, NSelect, NSwitch, NText, useMessage, useDialog } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PasswordModal from '../components/PasswordModal.vue'
import { get, post, put, del, errorText } from '../api/client'
import type { User } from '../api/types'
import { fmtDate } from '../utils/format'

const { t } = useI18n()
const message = useMessage()
const dialog = useDialog()

const rows = ref<User[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await get<User[]>('/api/admin/users')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ─── 新增／編輯 ───────────────────────────────────────────────
const showUser = ref(false)
const form = reactive({ id: null as number | null, username: '', password: '', role: 'admin', enabled: true })
const roleOptions = [{ label: 'admin', value: 'admin' }]

function open(u: User | null) {
  Object.assign(form, { id: u?.id ?? null, username: u?.username ?? '', password: '', role: u?.role ?? 'admin', enabled: u?.enabled ?? true })
  showUser.value = true
}

async function save() {
  if (!form.username.trim()) { message.warning(t('users.usernameRequired')); return }
  if (!form.id && form.password.length < 6) { message.warning(t('users.passwordTooShort')); return }
  const body: Record<string, unknown> = { username: form.username.trim(), role: form.role, enabled: form.enabled }
  if (!form.id) body.password = form.password
  try {
    const r = form.id ? await put<{ message: string }>(`/api/admin/users/${form.id}`, body)
      : await post<{ message: string }>('/api/admin/users', body)
    message.success(r.message)
    showUser.value = false
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}

// ─── 改密碼與刪除 ─────────────────────────────────────────────
const showPassword = ref(false)
const passwordUser = ref<User | null>(null)

function remove(u: User) {
  dialog.warning({
    title: t('common.confirmDelete'), content: t('users.deleteConfirm', { name: u.username }),
    positiveText: t('common.delete'), negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        message.success((await del<{ message: string }>(`/api/admin/users/${u.id}`)).message)
        load()
      } catch (e) {
        message.error(errorText(e))
      }
    },
  })
}

const columns = computed<DataTableColumns<User>>(() => [
  { title: t('users.username'), key: 'username', render: (u) => h('strong', u.username) },
  { title: t('users.role'), key: 'role' },
  {
    title: t('users.state'), key: 'enabled',
    render: (u) => h(NSpace, { size: 4 }, () => [
      h(NTag, { size: 'small', type: u.enabled ? 'success' : 'default', bordered: false }, () => (u.enabled ? t('common.enabled') : t('common.disabled'))),
      u.mustChange ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => t('users.mustChange')) : null,
    ]),
  },
  { title: t('users.created'), key: 'created', render: (u) => h(NText, { depth: 3 }, () => fmtDate(u.created)) },
  {
    title: t('common.actions'), key: 'actions', width: 240,
    render: (u) => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', onClick: () => open(u) }, () => t('common.edit')),
      h(NButton, { size: 'small', onClick: () => { passwordUser.value = u; showPassword.value = true } }, () => t('users.changePassword')),
      h(NButton, { size: 'small', type: 'error', ghost: true, onClick: () => remove(u) }, () => t('common.delete')),
    ]),
  },
])
</script>

<template>
  <NCard :title="$t('users.title')">
    <template #header-extra>
      <NButton type="primary" data-test="user-add" @click="open(null)">{{ $t('users.add') }}</NButton>
    </template>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(u: User) => u.id" :bordered="false" />

    <NModal v-model:show="showUser" preset="card" :title="form.id ? $t('users.editTitle') : $t('users.addTitle')" style="width: 420px">
      <NForm label-placement="top">
        <NFormItem :label="$t('users.username') + ' *'">
          <NInput v-model:value="form.username" placeholder="operator" data-test="user-name" />
        </NFormItem>
        <NFormItem v-if="!form.id" :label="$t('login.password') + ' *'">
          <NInput v-model:value="form.password" type="password" show-password-on="click" :placeholder="$t('users.passwordPlaceholder')" data-test="user-password" />
        </NFormItem>
        <NFormItem :label="$t('users.role')">
          <NSelect v-model:value="form.role" :options="roleOptions" />
        </NFormItem>
        <NSpace align="center"><NSwitch v-model:value="form.enabled" /><span>{{ $t('common.enabled') }}</span></NSpace>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showUser = false">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" data-test="user-save" @click="save">{{ $t('common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
    <PasswordModal v-model:show="showPassword" :user-id="passwordUser?.id ?? null"
                   :title="$t('users.changePasswordFor', { name: passwordUser?.username ?? '' })" @saved="load" />
  </NCard>
</template>
