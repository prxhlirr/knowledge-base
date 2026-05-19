<template>
  <div class="home-wrapper">
    <!-- 动态流体背景层 -->
    <div class="bg-fluid-container">
      <div class="bg-blob bg-blob-1"></div>
      <div class="bg-blob bg-blob-2"></div>
      <div class="bg-blob bg-blob-3"></div>
    </div>

    <!-- 顶部极简导航 -->
    <header class="app-header">
      <router-link to="/editor" class="btn-admin">写作相似推荐</router-link>
      <router-link to="/admin" class="btn-admin">后台管理</router-link>
    </header>


    <main class="search-main">
      <div class="search-hero" :class="{ 'has-results': hasSearched }">
        <h1 class="logo-text">智能检索中枢</h1>
        
        <div class="search-box-container">
          <div class="search-box">
            <input 
              v-model="query" 
              type="text" 
              aria-label="检索关键词或自然语言问题"
              placeholder="请输入政策、公文关键词或自然语言问题..." 
              @keyup.enter="handleSearch"
              class="search-input"
            />
            <button class="search-btn" @click="handleSearch" :disabled="loading">
              <span v-if="!loading">搜索</span>
              <span v-else class="loader"></span>
            </button>
          </div>
          <div v-if="error" class="error-text">{{ error }}</div>
        </div>
      </div>

      <section v-if="hasSearched" class="results-shell">
        <AiAnswerCard
          :state="qaState"
          :query="lastQuery"
          :total-results="totalResultCount"
          @open-citation="openQaCitation"
        />

        <!-- 结果视图切换 Tab：搜索时默认并行调用关键词/混合检索，Tab 仅控制展示 -->
        <div class="search-tabs result-tabs">
          <button class="tab-btn" :class="{ active: searchMode === 'keyword' }" :aria-pressed="searchMode === 'keyword'" @click="searchMode = 'keyword'">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-text"><path d="M17 6.1H3"/><path d="M21 12.1H3"/><path d="M15.1 18H3"/></svg>
            关键词检索
            <span v-if="keywordState.loading" class="tab-status-dot"></span>
          </button>
          <button class="tab-btn" :class="{ active: searchMode === 'hybrid' }" :aria-pressed="searchMode === 'hybrid'" @click="searchMode = 'hybrid'">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-git-merge"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M6 21V9a9 9 0 0 0 9 9"/></svg>
            混合检索
            <span v-if="hybridState.loading" class="tab-status-dot"></span>
          </button>
        </div>

        <!-- 搜索结果列表 -->
        <transition name="fade">
          <div v-if="displayedResults.length > 0" class="results-area">
            <div class="results-stats">
              {{ searchMode === 'hybrid' ? '混合检索' : '关键词检索' }} 找到约 {{ displayedTotal }} 条相关结果（用时 {{ tookMs }} ms）
            </div>
            
            <div v-for="(item, index) in displayedResults" :key="`${searchMode}-${item.doc_id || index}`" class="result-item">
              <div class="result-title-row">
                <span class="file-type" :class="item.file_type || 'default'">{{ item.file_type || '文档' }}</span>
                <h3 class="source-name">{{ item.file_name || item.organization || '综合部门' }}</h3>
                <span v-if="searchMode !== 'keyword'" class="relevance-score">匹配度: {{ (item.score * 100).toFixed(0) }}%</span>
              </div>
              <div class="result-meta-row">
                <div v-if="item.custom_tags" class="item-tags-wrap">
                  <span v-for="tag in item.custom_tags.split(',')" :key="tag" class="meta-tag-mini">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-tag"><path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5" fill="currentColor"/></svg>
                    {{ tag.trim() }}
                  </span>
                </div>
              </div>
              
               <!-- 多分片展示逻辑：过滤fine粒度chunk，去重后展示 -->
              <div v-if="item.chunks && filterAndDedupeChunks(item.chunks).length > 0" class="multi-chunks-container">
                <div v-for="(chunk, chunkIdx) in filterAndDedupeChunks(item.chunks)" :key="chunkIdx" class="chunk-content-item">
                  <div class="result-content" v-html="highlightText(chunk)"></div>
                </div>
              </div>
              <!-- 无chunk时的fallback -->
              <div v-else class="result-content" v-html="highlightText(item)"></div>
              
              <!-- 操作栏：打标增强 + 查看原文 + 分片明细 -->
              <div class="result-footer">
                <button class="btn-chunk" @click="openChunkDrawer(item)">
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-layers"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
                  分片明细
                </button>
                <button
                  v-if="item.file_name"
                  class="btn-source"
                  @click="openSourceFile(item.file_name, item.doc_id)"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" x2="21" y1="14" y2="3"/></svg>
                  查看原文
                </button>
              </div>
            </div>
          </div>
        </transition>

        <!-- 空状态 -->
        <div v-if="displayedResults.length === 0 && !loading" class="empty-results">
          <p>未找到与“{{ query }}”相关的条目，请尝试其他关键词</p>
        </div>
      </section>

      <!-- 搜索下方说明引导区 (仅在无结果时展示) -->
      <transition name="fade">
        <div v-if="!hasSearched" class="features-grid">
          <div class="feature-card">
             <div class="feature-icon">
               <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-git-merge"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M6 21V9a9 9 0 0 0 9 9"/></svg>
             </div>
             <h3>双路混合召回引擎</h3>
             <p>基于 Elasticsearch 原生集成 <b>BM25</b> 词频索引与 <b>HNSW</b> (1024维) 密集向量双通道倒推，精准击破“同义词不粘连”痛点。</p>
          </div>
          <div class="feature-card">
             <div class="feature-icon">
               <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-zap"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
             </div>
             <h3>RRF 脱敏与重排</h3>
             <p>动态应用 <b>Reciprocal Rank Fusion</b> (倒数排名融合算法)，在深层融合的基础上搭载毫秒级响应框架，匹配严格的组别行列脱敏。</p>
          </div>
          <div class="feature-card">
             <div class="feature-icon">
               <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-file-text"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><line x1="10" x2="8" y1="9" y2="9"/></svg>
             </div>
             <h3>长文本自适应分片</h3>
             <p>接入 <b>BGE-m3 FP16</b> 引擎处理超长公文上下文，根据特型进行滑动窗口切丝重组提取，彻底根治大段落语义丢失难题。</p>
          </div>
        </div>
      </transition>

      <!-- 打标弹窗 -->
      <transition name="fade">
        <div v-if="showTagModal" class="tag-modal-overlay">
          <div class="tag-modal">
            <h3>补充知识库标签 ({{ currentTagItem?.organization || '综合部门' }})</h3>
            <div class="form-group">
              <label>自定义标签 (逗号分隔):</label>
              <input v-model="tagForm.tags" placeholder="例如: 核心政策,指导意见,必读" class="tag-input" />
            </div>
            <div class="form-group">
              <label>扩充关键词:</label>
              <textarea v-model="tagForm.keywords" placeholder="输入你想补充检索被命中的自然语言片段或关键词..." class="tag-textarea"></textarea>
            </div>
            <div class="modal-actions">
              <button @click="closeTagModal" class="btn-cancel">取消</button>
              <button @click="submitTag" class="btn-submit" :disabled="submittingTag">
                {{ submittingTag ? '保存中...' : '提交打标' }}
              </button>
            </div>
          </div>
        </div>
      </transition>

      <!-- 分片明细侧边抽屉 -->
      <transition name="drawer-fade">
        <div v-show="showChunkDrawer" class="drawer-overlay" @click.self="closeChunkDrawer">
          <div class="drawer-content" :class="{ 'drawer-open': showChunkDrawer }">
            <div class="drawer-header">
              <h3>分片明细 ({{ chunksDataMeta.file_name || currentDocument?.organization || '未知文档' }})</h3>
              <p class="drawer-subtitle">总分片数量: {{ chunksDataMeta.total_chunks || chunksData.length }}</p>
              <button class="btn-close" @click="closeChunkDrawer">×</button>
            </div>
            <div class="drawer-body">
              <div v-if="loadingChunks" class="empty-text" style="color:#666;">
                <span class="loader center"></span> 加载分片中...
              </div>
              <div v-else-if="chunksTreeFlat.length === 0" class="empty-text">暂无分片数据</div>
              <div v-else class="chunk-list timeline-view">
                <div v-for="(chunk, idx) in chunksTreeFlat" :key="chunk.chunk_id" class="chunk-item timeline-item" :class="{ 'highlight-chunk': chunk.chunk_id === currentDocument?._id }" :style="{ marginLeft: (chunk.depth * 32) + 'px' }">
                  <div class="timeline-marker"></div>
                  <div class="chunk-card">
                    <div class="chunk-meta">
                      <span class="chunk-badge">#{{ chunk.chunk_index }}</span>
                      <span v-if="chunk.parent_chunk_id" class="parent-badge" title="父分片ID" @click="console.log(chunk.parent_chunk_id)">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-corner-left-up"><polyline points="14 9 9 4 4 9"/><path d="M9 20v-7a4 4 0 0 1 4-4h8"/></svg>
                        上级: {{ chunk.parent_chunk_id }}
                      </span>
                      <span>粒度: {{ chunk.chunk_gran }}</span>
                      <span>长度: {{ chunk.char_count }}字符</span>
                      <span v-if="chunk.chunk_id === currentDocument?._id" class="hit-badge">当前召回命中</span>
                      <button class="btn-probe-toggle" @click.stop="toggleProbe(chunk)" title="开启向量探针">
                        向量探针
                      </button>
                    </div>
                    <div class="chunk-text" v-html="highlightTextDrawer(chunk.chunk_text)"></div>
                    
                    <!-- 向量探针面板 -->
                    <transition name="probe-collapse">
                      <div v-if="activeProbeId === chunk.chunk_id" class="probe-panel">
                        <div class="probe-input-wrapper">
                          <input v-model="probeInput" placeholder="输入任意文本，点击右侧探测距离..." @keyup.enter="executeProbe(chunk)" />
                          <button @click="executeProbe(chunk)" :disabled="probing">
                            <span v-if="!probing">比对余弦相似度</span>
                            <span v-else class="loader-mini"></span>
                          </button>
                        </div>
                        <div v-if="probeResult !== null" class="probe-result-area">
                          <div class="probe-score-ring">
                            <svg viewBox="0 0 36 36" class="circular-chart" :class="getScoreColorClass(probeResult)">
                              <path class="circle-bg" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" />
                              <path class="circle" :stroke-dasharray="`${Math.round(probeResult * 100)}, 100`" d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831" />
                              <text x="18" y="20.35" class="percentage">{{ (probeResult * 100).toFixed(1) }}%</text>
                            </svg>
                          </div>
                          <div class="probe-score-text">
                            <div class="probe-score-title">空间距离分析</div>
                            <div class="probe-score-desc">
                              {{ probeResult >= 0.85 ? '极高匹配 (语义重合度极高)' : probeResult >= 0.65 ? '高度相关 (存在显著语义交集)' : probeResult > 0.4 ? '有些关联 (边缘语义联系)' : '毫不相干 (正交或排斥)' }}
                            </div>
                          </div>
                        </div>
                      </div>
                    </transition>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </transition>

      <!-- 原文预览侧边抽屉 -->
      <transition name="drawer-fade">
        <div v-show="showOriginalFileDrawer" class="drawer-overlay" @click.self="closeOriginalFileDrawer">
          <div class="drawer-content original-file-drawer" :class="{ 'drawer-open': showOriginalFileDrawer }">
            <div class="drawer-header">
              <h3>{{ originalMeta.file_name || '原文预览' }}</h3>
              <p class="drawer-subtitle" v-if="originalChunks.length">共 {{ originalChunks.length }} 个分片</p>
              <button class="btn-close" @click="closeOriginalFileDrawer">×</button>
            </div>
            <div class="drawer-body">
              <div v-if="loadingChunks" class="empty-text" style="color:#666;">
                <span class="loader center"></span> 加载原文中...
              </div>
              <div v-else-if="originalChunks.length === 0" class="empty-text">暂无内容</div>
              <div v-else class="original-chunk-list">
                <div v-for="(chunk, idx) in originalChunks" :key="chunk.chunk_id || idx" class="original-chunk-section">
                  <div class="chunk-text" v-html="highlightTextDrawer(chunk.chunk_text)"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </transition>

    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import AiAnswerCard from '../components/search/AiAnswerCard.vue'
