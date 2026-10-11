<script setup lang="ts">
import {
  NModal, NCard, NForm, NFormItem, NInput, NInputNumber, NSelect, NSwitch, NButton, NSpace, NCheckbox, NAlert, NText,
  NGrid, NGi, useMessage,
} from 'naive-ui'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { get, post, errorText } from '../api/client'
import type { Device, Profile } from '../api/types'
import { diskFilterMatches, fmtBytes } from '../utils/format'

const props = defineProps<{ show: boolean; device: Device | null; presetHost?: string }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>()
const { t } = useI18n()
const message = useMessage()

const AUTH_PROTOCOLS = ['MD5', 'SHA', 'SHA224', 'SHA256', 'SHA384', 'SHA512']
const PRIV_PROTOCOLS = ['DES', 'AES128', 'AES192', 'AES256']

const form = reactive({
  name: '', host: '', port: 161, snmpVersion: '2c', community: 'public',
  v3User: '', v3AuthProtocol: 'SHA', v3AuthPassword: '', v3PrivProtocol: 'AES128', v3PrivPassword: '', clearPriv: false,
  profileId: 'auto' as string, diskFilter: '', unreachableSeverity: '', unreachableCount: 1, enabled: true,
  retries: 2, timeoutMs: 3000,
})
const profiles = ref<Profile[]>([])
const saving = ref(false)
const editing = computed(() => props.device != null)

watch(() => props.show, async (v) => {
  if (!v) return
  const d = props.device
  Object.assign(form, {
    name: d?.name ?? '', host: d?.host ?? props.presetHost ?? '', port: d?.port ?? 161,
    snmpVersion: d?.snmpVersion ?? '2c', community: d ? '' : 'public',
    v3User: d?.v3User ?? '', v3AuthProtocol: d?.v3AuthProtocol || 'SHA', v3AuthPassword: '',
    v3PrivProtocol: d?.v3PrivProtocol || 'AES128', v3PrivPassword: '', clearPriv: false,
    profileId: d?.profileId != null ? String(d.profileId) : 'auto', diskFilter: d?.diskFilter ?? '',
    unreachableSeverity: d?.unreachableSeverity ?? '', unreachableCount: d?.unreachableCount || 1,
    enabled: d?.enabled ?? true, retries: d?.retries ?? 2, timeoutMs: d?.timeoutMs ?? 3000,
  })
  probed.value = null
  probeError.value = ''
  profiles.value = await get<Profile[]>('/api/admin/profiles').catch(() => [])
})

const versionOptions = [{ label: 'v1', value: '1' }, { label: 'v2c', value: '2c' }, { label: 'v3', value: '3' }]
const profileOptions = computed(() => [
  { label: t('deviceDialog.profileAuto'), value: 'auto' },
  ...profiles.value.map((p) => ({ label: p.displayName, value: String(p.id) })),
])
const unreachableOptions = computed(() => [
  { label: t('deviceDialog.unreachableOff'), value: '' },
  { label: t('severity.WARNING'), value: 'WARNING' },
  { label: t('severity.CRITICAL'), value: 'CRITICAL' },
])

/** 由表單組出 API body；v3 密碼留空代表沿用（編輯時不送出欄位） */
function body(): Record<string, unknown> | null {
  const b: Record<string, unknown> = {
    name: form.name.trim(), host: form.host.trim(), port: form.port, community: form.community.trim(),
    retries: form.retries, timeoutMs: form.timeoutMs, enabled: form.enabled, snmpVersion: form.snmpVersion,
    profileId: form.profileId === 'auto' ? null : Number(form.profileId), diskFilter: form.diskFilter.trim(),
    unreachableSeverity: form.unreachableSeverity, unreachableCount: form.unreachableCount || 1,
  }
  if (form.snmpVersion === '3') {
    if (!form.v3User.trim()) { message.warning(t('deviceDialog.v3UserRequired')); return null }
    b.v3User = form.v3User.trim()
    b.v3AuthProtocol = form.v3AuthProtocol
    b.v3PrivProtocol = form.v3PrivProtocol
    if (form.v3AuthPassword || !editing.value) b.v3AuthPassword = form.v3AuthPassword
    if (form.v3PrivPassword || !editing.value) b.v3PrivPassword = form.v3PrivPassword
    if (form.clearPriv) b.v3PrivPassword = ''
  }
  return b
}

