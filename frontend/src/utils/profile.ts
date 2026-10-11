// 監控範本的計算類型與各類型需要的參數：[參數名, 是否選填]（說明文字見語系檔 profiles.params.*）
export const KIND_PARAMS: Record<string, [string, boolean][]> = {
  VALUE: [['oid', false], ['scale', true]],
  HUNDRED_MINUS: [['oid', false]],
  USED_TOTAL_PERCENT: [['used', false], ['total', false], ['scale', true]],
  USED_FREE_PERCENT: [['used', false], ['free', false], ['scale', true]],
  TABLE_AVERAGE: [['column', false]],
  STORAGE_TABLE: [['typeFilter', false], ['entry', true], ['first', true]],
  UCD_MEMORY: [],
}

export const KINDS = Object.keys(KIND_PARAMS)
