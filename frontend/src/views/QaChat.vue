<template>
  <div class="qa-chat-wrapper" :class="{ 'is-empty': messages.length === 0, 'has-drawer': drawerVisible }">
    <div class="qa-header">
      <div class="qa-header-left">
        <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        <span>知识问答</span>
      </div>
      <router-link to="/" class="btn-back">
        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        返回检索
      </router-link>
    </div>

    <!-- 对话区域 -->
    <div class="qa-body" ref="chatBodyRef" @scroll="updateScrollButton">
      <!-- 欢迎语（无历史时） -->
      <div v-if="messages.length === 0" class="qa-welcome">
        <div class="welcome-icon">
          <svg xmlns="http://www.w3.org/2000/svg" width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8"/><path d="M8 13h5"/></svg>
        </div>
        <h2>问知识库</h2>
        <p>输入一个问题，直接获得答案和引用来源。</p>
        <div class="qa-mode-switch" aria-label="问答模式">
          <span class="active">知识问答</span>
          <span>引用溯源</span>
        </div>
        <div class="example-questions">
          <span @click="fillExample('请简要介绍一下相关政策的主要内容')">相关政策的主要内容</span>
          <span @click="fillExample('具体申请流程是什么？')">具体申请流程</span>
          <span @click="fillExample('有哪些特殊情况需要注意？')">特殊情况说明</span>
        </div>
      </div>

      <!-- 历史消息 -->
      <div v-for="(msg, idx) in messages" :key="idx" class="message-row" :class="msg.role">
        <div class="message-bubble">
          <!-- 用户消息 -->
          <template v-if="msg.role === 'user'">
            <span class="user-text">{{ msg.content }}</span>
          </template>

          <!-- AI 消息 -->
          <template v-else>
            <!-- 思考中动画 -->
            <div v-if="!msg.content && !msg.done" class="thinking-dots qa-stage">
              <span></span><span></span><span></span>
              <em>{{ getStageText(msg) }}</em>
            </div>

            <!-- 回答内容（支持 Markdown 换行） -->
            <div v-if="msg.content" class="ai-answer" v-html="renderMarkdown(msg.content, msg)" @click="handleAnswerClick($event, msg)"></div>

            <!-- 
            <div
              v-if="shouldShowVerification(msg)"
              class="qa-verification"
              :class="{ 'is-risk': verificationHasRisk(msg.verification) }"
            >
              <span class="qa-verification-dot"></span>
              <span class="qa-verification-text">
                <strong>{{ verificationTitle(msg.verification) }}</strong>
                <em>{{ verificationDescription(msg.verification) }}</em>
              </span>
            </div>
            --> 

            <!-- 引用来源列表（按文件名去重，点击展开原文抽屉） -->
            <div v-if="shouldShowCitations(msg)" class="citations">
              <button
                type="button"
                class="citation-summary"
                @click="toggleCitations(msg)"
              >
                <span class="citation-summary-main">参考了 {{ deduplicateCitations(msg.citations).length }} 份资料</span>
                <span class="citation-summary-sub">{{ citationSummary(msg) }}</span>
                <span class="citation-summary-arrow">{{ msg.citationsExpanded ? '收起' : '展开' }}</span>
              </button>
              <div v-if="msg.citationsExpanded" class="citation-list">
                <div
                  v-for="c in deduplicateCitations(msg.citations)"
                  :key="c.file_name"
                  class="citation-item"
                  @click="openDrawer(c)"
                >
                  <span class="citation-index" @click.stop="openDrawer(c)">
                    <button
                      type="button"
                      class="citation-index-button"
                      @click.stop="openDrawer(c)"
                    >
                      [{{ c.index }}]
                    </button>
                  </span>
                  <span class="citation-text">
                    <span class="citation-name">{{ c.file_name }}</span>
                    <span v-if="citationPreview(c)" class="citation-preview">{{ citationPreview(c) }}</span>
                  </span>
                  <span class="citation-arrow">→</span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <button
      v-if="showScrollToBottom && messages.length > 0 && !drawerVisible"
      type="button"
      class="scroll-bottom-btn"
      aria-label="滚动到底部"
      @click="scrollToBottom(true)"
    >
      <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
    </button>

    <div class="qa-input-area">
      <textarea
        ref="inputRef"
        v-model="inputText"
        class="qa-textarea"
        aria-label="输入知识库问答问题"
        placeholder="输入您的问题，按 Enter 发送（Shift+Enter 换行）"
        :disabled="isStreaming"
        @keydown.enter.exact.prevent="sendQuestion"
        rows="2"
      ></textarea>
      <div class="qa-tool-row">
        <span class="qa-tool-chip">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
          知识库
        </span>
        <span class="qa-tool-chip">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/><path d="M11 8v6"/><path d="M8 11h6"/></svg>
          引用定位
        </span>
      </div>
      <button class="btn-send" :disabled="isStreaming || !inputText.trim()" :aria-label="isStreaming ? '正在生成回答' : '发送问题'" @click="sendQuestion">
        <svg v-if="!isStreaming" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
        <svg v-else xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
        {{ isStreaming ? '生成中…' : '发送' }}
      </button>
    </div>
    <!-- 右侧原文抽屉 -->
    <transition name="drawer">
      <div v-if="drawerVisible" class="drawer-overlay" @click.self="closeDrawer">
        <div class="drawer-panel">
          <div class="drawer-header">
            <div class="drawer-title-wrap">
              <span class="drawer-kicker">原文查看</span>
              <span class="drawer-title">{{ drawerCite?.file_name }}</span>
            </div>
            <button class="drawer-close" @click="closeDrawer" aria-label="关闭原文">
              <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
            </button>
          </div>
          <div class="drawer-body">
            <div v-if="drawerLoading" class="drawer-empty">加载原文中...</div>
            <div v-else-if="drawerError" class="drawer-empty">{{ drawerError }}</div>
            <div v-else-if="drawerChunks.length" class="drawer-chunk-list">
              <div
                v-for="chunk in drawerChunks"
                :key="chunk.chunk_id || chunk.chunk_index"
                class="drawer-chunk"
                :class="{ 'hit-chunk': isHitChunk(chunk) }"
              >
                <div class="drawer-chunk-meta">
                  <span>Chunk {{ chunk.chunk_index ?? '-' }}</span>
                  <span v-if="chunk.chunk_gran">{{ chunk.chunk_gran }}</span>
                  <span v-if="isHitChunk(chunk)" class="hit-label">命中引用</span>
                </div>
                <div class="drawer-content">{{ chunk.chunk_text }}</div>
              </div>
            </div>
            <div v-else-if="drawerContent" class="drawer-content">{{ drawerContent }}</div>
            <div v-else class="drawer-empty">暂无原文片段内容</div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'

const inputText = ref('')
const messages = ref([])
const isStreaming = ref(false)
const chatBodyRef = ref(null)
const inputRef = ref(null)
const showScrollToBottom = ref(false)

// 抽屉状态
const drawerVisible = ref(false)
const drawerCite = ref(null)
const drawerLoading = ref(false)
const drawerError = ref('')
const drawerContent = ref('')
const drawerChunks = ref([])
const drawerHitTexts = ref([])

