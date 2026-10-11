<script setup lang="ts">
import { NTag } from 'naive-ui'
import type { Severity } from '../api/types'

defineProps<{ severity: Severity | '' | null | undefined }>()
</script>

<template>
  <NTag v-if="severity" size="small" :type="severity === 'CRITICAL' ? 'error' : 'warning'" :bordered="false">
    {{ $t(`severity.${severity}`) }}
  </NTag>
  <span v-else>—</span>
</template>