import { useHomeSearch } from '../composables/useHomeSearch'

const query = ref('')
const searchMode = ref('keyword')
const hasSearched = ref(false)
const ikTokens = ref([])
const lastQuery = ref('')

const homeSearch = useHomeSearch()

const keywordState = homeSearch.keyword
const hybridState = homeSearch.hybrid
const qaState = homeSearch.qa

const activeSearchState = computed(() => searchMode.value === 'keyword' ? keywordState : hybridState)
const displayedResults = computed(() => activeSearchState.value.list || [])
const displayedTotal = computed(() => activeSearchState.value.total || displayedResults.value.length)
const tookMs = computed(() => activeSearchState.value.tookMs || 0)
const loading = computed(() => homeSearch.loading.value)
const totalResultCount = computed(() => Math.max(keywordState.total || 0, hybridState.total || 0))
const error = computed(() => activeSearchState.value.error || '')

const executeSearch = (source = 'manual') => {
  const text = query.value.trim()
  if (!text) return
  hasSearched.value = true
  lastQuery.value = text
  searchMode.value = 'keyword'
  ikTokens.value = []

  homeSearch.search(text, { scene: source === 'auto' ? 'home_auto' : 'home', topK: 5, pageSize: 50 })
}

const handleSearch = () => {
  executeSearch('manual')
}

