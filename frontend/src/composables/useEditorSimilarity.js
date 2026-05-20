import { computed, reactive, ref } from 'vue'
import { API_BASE } from '../utils/apiBase'

function authHeaders(appCode) {
  return {
    'Content-Type': 'application/json',
    'X-Search-AppCode': appCode,
    'X-User-Id': import.meta.env.VITE_USER_ID || 'admin',
    'X-Authorities': import.meta.env.VITE_USER_ROLE || 'ROLE_SUPER_ADMIN',
    'X-Dept-Code': import.meta.env.VITE_DEPT_CODE || '620102000000'
  }
}

export function useEditorSimilarity(options = {}) {
  const state = reactive({
    items: [],
    total: 0,
    queryChars: 0,
    costMs: 0,
    cacheHit: false,
    skipped: false,
    skipReason: '',
    loading: false,
    error: ''
  })
  const requestId = ref(0)
  const minChars = options.minChars || 80
  const debounceMs = options.debounceMs || 1000
  let timer = null
  let controller = null

  const hasResults = computed(() => state.items.length > 0)

  function resetResult() {
    state.items = []
    state.total = 0
    state.queryChars = 0
    state.costMs = 0
    state.cacheHit = false
    state.skipped = false
    state.skipReason = ''
    state.error = ''
  }

  function abort() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    controller?.abort()
    controller = null
    state.loading = false
  }

  function schedule(payload = {}) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      refresh(payload)
    }, debounceMs)
  }

  async function refresh(payload = {}) {
    const text = String(payload.text || '').trim()
    const title = String(payload.title || '').trim()
    if ((title + text).length < minChars) {
      abort()
      resetResult()
      state.skipped = true
      state.skipReason = 'input_too_short'
      state.queryChars = (title + text).length
      return
    }

    abort()
    const id = requestId.value + 1
    requestId.value = id
    controller = new AbortController()
    state.loading = true
    state.error = ''

    try {
      const appCode = import.meta.env.VITE_APP_CODE || 'ADMIN_MASTER_KEY'
      const resp = await fetch(`${API_BASE}/editor/similar-docs`, {
        method: 'POST',
        headers: authHeaders(appCode),
        signal: controller.signal,
        body: JSON.stringify({
          text,
          title,
          topK: payload.topK || 5,
          excludeDocId: payload.excludeDocId || '',
          excludeSource: payload.excludeSource || ''
        })
      })
      if (id !== requestId.value) return
      if (!resp.ok) {
        state.error = `相似文档请求失败：${resp.status} ${resp.statusText}`
        return
      }
      const data = await resp.json()
      if (id !== requestId.value) return
      if (data.code !== 200) {
        state.error = data.msg || '相似文档请求失败'
        return
      }
      const body = data.data || {}
      state.items = Array.isArray(body.items) ? body.items : []
      state.total = Number(body.total || state.items.length)
      state.queryChars = Number(body.queryChars || 0)
      state.costMs = Number(body.costMs || 0)
      state.cacheHit = Boolean(body.cacheHit)
      state.skipped = Boolean(body.skipped)
      state.skipReason = body.skipReason || ''
    } catch (err) {
      if (err?.name !== 'AbortError') {
        state.error = err?.message || '相似文档请求失败'
      }
    } finally {
      if (id === requestId.value) {
        state.loading = false
      }
    }
  }

  return {
    state,
    hasResults,
    schedule,
    refresh,
    abort,
    resetResult
  }
}
