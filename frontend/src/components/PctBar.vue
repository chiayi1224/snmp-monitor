<script setup lang="ts">
import { NProgress } from 'naive-ui'
import { computed } from 'vue'

const props = defineProps<{ pct: number }>()
const color = computed(() => (props.pct >= 90 ? '#e53935' : props.pct >= 75 ? '#fb8c00' : '#43a047'))
</script>

<template>
  <div style="display: flex; align-items: center; gap: 8px; min-width: 150px">
    <NProgress type="line" :percentage="Math.min(100, Math.max(0, pct))" :color="color" :show-indicator="false"
               :height="8" style="width: 90px" />
    <span>{{ pct.toFixed(1) }}%</span>
  </div>
</template>
