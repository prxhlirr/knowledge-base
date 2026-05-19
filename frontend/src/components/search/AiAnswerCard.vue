<template>
  <section v-if="visible" class="ai-answer-card">
    <div class="ai-answer-top">
      <div class="ai-brand">
        <span class="ai-mark">AI</span>
        <span>知识问答</span>
        <small v-if="resultCountText">{{ resultCountText }}</small>
        <small v-if="answerSourceText" class="answer-source">{{ answerSourceText }}</small>
      </div>
      <div v-if="state.loading" class="ai-stage">
        <span class="stage-dot"></span>
        {{ stageText }}
      </div>
    </div>

    <div v-if="state.error" class="ai-error">{{ displayError }}</div>
    <div
      v-else-if="state.answer"
      class="ai-answer-text"
      v-html="renderedAnswer"
      @click="handleAnswerClick"
    ></div>
    <div v-else class="ai-answer-placeholder">
      <span class="typing-dot"></span>
      <span class="typing-dot"></span>
      <span class="typing-dot"></span>
      <em>{{ stageText }}</em>
    </div>

    <div v-if="dedupedCitations.length" class="ai-citations">
      <button type="button" class="citation-toggle" @click="expanded = !expanded">
        <span>参考了 {{ dedupedCitations.length }} 份资料</span>
        <small>{{ citationSummary }}</small>
        <strong>{{ expanded ? '收起' : '展开' }}</strong>
      </button>
      <div v-if="expanded" class="citation-list">
        <button
          v-for="cite in dedupedCitations"
          :key="citationKey(cite)"
          type="button"
          class="citation-item"
          @click="$emit('open-citation', cite)"
        >
          <span class="citation-index">[{{ cite.index }}]</span>
          <span class="citation-main">
            <b>{{ cite.file_name }}</b>
            <small v-if="citationPreview(cite)">{{ citationPreview(cite) }}</small>
          </span>
        </button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  state: {
    type: Object,
    required: true
  },
  query: {
    type: String,
    default: ''
  },
  totalResults: {
    type: Number,
    default: 0
  }
})

const emit = defineEmits(['open-citation'])
const expanded = ref(false)

const visible = computed(() =>
  props.state.loading ||
  props.state.answer ||
  props.state.error ||
  props.state.citations?.length
)

const stageText = computed(() => {
  if (props.state.stage === 'evidence') return '正在整理可引用的依据'
  if (props.state.stage === 'generating') return '正在生成回答'
  return '正在检索知识库'
})

const resultCountText = computed(() => {
  if (props.state.loading && !props.state.done) return ''
  if (!props.totalResults) return ''
  return `综合全网 ${props.totalResults} 条结果`
})

const renderedAnswer = computed(() => renderAnswer(
  props.state.answerParts || [],
  props.state.answer || '',
  props.state.citations || []
))
const dedupedCitations = computed(() => deduplicateCitations(props.state.citations || []))
const answerSourceText = computed(() => {
  const source = props.state.answerSource || props.state.metrics?.answer_source || ''
  if (source === 'non_stream_retry') return '非流式重试生成'
  if (source === 'extractive_fallback') return '资料摘录'
  return ''
})
const displayError = computed(() => sanitizeQaError(props.state.error))
const citationSummary = computed(() => {
  const list = dedupedCitations.value
  const names = list.slice(0, 2).map(c => c.file_name).join('、')
  return names + (list.length > 2 ? ` 等 ${list.length} 份` : '')
})

function sanitizeQaError(message) {
  const text = String(message || '').trim()
  if (!text) return '知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。'
  if (/HTTPSConnectionPool|SSLError|SSL|Traceback|Exception|\[ERROR\]|api\.siliconflow\.cn/i.test(text)) {
    return '知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。'
  }
  return text.length > 120
    ? '知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。'
    : text
}

watch(() => props.query, () => {
  expanded.value = false
})

function handleAnswerClick(event) {
  const target = event.target?.closest?.('[data-citation-index]')
  if (!target) return
  const index = Number(target.dataset.citationIndex)
  const cite = dedupedCitations.value.find(c => c.indexes.some(i => Number(i) === index))
  if (cite) emit('open-citation', cite)
}

function renderAnswer(parts, text, citations) {
  if (Array.isArray(parts) && parts.length) {
    const validCitationIndexes = buildValidCitationIndexes(citations)
    return parts.map(part => {
      if (part?.type === 'citation') {
        const index = Number(part.index)
        if (validCitationIndexes.size && !validCitationIndexes.has(index)) return ''
        return citationButton(index)
      }
      return renderInlineText(part?.text || '')
    }).join('')
  }
  return renderMarkdown(text, citations)
}

function renderInlineText(text) {
  return String(text || '')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/\n/g, '<br>')
}

