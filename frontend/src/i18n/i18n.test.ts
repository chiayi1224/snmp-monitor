import { describe, expect, it, beforeEach } from 'vitest'
import zhTW from '../locales/zh-TW'
import zhCN from '../locales/zh-CN'
import en from '../locales/en'
import { detectLocale, matchLocale, setLocale, currentLocale, i18n, naiveLocale, STORAGE_KEY } from './index'

/** 物件的所有葉節點路徑 */
function keys(obj: object, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === 'object' ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`])
}

describe('語言決定（spec：介面語言的決定與切換）', () => {
  it('首次使用英文瀏覽器 → en', () => {
    expect(detectLocale(null, ['en-US'])).toBe('en')
  })

  it('簡體中文瀏覽器 → zh-CN', () => {
    expect(detectLocale(null, ['zh-CN'])).toBe('zh-CN')
    expect(detectLocale(null, ['zh-Hans'])).toBe('zh-CN')
    expect(detectLocale(null, ['zh'])).toBe('zh-CN')
  })

  it('繁體中文相關 → zh-TW', () => {
    for (const tag of ['zh-TW', 'zh-HK', 'zh-MO', 'zh-Hant', 'zh-Hant-TW']) expect(matchLocale(tag)).toBe('zh-TW')
  })

  it('不支援的語言 → zh-TW；依偏好順序比對', () => {
    expect(detectLocale(null, ['ja-JP'])).toBe('zh-TW')
    expect(detectLocale(null, [])).toBe('zh-TW')
    expect(detectLocale(null, ['ja-JP', 'en-GB', 'zh-TW'])).toBe('en')
  })

  it('使用者先前的選擇優先；不合法的儲存值忽略', () => {
    expect(detectLocale('zh-CN', ['en-US'])).toBe('zh-CN')
    expect(detectLocale('fr', ['en-US'])).toBe('en')
  })
})

describe('切換並記住', () => {
  beforeEach(() => setLocale('zh-TW'))

  it('立即生效、同步 Naive UI 語系與 html lang，並寫入 localStorage', () => {
    setLocale('zh-CN')
    expect(currentLocale.value).toBe('zh-CN')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(i18n.global.t('nav.devices')).toBe('设备管理')
    expect(naiveLocale.value.name).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('zh-CN')

    setLocale('en')
    expect(i18n.global.t('nav.devices')).toBe('Devices')
    expect(naiveLocale.value.name).toBe('en-US')
  })
})

describe('三份語系內容一致', () => {
  const base = keys(zhTW).sort()

  it('zh-CN 與 en 的鍵與 zh-TW 完全相同', () => {
    expect(keys(zhCN).sort()).toEqual(base)
    expect(keys(en).sort()).toEqual(base)
  })

  it('英文沒有中文字；簡體中文沒有正體專用字', () => {
    for (const k of keys(en)) {
      const v = k.split('.').reduce<unknown>((o, p) => (o as Record<string, unknown>)[p], en)
      expect(String(v), k).not.toMatch(/[\u4e00-\u9fff]/)
    }
    const cnText = JSON.stringify(zhCN)
    for (const ch of ['設', '備', '範', '帳', '號', '啟', '錄', '臨', '數']) expect(cnText, ch).not.toContain(ch)
  })

  it('每則訊息的參數名稱一致', () => {
    const params = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join()
    const get = (o: object, k: string) => String(k.split('.').reduce<unknown>((x, p) => (x as Record<string, unknown>)[p], o))
    for (const k of base) {
      expect(params(get(zhCN, k)), k).toBe(params(get(zhTW, k)))
      expect(params(get(en, k)), k).toBe(params(get(zhTW, k)))
    }
  })
})