onBeforeUnmount(() => {
  homeSearch.abort()
})

// === 打标相关逻辑 ===
const showTagModal = ref(false)
const currentTagItem = ref(null)
const submittingTag = ref(false)
const tagForm = ref({ tags: '', keywords: '' })

const openTagModal = async (item) => {
  currentTagItem.value = item
  // 弹窗先立即显示，提升交互体验
  showTagModal.value = true
  tagForm.value.tags = ''
  // 由于后台将 keyword 放到了 custom_tags 下透传给前端
  tagForm.value.keywords = item.custom_tags || ''
  
  // 尝试向后端拉取最新的打标“草稿”（如果用户刚修改过但还没同步到 ES）
  if (item.doc_id) {
    try {
      const res = await fetch(`${import.meta.env.VITE_API_BASE_URL}/tags/detail?docId=${item.doc_id}`);
      const data = await res.json();
      if (data.code === 200 && data.data) {
        tagForm.value.tags = data.data.tags || '';
        tagForm.value.keywords = data.data.keywords || '';
      }
    } catch (err) {
      console.warn("未能获取最新打标记录草稿", err);
    }
  }
}

const closeTagModal = () => {
  showTagModal.value = false
  currentTagItem.value = null
  tagForm.value = { tags: '', keywords: '' }
}

const submitTag = async () => {
  if (!currentTagItem.value || !currentTagItem.value.doc_id) {
    alert("无法提取有效的关联文档ID (doc_id)");
    return;
  }
  submittingTag.value = true;
  try {
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/tags/save`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        docId: currentTagItem.value.doc_id,
        tags: tagForm.value.tags,
        keywords: tagForm.value.keywords
      })
    });
    const data = await response.json();
    if (data.code === 200) {
      alert('打标成功！后台可以进行人工关键词与向量同步。');
      closeTagModal();
    } else {
      alert('打标失败: ' + data.msg);
    }
  } catch(err) {
    alert('请求错误: ' + err.message);
  } finally {
    submittingTag.value = false;
  }
}

const openSourceFile = async (fileName, docId) => {
  if (!fileName && !docId) return
  const safeDocId = docId || 'by-file-name'
  const encodedDocId = encodeURIComponent(safeDocId)

  currentDocument.value = { file_name: fileName, doc_id: safeDocId }
  showOriginalFileDrawer.value = true
  loadingChunks.value = true
  originalChunks.value = []
  originalMeta.value = {}

  try {
    const res = await fetch(
      `${import.meta.env.VITE_API_BASE_URL}/doc/${encodedDocId}/chunks` +
      `?appCode=${import.meta.env.VITE_APP_CODE || ''}` +
      `&fileName=${encodeURIComponent(fileName || '')}`
    )
    const data = await res.json()
    if (data.code === 200 && data.data) {
      // 只保留 coarse 粒度分片，按顺序排列
      originalChunks.value = (data.data.chunks || [])
        .filter(c => c.chunk_gran !== 'fine' && c.chunk_granularity !== 'fine')
        .sort((a, b) => (a.chunk_index || 0) - (b.chunk_index || 0))
      originalMeta.value = {
        file_name: data.data.file_name || fileName,
        total_chunks: data.data.total_chunks
      }
    } else {
      alert('加载原文失败: ' + (data.msg || '未知错误'))
    }
  } catch (e) {
    console.error('[openSourceFile]', e)
    alert('加载原文失败，请检查网络')
  } finally {
    loadingChunks.value = false
  }
}

const openQaCitation = (cite) => {
  if (!cite) return
  const chunkId = Array.isArray(cite.chunk_ids) ? cite.chunk_ids.find(Boolean) : ''
  const nestedChunkId = Array.isArray(cite.chunks)
    ? cite.chunks.map(chunk => chunk?.chunk_id || chunk?.chunk_index).find(Boolean)
    : ''
  const docId = cite.doc_id || cite.docId || cite.source_doc_id || cite.sourceDocId || chunkId || nestedChunkId || 'by-file-name'
  openSourceFile(cite.file_name, docId)
}

const closeOriginalFileDrawer = () => {
  showOriginalFileDrawer.value = false
  originalChunks.value = []
  originalMeta.value = {}
}

const filterAndDedupeChunks = (chunks) => {
  if (!chunks || !Array.isArray(chunks)) return [];
  
  // 1. 过滤掉 fine 粒度的 chunk，只保留 coarse 和历史未标粒度分片
  const validChunks = chunks.filter(chunk => 
    chunk.chunk_gran !== 'fine' && chunk.chunk_granularity !== 'fine'
  );

  if (searchMode.value === 'keyword') {
    // 关键词模式由后端保证至少 3 个 coarse 证据；前端不得用文本包含关系折叠前三条。
    const seen = new Set();
    return validChunks
      .filter(chunk => {
        const key = chunk.chunk_id || `${chunk.chunk_index || ''}:${chunk.chunk_text || ''}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      })
      .sort((a, b) => (a.chunk_index || 0) - (b.chunk_index || 0));
  }
  
  // 2. 按文本长度降序排列，长文本优先保留
  // 子集去重：如果短文本完全包含在某个长文本中则丢弃
  const sortedByLen = [...validChunks].sort((a, b) => (b.chunk_text || '').length - (a.chunk_text || '').length);
  const keepSet = new Set();
  const keepTexts = [];
  
  for (const chunk of sortedByLen) {
    const text = (chunk.chunk_text || '').trim();
    if (!text) continue;
    
    // 检查当前文本是否已被保留文本中的某一条包含（子集关系检测）
    const isSubset = keepTexts.some(existingText => existingText.includes(text));
    if (!isSubset) {
      keepTexts.push(text);
      // 用 chunk_id 作为去重标识
      keepSet.add(chunk.chunk_id || text);
    }
  }
  
  // 3. 按 chunk_index 升序排列，保证原文前面的内容展示在前面
  return validChunks.filter(c => {
    const key = c.chunk_id || (c.chunk_text || '').trim();
    return keepSet.has(key);
  }).sort((a, b) => (a.chunk_index || 0) - (b.chunk_index || 0));
}

