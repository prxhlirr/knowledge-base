import { computed, reactive, ref } from 'vue'
import { API_BASE } from '../utils/apiBase'

function createSearchState() {
  return reactive({
    list: [],
    total: 0,
    tookMs: 0,
    loading: false,
    error: '',
    slaTimeout: false,
    cacheHit: false
  })
}

function createQaState() {
  return reactive({
    answer: '',
    answerParts: [],
    citations: [],
    evidence: null,
    verification: null,
    metrics: null,
    answerSource: '',
    loading: false,
    done: false,
    stage: 'idle',
    error: ''
  })
}

function authHeaders(appCode) {
  return {
    'Content-Type': 'application/json',
    'X-Search-AppCode': appCode,
    'X-User-Id': import.meta.env.VITE_USER_ID || 'admin',
    'X-Authorities': import.meta.env.VITE_USER_ROLE || 'ROLE_SUPER_ADMIN',
    'X-Dept-Code': import.meta.env.VITE_DEPT_CODE || '620102000000'
  }
}

export function useHomeSearch() {
  const keyword = createSearchState()
  const hybrid = createSearchState()
  const qa = createQaState()
  const requestId = ref(0)
  let controller = null

  const loading = computed(() => keyword.loading || hybrid.loading || qa.loading)

  function resetSearchState(state) {
    state.list = []
    state.total = 0
    state.tookMs = 0
    state.loading = false
    state.error = ''
    state.slaTimeout = false
    state.cacheHit = false
  }

  function resetQaState() {
    qa.answer = ''
    qa.answerParts = []
    qa.citations = []
    qa.evidence = null
    qa.verification = null
    qa.metrics = null
    qa.answerSource = ''
    qa.loading = false
    qa.done = false
    qa.stage = 'idle'
    qa.error = ''
  }

  function reset() {
    resetSearchState(keyword)
    resetSearchState(hybrid)
    resetQaState()
  }

  function abort() {
    controller?.abort()
    controller = null
  }

  async function search(queryText, options = {}) {
    const question = String(queryText || '').trim()
    if (!question) return

    abort()
    reset()
    const id = requestId.value + 1
    requestId.value = id
    controller = new AbortController()
    keyword.loading = true
    hybrid.loading = true
    qa.loading = true
    qa.stage = 'searching'

    try {
      const appCode = import.meta.env.VITE_APP_CODE || 'ADMIN_MASTER_KEY'
      const resp = await fetch(`${API_BASE}/search/home`, {
        method: 'POST',
        headers: authHeaders(appCode),
        signal: controller.signal,
        body: JSON.stringify({
          queryText: question,
          pageSize: options.pageSize || 50,
          topK: options.topK || 5,
          appCode,
          scene: options.scene || 'home'
        })
      })

      if (!resp.ok) {
        const message = `首页搜索请求失败：${resp.status} ${resp.statusText}`
        keyword.error = message
        hybrid.error = message
        qa.error = message
        return
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let streamDone = false

      while (!streamDone) {
        const { done, value } = await reader.read()
        if (done) break
        if (id !== requestId.value) return
        buffer += decoder.decode(value, { stream: true })

        const eventBlocks = buffer.split(/\n\n|\r\n\r\n/)
        buffer = eventBlocks.pop() ?? ''

        for (const block of eventBlocks) {
          if (!block.trim()) continue
          const parsed = parseSseBlock(block)
          if (!parsed.data) continue
          if (id !== requestId.value) return

          if (parsed.eventName === 'keyword') {
            applySearchPayload(keyword, parsed.data)
          } else if (parsed.eventName === 'hybrid') {
            applySearchPayload(hybrid, parsed.data)
          } else if (parsed.eventName === 'qa_stage') {
            const payload = parseJson(parsed.data, null)
            qa.stage = payload?.stage || parsed.data || qa.stage
          } else if (parsed.eventName === 'token') {
            qa.stage = 'generating'
            qa.answer += parseToken(parsed.data)
          } else if (parsed.eventName === 'answer_parts') {
            qa.stage = 'generating'
            qa.answerParts = parseJson(parsed.data, [])
          } else if (parsed.eventName === 'citations') {
            qa.stage = 'generating'
            qa.citations = parseJson(parsed.data, [])
          } else if (parsed.eventName === 'evidence') {
            qa.stage = 'evidence'
            qa.evidence = parseJson(parsed.data, null)
          } else if (parsed.eventName === 'verification') {
            qa.verification = parseJson(parsed.data, null)
          } else if (parsed.eventName === 'metrics') {
            qa.metrics = parseJson(parsed.data, null)
            qa.answerSource = qa.metrics?.answer_source || ''
          } else if (parsed.eventName === 'done') {
            qa.done = true
            streamDone = true
            break
          } else if (parsed.eventName === 'error') {
            const payload = parseJson(parsed.data, null)
            qa.error = payload?.msg || parsed.data || '首页搜索生成失败'
            qa.done = true
            streamDone = true
            break
          }
        }
      }
    } catch (err) {
      if (err?.name === 'AbortError') return
      if (id === requestId.value) {
        const message = `首页搜索网络异常：${err.message}`
        keyword.error = keyword.list.length ? '' : message
        hybrid.error = hybrid.list.length ? '' : message
        qa.error = qa.answer ? '' : message
      }
    } finally {
      if (id === requestId.value) {
        keyword.loading = false
        hybrid.loading = false
        qa.loading = false
        qa.done = qa.done || Boolean(qa.answer || qa.error)
        if (!qa.error && !qa.answer && qa.done) {
          qa.error = '知识问答暂未生成内容，请查看下方检索结果。'
        }
      }
    }
  }

  function clear() {
    abort()
    requestId.value += 1
    reset()
  }

  return {
    keyword,
    hybrid,
    qa,
    loading,
    search,
    abort,
    clear,
    reset
  }
}

function applySearchPayload(state, rawData) {
  const payload = parseJson(rawData, null)
  state.loading = false
  if (!payload) {
    state.error = '检索结果解析失败'
    return
  }
  if (payload.code === 200) {
    const data = payload.data || {}
    state.list = data.list || []
    state.total = data.total ?? state.list.length
    state.tookMs = data.took_ms || 0
    state.cacheHit = Boolean(data.cache_hit)
    state.slaTimeout = Boolean(data.sla_timeout)
    state.error = state.slaTimeout && state.list.length === 0
      ? '检索响应超时，暂未返回结果。请稍后重试或简化查询词。'
      : ''
  } else {
    state.error = payload.msg || '检索失败，请稍后重试'
  }
}

function parseSseBlock(block) {
  let eventName = 'message'
  const dataLines = []
  for (const line of block.split(/\n|\r\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5))
    }
  }
  return { eventName, data: dataLines.join('\n').trim() }
}

function parseToken(data) {
  try {
    return JSON.parse(data)
  } catch {
    return data
  }
}

function parseJson(data, fallback) {
  try {
    return JSON.parse(data)
  } catch {
    return fallback
  }
}
