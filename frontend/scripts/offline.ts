// 離線檢查：建置結果中不可有指向外部主機、會被瀏覽器實際請求的網址
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

/** 不會被請求的網址（命名空間、授權與文件連結等） */
const ALLOWED = [
  /^https?:\/\/www\.w3\.org\//,
  /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//,
  /^https?:\/\/github\.com\//,
  /^https?:\/\/vuejs\.org\//,
  /^https?:\/\/vue-i18n\.intlify\.dev\//,
  /^https?:\/\/(www\.)?naiveui\.com\//,
  /^https?:\/\/(www\.)?npmjs\.(com|org)\//,
  /^https?:\/\/fb\.me\//,
  /^https?:\/\/developer\.mozilla\.org\//,
  /^https?:\/\/tc39\.es\//,
]

/** 會讓瀏覽器發出請求的寫法：src/href 屬性、CSS url()、import、fetch */
const REQUEST_PATTERNS = [
  /(?:src|href)\s*=\s*["'](https?:\/\/[^"']+)["']/g,
  /url\(\s*["']?(https?:\/\/[^"')]+)["']?\s*\)/g,
  /(?:import\s*\(|from\s+)["'](https?:\/\/[^"']+)["']/g,
  /@import\s+["'](https?:\/\/[^"']+)["']/g,
  /fetch\(\s*["'](https?:\/\/[^"']+)["']/g,
]

export function findExternalUrls(dir: string): string[] {
  const found: string[] = []
  const walk = (d: string) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name)
      if (statSync(p).isDirectory()) { walk(p); continue }
      if (!/\.(html|js|mjs|css|json|svg)$/.test(name)) continue
      const text = readFileSync(p, 'utf8')
      for (const re of REQUEST_PATTERNS) {
        for (const m of text.matchAll(re)) {
          if (!ALLOWED.some((a) => a.test(m[1]))) found.push(`${p}: ${m[1]}`)
        }
      }
    }
  }
  walk(dir)
  return found
}