const normalizeDisplayText = (value) => String(value || '')
  .replace(/\\r\\n|\\n|\\r/g, ' ')
  .replace(/[\r\n]+/g, ' ')
  .replace(/[ \t]{2,}/g, ' ')
  .trim()

// === 双引擎高亮辅助算法 ===
/**
 * 业务功能：从用户检索 Query 中提取高亮关键词（引号短语优先）
 * 关键流程：
 *   1. 使用正则识别并提取被各种中英文单双引号包裹的完整短语（如 “商事调解”、"知识产权"）作为高优先级精确词；
 *   2. 将剩余文本按中英文空格进行常规分词，长度大于 1 的词作为普通高亮词；
 *   3. 去重并对所有词按字符长度从长到短排序（防止短词在正则替换时破坏长词的 HTML 结构）。
 */
const extractKeywords = (queryStr) => {
  if (!queryStr) return [];
  const text = queryStr.trim();
  const quoteKeywords = [];
  
  // 匹配中英文单双引号包裹的内容：“...”、”...“、"..."、'...'、‘...’、’...‘
  const quoteRegex = /["'“”‘']([^"'“”‘’]+)["'“”’’]/g;
  const processedText = text.replace(quoteRegex, (match, p1) => {
    const trimmed = p1.trim();
    if (trimmed.length > 0) {
      quoteKeywords.push(trimmed);
    }
    return ' '; // 用空格替换被抽走的词，防止它们和后面的词粘连
  });
  
  // 提取其余普通词（长度大于 1 避免单字无效高亮膨胀）
  const ordinaryKeywords = processedText
    .split(/[\s\u3000]+/)
    .map(w => w.trim())
    .filter(w => w.length > 1);
    
  // 合并并去重，同时保留引号词的高优先级
  const combined = [...new Set([...quoteKeywords, ...ordinaryKeywords])];
  return combined;
};

/**
 * 业务功能：在包含或不包含 HTML 的字符串上安全应用前端高亮
 * 关键流程：
 *   1. 提取关键词，并按长度从大到小排序；
 *   2. 对每个关键词使用负向先行断言正则匹配 `(关键词)(?![^<]*>)`；
 *   3. 仅在高亮词位于 HTML 标签外部时才将其包裹为 <em class="highlight">，100% 避免破坏已有标签结构。
 */
const applyFrontendHighlight = (htmlOrText, queryStr) => {
  if (!htmlOrText) return '';
  if (!queryStr) return htmlOrText;
  
  let text = htmlOrText;
  const kws = extractKeywords(queryStr);
  if (kws.length === 0) return text;
  
  // 按长度降序，防止子串匹配时截断或污染较长匹配词的标签结构
  const sorted = [...kws].sort((a, b) => b.length - a.length);
  
  for (const kw of sorted) {
    try {
      const escaped = kw.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&');
      // (?![^<]*>) 负向断言：匹配的关键词右边如果存在 '>'，则中间必须包含一个 '<'。
      // 这保证了该关键词绝对不在任何 HTML 标签的属性或内部（如 class="highlight" 中）。
      const regex = new RegExp(`(${escaped})(?![^<]*>)`, 'gi');
      text = text.replace(regex, `<em class="highlight">$1</em>`);
    } catch (e) {
      console.warn("高亮匹配异常:", e);
    }
  }
  return text;
};

const highlightText = (item) => {
  let displayText = '';
  // 优先展示后端返回的高亮字段 (ES 索引字段名为 content)
  if (item.highlight) {
    // 兼容多种可能的 key
    const hl = item.highlight.content || item.highlight.chunk_text;
    if (hl && hl.length > 0) {
      displayText = normalizeDisplayText(hl.join(' ... '));
    }
  }
  if (!displayText) {
    displayText = normalizeDisplayText(item.chunk_text);
  }
  // 双引擎混合：在后端高亮/原文字符串上，强行叠加上述提取的前端引号/普通精准高亮
  return applyFrontendHighlight(displayText, query.value);
}

// === 分片抽屉逻辑 ===
const showChunkDrawer = ref(false)
const showOriginalFileDrawer = ref(false)
const currentDocument = ref(null)
const loadingChunks = ref(false)
const chunksData = ref([])
const chunksDataMeta = ref({})
const originalChunks = ref([])
const originalMeta = ref({})

const flattenTree = (nodes, depth = 0) => {
  let result = [];
  nodes.forEach(node => {
    result.push({ ...node, depth });
    if (node.children && node.children.length > 0) {
      result = result.concat(flattenTree(node.children, depth + 1));
    }
  });
  return result;
};

const chunksTreeFlat = computed(() => {
  if (!chunksData.value || !Array.isArray(chunksData.value)) {
    return [];
  }
  const map = {}
  const roots = []
  
  chunksData.value.forEach(c => {
    map[c.chunk_id] = { ...c, children: [] }
  })
  
  chunksData.value.forEach(c => {
    const node = map[c.chunk_id]
    if (c.parent_chunk_id && map[c.parent_chunk_id]) {
      map[c.parent_chunk_id].children.push(node)
    } else {
      roots.push(node)
    }
  })
  
  const sortFn = (a, b) => (a.chunk_index || 0) - (b.chunk_index || 0)
  roots.sort(sortFn)
  Object.values(map).forEach(node => {
    node.children.sort(sortFn)
  })
  
  return flattenTree(roots);
})

