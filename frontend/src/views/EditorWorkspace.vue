<template>
  <main class="editor-workspace">
    <header class="editor-topbar">
      <div>
        <p class="eyebrow">Knowledge writing desk</p>
        <h1>文档写作相似度</h1>
      </div>
      <nav class="editor-actions" aria-label="页面导航">
        <router-link to="/" class="ghost-link">检索</router-link>
        <router-link to="/qa" class="ghost-link">问答</router-link>
        <router-link to="/admin" class="ghost-link">后台</router-link>
      </nav>
    </header>

    <section class="editor-grid">
      <div class="writing-pane">
        <div class="field-row">
          <label for="doc-title">标题</label>
          <input
            id="doc-title"
            v-model="title"
            class="title-input"
            placeholder="输入文档标题"
            @input="scheduleSimilarity"
          />
        </div>

        <div class="field-row grow">
          <div class="label-line">
            <label for="doc-body">正文</label>
            <span>{{ body.length }} 字</span>
          </div>
          <textarea
            id="doc-body"
            v-model="body"
            class="body-editor"
            placeholder="在这里起草文档内容，停顿片刻后右侧会自动推荐知识库中的相似文档。"
            @input="scheduleSimilarity"
          ></textarea>
        </div>

        <div class="editor-foot">
          <div class="foot-stat">
            <span>最小触发</span>
            <strong>{{ minChars }} 字</strong>
          </div>
          <div class="foot-stat">
            <span>推荐数量</span>
            <strong>{{ topK }}</strong>
          </div>
          <button class="primary-btn" :disabled="similarity.state.loading" @click="refreshSimilarity">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M21 12a9 9 0 0 1-15.4 6.4L3 16" />
              <path d="M3 21v-5h5" />
              <path d="M3 12A9 9 0 0 1 18.4 5.6L21 8" />
              <path d="M16 3h5v5" />
            </svg>
            刷新推荐
          </button>
        </div>
      </div>

      <aside class="similar-pane" aria-live="polite">
        <div class="similar-head">
          <div>
            <p class="eyebrow">Similar documents</p>
            <h2>相似文档</h2>
          </div>
          <span v-if="similarity.state.cacheHit" class="cache-pill">缓存</span>
          <span v-else-if="similarity.state.loading" class="loading-pill">分析中</span>
        </div>

        <div class="metrics-strip">
          <div>
            <span>输入</span>
            <strong>{{ similarity.state.queryChars || title.length + body.length }}</strong>
          </div>
          <div>
            <span>结果</span>
            <strong>{{ similarity.state.total }}</strong>
          </div>
          <div>
            <span>耗时</span>
            <strong>{{ similarity.state.costMs }}ms</strong>
          </div>
        </div>

        <div v-if="similarity.state.error" class="notice error">{{ similarity.state.error }}</div>
        <div v-else-if="similarity.state.skipped" class="notice">
          继续输入更多上下文后开始推荐。
        </div>
        <div v-else-if="!similarity.state.loading && similarity.state.items.length === 0" class="notice">
          暂无相似文档。
        </div>

        <div class="similar-list">
          <article v-for="doc in similarity.state.items" :key="doc.docId || doc.source" class="similar-item">
            <div class="similar-title-row">
              <h3>{{ doc.title || doc.source || '未命名文档' }}</h3>
              <span>{{ scorePercent(doc.similarity) }}</span>
            </div>
            <div class="similar-meta">
              <span v-if="doc.vectorScore !== undefined">向量 {{ scorePercent(doc.vectorScore) }}</span>
              <span>{{ doc.label || '相关' }}</span>
              <span>{{ doc.chunkCount || 0 }} 片段</span>
              <button 
                class="view-source-btn" 
                @click="handleViewSource(doc)" 
                :disabled="previewingId === (doc.docId || doc.source)"
              >
                {{ previewingId === (doc.docId || doc.source) ? '加载中...' : '查看原文' }}
              </button>
            </div>
            <p v-if="reasonSummary(doc)" class="reason-line">{{ reasonSummary(doc) }}</p>
            <p v-if="doc.snippet" class="snippet">{{ doc.snippet }}</p>
            <p v-else class="source-line">{{ doc.source }}</p>
          </article>
        </div>
      </aside>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useEditorSimilarity } from '../composables/useEditorSimilarity'
import { API_BASE } from '../utils/apiBase'

const title = ref('')
const body = ref('')
const topK = 5
const minChars = 80
const similarity = useEditorSimilarity({ minChars, debounceMs: 1000 })

function payload() {
  return {
    title: title.value,
    text: body.value,
    topK
  }
}

function scheduleSimilarity() {
  similarity.schedule(payload())
}

function refreshSimilarity() {
  similarity.refresh(payload())
}

function scorePercent(score) {
  const value = Number(score || 0)
  return `${Math.round(value * 100)}%`
}

function reasonSummary(doc) {
  const reason = doc?.reason || {}
  if (reason.summary) return reason.summary
  const terms = Array.isArray(reason.matchedTerms) ? reason.matchedTerms : []
  if (terms.length > 0) return `命中主题词：${terms.join('、')}`
  return ''
}

const previewingId = ref(null)

