<template>
  <div class="tag-management">
    <header class="page-header">
      <h2>人工知识增强 (打标管理)</h2>
      <p class="subtitle">管理由业务人员补充的前端搜索结果标签与关键词，并人工同步至 AI 向量库。</p>
    </header>

    <div class="table-container">
      <table class="tag-table">
        <thead>
          <tr>
            <th width="80">ID</th>
            <th width="320">自定义标签</th>
            <th>补充关键词</th>
            <th width="120">状态</th>
            <th width="160">最后更新</th>
            <th width="140">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="6" class="empty-state">数据加载中...</td>
          </tr>
          <tr v-else-if="tagList.length === 0">
            <td colspan="6" class="empty-state">暂无任何打标数据</td>
          </tr>
          <tr v-else v-for="item in tagList" :key="item.id">
            <td>{{ item.id }}</td>
            <td>
              <div class="tag-cell-wrap">
                <span v-for="tag in (item.tags ? item.tags.split(',') : [])" :key="tag" class="meta-tag">
                  {{ tag.trim() }}
                </span>
                <span v-if="!item.tags" class="text-muted">无</span>
              </div>
            </td>
            <td>
              <div class="keyword-cell" :title="item.keywords">
                {{ item.keywords || '无' }}
              </div>
            </td>
            <td>
              <span class="status-badge" :class="getStatusClass(item.syncStatus)">
                {{ getStatusText(item.syncStatus) }}
              </span>
            </td>
            <td class="time-cell">{{ formatTime(item.updateTime) }}</td>
            <td>
              <button 
                class="btn-sync" 
                @click="syncTag(item)" 
                :disabled="syncingId === item.id">
                <span v-if="syncingId === item.id" class="loader-small"></span>
                {{ syncingId === item.id ? '同步中' : '全量同步与重建' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 极简分页 -->
    <div v-if="total > 0" class="pagination">
      <button @click="changePage(currentPage - 1)" :disabled="currentPage <= 1">上一页</button>
      <span class="page-info">{{ currentPage }} / {{ Math.ceil(total / pageSize) }}</span>
      <button @click="changePage(currentPage + 1)" :disabled="currentPage >= Math.ceil(total / pageSize)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const tagList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const syncingId = ref(null)

const fetchTagList = async () => {
  loading.value = true
  try {
    const res = await fetch(`${import.meta.env.VITE_API_BASE_URL}/admin/tags/list?current=${currentPage.value}&size=${pageSize.value}`)
    const data = await res.json()
    if (data.code === 200) {
      tagList.value = data.data.records
      total.value = data.data.total
    }
  } catch (e) {
    console.error('Failed to load tags:', e)
  } finally {
    loading.value = false
  }
}

const syncTag = async (item) => {
  if (!confirm(`确认要将 ID: ${item.id} 的打标内容同步并重组至 Elasticsearch 与 向量大模型吗？\n（此操作将调用外部 AI 模型耗时数秒）`)) {
    return
  }
  
  syncingId.value = item.id
  try {
    const res = await fetch(`${import.meta.env.VITE_API_BASE_URL}/admin/tags/${item.id}/sync`, {
      method: 'POST'
    })
    const data = await res.json()
    if (data.code === 200) {
      alert('同步重建成功！')
      fetchTagList() // 刷新列表状态
    } else {
      alert('同步失败: ' + data.msg)
    }
  } catch (e) {
    alert('请求异常: ' + e.message)
  } finally {
    syncingId.value = null
  }
}

const changePage = (page) => {
  currentPage.value = page
  fetchTagList()
}

const getStatusText = (status) => {
  if (status === 1) return '已同步'
  if (status === 2) return '同步失败'
  return '未同步'
}

const getStatusClass = (status) => {
  if (status === 1) return 'status-success'
  if (status === 2) return 'status-error'
  return 'status-pending'
}

const formatTime = (timeStr) => {
  if (!timeStr) return '-'
  return new Date(timeStr).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  fetchTagList()
})
</script>

<style scoped>
.tag-management {
  background: var(--snow-bg);
  border-radius: 24px;
  padding: 32px 40px;
  box-shadow: var(--shadow-sm);
  border: 1px solid var(--border-ultra-light);
  min-height: calc(100vh - 200px);
}

.page-header {
  margin-bottom: 32px;
}

.page-header h2 {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-main);
  margin: 0 0 8px 0;
}

.subtitle {
  color: var(--text-muted);
  font-size: 14px;
  margin: 0;
}

.table-container {
  overflow-x: auto;
  border-radius: 12px;
  border: 1px solid var(--border-ultra-light);
}

.tag-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  background: #fff;
}

.tag-table th {
  background: #f8fafc;
  color: var(--text-muted);
  font-weight: 600;
  font-size: 13px;
  padding: 16px 20px;
  border-bottom: 2px solid var(--border-soft);
}

.tag-table td {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-soft);
  color: var(--text-main);
  font-size: 14px;
  vertical-align: middle;
}

.tag-table tr:hover td {
  background: #fdfdfe;
}

.tag-cell-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-tag {
  font-size: 11px;
  color: #1e40af;
  background: #dbeafe;
  padding: 4px 8px;
  border-radius: 12px;
  font-weight: 500;
}

.keyword-cell {
  max-width: 300px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #4b5563;
}

.status-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 20px;
  display: inline-block;
}

.status-pending { background: #fee2e2; color: #b91c1c; }
.status-success { background: #d1fae5; color: #047857; }
.status-error   { background: #f3f4f6; color: #6b7280; }

.time-cell {
  font-size: 13px;
  color: var(--text-muted);
}

.btn-sync {
  background: linear-gradient(135deg, var(--primary) 0%, #3B82F6 100%);
  color: #fff;
  border: none;
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-sync:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.25);
}

.btn-sync:disabled {
  opacity: 0.7;
  cursor: wait;
}

.empty-state {
  text-align: center;
  padding: 40px !important;
  color: var(--text-muted);
}

.loader-small {
  width: 12px;
  height: 12px;
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

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
}

.pagination button {
  padding: 8px 16px;
  border-radius: 6px;
  border: 1px solid var(--border-soft);
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.pagination button:hover:not(:disabled) {
  background: #f8fafc;
  border-color: var(--border-ultra-light);
}

.pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-info {
  font-size: 14px;
  color: var(--text-muted);
}
</style>