function renderMarkdown(text, citations) {
  const validCitationIndexes = buildValidCitationIndexes(citations)
  
  let thinkHtml = ''
  let mainText = String(text || '')
  
  // Extract <think>...</think> block
  const thinkMatch = mainText.match(/<think>([\s\S]*?)(?:<\/think>|$)/)
  if (thinkMatch) {
    const thinkContent = thinkMatch[1].trim()
    if (thinkContent) {
      const isClosed = mainText.includes('</think>')
      const safeThink = normalizeCitationText(thinkContent)
        .replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/\n/g, '<br>')
      thinkHtml = `<details class="think-block" ${!isClosed ? 'open' : ''}><summary>思考过程</summary><div class="think-content">${safeThink}${!isClosed ? '<span class="thinking-cursor">...</span>' : ''}</div></details>`
    }
    mainText = mainText.replace(/<think>[\s\S]*?(?:<\/think>|$)/, '').trim()
  }

  const mainHtml = normalizeCitationText(mainText)
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/^\s*[-•]\s+(.+)$/gm, '<li>$1</li>')
    .replace(/\[(\d+)\]/g, (_match, rawIndex) => {
      const index = Number(rawIndex)
      if (validCitationIndexes.size && !validCitationIndexes.has(index)) return ''
      return citationButton(index)
    })
    .replace(/\n/g, '<br>')
    
  return thinkHtml + mainHtml
}

function normalizeCitationText(text) {
  return text
    .replace(/\[\s*\[\s*(\d+)\s*\]\s*\]/g, '[$1]')
    .replace(/【\s*(\d+)\s*】/g, '[$1]')
    .replace(/\[\s*\]/g, '')
    .replace(/\[\s*\[\s*\]\s*\]/g, '')
    .replace(/\s+([。！？；，、,.])/g, '$1')
}

function citationButton(displayIndex) {
  const label = `查看引用 ${displayIndex}`
  return `<button type="button" class="answer-citation-link" data-citation-index="${displayIndex}" aria-label="${label}" title="查看引用来源"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10.5 13.5a3.5 3.5 0 0 0 4.95 0l3.05-3.05a3.5 3.5 0 0 0-4.95-4.95l-1.15 1.15"/><path d="M13.5 10.5a3.5 3.5 0 0 0-4.95 0L5.5 13.55a3.5 3.5 0 0 0 4.95 4.95l1.15-1.15"/></svg></button>`
}

function buildValidCitationIndexes(citations) {
  const indexes = new Set()
  for (const group of deduplicateCitations(citations || [])) {
    for (const index of group.indexes) {
      indexes.add(Number(index))
    }
  }
  return indexes
}

function deduplicateCitations(citations) {
  const map = new Map()
  for (const c of citations) {
    if (!c?.file_name) continue
    const docId = c.doc_id || c.docId || ''
    const sourceDocId = c.source_doc_id || c.sourceDocId || ''
    const key = `${c.file_name}::${docId || sourceDocId || c.index || ''}`
    const hitTexts = getCitationHitTexts(c)
    const chunkIds = getChunkIds(c)
    if (!map.has(key)) {
      map.set(key, {
        file_name: c.file_name,
        index: Number(c.index) || map.size + 1,
        indexes: [Number(c.index) || map.size + 1],
        chunk_text: c.chunk_text,
        hit_texts: hitTexts,
        chunks: Array.isArray(c.chunks) ? [...c.chunks] : [],
        chunk_ids: chunkIds,
        doc_id: docId,
        source_doc_id: sourceDocId
      })
    } else {
      const item = map.get(key)
      const nextIndex = Number(c.index) || item.index
      item.indexes.push(nextIndex)
      item.hit_texts.push(...hitTexts)
      item.chunk_ids.push(...chunkIds)
      if (Array.isArray(c.chunks)) item.chunks.push(...c.chunks)
      if (!item.doc_id && (c.doc_id || c.docId)) item.doc_id = c.doc_id || c.docId
      if (!item.source_doc_id && (c.source_doc_id || c.sourceDocId)) {
        item.source_doc_id = c.source_doc_id || c.sourceDocId
      }
      item.indexes = [...new Set(item.indexes)].sort((a, b) => Number(a) - Number(b))
      item.chunk_ids = [...new Set(item.chunk_ids.filter(Boolean))]
      item.index = item.indexes[0]
    }
  }
  return [...map.values()]
}

function getCitationHitTexts(cite) {
  if (!cite) return []
  const chunkHits = Array.isArray(cite.chunks)
    ? cite.chunks.flatMap(chunk => [chunk?.hit_text, chunk?.chunk_text, chunk?.content]).filter(Boolean)
    : []
  const texts = Array.isArray(cite.hit_texts) ? [...cite.hit_texts] : [cite.chunk_text, cite.hit_text]
  texts.push(...chunkHits)
  return texts.filter(text => typeof text === 'string' && text.trim())
}

