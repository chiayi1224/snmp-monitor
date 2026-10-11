import { describe, expect, it } from 'vitest'
import { existsSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { findExternalUrls } from './offline'

const DIST = join(__dirname, '..', 'dist')

describe('離線可用（spec：完全離線可用）', () => {
  it.skipIf(!existsSync(DIST))('建置結果沒有會被請求的外部網址', () => {
    expect(findExternalUrls(DIST)).toEqual([])
  })

  it('檢查能找出外部網址', () => {
    const dir = mkdtempSync(join(tmpdir(), 'offline-'))
    try {
      writeFileSync(join(dir, 'index.html'), '<link href="https://fonts.googleapis.com/css?family=X" rel="stylesheet"><svg xmlns="http://www.w3.org/2000/svg"/>')
      writeFileSync(join(dir, 'a.css'), 'body{background:url(https://cdn.example.com/bg.png)}')
      const found = findExternalUrls(dir)
      expect(found).toHaveLength(2)
      expect(found.join()).toContain('fonts.googleapis.com')
      expect(found.join()).toContain('cdn.example.com')
    } finally {
      rmSync(dir, { recursive: true })
    }
  })
})
