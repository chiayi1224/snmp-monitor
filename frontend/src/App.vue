<script setup lang="ts">
import { NConfigProvider, NMessageProvider, NDialogProvider, NLoadingBarProvider } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import { naiveLocale, naiveDateLocale } from './i18n'

const themeOverrides: GlobalThemeOverrides = {
  common: { primaryColor: '#1565c0', primaryColorHover: '#1e74d6', primaryColorPressed: '#0d4f9c', primaryColorSuppl: '#1e74d6' },
}
</script>

<template>
  <NConfigProvider :locale="naiveLocale" :date-locale="naiveDateLocale" :theme-overrides="themeOverrides">
    <NLoadingBarProvider>
      <NMessageProvider>
        <NDialogProvider>
          <RouterView />
        </NDialogProvider>
      </NMessageProvider>
    </NLoadingBarProvider>
  </NConfigProvider>
</template>