async function openDrawer(cite) {
  drawerCite.value = cite
  drawerVisible.value = true
  drawerError.value = ''
  drawerContent.value = cite?.chunk_text || ''
  drawerChunks.value = normalizeCitationChunks(cite)
  drawerHitTexts.value = getCitationHitTexts(cite)

  if (!cite?.file_name && !cite?.doc_id) return

  drawerLoading.value = true
  try {
    const apiBase = import.meta.env.VITE_API_BASE_URL || ''
    const appCode = import.meta.env.VITE_APP_CODE || 'ADMIN_MASTER_KEY'
    const docId = encodeURIComponent(cite.doc_id || 'by-file-name')
    const fileName = encodeURIComponent(cite.file_name || '')
    const resp = await fetch(`${apiBase}/doc/${docId}/chunks?appCode=${encodeURIComponent(appCode)}&fileName=${fileName}`)
    const data = await resp.json()

    if (data.code === 200 && data.data) {
      const chunks = Array.isArray(data.data.chunks) ? [...data.data.chunks] : []
      chunks.sort((a, b) => normalizeChunkIndex(a.chunk_index) - normalizeChunkIndex(b.chunk_index))
      drawerChunks.value = chunks
      const originalContent = chunks
        .map(chunk => chunk.chunk_text || '')
        .filter(Boolean)
        .join('\n\n')

      if (originalContent) {
        drawerContent.value = originalContent
      } else if (!drawerContent.value) {
        drawerError.value = '暂无原文数据'
      }
    } else if (!drawerContent.value) {
      drawerError.value = data.msg || '原文加载失败'
    }
  } catch (err) {
    console.error('[qa citation original]', err)
    if (!drawerContent.value) {
      drawerError.value = '原文加载失败，请稍后重试'
    }
  } finally {
    drawerLoading.value = false
  }
}
function closeDrawer() {
  drawerVisible.value = false
  drawerLoading.value = false
  drawerError.value = ''
  drawerContent.value = ''
  drawerChunks.value = []
  drawerHitTexts.value = []
}
function normalizeChunkIndex(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
function getCitationHitTexts(cite) {
  if (!cite) return []
  const chunkHits = Array.isArray(cite.chunks)
    ? cite.chunks.map(chunk => chunk?.hit_text).filter(Boolean)
    : []
  const texts = Array.isArray(cite.hit_texts) ? [...cite.hit_texts] : [cite.chunk_text]
  texts.push(...chunkHits)
  return texts.filter(text => typeof text === 'string' && text.trim())
}
function normalizeCitationChunks(cite) {
  if (!Array.isArray(cite?.chunks)) return []
  return cite.chunks
    .map((chunk, idx) => ({
      chunk_id: chunk.chunk_id || chunk.chunk_index || idx,
      chunk_index: chunk.chunk_index ?? chunk.chunk_id ?? idx,
      chunk_gran: chunk.chunk_gran || chunk.section_path || '',
      chunk_text: chunk.chunk_text || chunk.hit_text || ''
    }))
    .filter(chunk => chunk.chunk_text)
}
function normalizeTextForMatch(text) {
  return String(text || '')
    .replace(/<\/?em[^>]*>/g, '')
    .replace(/\s+/g, '')
    .trim()
}
function isHitChunk(chunk) {
  const chunkText = normalizeTextForMatch(chunk?.chunk_text)
  if (!chunkText) return false
  return drawerHitTexts.value.some(hit => {
    const hitText = normalizeTextForMatch(hit)
    if (!hitText) return false
    return chunkText.includes(hitText) || hitText.includes(chunkText)
  })
}
function shouldShowCitations(msg) {
  return Boolean(
    msg
    && msg.done
    && !msg.thinking
    && msg.content
    && Array.isArray(msg.citations)
    && msg.citations.length > 0
  )
}
function shouldShowVerification(msg) {
  return Boolean(msg && msg.done && !msg.thinking && msg.verification)
}
function verificationHasRisk(verification) {
  if (!verification) return false
  const citation = verification.citation || {}
  const claim = verification.claim || {}
  return verification.pass === false
    || citation.pass === false
    || claim.pass === false
    || citation.has_invalid_citation === true
    || getVerificationWarnings(verification).length > 0
}
function verificationTitle(verification) {
  return verificationHasRisk(verification)
    ? '\u56de\u7b54\u9700\u8981\u590d\u6838'
    : '\u5f15\u7528\u5df2\u6821\u9a8c'
}
function verificationDescription(verification) {
  const notes = []
  const citation = verification?.citation || {}
  if (Array.isArray(citation.invalid_indexes) && citation.invalid_indexes.length) {
    notes.push(`\u5b58\u5728\u65e0\u6548\u5f15\u7528\u7f16\u53f7\uff1a${citation.invalid_indexes.join(', ')}`)
  }
  if (citation.citation_count > 0 && citation.has_answer_citation === false) {
    notes.push('\u7b54\u6848\u6ca1\u6709\u4f7f\u7528\u5df2\u53ec\u56de\u7684\u5f15\u7528\u7f16\u53f7')
  }
  for (const warning of getVerificationWarnings(verification)) {
    notes.push(verificationWarningText(warning))
  }
  const cleanNotes = notes.filter(Boolean)
  if (cleanNotes.length) return cleanNotes.slice(0, 2).join('\uff1b')
  return '\u5f15\u7528\u7f16\u53f7\u4e0e\u8bc1\u636e\u5217\u8868\u4e00\u81f4'
}
function getVerificationWarnings(verification) {
  const warnings = verification?.claim?.warnings
  return Array.isArray(warnings) ? warnings : []
}
function verificationWarningText(warning) {
  const textMap = {
    status_claim_not_supported_by_evidence: '\u4efb\u804c\u6216\u4efb\u547d\u7ed3\u8bba\u7f3a\u5c11\u5f3a\u8bc1\u636e\u652f\u6491',
    status_claim_may_overstate_weak_evidence: '\u53ef\u80fd\u628a\u62df\u4efb\u6216\u516c\u793a\u4fe1\u606f\u8868\u8ff0\u6210\u5df2\u53d1\u751f\u4e8b\u5b9e',
    status_claim_not_supported_by_cited_evidence: '\u72b6\u6001\u7c7b\u7ed3\u8bba\u672a\u88ab\u5f53\u53e5\u5f15\u7528\u76f4\u63a5\u652f\u6491',
    status_claim_may_overstate_weak_cited_evidence: '\u5f53\u53e5\u5f15\u7528\u53ea\u652f\u6491\u5f31\u72b6\u6001\u4fe1\u606f'
  }
  return textMap[warning] || String(warning || '')
}
function getStageText(msg) {
  const stage = msg?.stage || 'searching'
  if (stage === 'evidence') return '正在整理可引用的依据…'
  if (stage === 'generating') return '正在生成回答…'
  return '正在检索知识库…'
}
function toggleCitations(msg) {
  if (!msg) return
  msg.citationsExpanded = !msg.citationsExpanded
}
function citationSummary(msg) {
  const citations = deduplicateCitations(msg?.citations || [])
  if (!citations.length) return ''
  return citations.slice(0, 2).map(c => c.file_name).join('、') + (citations.length > 2 ? ` 等 ${citations.length} 份` : '')
}
function citationPreview(cite) {
  const text = getCitationHitTexts(cite)[0] || ''
  const oneLine = text.replace(/\s+/g, ' ').trim()
  return oneLine.length > 72 ? `${oneLine.slice(0, 72)}…` : oneLine
}
function handleAnswerClick(event, msg) {
  const target = event.target?.closest?.('[data-citation-index]')
  if (!target) return
  event.preventDefault()
  event.stopPropagation()
  const index = Number(target.dataset.citationIndex)
  if (!Number.isFinite(index)) return
  const grouped = deduplicateCitations(msg?.citations || [])
  const cite = grouped.find(c =>
    Number(c.index) === index || (Array.isArray(c.indexes) && c.indexes.some(i => Number(i) === index))
  ) || (msg?.citations || []).find(c => Number(c?.index) === index)
  if (cite) {
    openDrawer(cite)
  }
}

/**
 * 对引用列表按 file_name 去重，合并相同来源的多个 index
 * 返回: [{ file_name, indexes: [1,2,...], chunk_text, doc_id }]
 */
function deduplicateCitations(citations) {
  const map = new Map()
  for (const c of citations) {
    if (!c?.file_name) continue
    const ref = {
      file_name: c.file_name,
      index: c.index,
      indexes: [c.index],
      chunk_text: c.chunk_text,
      hit_texts: getCitationHitTexts(c),
      chunks: Array.isArray(c.chunks) ? c.chunks : [],
      doc_id: c.doc_id
    }
      if (!map.has(c.file_name)) {
        map.set(c.file_name, {
          file_name: c.file_name,
          index: c.index,
          indexes: [c.index],
          refs: [ref],
          chunk_text: c.chunk_text,
        hit_texts: getCitationHitTexts(c),
        chunks: Array.isArray(c.chunks) ? c.chunks : [],
        doc_id: c.doc_id
      })
    } else {
      const item = map.get(c.file_name)
      item.indexes.push(c.index)
      item.refs.push(ref)
      item.hit_texts.push(...getCitationHitTexts(c))
      if (Array.isArray(c.chunks)) {
        item.chunks.push(...c.chunks)
      }
      item.refs.sort((a, b) => Number(a.index) - Number(b.index))
      item.indexes.sort((a, b) => Number(a) - Number(b))
      item.index = item.indexes[0]
    }
  }
  return [...map.values()]
}

function buildCitationIndexMap(citations) {
  const indexMap = new Map()
  for (const group of deduplicateCitations(citations || [])) {
    for (const index of group.indexes) {
      indexMap.set(Number(index), Number(group.index))
    }
  }
  return indexMap
}

/**
 * 将简单 Markdown 格式转为 HTML（粗体、换行、列表项）
 * 不引入重量级依赖库，保持简洁
 */
function renderMarkdown(text, msg = null) {
  if (!text) return ''
  const citationIndexMap = buildCitationIndexMap(msg?.citations)
  
  let thinkHtml = ''
  let mainText = String(text)
  
  // Extract <think>...</think> block
  const thinkMatch = mainText.match(/<think>([\s\S]*?)(?:<\/think>|$)/)
  if (thinkMatch) {
    const thinkContent = thinkMatch[1].trim()
    if (thinkContent) {
      const isClosed = mainText.includes('</think>')
      // We will render it as a collapsible details block
      const safeThink = thinkContent
        .replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/\n/g, '<br>')
      thinkHtml = `<details class="think-block" ${!isClosed ? 'open' : ''}><summary>思考过程</summary><div class="think-content">${safeThink}${!isClosed ? '<span class="thinking-cursor">...</span>' : ''}</div></details>`
    }
    // Remove the entire think block from mainText
    mainText = mainText.replace(/<think>[\s\S]*?(?:<\/think>|$)/, '').trim()
  }

  const mainHtml = mainText
    // 转义 XSS 危险字符（先处理，避免后续替换引入风险）
    .replace(/</g, '&lt;').replace(/>/g, '&gt;')
    // 粗体 **...**
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // 斜体 *...*
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    // 无序列表行 - ...
    .replace(/^[-•] (.+)$/gm, '<li>$1</li>')
    // 引用索引 [1]，点击后打开对应原文
    .replace(/\[(\d+)\]/g, (_match, rawIndex) => {
      const displayIndex = citationIndexMap.get(Number(rawIndex)) || Number(rawIndex)
      return `<button type="button" class="answer-citation-link" data-citation-index="${displayIndex}" aria-label="查看引用 ${displayIndex}">${displayIndex}</button>`
    })
    // 换行转 <br>
    .replace(/\n/g, '<br>')
    
  return thinkHtml + mainHtml
}
/** 填入示例问题 */
function fillExample(q) {
  inputText.value = q
  inputRef.value && inputRef.value.focus()
}

