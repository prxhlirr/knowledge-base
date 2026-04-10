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
      <router-link to="/admin" class="btn-admin">后台管理</router-link>
    </header>

    <main class="search-main">
      <div class="search-hero" :class="{ 'has-results': results.length > 0 }">
        <h1 class="logo-text">智能检索中枢</h1>
        
        <div class="search-box-container">
          <div class="search-box">
            <input 
              v-model="query" 
              type="text" 
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

      <!-- 搜索下方说明引导区 (仅在无结果时展示) -->
      <transition name="fade">
        <div v-if="!hasSearched || (results.length === 0 && !loading)" class="features-grid">
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

      <!-- 搜索结果列表 -->
      <transition name="fade">
        <div v-if="results.length > 0" class="results-area">
          <div class="results-stats">
            为您找到约 {{ results.length }} 条相关结果（用时 {{ tookMs }} ms）
          </div>
          
          <div v-for="(item, index) in results" :key="index" class="result-item">
            <div class="result-title-row">
              <span class="file-type" :class="item.file_type || 'default'">{{ item.file_type || '文档' }}</span>
              <h3 class="source-name">{{ item.organization || '综合部门' }}</h3>
              <span class="relevance-score">匹配度: {{ (item.score * 100).toFixed(0) }}%</span>
            </div>
            <div class="result-meta-row">
              <span class="publish-date">发布时间: {{ item.publish_time || '2024-01-10' }}</span>
              <div v-if="item.custom_tags" class="item-tags-wrap">
                <span v-for="tag in item.custom_tags.split(',')" :key="tag" class="meta-tag-mini">
                  <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-tag"><path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5" fill="currentColor"/></svg>
                  {{ tag.trim() }}
                </span>
              </div>
            </div>
            
            <div class="result-content" v-html="highlightText(item)"></div>
            
            <!-- 操作栏：打标增强 + 查看原文 -->
            <div class="result-footer">
              <button class="btn-tag" @click="openTagModal(item)">📝 打标增强</button>
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

      <!-- 空状态 -->
      <div v-if="hasSearched && results.length === 0 && !loading" class="empty-results">
        <p>未找到与“{{ query }}”相关的条目，请尝试其他关键词</p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import axios from 'axios'

const query = ref('')
const results = ref([])
const loading = ref(false)
const tookMs = ref(0)
const error = ref('')
const hasSearched = ref(false)

const handleSearch = async () => {
  if (!query.value.trim()) return
  
  loading.value = true
  error.value = ''
  hasSearched.value = true
  
  try {
    // 调用 Java 网关进行混合搜索，已重构为跨环境动态请求寻址
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Search-AppCode': import.meta.env.VITE_APP_CODE || 'ADMIN_MASTER_KEY',
        'X-User-Id': import.meta.env.VITE_USER_ID || 'admin',
        'X-Authorities': import.meta.env.VITE_USER_ROLE || 'ROLE_SUPER_ADMIN',
        'X-Dept-Code': import.meta.env.VITE_DEPT_CODE || '620102000000'
      },
      body: JSON.stringify({
        queryText: query.value.trim(),
        pageSize: 10
      })
    });
    
    const data = await response.json();

    if (data.code === 200) {
      results.value = data.data.list || []
      tookMs.value = data.data.took_ms || 0
      // SLA 超时降级（仍返回200，但 sla_timeout=true），给用户友好提示
      if (data.data.sla_timeout && results.value.length === 0) {
        error.value = '检索响应超时（SLA），结果为空。请稍后重试或简化查询词。'
      }
    } else if (data.code === 401) {
      error.value = '鉴权失败：AppCode 无效，请联系管理员。'
    } else {
      error.value = data.msg || '检索失败，请稍后重试'
    }
  } catch (err) {
    console.error('Search error:', err)
    error.value = '无法连接到后端服务，请确保 Java 网关已启动。'
  } finally {
    loading.value = false
  }
}

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
  try {
    // 优先传 docId（精确定位唯一版本），sourceName 作备用兜底
    const params = new URLSearchParams()
    if (docId)    params.set('docId',      docId)
    if (fileName) params.set('sourceName', fileName)
    const resp = await fetch(
      `${import.meta.env.VITE_API_BASE_URL}/file/preview?${params.toString()}`
    )
    const data = await resp.json()
    if (data.code === 200 && data.data?.url) {
      window.open(data.data.url, '_blank')
    } else if (data.code === 503) {
      alert('文件存储服务暂时不可用，请联系管理员')
    } else if (data.code === 404) {
      alert(data.msg || '文件未找到，可能尚未入库或已删除')
    } else {
      alert('打开失败：' + (data.msg || '未知错误'))
    }
  } catch (err) {
    alert('请求异常，请确认网络连接正常')
    console.error('[openSourceFile]', err)
  }
}

const highlightText = (item) => {
  // 优先展示后端返回的高亮字段 (ES 索引字段名为 content)
  if (item.highlight) {
    // 兼容多种可能的 key
    const hl = item.highlight.content || item.highlight.chunk_text;
    if (hl && hl.length > 0) {
      return hl.join(' ... ')
    }
  }
  return item.chunk_text || ''
}
</script>

<style scoped>
.home-wrapper {
  width: 100%;
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
  transform: translateY(1px) scale(0.96); /* 缩放反馈 */
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

/* 左侧公文特征色带 */
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
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

/* 高亮样式渲染 */
:deep(.highlight), :deep(em) {
  font-style: normal;
  color: #c81e1e !important;
  font-weight: 700;
  background: rgba(255, 242, 0, 0.25);
  padding: 0 2px;
  border-radius: 2px;
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

/* 首页下方特色说明网格区 */
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
</style>
