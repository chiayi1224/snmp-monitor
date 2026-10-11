// 後端 API 的回應型別（欄位名稱與後端 JSON 相同）

export type Severity = 'WARNING' | 'CRITICAL'
export type AlertEventType = 'TRIGGERED' | 'CHANGED' | 'RESOLVED'
export type SnmpVersion = '1' | '2c' | '3'

export interface User {
  id: number
  username: string
  role: string
  mustChange?: boolean
  enabled?: boolean
  created?: string
}

export interface Device {
  id: number
  name: string
  host: string
  port: number
  enabled: boolean
  retries: number
  timeoutMs: number
  snmpVersion: SnmpVersion
  v3User: string
  v3AuthProtocol: string
  v3PrivProtocol: string
  communityConfigured: boolean
  v3AuthConfigured: boolean
  v3PrivConfigured: boolean
  profileId: number | null
  sysObjectId: string
  diskFilter: string
  unreachableSeverity: '' | Severity
  unreachableCount: number
  status: string
  lastSeen: string
  appliedProfileId: number | null
  appliedProfileName: string
  appliedProfileDisplayName: string
}

export interface DiskStatus {
  mountPoint: string
  usedPct: number
  totalBytes: number
  usedBytes: number
  ts: string
}

export interface DeviceStatus {
  deviceName: string
  cpu?: { usedPct: number; ts: string } | null
  memory?: { usedPct: number; totalKb: number; usedKb: number; ts: string } | null
  disks?: DiskStatus[]
  custom?: { metricKey: string; value: number; unit: string; ts: string }[]
}

export interface AlertRecord {
  id: number
  ts: string
  deviceName: string
  metricName: string
  eventType: AlertEventType
  severity: Severity
  value: number
  thresholdVal: number
  message: string
}

export interface Threshold {
  id: number
  deviceName: string
  metricName: string
  direction: 'HIGH' | 'LOW'
  warning: number | null
  critical: number | null
  rearmMargin: number
  triggerCount: number
  rearmCount: number
  updated: string
}

export interface ProfileMetric {
  metricKey: string
  kind: string
  params: Record<string, string>
  unit: string
  enabled: boolean
}

export interface Profile {
  id: number
  name: string
  displayName: string
  description: string
  displayDescription: string
  matchPrefix: string
  builtin: boolean
  deviceCount: number
  metrics: ProfileMetric[]
}

export interface PreviewResult {
  metricKey: string
  kind: string
  unit: string
  enabled: boolean
  ok: boolean
  values?: { instance: string | null; value: number; totalBytes: number | null; usedBytes: number | null }[]
  error?: string
}

export interface TrapMapping {
  id: number
  metricName: string
  triggeredOid: string
  resolvedOid: string | null
  description: string
  severity: Severity
  enabled: boolean
}

export interface UnknownTrap {
  id: number
  trapOid: string
  deviceName: string
  sourceIp: string
  count: number
  lastSeen: string
  varbinds: string
}

export interface UnknownSource {
  id: number
  sourceIp: string
  lastTrapOid: string
  count: number
  lastSeen: string
}

export interface Scan {
  id: number
  deviceName: string
  started: string
  finished: string | null
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  progress: string
  error: string | null
  walk: boolean
  sysDescr: string | null
  sysObjectId: string | null
  sysName: string | null
  sysUpTime: string | null
  vendor: string | null
  oidCount: number
  truncated: boolean
  probes?: { key: string; name: string; mib: string; supported: boolean; samples: string[]; error: string | null }[]
}

export interface ScanOid {
  oid: string
  type: string
  value: string
}

export interface ApiToken {
  id: number
  description: string
  enabled: boolean
  expiresAt: string | null
  createdAt: string | null
  lastUsedAt: string | null
}

export interface AuditLog {
  id: number
  ts: string
  eventType: string
  actor: string | null
  sourceIp: string | null
  method: string | null
  path: string | null
  status: number
  detail: string | null
}

export interface Setting {
  key: string
  value: string
  description: string
  updated: string
}