/** 滚动到底部 */
function updateScrollButton() {
  const el = chatBodyRef.value
  if (!el) {
    showScrollToBottom.value = false
    return
  }
  const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollToBottom.value = distanceToBottom > 160
}

async function scrollToBottom(smooth = false) {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTo({
      top: chatBodyRef.value.scrollHeight,
      behavior: smooth ? 'smooth' : 'auto'
    })
    showScrollToBottom.value = false
  }
}

/** 发送问题并启动 SSE 流式接收 */
async function sendQuestion() {
  const question = inputText.value.trim()
  if (!question || isStreaming.value) return
  inputText.value = ''

  // 添加用户消息
  messages.value.push({ role: 'user', content: question })

  // 添加 AI 占位消息（thinking 状态）
  const aiMsg = {
    role: 'assistant',
    content: '',
    thinking: true,
    done: false,
    stage: 'searching',
    citations: [],
    verification: null,
    metrics: null,
    citationsExpanded: false
  }
  messages.value.push(aiMsg)
  await scrollToBottom()

  isStreaming.value = true

  try {
    const apiBase = import.meta.env.VITE_API_BASE_URL || ''
    const appCode = import.meta.env.VITE_APP_CODE || 'ADMIN_MASTER_KEY'

    // 使用 fetch 读取 SSE 流（EventSource 不支持 POST）
    const resp = await fetch(`${apiBase}/search/qa`, {
      method: 'POST',
      headers: { 
        'Content-Type': 'application/json',
        'X-Search-AppCode': appCode
      },
      body: JSON.stringify({ queryText: question, topK: 5, appCode })
    })

    if (!resp.ok) {
      aiMsg.content = `请求失败：${resp.status} ${resp.statusText}`
      aiMsg.thinking = false
      aiMsg.done = true
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let done = false

    // SSE 标准格式：每个事件由多行组成，事件之间以空行分隔
    // event: <eventName>
    // data: <payload>
    //  (空行)
    while (!done) {
      const { done: streamDone, value } = await reader.read()
      if (streamDone) break
      buffer += decoder.decode(value, { stream: true })

      // 按双换行分割事件块（SSE 规范：事件之间用空行分隔）
      const eventBlocks = buffer.split(/\n\n|\r\n\r\n/)
      // 最后一块可能不完整，保留到下次
      buffer = eventBlocks.pop() ?? ''

      for (const block of eventBlocks) {
        if (!block.trim()) continue

        // 解析事件块：通过行前缀提取 event 和 data
        let eventName = 'message'
        const dataLines = []

        for (const line of block.split(/\n|\r\n/)) {
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5))
          }
        }
        const dataStr = dataLines.join('\n').trim()

        if (!dataStr) continue

        // 根据事件名分发处理
        if (eventName === 'token') {
          aiMsg.stage = 'generating'
          // token 事件：data 是 JSON 编码的字符串片段
          try {
            aiMsg.content += JSON.parse(dataStr)
          } catch {
            aiMsg.content += dataStr
          }
          aiMsg.thinking = false
          await scrollToBottom()

        } else if (eventName === 'citations') {
          aiMsg.stage = 'generating'
          // citations 事件：data 是 JSON 格式的引用列表
          try {
            aiMsg.citations = JSON.parse(dataStr)
          } catch (err) {
            console.error('[qa citations parse]', err, dataStr)
          }

        } else if (eventName === 'evidence') {
          aiMsg.stage = 'evidence'
          try {
            aiMsg.evidence = JSON.parse(dataStr)
            console.debug('[qa evidence]', aiMsg.evidence)
          } catch (err) {
            console.error('[qa evidence parse]', err, dataStr)
          }

        } else if (eventName === 'verification') {
          try {
            aiMsg.verification = JSON.parse(dataStr)
            console.debug('[qa verification]', aiMsg.verification)
          } catch (err) {
            console.error('[qa verification parse]', err, dataStr)
          }

        } else if (eventName === 'metrics') {
          try {
            aiMsg.metrics = JSON.parse(dataStr)
            console.debug('[qa metrics]', aiMsg.metrics)
          } catch (err) {
            console.error('[qa metrics parse]', err, dataStr)
          }

        } else if (eventName === 'done') {
          aiMsg.thinking = false
          aiMsg.done = true
          done = true
          break

        } else if (eventName === 'error') {
          try {
            const err = JSON.parse(dataStr)
            aiMsg.content = `生成失败：${err.msg || dataStr}`
          } catch {
            aiMsg.content = `生成失败：${dataStr}`
          }
          aiMsg.thinking = false
          aiMsg.done = true
          done = true
          break
        }
      }
    }

    aiMsg.thinking = false
    aiMsg.done = true
  } catch (err) {
    aiMsg.content = `网络异常：${err.message}`
    aiMsg.thinking = false
    aiMsg.done = true
  } finally {
    isStreaming.value = false
    await scrollToBottom()
  }
}