const openChunkDrawer = async (item) => {
  currentDocument.value = item;
  showChunkDrawer.value = true;
  loadingChunks.value = true;
  chunksData.value = [];
  chunksDataMeta.value = {};
  try {
    // 调用/doc/{docId}/chunks 查询对应文档的全部分片
    const res = await fetch(
      `${import.meta.env.VITE_API_BASE_URL}/doc/${item.doc_id}/chunks` +
      `?appCode=${import.meta.env.VITE_APP_CODE || ''}` +
      `&fileName=${encodeURIComponent(item.file_name || '')}`
    );
    const data = await res.json();
    if (data.code === 200 && data.data) {
      chunksData.value = data.data.chunks || [];
      chunksDataMeta.value = {
        file_name: data.data.file_name,
        total_chunks: data.data.total_chunks
      };
    } else {
      alert("加载分片失败: " + data.msg);
    }
  } catch (e) {
    console.error(e);
    alert("加载分片失败，请检查网络");
  } finally {
    loadingChunks.value = false;
  }
}

const closeChunkDrawer = () => {
  showChunkDrawer.value = false;
  currentDocument.value = null;
  chunksData.value = [];
  chunksDataMeta.value = {};
  activeProbeId.value = null;
}

// === 探针逻辑 ===
const activeProbeId = ref(null);
const probeInput = ref('');
const probeResult = ref(null);
const probing = ref(false);

const toggleProbe = (chunk) => {
  if (activeProbeId.value === chunk.chunk_id) {
    activeProbeId.value = null;
  } else {
    activeProbeId.value = chunk.chunk_id;
    probeInput.value = '';
    probeResult.value = null;
  }
}

const getScoreColorClass = (score) => {
  if (score >= 0.85) return 'score-green';
  if (score >= 0.65) return 'score-blue';
  if (score > 0.4) return 'score-orange';
  return 'score-gray';
}

const executeProbe = async (chunk) => {
  if (!probeInput.value.trim()) return;
  probing.value = true;
  probeResult.value = null;
  try {
    const res = await fetch(`${import.meta.env.VITE_API_BASE_URL}/search/similarity/probe`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        queryText: probeInput.value.trim(),
        chunkText: chunk.chunk_text
      })
    });
    const data = await res.json();
    if (data.code === 200 && data.data) {
      probeResult.value = data.data.cosine || 0;
    } else {
      alert("探测请求失败: " + data.msg);
    }
  } catch (err) {
    alert("探针请求异常");
  } finally {
    probing.value = false;
  }
}

// 供 Drawer 展示的分片高亮逻辑（简单全局搜索词高亮，不使用片段 highlight 以保证内容的完整展示）
const highlightTextDrawer = (chunkText) => {
  const normalizedText = normalizeDisplayText(chunkText)
  if (!normalizedText) return '';
  
  let text = normalizedText;
  // 避免 XSS，简单替换 HTML 尖括号标签防止恶意注入，随后安全应用前端高亮
  text = text.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  
  return applyFrontendHighlight(text, query.value);
}
</script>

<style scoped>
.home-wrapper {
  width: 100%;
}

/* 顶部极简导航样式 */
.app-header {
  position: fixed;
  top: 0;
  right: 0;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  z-index: 100;
}

/* 知识问答 入口按钮 */
.btn-qa {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  color: #3d50e0;
  background: rgba(255,255,255,0.82);
  border: 1px solid #dde0f5;
  border-radius: 20px;
  padding: 6px 14px;
  backdrop-filter: blur(6px);
  transition: all 0.15s;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.btn-qa:hover {
  background: #f0f2ff;
  border-color: #aab0e8;
  box-shadow: 0 2px 8px rgba(61,80,224,.15);
}

.qa-hero-action {
  width: 100%;
  margin-top: 18px;
  display: flex;
  justify-content: center;
}
.btn-qa-hero {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  min-height: 48px;
  padding: 0 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(23, 32, 51, 0.1);
  transition: all 0.25s ease;
  box-shadow: none;
}
.btn-qa-hero:hover {
  background: #ffffff;
  border-color: rgba(36, 86, 214, 0.22);
  box-shadow: 0 8px 18px rgba(23, 32, 51, 0.08);
  transform: translateY(-1px);
}
.qa-hero-icon {
  display: flex;
  align-items: center;
  color: var(--primary-blue);
}
.qa-hero-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.qa-hero-text strong {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}
.qa-hero-text small {
  font-size: 12px;
  color: #6b7280;
}
.qa-hero-arrow {
  color: #9ca3af;
  transition: transform 0.2s ease;
}
.btn-qa-hero:hover .qa-hero-arrow {
  transform: translateX(3px);
  color: var(--primary-blue);
}

.search-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 20px;
}

.search-hero {
  width: 100%;
  max-width: 680px;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 15vh;
  transition: margin 0.3s ease;
}

.search-hero.has-results {
  margin-top: 60px;
}

.logo-text {
  font-size: 42px;
  font-weight: 300;
  color: var(--primary-blue);
  margin-bottom: 40px;
  letter-spacing: 1px;
}

.search-box-container {
  width: 100%;
  position: relative;
  transition: var(--transition-smooth);
}

.search-box {
  display: flex;
  align-items: center;
  background: var(--glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  border: 1px solid var(--glass-border);
  border-radius: 48px; /* 更圆滑的 Pill 形状 */
  padding: 8px 10px 8px 28px;
  box-shadow: var(--shadow-soft);
  transition: var(--transition-fast);
  height: 64px; /* 更大，符合无障碍标准，易于点击 */
}

.search-box:hover, .search-box:focus-within {
  box-shadow: var(--shadow-glow); /* focus 发光反馈 */
  border-color: rgba(26, 86, 219, 0.3); /* Chrome 100 支持的 RGBA 边框颜色变体 */
  background: #ffffff;
}

.search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 16px; /* 最小 16px 防止 iOS 自动缩放 */
  color: var(--text-main);
  padding: 10px 0;
  height: 100%;
}

.search-input:focus,
.search-input:focus-visible {
  outline: none;
  box-shadow: none;
}

.search-input::placeholder {
  color: var(--text-light);
  font-weight: 400;
}

