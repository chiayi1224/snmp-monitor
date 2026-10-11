<script setup lang="ts">
import { NDropdown, NButton, NIcon } from 'naive-ui'
import { LanguageOutline } from '@vicons/ionicons5'
import { computed } from 'vue'
import { currentLocale, setLocale, SUPPORTED_LOCALES, LOCALE_NAMES } from '../i18n'
import type { AppLocale } from '../i18n'

withDefaults(defineProps<{ dark?: boolean }>(), { dark: false })

const options = computed(() => SUPPORTED_LOCALES.map((l) => ({ key: l, label: LOCALE_NAMES[l] })))
</script>

<template>
  <NDropdown :options="options" trigger="click" @select="(key: AppLocale) => setLocale(key)">
    <NButton quaternary size="small" :style="dark ? { color: '#ddd' } : undefined" data-test="locale-switcher">
      <template #icon><NIcon><LanguageOutline /></NIcon></template>
      {{ LOCALE_NAMES[currentLocale] }}
    </NButton>
  </NDropdown>
</template>