function getChunkIds(cite) {
  const ids = Array.isArray(cite?.chunk_ids) ? [...cite.chunk_ids] : []
  if (Array.isArray(cite?.chunks)) {
    for (const chunk of cite.chunks) {
      ids.push(chunk?.chunk_id, chunk?.chunk_index)
    }
  }
  return ids.filter(Boolean)
}

function citationPreview(cite) {
  const text = getCitationHitTexts(cite)[0] || ''
  const oneLine = text.replace(/\s+/g, ' ').trim()
  return oneLine.length > 86 ? `${oneLine.slice(0, 86)}...` : oneLine
}

function citationKey(cite) {
  return `${cite.doc_id || cite.source_doc_id || cite.file_name}-${cite.index}`
}
</script>

<style scoped>
.ai-answer-card {
  width: 100%;
  padding: 0 0 24px;
  background: transparent;
}

.ai-answer-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.ai-brand {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: #172033;
  font-size: 15px;
  font-weight: 700;
}

.ai-mark {
  color: #2456d6;
  letter-spacing: 0;
}

.ai-brand small {
  color: #667085;
  font-size: 13px;
  font-weight: 500;
}

.ai-brand .answer-source {
  color: #8a5a00;
  background: #fff8e6;
  border-radius: 4px;
  padding: 2px 6px;
}

.ai-stage {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #475569;
  font-size: 13px;
  white-space: nowrap;
}

.stage-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2456d6;
  box-shadow: 0 0 0 4px rgba(36, 86, 214, 0.12);
}

.ai-answer-text {
  color: #172033;
  font-size: 17px;
  line-height: 1.9;
  word-break: break-word;
}

.ai-answer-text :deep(strong) {
  font-weight: 700;
}

.ai-answer-text :deep(li) {
  margin-left: 18px;
}

/* 思考过程面板 */
.ai-answer-text :deep(.think-block) {
  margin: 8px 0 16px 0;
  border-radius: 8px;
  background: #f8f9fc;
  border: 1px solid #eaecf5;
  overflow: hidden;
}
.ai-answer-text :deep(.think-block summary) {
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
.ai-answer-text :deep(.think-block summary::before) {
  content: '▶';
  display: inline-block;
  margin-right: 6px;
  font-size: 10px;
  transition: transform 0.2s;
}
.ai-answer-text :deep(.think-block[open] summary::before) {
  transform: rotate(90deg);
}
.ai-answer-text :deep(.think-content) {
  padding: 12px;
  border-top: 1px solid #eaecf5;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
  background: #fbfbfc;
}
.ai-answer-text :deep(.thinking-cursor) {
  display: inline-block;
  margin-left: 4px;
  animation: blink 1s step-end infinite;
}
@keyframes blink { 50% { opacity: 0; } }

.ai-answer-text :deep(.answer-citation-link) {
  width: 18px;
  min-width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin: 0 2px;
  padding: 0;
  vertical-align: -2px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: #1f4fd7;
  cursor: pointer;
  transition: color 0.15s ease, transform 0.15s ease;
}

.ai-answer-text :deep(.answer-citation-link:hover) {
  color: #1742b8;
  transform: translateY(-1px);
}

.ai-answer-text :deep(.answer-citation-link:focus-visible) {
  outline: 2px solid rgba(36, 86, 214, 0.28);
  outline-offset: 2px;
}

.ai-answer-text :deep(.answer-citation-link svg) {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.25;
  stroke-linecap: round;
  stroke-linejoin: round;
  pointer-events: none;
}

.ai-answer-placeholder {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 54px;
  color: #64748b;
}

.ai-answer-placeholder em {
  margin-left: 6px;
  font-style: normal;
  font-size: 14px;
  font-weight: 400;
  color: inherit;
  background: transparent;
  text-shadow: none;
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2456d6;
  animation: pulse 1.2s infinite ease-in-out;
}

.typing-dot:nth-child(2) { animation-delay: 0.15s; }
.typing-dot:nth-child(3) { animation-delay: 0.3s; }

.ai-error {
  color: #b42318;
  background: #fff4f2;
  border: 1px solid #ffd8d2;
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 14px;
}

.ai-citations {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
}

.citation-toggle {
  width: 100%;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  text-align: left;
  color: #172033;
  background: transparent;
  border: 0;
  padding: 0;
  cursor: pointer;
}

.citation-toggle span {
  font-weight: 700;
}

.citation-toggle small {
  color: #667085;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.citation-toggle strong {
  color: #2456d6;
  font-size: 13px;
}

.citation-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.citation-item {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 10px;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid #edf0f4;
  border-radius: 8px;
  background: #f8fafc;
  color: #172033;
  cursor: pointer;
}

.citation-item:hover {
  border-color: #bfccff;
  background: #f4f7ff;
}

.citation-index {
  color: #2456d6;
  font-weight: 700;
}

.citation-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.citation-main b {
  font-size: 14px;
}

.citation-main small {
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

@keyframes pulse {
  0%, 80%, 100% { opacity: 0.35; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
</style>