.search-btn {
  background: linear-gradient(135deg, var(--primary) 0%, #3B82F6 100%);
  color: white;
  padding: 0 32px;
  height: 48px;
  border-radius: 48px;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.25);
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 100px;
}

.search-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(26, 86, 219, 0.35);
  background: linear-gradient(135deg, var(--primary-hover) 0%, var(--primary) 100%);
}

.search-btn:active {
  transform: translateY(1px) scale(0.96); /* 按下反馈 */
  box-shadow: 0 2px 8px rgba(26, 86, 219, 0.2);
}

.search-btn:disabled {
  background: #E5E7EB;
  color: #9CA3AF;
  box-shadow: none;
  cursor: not-allowed;
  transform: none;
}

.error-text {
  color: #d93025;
  font-size: 13px;
  margin-top: 12px;
  padding-left: 20px;
}

/* 结果列表样式 */
.results-area {
  width: 100%;
  max-width: 720px;
  margin-top: 40px;
  padding-bottom: 100px;
}

.results-stats {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 24px;
}

.result-item {
  position: relative;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  padding: 24px 24px 24px 32px;
  border-radius: 16px;
  margin-bottom: 24px;
  box-shadow: var(--glass-shadow);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}

/* 搜索结果左侧高亮条 */
.result-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 5px;
  background: var(--primary);
  opacity: 0.85;
}

.result-item:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

.result-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.file-type {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  text-transform: uppercase;
  font-weight: 700;
  background: #eee;
}

.file-type.pdf { background: #fde8e8; color: #c81e1e; }
.file-type.docx { background: #e1effe; color: #1e429f; }

.source-name {
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text-main);
  margin: 0;
  flex: 1;
}

.result-meta-row {
  margin-bottom: 12px;
  font-size: 0.85rem;
  color: #666;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 15px;
}

.relevance-score {
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--primary);
  background: rgba(26, 115, 232, 0.08);
  padding: 2px 8px;
  border-radius: 4px;
}

.result-content {
  font-size: 0.95rem;
  line-height: 1.6;
  color: #444;
  word-break: break-all;
  white-space: pre-wrap;
}

.multi-chunks-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}
.chunk-content-item {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px 14px;
}
.chunk-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

/* 高亮样式渲染 */
:deep(.highlight), :deep(em) {
  font-style: normal;
  color: #c81e1e !important;
  font-weight: 700;
  background: rgba(255, 242, 0, 0.25);
  padding: 0 2px;
  border-radius: 2px;
  box-shadow: 0 1px 2px rgba(200, 30, 30, 0.1);
}

.result-footer {
  display: flex;
  gap: 16px;
}

.meta-tag {
  font-size: 11px;
  color: #70757a;
  background: #f8f9fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.empty-results {
  margin-top: 60px;
  color: var(--text-muted);
}

.loader {
  width: 16px;
  height: 16px;
  border: 2px solid #FFF;
  border-bottom-color: transparent;
  border-radius: 50%;
  display: inline-block;
  animation: rotation 1s linear infinite;
}

@keyframes rotation {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.5s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

/* 搜索下方特性卡片展示区 */
.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  width: 100%;
  max-width: 860px;
  margin-top: 60px;
}

.feature-card {
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  padding: 28px;
  border-radius: 16px;
  text-align: left;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  overflow: hidden;
}

.feature-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 4px;
  background: linear-gradient(90deg, var(--primary), #4facfe);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.feature-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 20px 40px 0 rgba(31, 38, 135, 0.15);
  border-color: rgba(255, 255, 255, 0.8);
}

.feature-card:hover::before {
  opacity: 1;
}

.feature-icon {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: rgba(26, 86, 219, 0.06);
  color: var(--primary);
}

.feature-card h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  margin: 0 0 8px 0;
}

.feature-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
  margin: 0;
}

@media (max-width: 768px) {
  .features-grid {
    grid-template-columns: 1fr;
  }
}

/* === 自定义打标展示 === */
.item-tags-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-tag-mini {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  border-radius: 4px;
  background-color: rgba(26, 86, 219, 0.08);
  color: var(--primary);
  font-size: 11px;
  border: 1px solid rgba(26, 86, 219, 0.15);
}

/* === 打标按钮 === */
.result-footer {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-tag {
  font-size: 12px;
  font-weight: 600;
  color: var(--primary);
  background: rgba(26, 86, 219, 0.06);
  border: 1px solid rgba(26, 86, 219, 0.18);
  padding: 4px 14px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-tag:hover {
  background: rgba(26, 86, 219, 0.14);
  transform: translateY(-1px);
}

/* === 打标弹窗遮罩 === */
.tag-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.tag-modal {
  background: #ffffff;
  border-radius: 20px;
  padding: 36px 40px;
  width: 90%;
  max-width: 520px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.2);
}

.tag-modal h3 {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-main);
  margin: 0 0 24px 0;
}

.form-group {
  margin-bottom: 18px;
}

.form-group label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.tag-input,
.tag-textarea {
  width: 100%;
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  color: var(--text-main);
  background: #f9fafb;
  outline: none;
  transition: border-color 0.2s ease;
  box-sizing: border-box;
}

.tag-input:focus,
.tag-textarea:focus {
  border-color: var(--primary);
  background: #fff;
}

.tag-textarea {
  height: 90px;
  resize: vertical;
  font-family: inherit;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.btn-cancel {
  padding: 8px 20px;
  border-radius: 10px;
  font-size: 14px;
  background: #f1f1f1;
  color: #666;
  transition: background 0.2s ease;
}

.btn-cancel:hover {
  background: #e5e5e5;
}

.btn-submit {
  padding: 8px 24px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--primary) 0%, #3B82F6 100%);
  color: #fff;
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.25);
  transition: all 0.2s ease;
}

.btn-submit:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(26, 86, 219, 0.35);
}