function url() {
  return editing.value ? `/api/admin/devices/${encodeURIComponent(props.device!.name)}` : '/api/admin/devices'
}

async function save() {
  const b = body()
  if (!b) return
  if (!b.name || !b.host) { message.warning(t('deviceDialog.nameHostRequired')); return }
  saving.value = true
  try {
    const r = await post<{ message: string }>(url(), b)
    message.success(r.message)
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    saving.value = false
  }
}

// ─── 探測磁碟：以目前填的連線資料向設備查詢可監控的掛載點 ─────────
interface ProbedDisk { mountPoint: string; usedPct: number; totalBytes: number; usedBytes: number }
const probed = ref<ProbedDisk[] | null>(null)
const picked = ref<string[]>([])
const probing = ref(false)
const probeError = ref('')

async function probe() {
  const b = body()
  if (!b) return
  if (!b.host) { message.warning(t('deviceDialog.hostRequired')); return }
  probing.value = true
  probeError.value = ''
  try {
    const r = await post<{ disks: ProbedDisk[] }>(url(), { ...b, action: 'probe-disks' })
    probed.value = r.disks
    picked.value = r.disks.filter((d) => diskFilterMatches(form.diskFilter, d.mountPoint)).map((d) => d.mountPoint)
    if (!r.disks.length) probeError.value = t('deviceDialog.noDisks')
  } catch (e) {
    probed.value = null
    probeError.value = errorText(e)
  } finally {
    probing.value = false
  }
}

/** 勾選後更新條件：全選或全不選 → 留空（監控全部），否則填入勾選的掛載點 */
function onPick(list: string[]) {
  picked.value = list
  const all = probed.value?.length ?? 0
  form.diskFilter = list.length === 0 || list.length === all ? '' : list.join(',')
}
function toggle(mount: string, checked: boolean) {
  onPick(checked ? [...picked.value, mount] : picked.value.filter((m) => m !== mount))
}
</script>

