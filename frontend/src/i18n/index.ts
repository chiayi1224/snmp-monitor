import { createI18n } from 'vue-i18n'
import { zhTW, zhCN, enUS, dateZhTW, dateZhCN, dateEnUS } from 'naive-ui'
import type { NLocale, NDateLocale } from 'naive-ui'
import { computed, ref } from 'vue'
import zhTWMessages from '../locales/zh-TW'
import zhCNMessages from '../locales/zh-CN'
import enMessages from '../locales/en'

/** 支援的語言（第一個為預設） */
export const SUPPORTED_LOCALES = ['zh-TW', 'zh-CN', 'en'] as const
export type AppLocale = (typeof SUPPORTED_LOCALES)[number]
export const DEFAULT_LOCALE: AppLocale = 'zh-TW'

/** 語言選單顯示的名稱（以各語言本身書寫，不隨目前語言翻譯） */
export const LOCALE_NAMES: Record<AppLocale, string> = {
  'zh-TW': '正體中文',
  'zh-CN': '简体中文',
  en: 'English',
}

export const STORAGE_KEY = 'snmp-monitor.locale'

/**
 * 單一語言標籤對應到支援的語言；不支援回傳 null（規則與後端 Messages.match 相同）
 *   zh-TW / zh-HK / zh-MO / zh-Hant* → zh-TW
 *   zh-CN / zh-SG / zh-Hans* / 單純 zh → zh-CN
 *   en*                               → en
 */
export function matchLocale(tag: string | null | undefined): AppLocale | null {
  if (!tag) return null
  const t = tag.trim().toLowerCase().replace(/_/g, '-')
  if (t === 'zh' || t.startsWith('zh-cn') || t.startsWith('zh-sg') || t.startsWith('zh-hans')) return 'zh-CN'
  if (t.startsWith('zh-')) return 'zh-TW'
  if (t === 'en' || t.startsWith('en-')) return 'en'
  return null
}

/** 決定語言：使用者先前的選擇 → 瀏覽器偏好語言（依序） → 正體中文 */
export function detectLocale(stored: string | null, browserLanguages: readonly string[]): AppLocale {
  if (stored && (SUPPORTED_LOCALES as readonly string[]).includes(stored)) return stored as AppLocale
  for (const lang of browserLanguages) {
    const m = matchLocale(lang)
    if (m) return m
  }
  return DEFAULT_LOCALE
}

function browserLanguages(): readonly string[] {
  if (typeof navigator === 'undefined') return []
  return navigator.languages?.length ? navigator.languages : [navigator.language]
}

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

const initial = detectLocale(readStored(), browserLanguages())

export const i18n = createI18n({
  legacy: false,
  locale: initial,
  fallbackLocale: DEFAULT_LOCALE,
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'zh-TW': zhTWMessages, 'zh-CN': zhCNMessages, en: enMessages },
})

/** 目前語言（reactive），供 Naive UI 與 API 用戶端使用 */
export const currentLocale = ref<AppLocale>(initial)

const NAIVE_LOCALES: Record<AppLocale, { locale: NLocale; dateLocale: NDateLocale }> = {
  'zh-TW': { locale: zhTW, dateLocale: dateZhTW },
  'zh-CN': { locale: zhCN, dateLocale: dateZhCN },
  en: { locale: enUS, dateLocale: dateEnUS },
}

export const naiveLocale = computed(() => NAIVE_LOCALES[currentLocale.value].locale)
export const naiveDateLocale = computed(() => NAIVE_LOCALES[currentLocale.value].dateLocale)

/** 切換語言：立即生效並記住選擇 */
export function setLocale(locale: AppLocale) {
  currentLocale.value = locale
  i18n.global.locale.value = locale
  if (typeof document !== 'undefined') document.documentElement.lang = locale
  try {
    localStorage.setItem(STORAGE_KEY, locale)
  } catch {
    /* 無法寫入 localStorage（例如私密瀏覽）時只在本次生效 */
  }
}

if (typeof document !== 'undefined') document.documentElement.lang = initial

export const t = i18n.global.t