.btn-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* === 查看原文按鈕 === */
.btn-source {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
  background: rgba(55, 65, 81, 0.06);
  border: 1px solid rgba(55, 65, 81, 0.18);
  padding: 4px 14px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-source:hover {
  background: rgba(55, 65, 81, 0.14);
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(0,0,0,0.08);
}


/* === 检索模式 Tabs === */
.search-tabs {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 24px;
}

.result-tabs {
  width: 100%;
  justify-content: flex-start;
  margin-top: 0;
  padding: 18px 0;
  border-top: 1px solid #e5e7eb;
}
.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(8px);
  padding: 8px 18px;
  border-radius: 24px;
  font-size: 14px;
  font-weight: 500;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 2px 6px rgba(0,0,0,0.04);
}
.tab-btn:hover {
  background: rgba(255, 255, 255, 0.9);
  transform: translateY(-1px);
}
.tab-btn.active {
  background: var(--primary-blue);
  color: white;
  border-color: var(--primary-blue);
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.25);
}

/* === 分片明细侧边抽屉 === */
.btn-chunk {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: var(--primary-blue);
  background: rgba(26, 86, 219, 0.08);
  border: 1px solid rgba(26, 86, 219, 0.2);
  padding: 4px 14px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-chunk:hover {
  background: rgba(26, 86, 219, 0.15);
  transform: translateY(-1px);
}

.drawer-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0,0,0,0.4);
  backdrop-filter: blur(2px);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.drawer-content {
  width: 540px;
  max-width: 90vw;
  height: 100%;
  background: #fdfdfd;
  box-shadow: -4px 0 24px rgba(0,0,0,0.15);
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform 0.35s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.drawer-content.drawer-open {
  transform: translateX(0);
}
.drawer-fade-enter-active, .drawer-fade-leave-active {
  transition: opacity 0.35s ease;
}
.drawer-fade-enter-from, .drawer-fade-leave-to {
  opacity: 0;
}
.drawer-fade-enter-from .drawer-content, .drawer-fade-leave-to .drawer-content {
  transform: translateX(100%);
}
.drawer-header {
  padding: 24px 24px 20px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
  position: relative;
  flex-shrink: 0;
}
.drawer-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #111827;
  padding-right: 32px;
  line-height: 1.4;
}
.drawer-subtitle {
  margin: 8px 0 0 0;
  font-size: 13px;
  color: #6b7280;
}
.btn-close {
  position: absolute;
  top: 24px;
  right: 20px;
  background: none;
  border: none;
  font-size: 26px;
  color: #9ca3af;
  cursor: pointer;
  line-height: 1;
  padding: 0;
  border-radius: 4px;
  transition: color 0.2s;
}
.btn-close:hover {
  color: #1f2937;
}
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f9fafb;
}
.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.chunk-item {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  transition: border-color 0.2s;
}
.chunk-item:hover {
  border-color: #d1d5db;
}
.chunk-item.highlight-chunk {
  border-color: rgba(26, 86, 219, 0.4);
  background: rgba(26, 86, 219, 0.02);
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.08);
}
.chunk-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f3f4f6;
}
.chunk-badge {
  background: #f3f4f6;
  padding: 3px 8px;
  border-radius: 6px;
  font-weight: 600;
  color: #4b5563;
  letter-spacing: 0.5px;
}
.hit-badge {
  color: #d97706;
  background: #fef3c7;
  padding: 3px 8px;
  border-radius: 6px;
  font-weight: 600;
}
.chunk-text {
  font-size: 14.5px;
  line-height: 1.7;
  color: #374151;
  word-break: break-all;
  white-space: pre-wrap;
}

/* 原文预览抽屉 */
.original-file-drawer {
  width: 800px;
  max-width: 95vw;
}

.original-file-content {
  padding: 16px 0;
}

.original-file-text {
  font-size: 1rem;
  line-height: 1.8;
  color: #374151;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  padding: 24px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.original-chunk-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.original-chunk-section {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px 20px;
}

/* --- Timeline UI --- */
.timeline-view {
  position: relative;
  padding-left: 20px;
}
.timeline-view::before {
  content: '';
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 7px;
  width: 2px;
  background: #e5e7eb;
  border-radius: 2px;
}
.timeline-item {
  position: relative;
  padding: 0 0 20px 20px !important;
  border: none !important;
  background: transparent !important;
  box-shadow: none !important;
}
.timeline-marker {
  position: absolute;
  top: 16px;
  left: -17px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #ffffff;
  border: 2px solid #6366f1;
  z-index: 10;
}
.chunk-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px;
  transition: all 0.2s ease;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}
.chunk-card:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.05);
  border-color: #d1d5db;
}
.highlight-chunk .chunk-card {
  border-color: #6366f1;
  box-shadow: 0 0 0 1px #6366f1;
}
.highlight-chunk .timeline-marker {
  background: #6366f1;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.2);
}