<template>
  <NModal :show="show" @update:show="(v: boolean) => emit('update:show', v)">
    <NCard :title="editing ? $t('deviceDialog.editTitle') : $t('deviceDialog.addTitle')" style="width: 640px; max-width: 95vw"
           :bordered="false" role="dialog">
      <NForm label-placement="top" :show-feedback="false" style="display: flex; flex-direction: column; gap: 12px">
        <NGrid :cols="2" :x-gap="12" :y-gap="12">
          <NGi><NFormItem :label="$t('deviceDialog.name') + ' *'">
            <NInput v-model:value="form.name" :disabled="editing" placeholder="Server-01" data-test="dev-name" />
          </NFormItem></NGi>
          <NGi><NFormItem :label="$t('deviceDialog.host') + ' *'">
            <NInput v-model:value="form.host" placeholder="192.168.1.10" data-test="dev-host" />
          </NFormItem></NGi>
          <NGi><NFormItem :label="$t('deviceDialog.port')">
            <NInputNumber v-model:value="form.port" :min="1" :max="65535" :show-button="false" style="width: 100%" data-test="dev-port" />
          </NFormItem></NGi>
          <NGi><NFormItem :label="$t('deviceDialog.version')">
            <NSelect v-model:value="form.snmpVersion" :options="versionOptions" data-test="dev-version" />
          </NFormItem></NGi>
        </NGrid>

        <NFormItem v-if="form.snmpVersion !== '3'" label="Community String">
          <NInput v-model:value="form.community" :placeholder="editing ? $t('deviceDialog.keepUnchanged') : ''" data-test="dev-community" />
        </NFormItem>
        <template v-else>
          <NGrid :cols="2" :x-gap="12" :y-gap="12">
            <NGi :span="2"><NFormItem :label="$t('deviceDialog.v3User') + ' *'">
              <NInput v-model:value="form.v3User" autocomplete="off" data-test="dev-v3-user" />
            </NFormItem></NGi>
            <NGi><NFormItem :label="$t('deviceDialog.authProtocol')">
              <NSelect v-model:value="form.v3AuthProtocol" :options="AUTH_PROTOCOLS.map((v) => ({ label: v, value: v }))" />
            </NFormItem></NGi>
            <NGi><NFormItem :label="$t('deviceDialog.authPassword')">
              <NInput v-model:value="form.v3AuthPassword" type="password" show-password-on="click" autocomplete="new-password"
                      :placeholder="device?.v3AuthConfigured ? $t('deviceDialog.passwordSet') : $t('deviceDialog.passwordMin')" data-test="dev-v3-auth" />
            </NFormItem></NGi>
            <NGi><NFormItem :label="$t('deviceDialog.privProtocol')">
              <NSelect v-model:value="form.v3PrivProtocol" :options="PRIV_PROTOCOLS.map((v) => ({ label: v, value: v }))" />
            </NFormItem></NGi>
            <NGi><NFormItem :label="$t('deviceDialog.privPassword')">
              <NInput v-model:value="form.v3PrivPassword" type="password" show-password-on="click" autocomplete="new-password"
                      :placeholder="device?.v3PrivConfigured ? $t('deviceDialog.passwordSet') : $t('deviceDialog.passwordMin')" data-test="dev-v3-priv" />
            </NFormItem></NGi>
          </NGrid>
          <NCheckbox v-if="device?.v3PrivConfigured" v-model:checked="form.clearPriv">{{ $t('deviceDialog.clearPriv') }}</NCheckbox>
        </template>

        <NFormItem :label="$t('deviceDialog.profile')">
          <NSpace vertical :size="4" style="width: 100%">
            <NSelect v-model:value="form.profileId" :options="profileOptions" data-test="dev-profile" />
            <NText v-if="device?.appliedProfileDisplayName && form.profileId === 'auto'" depth="3" style="font-size: 12px">
              {{ $t('deviceDialog.profileCurrent', { name: device.appliedProfileDisplayName }) }}
            </NText>
          </NSpace>
        </NFormItem>

        <NFormItem :label="$t('deviceDialog.diskFilter')">
          <NSpace vertical style="width: 100%">
            <div style="display: flex; gap: 8px">
              <NInput v-model:value="form.diskFilter" :placeholder="$t('deviceDialog.diskFilterPlaceholder')" maxlength="500" data-test="dev-disk-filter" />
              <NButton :loading="probing" data-test="dev-probe" @click="probe">{{ $t('deviceDialog.probe') }}</NButton>
            </div>
            <div v-if="probed?.length" style="border: 1px solid #eee; border-radius: 4px; padding: 6px 10px; max-height: 200px; overflow: auto" data-test="dev-disk-list">
              <div v-for="d in probed" :key="d.mountPoint" style="display: flex; align-items: center; gap: 8px; margin: 2px 0">
                <NCheckbox :checked="picked.includes(d.mountPoint)" @update:checked="(c: boolean) => toggle(d.mountPoint, c)">
                  <span class="mono">{{ d.mountPoint }}</span>
                </NCheckbox>
                <NText depth="3" style="margin-left: auto; font-size: 12px">
                  {{ fmtBytes(d.usedBytes) }} / {{ fmtBytes(d.totalBytes) }} {{ $t('common.parenthesized', { text: d.usedPct.toFixed(1) + '%' }) }}
                </NText>
              </div>
            </div>
            <NAlert v-if="probeError" type="error" :show-icon="false" data-test="dev-probe-error">{{ probeError }}</NAlert>
            <NText depth="3" style="font-size: 12px">
              {{ probed?.length ? $t('deviceDialog.probeFound', { n: probed.length }) : $t('deviceDialog.diskFilterHint') }}
            </NText>
          </NSpace>
        </NFormItem>

        <NFormItem :label="$t('deviceDialog.unreachable')">
          <div style="display: flex; gap: 8px; align-items: center; width: 100%">
            <NSelect v-model:value="form.unreachableSeverity" :options="unreachableOptions" style="flex: 1" data-test="dev-unreachable" />
            <span>{{ $t('deviceDialog.consecutive') }}</span>
            <NInputNumber v-model:value="form.unreachableCount" :min="1" :disabled="!form.unreachableSeverity" :show-button="false" style="width: 80px" />
            <span>{{ $t('deviceDialog.times') }}</span>
          </div>
        </NFormItem>

        <NSpace align="center">
          <NSwitch v-model:value="form.enabled" />
          <span>{{ $t('deviceDialog.enabled') }}</span>
        </NSpace>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="emit('update:show', false)">{{ $t('common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" data-test="dev-save" @click="save">{{ $t('common.save') }}</NButton>
        </NSpace>
      </template>
    </NCard>
  </NModal>
</template>