async function handleViewSource(doc) {
  const currentId = doc.docId || doc.source
  if (!currentId) return
  
  previewingId.value = currentId
  try {
    const params = new URLSearchParams()
    if (doc.docId) params.append('docId', doc.docId)
    if (doc.source) params.append('sourceName', doc.source)
    
    const res = await fetch(`${API_BASE}/file/preview?${params.toString()}`)
    const data = await res.json()
    
    if (data.code === 200 && data.data && data.data.url) {
      window.open(data.data.url, '_blank')
    } else {
      alert(data.msg || '无法获取预览链接，该文档可能尚未入库或已被删除')
    }
  } catch (err) {
    alert('请求预览链接失败: ' + err.message)
  } finally {
    previewingId.value = null
  }
}
</script>

<style scoped>
.editor-workspace {
  min-height: 100vh;
  padding: 28px;
  background:
    linear-gradient(90deg, rgba(15, 138, 95, 0.05) 0, transparent 34%),
    linear-gradient(180deg, #f8fafc 0, #eef3f8 100%);
  color: var(--text-main);
}

.editor-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  max-width: 1440px;
  margin: 0 auto 24px;
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--accent-cyan);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1,
h2,
h3 {
  margin: 0;
  letter-spacing: 0;
}

h1 {
  font-size: 28px;
}

h2 {
  font-size: 20px;
}

.editor-actions {
  display: flex;
  gap: 8px;
}

.ghost-link,
.primary-btn {
  min-height: 40px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
}

.ghost-link {
  padding: 0 14px;
  color: var(--text-muted);
  border: 1px solid var(--border-light);
  background: rgba(255, 255, 255, 0.7);
}

.primary-btn {
  padding: 0 16px;
  color: #fff;
  background: var(--primary);
}

.primary-btn svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.editor-grid {
  max-width: 1440px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 20px;
  align-items: stretch;
}

.writing-pane,
.similar-pane {
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
}

.writing-pane {
  min-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
  padding: 20px;
  border-radius: 10px;
}

.similar-pane {
  height: calc(100vh - 120px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 18px;
  border-radius: 10px;
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.field-row.grow {
  flex: 1;
  min-height: 0;
}

label,
.label-line {
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 700;
}

.label-line {
  display: flex;
  justify-content: space-between;
}

.title-input,
.body-editor {
  width: 100%;
  border: 1px solid var(--border-light);
  background: #fff;
  color: var(--text-main);
  border-radius: 8px;
  outline: none;
}

.title-input {
  height: 48px;
  padding: 0 14px;
  font-size: 18px;
  font-weight: 700;
}

.body-editor {
  flex: 1;
  min-height: 420px;
  resize: none;
  padding: 18px;
  font-size: 15px;
  line-height: 1.75;
}

.editor-foot,
.similar-head,
.metrics-strip,
.similar-title-row,
.similar-meta {
  display: flex;
  align-items: center;
}

.editor-foot {
  justify-content: space-between;
  gap: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-ultra-light);
}

.foot-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-right: auto;
}

.foot-stat + .foot-stat {
  margin-left: 8px;
}

.foot-stat span,
.metrics-strip span,
.similar-meta,
.source-line {
  color: var(--text-muted);
  font-size: 12px;
}

.foot-stat strong,
.metrics-strip strong {
  font-size: 15px;
}

.similar-head {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.cache-pill,
.loading-pill {
  padding: 5px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.cache-pill {
  color: var(--accent-green);
  background: rgba(15, 138, 95, 0.1);
}

.loading-pill {
  color: var(--accent-amber);
  background: rgba(183, 121, 31, 0.12);
}

.metrics-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 14px;
}

.metrics-strip > div {
  padding: 10px;
  border-radius: 8px;
  background: var(--snow-soft);
}

.metrics-strip span,
.metrics-strip strong {
  display: block;
}

.notice {
  padding: 14px;
  border-radius: 8px;
  color: var(--text-muted);
  background: var(--surface-muted);
  font-size: 14px;
}

.notice.error {
  color: #9f2f2f;
  background: rgba(159, 47, 47, 0.08);
}

.similar-list {
  overflow: auto;
  padding-right: 2px;
}

.similar-item {
  padding: 14px 0;
  border-bottom: 1px solid var(--border-ultra-light);
}

.similar-title-row {
  justify-content: space-between;
  gap: 12px;
}

.similar-title-row h3 {
  min-width: 0;
  font-size: 15px;
  line-height: 1.4;
}

.similar-title-row span {
  flex: 0 0 auto;
  color: var(--primary);
  font-weight: 900;
}

.similar-meta {
  gap: 10px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.view-source-btn {
  background: none;
  border: none;
  padding: 0;
  margin-left: auto;
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.view-source-btn:hover {
  color: var(--primary-hover, #0d7a54);
}
.view-source-btn:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
  text-decoration: none;
}

.snippet,
.source-line,
.reason-line {
  margin: 10px 0 0;
  line-height: 1.6;
  font-size: 13px;
}

.reason-line {
  color: var(--accent-green);
}

.snippet {
  color: var(--text-main);
}

@media (max-width: 980px) {
  .editor-workspace {
    padding: 16px;
  }

  .editor-topbar,
  .editor-grid {
    display: block;
  }

  .editor-actions {
    margin-top: 16px;
    overflow-x: auto;
  }

  .similar-pane {
    height: auto;
    margin-top: 16px;
  }

  .writing-pane {
    min-height: auto;
  }
}
</style>