/* --- Probe UI --- */
.btn-probe-toggle {
  margin-left: auto;
  background: #f3f4f6;
  border: none;
  color: #4b5563;
  font-size: 12px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-probe-toggle:hover {
  background: #e0e7ff;
  color: #4f46e5;
}
.probe-panel {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #e5e7eb;
  overflow: hidden;
}
.probe-input-wrapper {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.probe-input-wrapper input {
  flex: 1;
  padding: 8px 12px;
  font-size: 13px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  outline: none;
  transition: border-color 0.2s;
}
.probe-input-wrapper input:focus {
  border-color: #6366f1;
}
.probe-input-wrapper button {
  background: #4f46e5;
  color: white;
  border: none;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}
.probe-input-wrapper button:hover:not(:disabled) {
  background: #4338ca;
}
.probe-input-wrapper button:disabled {
  background: #a5b4fc;
  cursor: not-allowed;
}
.probe-result-area {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
}
.probe-score-ring {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
}
.circular-chart {
  display: block;
  margin: 0 auto;
  max-width: 100%;
  max-height: 250px;
}
.circle-bg {
  fill: none;
  stroke: #eee;
  stroke-width: 2.5;
}
.circle {
  fill: none;
  stroke-width: 2.5;
  stroke-linecap: round;
  transition: stroke-dasharray 1s ease-out;
}
.percentage {
  fill: #374151;
  font-family: sans-serif;
  font-size: 8px;
  font-weight: bold;
  text-anchor: middle;
}
.score-green .circle { stroke: #10b981; }
.score-blue .circle { stroke: #3b82f6; }
.score-orange .circle { stroke: #f59e0b; }
.score-gray .circle { stroke: #9ca3af; }
.probe-score-title {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 2px;
}
.probe-score-desc {
  font-size: 12px;
  color: #6b7280;
}
.loader-mini {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid #ffffff;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.probe-collapse-enter-active, .probe-collapse-leave-active {
  transition: all 0.3s ease;
  max-height: 200px;
  opacity: 1;
}
.probe-collapse-enter-from, .probe-collapse-leave-to {
  max-height: 0;
  opacity: 0;
  padding-top: 0;
  margin-top: 0;
  border-top-color: transparent;
}
.parent-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #4f46e5;
  background: #e0e7ff;
  padding: 3px 8px;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}
.parent-badge:hover {
  background: #c7d2fe;
}

/* === UI/UX Pro Max refinement layer === */
.home-wrapper {
  min-height: 100dvh;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(246, 248, 251, 0.98) 42%),
    radial-gradient(circle at 18% 8%, rgba(15, 138, 157, 0.12), transparent 32%),
    linear-gradient(135deg, #f8fbff 0%, #f2f6fa 100%);
  overflow-x: hidden;
}

.bg-fluid-container {
  display: none;
}

.app-header {
  height: 72px;
  padding: 0 clamp(16px, 4vw, 40px);
}

.btn-admin {
  min-height: 44px;
  padding: 0 18px;
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid var(--border-ultra-light);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.search-main {
  width: 100%;
  padding: 0 clamp(16px, 4vw, 32px) 72px;
}

.search-hero {
  max-width: 760px;
  margin-top: clamp(112px, 17vh, 180px);
}

.search-hero.has-results {
  margin-top: 88px;
}

.logo-text {
  margin-bottom: 32px;
  color: var(--text-main);
  font-size: clamp(34px, 6vw, 56px);
  font-weight: 760;
  line-height: 1.12;
  letter-spacing: 0;
}

.search-box {
  height: auto;
  min-height: 64px;
  padding: 8px;
  padding-left: 22px;
  background: rgba(255, 255, 255, 0.94);
  border-color: rgba(23, 32, 51, 0.1);
  border-radius: 18px;
  box-shadow: var(--shadow-md);
}

.search-box:hover {
  border-color: rgba(23, 32, 51, 0.14);
  box-shadow: var(--shadow-md);
}

.search-box:focus-within {
  border-color: rgba(36, 86, 214, 0.34);
  box-shadow: var(--focus-ring), var(--shadow-md);
  background: #ffffff;
}

.search-btn {
  min-width: 112px;
  height: 48px;
  border-radius: 14px;
  background: var(--primary);
  box-shadow: 0 12px 24px rgba(36, 86, 214, 0.2);
}

.search-btn:hover {
  background: var(--primary-hover);
}

.search-tabs {
  flex-wrap: wrap;
  gap: 10px;
}

.tab-btn {
  min-height: 44px;
  padding: 0 16px;
  background: rgba(255, 255, 255, 0.8);
  border-color: var(--border-ultra-light);
  border-radius: 12px;
  box-shadow: none;
}

.tab-btn.active {
  background: #eaf0ff;
  color: var(--primary);
  border-color: rgba(36, 86, 214, 0.24);
  box-shadow: inset 0 0 0 1px rgba(36, 86, 214, 0.06);
}

.tab-status-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.75;
  animation: tabPulse 1.15s ease-in-out infinite;
}

@keyframes tabPulse {
  0%, 100% { transform: scale(0.75); opacity: 0.45; }
  50% { transform: scale(1); opacity: 1; }
}

.qa-hero-action {
  width: 100%;
  display: flex;
  justify-content: center;
  margin-top: 18px;
}

.btn-qa-hero {
  width: auto;
  min-height: 48px;
  border-radius: 14px;
  border-color: rgba(23, 32, 51, 0.1);
  background: rgba(255, 255, 255, 0.76);
  box-shadow: none;
}

.btn-qa-hero:hover {
  background: #ffffff;
  box-shadow: 0 8px 18px rgba(23, 32, 51, 0.08);
}

.features-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-width: 1040px;
  gap: 18px;
}

.feature-card,
.result-item,
.chunk-card,
.original-chunk-section,
.tag-modal {
  border-radius: var(--radius-lg);
  border-color: rgba(23, 32, 51, 0.1);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-md);
}

.feature-card {
  padding: 24px;
}

.feature-card:hover,
.result-item:hover {
  transform: translateY(-2px);
}

.results-area {
  max-width: 880px;
  margin-top: 0;
  padding-bottom: 0;
}

.results-shell {
  width: 100%;
  max-width: 980px;
  margin-top: 30px;
  padding: 28px 32px 34px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(23, 32, 51, 0.1);
  border-radius: 10px;
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.08);
}

.results-shell .results-area {
  max-width: none;
}

.results-shell .result-item {
  margin-bottom: 16px;
  background: #ffffff;
  border-color: #edf0f4;
  box-shadow: none;
}

.results-shell .result-item:last-child {
  margin-bottom: 0;
}

.results-shell .results-stats {
  margin: 0 0 16px;
  padding-top: 4px;
}

.result-title-row,
.result-footer,
.probe-input-wrapper {
  flex-wrap: wrap;
}

.result-content,
.chunk-text,
.original-file-text {
  color: #334155;
  line-height: 1.75;
  word-break: break-word;
}

.btn-chunk,
.btn-source,
.btn-tag,
.btn-probe-toggle {
  min-height: 36px;
  border-radius: 10px;
}

.drawer-content {
  width: min(760px, 92vw);
  background: #f8fafc;
}

.btn-close {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 900px) {
  .features-grid {
    grid-template-columns: 1fr;
    max-width: 680px;
    margin-top: 40px;
  }
}

@media (max-width: 640px) {
  .search-hero {
    align-items: stretch;
    margin-top: 96px;
  }

  .logo-text {
    text-align: left;
  }

  .search-box {
    align-items: stretch;
    flex-direction: column;
    padding: 12px;
    border-radius: 18px;
  }

  .search-input {
    min-height: 44px;
    padding: 0 4px;
  }

  .search-btn {
    width: 100%;
  }

  .btn-qa-hero {
    width: 100%;
    justify-content: center;
    align-items: center;
  }

  .qa-hero-arrow {
    display: none;
  }

  .result-item {
    padding: 20px;
  }

  .result-item::before {
    width: 3px;
  }

  .drawer-content,
  .original-file-drawer {
    width: 100vw;
    max-width: 100vw;
  }

  .probe-input-wrapper {
    flex-direction: column;
  }
}
</style>