</script>

<style scoped>
.qa-chat-wrapper {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f7f8fc;
  font-family: 'Inter', 'PingFang SC', sans-serif;
}

/* 顶部 Header */
.qa-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e8eaf0;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
  flex-shrink: 0;
}
.qa-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #3d50e0;
}
.btn-back {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #666;
  text-decoration: none;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid #ddd;
  transition: all .15s;
}
.btn-back:hover { background: #f0f1ff; color: #3d50e0; border-color: #c5caf7; }

/* 对话区域 */
.qa-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 欢迎区域 */
.qa-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  text-align: center;
  padding: 40px 20px;
  color: #555;
}
.welcome-icon { font-size: 52px; margin-bottom: 16px; }
.qa-welcome h2 { font-size: 22px; font-weight: 600; color: #2d3357; margin-bottom: 8px; }
.qa-welcome p { font-size: 14px; line-height: 1.7; color: #778; margin-bottom: 24px; }
.example-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}
.example-questions span {
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #dde0f5;
  border-radius: 20px;
  font-size: 13px;
  color: #3d50e0;
  cursor: pointer;
  transition: all .15s;
  box-shadow: 0 1px 3px rgba(0,0,0,.05);
}
.example-questions span:hover { background: #f0f2ff; border-color: #9aa3e8; }

/* 消息行 */
.message-row {
  display: flex;
}
.message-row.user { justify-content: flex-end; }
.message-row.assistant { justify-content: flex-start; }
.message-bubble {
  max-width: 78%;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.message-row.user .message-bubble {
  background: #3d50e0;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.message-row.assistant .message-bubble {
  background: #fff;
  color: #333;
  border-bottom-left-radius: 4px;
  border: 1px solid #eaecf5;
}

/* 思考动画 */
.thinking-dots {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #888;
  font-size: 13px;
}
.thinking-dots span {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: #a0a8d0;
  animation: thinking-bounce 1.1s infinite ease-in-out;
}
.thinking-dots span:nth-child(2) { animation-delay: .16s; }
.thinking-dots span:nth-child(3) { animation-delay: .32s; }
@keyframes thinking-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: .4; }
  40% { transform: scale(1); opacity: 1; }
}
.thinking-dots em { font-style: normal; margin-left: 6px; }

/* AI 回答 */
.ai-answer :deep(strong) { font-weight: 600; }
.ai-answer :deep(li) { margin-left: 16px; list-style: disc; }

/* 思考过程面板 */
.ai-answer :deep(.think-block) {
  margin: 8px 0 16px 0;
  border-radius: 8px;
  background: #f8f9fc;
  border: 1px solid #eaecf5;
  overflow: hidden;
}
.ai-answer :deep(.think-block summary) {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  color: #6b7280;
  cursor: pointer;
  user-select: none;
  display: flex;
  align-items: center;
  outline: none;
}
.ai-answer :deep(.think-block summary::before) {
  content: '▶';
  display: inline-block;
  margin-right: 6px;
  font-size: 10px;
  transition: transform 0.2s;
}
.ai-answer :deep(.think-block[open] summary::before) {
  transform: rotate(90deg);
}
.ai-answer :deep(.think-content) {
  padding: 12px;
  border-top: 1px solid #eaecf5;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
  background: #fbfbfc;
}
.ai-answer :deep(.thinking-cursor) {
  display: inline-block;
  margin-left: 4px;
  animation: blink 1s step-end infinite;
}
@keyframes blink { 50% { opacity: 0; } }

/* 引用来源 */
.citations {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #eee;
}
.citations-title { font-size: 12px; color: #888; margin-bottom: 6px; }
.citation-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12.5px;
  color: #5566aa;
  margin-bottom: 4px;
  cursor: pointer;
  padding: 5px 8px;
  border-radius: 6px;
  transition: background .15s;
}
.citation-item:hover { background: #f0f2ff; }
.citation-index { font-weight: 600; color: #3d50e0; flex-shrink: 0; }
.citation-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.citation-arrow { color: #aab; font-size: 14px; flex-shrink: 0; }

/* ── 右侧原文抽屉 ── */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,.35);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.drawer-panel {
  width: min(480px, 90vw);
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 20px rgba(0,0,0,.12);
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #eaecf5;
  background: #f9faff;
  flex-shrink: 0;
}
.drawer-title {
  font-size: 14px;
  font-weight: 600;
  color: #2d3357;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: calc(100% - 40px);
}
.drawer-close {
  background: none;
  border: none;
  font-size: 18px;
  color: #888;
  cursor: pointer;
  padding: 4px 6px;
  line-height: 1;
  border-radius: 4px;
  flex-shrink: 0;
  transition: color .15s, background .15s;
}
.drawer-close:hover { color: #333; background: #eee; }
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.drawer-chunk-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.drawer-chunk {
  padding: 14px 16px;
  border: 1px solid #e7e9f3;
  border-radius: 8px;
  background: #fff;
  transition: border-color .15s, box-shadow .15s, background .15s;
}
.drawer-chunk.hit-chunk {
  border-color: #f0b429;
  background: #fff8e6;
  box-shadow: 0 0 0 2px rgba(240, 180, 41, .18);
}
.drawer-chunk-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #778;
}
.hit-label {
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 999px;
  color: #8a5a00;
  background: #ffe8a3;
  font-weight: 600;
}
.drawer-content {
  font-size: 14px;
  line-height: 1.9;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}
.drawer-empty {
  font-size: 13px;
  color: #aaa;
  text-align: center;
  margin-top: 40px;
}

/* 抽屉滑入动画 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity .25s;
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-active .drawer-panel,
.drawer-leave-active .drawer-panel {
  transition: transform .25s cubic-bezier(.4,0,.2,1);
}
.drawer-enter-from .drawer-panel,
.drawer-leave-to .drawer-panel {
  transform: translateX(100%);
}


/* 输入区域 */
.qa-input-area {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 20px;
  background: #fff;
  border-top: 1px solid #e8eaf0;
  flex-shrink: 0;
}
.qa-textarea {
  flex: 1;
  resize: none;
  border: 1.5px solid #dde0f5;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  font-family: inherit;
  color: #333;
  outline: none;
  transition: border-color .2s;
  line-height: 1.6;
}
.qa-textarea:focus { border-color: #3d50e0; }
.qa-textarea:disabled { background: #f5f5f5; cursor: not-allowed; }
.btn-send {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  background: #3d50e0;
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all .15s;
  flex-shrink: 0;
  white-space: nowrap;
}
.btn-send:hover:not(:disabled) { background: #2d42d0; }
.btn-send:disabled { opacity: .5; cursor: not-allowed; }

/* === AI search refresh === */
.qa-chat-wrapper {
  position: relative;
  min-height: 100vh;
  background:
    radial-gradient(circle at 50% 0%, rgba(61, 80, 224, .09), transparent 42%),
    linear-gradient(180deg, #f8fbff 0%, #f4f7fb 45%, #f8fafc 100%);
  color: #1f2937;
}

.qa-chat-wrapper::before {
  content: '';
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(61, 80, 224, .035), transparent 44%, rgba(15, 23, 42, .025)),
    linear-gradient(180deg, rgba(255, 255, 255, .5), transparent 28%);
}

.qa-header {
  position: sticky;
  top: 0;
  z-index: 30;
  height: 60px;
  padding: 0 28px;
  background: rgba(255, 255, 255, .78);
  border-bottom: 1px solid rgba(226, 232, 240, .78);
  box-shadow: none;
  backdrop-filter: blur(16px);
}

.qa-header-left {
  color: #172554;
  font-size: 15px;
  font-weight: 700;
}

.qa-header-left svg {
  width: 18px;
  height: 18px;
  padding: 8px;
  color: #3150d4;
  background: rgba(61, 80, 224, .09);
  border-radius: 12px;
  box-sizing: content-box;
}

.btn-back {
  color: #475569;
  border-radius: 999px;
  padding: 8px 12px;
  transition: color .18s, background .18s;
}

.btn-back:hover {
  color: #1d4ed8;
  background: rgba(61, 80, 224, .08);
}

.qa-body {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 920px;
  margin: 0 auto;
  padding: 56px 20px 168px;
  gap: 22px;
}

.qa-welcome {
  width: 100%;
  min-height: calc(100vh - 260px);
  padding: 24px 0;
  justify-content: center;
}

.welcome-icon {
  width: 58px;
  height: 58px;
  margin-bottom: 20px;
  color: #3150d4;
  background: rgba(255, 255, 255, .92);
  border: 1px solid rgba(203, 213, 225, .75);
  border-radius: 18px;
  box-shadow: 0 18px 42px rgba(30, 64, 175, .12);
}

.welcome-icon svg {
  width: 28px;
  height: 28px;
}

.qa-welcome h2 {
  margin: 0 0 10px;
  color: #0f172a;
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 760;
  line-height: 1.12;
}

.qa-welcome p {
  max-width: 520px;
  margin: 0 auto 24px;
  color: #64748b;
  font-size: 15px;
  line-height: 1.8;
}

.example-questions {
  max-width: 720px;
  gap: 10px;
  justify-content: center;
}

.example-questions span {
  padding: 9px 14px;
  color: #334155;
  background: rgba(255, 255, 255, .86);
  border: 1px solid rgba(203, 213, 225, .72);
  border-radius: 999px;
  box-shadow: 0 10px 26px rgba(15, 23, 42, .06);
  transition: transform .18s, border-color .18s, color .18s, box-shadow .18s;
}

.example-questions span:hover {
  color: #1d4ed8;
  border-color: rgba(61, 80, 224, .3);
  box-shadow: 0 14px 30px rgba(30, 64, 175, .12);
  transform: translateY(-1px);
}

.message-row {
  width: 100%;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  justify-content: flex-start;
}

.message-bubble {
  box-shadow: none;
}

.message-row.user .message-bubble {
  max-width: min(680px, 88%);
  padding: 12px 16px;
  color: #fff;
  background: linear-gradient(135deg, #2f56e6, #1d4ed8);
  border-radius: 18px 18px 4px 18px;
  box-shadow: 0 12px 26px rgba(37, 99, 235, .2);
}

.message-row.assistant .message-bubble {
  width: min(820px, 100%);
  max-width: 100%;
  padding: 22px 24px;
  background: rgba(255, 255, 255, .9);
  border: 1px solid rgba(226, 232, 240, .92);
  border-radius: 20px;
  box-shadow: 0 18px 45px rgba(15, 23, 42, .065);
  backdrop-filter: blur(12px);
}

.user-text {
  color: #fff;
  line-height: 1.7;
  white-space: pre-wrap;
}

.ai-answer {
  color: #273244;
  font-size: 15px;
  line-height: 1.9;
}

.thinking-dots {
  min-width: 72px;
  padding: 2px 0;
}

.thinking-dots span {
  background: #3150d4;
  box-shadow: 0 0 0 4px rgba(61, 80, 224, .08);
}

.citations {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(226, 232, 240, .86);
}

.citations-title {
  margin-bottom: 10px;
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.citation-item {
  min-height: 44px;
  margin-bottom: 8px;
  padding: 10px 12px;
  align-items: center;
  color: #334155;
  background: #f8fafc;
  border: 1px solid rgba(226, 232, 240, .9);
  border-radius: 12px;
  transition: transform .18s, border-color .18s, background .18s, box-shadow .18s;
}

.citation-item:hover {
  background: #eef4ff;
  border-color: rgba(61, 80, 224, .28);
  box-shadow: 0 10px 24px rgba(30, 64, 175, .1);
  transform: translateY(-1px);
}

.citation-index {
  min-width: 28px;
  height: 24px;
  margin-right: 10px;
  padding: 0 7px;
  color: #1d4ed8;
  background: rgba(61, 80, 224, .1);
  border-radius: 999px;
  font-size: 12px;
  line-height: 24px;
  text-align: center;
}

.citation-index-button {
  padding: 0 2px;
  color: inherit;
  background: transparent;
  border: 0;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.citation-index-button:hover {
  color: #123db7;
  text-decoration: underline;
}

.citation-file {
  color: #1f2937;
  font-weight: 600;
}

.citation-page {
  color: #64748b;
}

.citation-arrow {
  color: #94a3b8;
  font-size: 16px;
}

.qa-input-area {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 25;
  max-width: 900px;
  margin: 0 auto;
  padding: 18px 20px 22px;
  background: linear-gradient(180deg, rgba(248, 251, 255, 0), rgba(248, 251, 255, .94) 28%, rgba(248, 251, 255, .99));
  border-top: 0;
}

.qa-input-area::before {
  content: '';
  position: absolute;
  left: 20px;
  right: 20px;
  top: 12px;
  bottom: 16px;
  z-index: -1;
  background: rgba(255, 255, 255, .9);
  border: 1px solid rgba(226, 232, 240, .9);
  border-radius: 22px;
  box-shadow: 0 18px 45px rgba(15, 23, 42, .1);
  backdrop-filter: blur(14px);
}

.qa-textarea {
  min-height: 56px;
  max-height: 128px;
  padding: 15px 8px 13px 16px;
  color: #0f172a;
  background: transparent;
  border: 0;
  border-radius: 18px;
  line-height: 1.6;
}

.qa-textarea:focus {
  border-color: transparent;
}

.qa-textarea::placeholder {
  color: #94a3b8;
}

.qa-textarea:disabled {
  background: transparent;
}

.btn-send {
  min-width: 92px;
  height: 42px;
  padding: 0 16px;
  justify-content: center;
  background: #1d4ed8;
  border-radius: 14px;
  box-shadow: 0 12px 24px rgba(37, 99, 235, .22);
}

.btn-send:hover:not(:disabled) {
  background: #1e40af;
  transform: translateY(-1px);
}

.btn-send:disabled {
  color: #94a3b8;
  background: #e2e8f0;
  box-shadow: none;
  opacity: 1;
}

.drawer-overlay {
  background: rgba(15, 23, 42, .28);
  backdrop-filter: blur(2px);
}

.drawer-panel {
  width: min(640px, 94vw);
  background: #f8fafc;
  box-shadow: -20px 0 48px rgba(15, 23, 42, .14);
}

.drawer-header {
  height: 58px;
  background: rgba(255, 255, 255, .94);
  border-bottom: 1px solid rgba(226, 232, 240, .9);
  backdrop-filter: blur(12px);
}

.drawer-title {
  color: #0f172a;
  font-weight: 700;
}

.drawer-body {
  padding: 18px;
}

.drawer-chunk {
  padding: 16px;
  background: #fff;
  border: 1px solid rgba(226, 232, 240, .92);
  border-radius: 14px;
  box-shadow: 0 10px 24px rgba(15, 23, 42, .045);
}

.drawer-chunk.hit-chunk {
  background: #fffbeb;
  border-color: #f2c94c;
  box-shadow: 0 0 0 2px rgba(242, 201, 76, .18), 0 12px 28px rgba(161, 98, 7, .08);
}

.hit-label {
  color: #8a5a00;
  background: #ffe8a3;
  border-radius: 999px;
}

.chunk-index {
  color: #475569;
  font-weight: 700;
}

.drawer-content {
  color: #334155;
}

@media (max-width: 768px) {
  .qa-header {
    height: 56px;
    padding: 0 16px;
  }

  .qa-body {
    padding: 36px 14px 148px;
  }

  .qa-welcome {
    min-height: calc(100vh - 230px);
  }

  .qa-welcome h2 {
    font-size: 34px;
  }

  .message-row.user .message-bubble,
  .message-row.assistant .message-bubble {
    max-width: 100%;
  }

  .message-row.assistant .message-bubble {
    padding: 18px;
  }

  .qa-input-area {
    padding: 14px 12px 16px;
  }

  .qa-input-area::before {
    left: 12px;
    right: 12px;
    bottom: 10px;
  }

  .btn-send {
    min-width: 72px;
    padding: 0 12px;
  }

  .drawer-panel {
    width: 100vw;
  }
}

/* === Reference-style minimal layout === */
.qa-chat-wrapper {
  background: #fff;
  color: #111827;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: #cfd4dc transparent;
}

.qa-chat-wrapper::before {
  display: none;
}

.qa-header {
  position: fixed;
  top: 14px;
  left: 8px;
  right: auto;
  z-index: 40;
  width: auto;
  height: 42px;
  padding: 0;
  gap: 12px;
  background: transparent;
  border: 0;
  box-shadow: none;
  backdrop-filter: none;
}

.qa-header-left {
  gap: 0;
  height: 36px;
}

.qa-header-left span {
  display: none;
}

.qa-header-left svg {
  width: 24px;
  height: 24px;
  padding: 0;
  color: #3161ff;
  background: transparent;
  border-radius: 0;
}

.btn-back {
  height: 38px;
  padding: 0 14px;
  gap: 8px;
  color: #111827;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, .08);
}

.btn-back svg {
  width: 15px;
  height: 15px;
}

.btn-back:hover {
  color: #3161ff;
  background: #fff;
  border-color: #c7d2fe;
}

.qa-body {
  max-width: 790px;
  padding: 96px 20px 164px;
}

.qa-chat-wrapper.is-empty .qa-body {
  min-height: 100vh;
  padding: 0 20px;
  justify-content: center;
  transform: translateY(-118px);
}

.qa-chat-wrapper.is-empty .qa-welcome {
  display: grid;
  grid-template-columns: auto auto;
  align-items: center;
  justify-content: center;
  column-gap: 10px;
  width: 100%;
  min-height: 0;
  padding: 0;
}

.qa-chat-wrapper.is-empty .welcome-icon {
  grid-column: 1;
  grid-row: 1;
  width: 28px;
  height: 28px;
  margin: 0;
  color: #3161ff;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.qa-chat-wrapper.is-empty .welcome-icon svg {
  width: 28px;
  height: 28px;
  stroke-width: 2;
}

.qa-chat-wrapper.is-empty .qa-welcome h2 {
  grid-column: 2;
  grid-row: 1;
  margin: 0;
  color: #020617;
  font-size: 24px;
  font-weight: 760;
  line-height: 1.2;
}

.qa-chat-wrapper.is-empty .qa-welcome p,
.qa-chat-wrapper.is-empty .example-questions {
  display: none;
}

.qa-mode-switch {
  display: none;
}

.qa-chat-wrapper.is-empty .qa-mode-switch {
  display: none;
}

.qa-mode-switch span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 130px;
  height: 36px;
  padding: 0 20px;
  color: #111827;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 520;
}

.qa-mode-switch span.active {
  color: #3161ff;
  background: #f0f5ff;
  box-shadow: inset 0 0 0 1px #b9caff;
}

.qa-input-area {
  display: grid;
  grid-template-columns: 1fr auto;
  grid-template-rows: auto auto;
  align-items: end;
  gap: 14px 10px;
  max-width: 790px;
  padding: 14px 14px 12px;
  background: #fff;
  border: 1px solid #e6eaf1;
  border-radius: 18px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, .055);
}

.qa-input-area::before {
  display: none;
}

.qa-chat-wrapper.is-empty .qa-input-area {
  position: fixed;
  top: 50%;
  left: 50%;
  right: auto;
  bottom: auto;
  width: min(776px, calc(100vw - 40px));
  max-width: 776px;
  transform: translate(-50%, 30px);
  box-shadow: 0 10px 28px rgba(15, 23, 42, .06);
}

.qa-textarea {
  grid-column: 1 / span 2;
  width: 100%;
  min-height: 42px;
  max-height: 130px;
  padding: 0 4px;
  color: #111827;
  background: transparent;
  border: 0;
  border-radius: 0;
  font-size: 16px;
  line-height: 1.65;
}

.qa-textarea::placeholder {
  color: #9ca3af;
}

.qa-tool-row {
  grid-column: 1;
  grid-row: 2;
  display: flex;
  align-items: center;
  gap: 10px;
}

.qa-tool-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 13px;
  gap: 6px;
  color: #3161ff;
  background: #f5f8ff;
  border: 1px solid #b9caff;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 560;
  cursor: default;
}

.qa-tool-chip svg {
  flex: 0 0 auto;
}

.btn-send {
  grid-column: 2;
  grid-row: 2;
  width: 38px;
  min-width: 38px;
  height: 38px;
  padding: 0;
  justify-content: center;
  color: #fff;
  background: #9fb5ff;
  border-radius: 12px;
  box-shadow: none;
  font-size: 0;
  gap: 0;
}

.btn-send svg {
  width: 17px;
  height: 17px;
}

.btn-send:hover:not(:disabled) {
  background: #3161ff;
  transform: none;
}

.btn-send:disabled {
  color: #fff;
  background: #b5c5ff;
  opacity: 1;
}

.message-row.user .message-bubble {
  max-width: min(620px, 86%);
  background: #3161ff;
  border-radius: 18px 18px 6px 18px;
  box-shadow: none;
}

.message-row.assistant .message-bubble {
  width: 100%;
  padding: 20px 0;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  backdrop-filter: none;
}

.ai-answer {
  --qa-citation-size: 22px;
  --qa-citation-bg: #eef1f5;
  --qa-citation-bg-hover: #e3e8ef;
  --qa-citation-text: #6b7280;
  --qa-citation-text-hover: #374151;
  --qa-citation-ring: rgba(100, 116, 139, .28);
  color: #111827;
  font-size: 15px;
  line-height: 1.95;
}

.ai-answer :deep(.answer-citation-link) {
  appearance: none;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: var(--qa-citation-size) !important;
  width: auto;
  height: var(--qa-citation-size) !important;
  min-height: 0 !important;
  margin: 0 3px;
  padding: 0 7px;
  color: var(--qa-citation-text);
  background: var(--qa-citation-bg);
  border: 1px solid transparent;
  border-radius: 999px;
  font: inherit;
  font-size: 12px;
  font-weight: 750;
  line-height: var(--qa-citation-size) !important;
  cursor: pointer;
  vertical-align: .18em;
  box-shadow: inset 0 -1px 0 rgba(15, 23, 42, .04);
  transition: background .15s ease, border-color .15s ease, box-shadow .15s ease, color .15s ease;
}

.ai-answer :deep(.answer-citation-link:hover) {
  background: var(--qa-citation-bg-hover);
  color: var(--qa-citation-text-hover);
  box-shadow: inset 0 -1px 0 rgba(15, 23, 42, .06);
  text-decoration: none;
}

.ai-answer :deep(.answer-citation-link:focus-visible) {
  outline: 2px solid var(--qa-citation-ring);
  outline-offset: 2px;
}

.qa-verification {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin-top: 14px;
  padding: 10px 12px;
  color: #25635d;
  background: #f2fbf8;
  border: 1px solid #caeee3;
  border-radius: 12px;
}

.qa-verification.is-risk {
  color: #8a4b10;
  background: #fff8ec;
  border-color: #f3d49c;
}

.qa-verification-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  margin-top: 7px;
  background: currentColor;
  border-radius: 999px;
}

.qa-verification-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qa-verification-text strong {
  color: inherit;
  font-size: 13px;
  line-height: 1.35;
}

.qa-verification-text em {
  color: #64748b;
  font-size: 12px;
  font-style: normal;
  line-height: 1.55;
}

.citations {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #eef0f4;
}

.citation-item {
  background: #fff;
  border-color: #e5e7eb;
  box-shadow: none;
}

.citation-item:hover {
  background: #f8fbff;
  border-color: #b9caff;
  box-shadow: none;
}

@media (max-width: 768px) {
  .qa-header {
    top: 10px;
    left: 10px;
    height: 38px;
  }

  .btn-back {
    height: 36px;
    padding: 0 12px;
  }

  .qa-body {
    padding: 82px 16px 148px;
  }

  .qa-chat-wrapper.is-empty .qa-body {
    transform: translateY(-100px);
  }

  .qa-chat-wrapper.is-empty .qa-welcome h2 {
    font-size: 22px;
  }

  .qa-mode-switch span {
    min-width: 108px;
    height: 34px;
    padding: 0 14px;
    font-size: 13px;
  }

  .qa-chat-wrapper.is-empty .qa-input-area {
    width: calc(100vw - 28px);
    transform: translate(-50%, 26px);
  }

  .qa-input-area {
    left: 14px;
    right: 14px;
    max-width: none;
    padding: 13px;
    border-radius: 20px;
  }

  .qa-textarea {
    font-size: 15px;
  }

  .qa-tool-chip {
    height: 32px;
    padding: 0 10px;
    font-size: 12px;
  }
}

/* === Header and hero polish === */
.qa-header {
  top: 18px;
  left: auto;
  right: 24px;
  height: 40px;
}

.qa-header-left {
  display: none;
}

.btn-back {
  height: 40px;
  padding: 0 16px;
  color: #1f2937;
  background: #fff;
  border: 1px solid #edf0f5;
  border-radius: 999px;
  box-shadow: 0 10px 28px rgba(15, 23, 42, .07);
  font-size: 13px;
}

.btn-back:hover {
  color: #2456ff;
  background: #f8fbff;
  border-color: #d8e2ff;
  box-shadow: 0 12px 30px rgba(49, 97, 255, .1);
}

.qa-chat-wrapper.is-empty .qa-body {
  transform: translateY(-58px);
}

.qa-chat-wrapper.is-empty .qa-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
}

.qa-chat-wrapper.is-empty .welcome-icon {
  width: 48px;
  height: 48px;
  margin: 0 0 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3161ff;
  background: #f3f6ff;
  border: 1px solid #e0e8ff;
  border-radius: 14px;
  box-shadow: none;
}

.qa-chat-wrapper.is-empty .welcome-icon svg {
  width: 25px;
  height: 25px;
  stroke-width: 2.1;
}

.qa-chat-wrapper.is-empty .qa-welcome h2 {
  margin: 0;
  color: #050816;
  font-size: 30px;
  font-weight: 760;
  line-height: 1.18;
  letter-spacing: 0;
}

.qa-chat-wrapper.is-empty .qa-mode-switch {
  margin-top: 26px;
}

.qa-chat-wrapper.is-empty .qa-input-area {
  transform: translate(-50%, 26px);
}

:global(html) {
  scrollbar-width: thin;
  scrollbar-color: #cfd4dc transparent;
}

:global(body) {
  scrollbar-width: thin;
  scrollbar-color: #cfd4dc transparent;
}

:global(::-webkit-scrollbar) {
  width: 8px;
  height: 8px;
}

:global(::-webkit-scrollbar-track) {
  background: transparent;
}

:global(::-webkit-scrollbar-thumb) {
  background: #cfd4dc;
  border: 2px solid #fff;
  border-radius: 999px;
}

:global(::-webkit-scrollbar-thumb:hover) {
  background: #b9c0ca;
}

@media (max-width: 768px) {
  .qa-header {
    top: 12px;
    right: 14px;
    left: auto;
  }

  .btn-back {
    height: 38px;
    padding: 0 14px;
  }

  .qa-chat-wrapper.is-empty .qa-body {
    transform: translateY(-52px);
  }

  .qa-chat-wrapper.is-empty .welcome-icon {
    width: 44px;
    height: 44px;
  }

  .qa-chat-wrapper.is-empty .qa-welcome h2 {
    font-size: 26px;
  }

  .qa-chat-wrapper.is-empty .qa-input-area {
    transform: translate(-50%, 24px);
  }
}

/* === QA / original reader split view === */
.qa-chat-wrapper:not(.is-empty) {
  background:
    linear-gradient(90deg, #fbfcff 0%, #fff 52%, #f8fafc 100%);
}

.qa-chat-wrapper:not(.is-empty) .qa-body {
  max-width: 820px;
  padding-top: 92px;
}

.qa-chat-wrapper.has-drawer .qa-body,
.qa-chat-wrapper.has-drawer .qa-input-area {
  transition: opacity .2s ease, transform .2s ease, filter .2s ease;
}

.qa-chat-wrapper.has-drawer .qa-body {
  opacity: .72;
  filter: saturate(.88);
}

.qa-chat-wrapper.has-drawer .qa-input-area {
  opacity: .82;
}

.message-row.assistant .message-bubble {
  position: relative;
}

.message-row.assistant .message-bubble::before {
  content: '';
  position: absolute;
  left: -18px;
  top: 26px;
  bottom: 26px;
  width: 2px;
  background: #e8edf5;
  border-radius: 999px;
}

.citations {
  border-top-color: #edf1f7;
}

.citations-title {
  color: #475569;
  font-size: 13px;
}

.citation-item {
  min-height: 50px;
  padding: 12px 14px;
  background: #fff;
  border-color: #e6ebf2;
  border-radius: 14px;
}

.citation-index {
  background: #f1f5ff;
}

.drawer-overlay {
  display: flex;
  justify-content: flex-end;
  background:
    linear-gradient(90deg, rgba(248, 250, 252, .78) 0%, rgba(248, 250, 252, .62) 46%, rgba(248, 250, 252, 0) 72%);
  backdrop-filter: blur(1.5px);
}

.drawer-panel {
  width: min(720px, 46vw);
  min-width: 620px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
  border-left: 1px solid #e2e8f0;
  box-shadow: -18px 0 42px rgba(15, 23, 42, .11);
}

.drawer-header {
  height: auto;
  min-height: 76px;
  padding: 18px 24px 16px;
  align-items: center;
  background: rgba(255, 255, 255, .96);
  border-bottom: 1px solid #e6ebf2;
  box-shadow: 0 1px 0 rgba(255, 255, 255, .8);
}

.drawer-title-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.drawer-kicker {
  color: #3161ff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.drawer-title {
  max-width: 100%;
  color: #0f172a;
  font-size: 16px;
  font-weight: 760;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-close {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  background: #f8fafc;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
  transition: background .16s, color .16s, border-color .16s;
}

.drawer-close:hover {
  color: #111827;
  background: #fff;
  border-color: #cfd8e6;
}

.drawer-body {
  flex: 1;
  padding: 20px 22px 28px;
  overflow-y: auto;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, .72), rgba(248, 250, 252, 1) 120px);
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.drawer-body::-webkit-scrollbar {
  width: 8px;
}

.drawer-body::-webkit-scrollbar-track {
  background: transparent;
}

.drawer-body::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border: 2px solid #f8fafc;
  border-radius: 999px;
}

.drawer-chunk-list {
  gap: 14px;
}

.drawer-chunk {
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #e4eaf2;
  border-radius: 16px;
  box-shadow: none;
}

.drawer-chunk.hit-chunk {
  background: #fff9e8;
  border-color: #f2c94c;
  box-shadow: inset 3px 0 0 #f2c94c;
}

.drawer-chunk-meta {
  margin-bottom: 10px;
  color: #64748b;
}

.drawer-content {
  color: #243044;
  font-size: 14px;
  line-height: 2;
}

.hit-label {
  padding: 4px 9px;
  color: #8a5a00;
  background: #fff0bd;
  border-radius: 999px;
  font-size: 12px;
}

.drawer-empty {
  margin: 80px auto 0;
  max-width: 320px;
  padding: 24px;
  color: #64748b;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 16px;
}

@media (max-width: 1180px) {
  .drawer-panel {
    width: min(680px, 58vw);
    min-width: 540px;
  }
}

@media (max-width: 768px) {
  .qa-chat-wrapper.has-drawer .qa-body,
  .qa-chat-wrapper.has-drawer .qa-input-area {
    opacity: 1;
    filter: none;
  }

  .drawer-overlay {
    background: rgba(15, 23, 42, .18);
    backdrop-filter: blur(2px);
  }

  .drawer-panel {
    width: 100vw;
    min-width: 0;
  }

  .drawer-header {
    padding: 16px 18px 14px;
  }

  .drawer-body {
    padding: 16px 14px 24px;
  }
}

/* === Floating scroll cue, matching reference style === */
.qa-body {
  scrollbar-width: none;
}

.qa-body::-webkit-scrollbar {
  display: none;
}

.scroll-bottom-btn {
  position: fixed;
  left: 50%;
  bottom: 152px;
  z-index: 31;
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #111827;
  background: rgba(255, 255, 255, .96);
  border: 1px solid #e6eaf1;
  border-radius: 50%;
  box-shadow: 0 8px 22px rgba(15, 23, 42, .08);
  transform: translateX(330px);
  cursor: pointer;
  transition: background .16s ease, border-color .16s ease, box-shadow .16s ease, transform .16s ease;
}

.scroll-bottom-btn:hover {
  background: #fff;
  border-color: #d6dde8;
  box-shadow: 0 10px 26px rgba(15, 23, 42, .12);
  transform: translateX(330px) translateY(-1px);
}

.scroll-bottom-btn svg {
  width: 18px;
  height: 18px;
}

@media (max-width: 900px) {
  .scroll-bottom-btn {
    right: 28px;
    left: auto;
    transform: none;
  }

  .scroll-bottom-btn:hover {
    transform: translateY(-1px);
  }
}

@media (max-width: 768px) {
  .scroll-bottom-btn {
    right: 28px;
    bottom: 138px;
  }
}

/* === UI/UX Pro Max accessibility and density pass === */
.qa-chat-wrapper {
  min-height: 100dvh;
}

.qa-body {
  width: 100%;
}

.qa-textarea {
  min-height: 48px;
  font-size: 16px;
}

.qa-textarea:focus-visible {
  box-shadow: none;
}

.qa-input-area:focus-within {
  border-color: rgba(36, 86, 214, 0.28);
  box-shadow: var(--shadow-glow);
}

.qa-tool-chip,
.btn-back,
.btn-send,
.citation-item,
.drawer-close {
  min-height: 44px;
}

.qa-tool-chip {
  cursor: default;
  user-select: none;
}

.btn-send {
  background: var(--primary);
}

.btn-send:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-send:disabled {
  background: #cbd5e1;
}

.message-row.user .message-bubble {
  background: var(--primary);
}

.message-row.assistant .message-bubble::before {
  background: rgba(15, 138, 157, 0.2);
}

.citation-item,
.drawer-chunk {
  border-radius: var(--radius-lg);
}

@media (max-width: 640px) {
  .qa-chat-wrapper.is-empty .qa-input-area {
    width: calc(100vw - 24px);
  }

  .qa-tool-row {
    overflow-x: auto;
  }

  .qa-tool-chip {
    flex: 0 0 auto;
  }

  .message-row.user .message-bubble {
    max-width: 94%;
  }
}

/* === Polished answer sources === */
.qa-stage {
  min-height: 44px;
  padding: 12px 14px;
  color: #475569;
  background: #f8fafc;
  border: 1px solid #e6ebf2;
  border-radius: 14px;
}

.qa-stage em {
  color: #475569;
  font-weight: 560;
}

.citations {
  margin-top: 18px !important;
  padding-top: 0 !important;
  border-top: 0 !important;
}

.citation-summary {
  width: 100%;
  min-height: 48px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  color: #0f172a;
  background: #f8fafc;
  border: 1px solid #e6ebf2;
  border-radius: 14px;
  text-align: left;
  cursor: pointer;
  transition: background .16s ease, border-color .16s ease, box-shadow .16s ease;
}

.citation-summary:hover {
  background: #fff;
  border-color: #cbd5e1;
  box-shadow: 0 8px 22px rgba(15, 23, 42, .06);
}

.citation-summary-main {
  font-size: 13px;
  font-weight: 720;
  color: #1e40af;
}

.citation-summary-sub {
  min-width: 0;
  overflow: hidden;
  color: #64748b;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.citation-summary-arrow {
  color: #64748b;
  font-size: 12px;
  font-weight: 650;
}

.citation-list {
  display: grid;
  gap: 10px;
  margin-top: 10px;
}

.citation-item {
  display: grid !important;
  grid-template-columns: auto 1fr auto;
  align-items: center !important;
  gap: 12px !important;
}

.citation-text {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.citation-preview {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 640px) {
  .citation-summary {
    grid-template-columns: 1fr auto;
  }

  .citation-summary-sub {
    grid-column: 1 / -1;
    white-space: normal;
  }
}
</style>
