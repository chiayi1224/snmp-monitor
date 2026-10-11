import { currentLocale } from '../i18n'

/** ISO-8601 時間依目前語言顯示（24 小時制）；空值為「—」 */
export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(currentLocale.value, {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(d)
}

/** 只顯示日期 */
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(currentLocale.value, { year: 'numeric', month: '2-digit', day: '2-digit' }).format(d)
}

/** 數值：最多兩位小數，去掉多餘的 0 */
export function fmtNum(v: unknown): string {
  return typeof v === 'number' ? String(Math.round(v * 100) / 100) : String(v ?? '')
}

export function fmtBytes(n: number | null | undefined): string {
  if (n == null) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = Number(n)
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`
}

/** cpu／memory／disk 與 disk:<掛載點> 為百分比指標 */
export function isPercentMetric(name: string): boolean {
  return /^(cpu|memory|disk)(:|$)/.test(name)
}

/** 告警數值：百分比指標加上 %，其他顯示原始數值 */
export function fmtAlertValue(metric: string, v: number): string {
  return isPercentMetric(metric) ? `${v.toFixed(1)}%` : fmtNum(v)
}

/** 與後端 DiskFilter 相同的規則：逗號或換行分隔、完整名稱、* 萬用字元、不分大小寫；空白代表全部 */
export function diskFilterMatches(filter: string, name: string): boolean {
  const items = filter.split(/[,\n]/).map((x) => x.trim()).filter(Boolean)
  if (!items.length) return true
  return items.some((it) => {
    const pattern = it.split('*').map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*')
    return new RegExp(`^${pattern}$`, 'i').test(name)
  })
}
