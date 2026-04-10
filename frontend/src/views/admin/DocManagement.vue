<template>
  <div class="doc-management-container">
    <header class="page-header">
      <h2>文档管理</h2>
      <p class="subtitle">管理知识库中所有已入库文档，支持搜索、查看历史版本、编辑元数据及逻辑删除</p>
    </header>

    <!-- 搜索工具栏 -->
    <section class="toolbar">
      <div class="search-row">
        <input
          type="text"
          v-model="keyword"
          placeholder="搜索文档名、单位、文号、标签..."
          class="search-input"
          @keyup.enter="fetchDocs"
        />
        <select v-model="statusFilter" class="status-select">
          <option value="INDEXED">已入库</option>
          <option value="DELETED">已删除</option>
          <option value="">全部</option>
        </select>
        <button class="btn-primary" @click="fetchDocs" :disabled="loading">
          {{ loading ? '查询中...' : '🔍 搜索' }}
        </button>
      </div>
      <div class="stat-row">
        <span class="stat-badge">共 {{ total }} 份文档</span>
      </div>
    </section>

    <!-- 文档列表表格 -->
    <section class="table-section">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="docs.length === 0" class="empty-state">
        <div class="empty-icon">📂</div>
        <p>暂无文档记录</p>
      </div>

      <table v-else class="doc-table">
        <thead>
          <tr>
            <th>文档名称</th>
            <th>版本</th>
            <th>可见度</th>
            <th>标签</th>
            <th>来源单位</th>
            <th>切片数</th>
            <th>入库时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in docs" :key="doc.id" :class="{ 'deleted-row': doc.status === 'DELETED' }">
            <td class="name-cell" :title="doc.sourceName">
              {{ truncate(doc.sourceName, 28) }}
            </td>
            <td>
              <span class="version-badge">v{{ doc.docVersion }}</span>
            </td>
            <td>
              <span class="vis-tag" :class="(doc.visibility || 'INTERNAL').toLowerCase()">
                {{ doc.visibility || 'INTERNAL' }}
              </span>
            </td>
            <td class="tags-cell">
              <template v-if="doc.tags">
                <span class="tag-chip" v-for="t in doc.tags.split(',')" :key="t">{{ t.trim() }}</span>
              </template>
              <span v-else class="no-data">—</span>
            </td>
            <td>{{ doc.unit || '—' }}</td>
            <td class="center">{{ doc.chunkCount ?? '—' }}</td>
            <td class="time-cell">{{ formatDate(doc.createdAt) }}</td>
            <td class="action-cell">
              <button class="btn-action btn-edit" @click="openEdit(doc)" :disabled="doc.status === 'DELETED'">编辑</button>
              <button class="btn-action btn-history" @click="openHistory(doc)">历史</button>
              <button class="btn-action btn-delete"
                      @click="confirmDelete(doc)"
                      :disabled="doc.status === 'DELETED'">
                {{ doc.status === 'DELETED' ? '已删除' : '删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <div class="pagination" v-if="total > pageSize">
        <button class="page-btn" @click="changePage(currentPage - 1)" :disabled="currentPage === 1">‹</button>
        <span class="page-info">第 {{ currentPage }} / {{ totalPages }} 页</span>
        <button class="page-btn" @click="changePage(currentPage + 1)" :disabled="currentPage >= totalPages">›</button>
      </div>
    </section>

    <!-- 编辑元数据弹层 -->
    <div class="modal-overlay" v-if="editDialogVisible" @click.self="editDialogVisible = false">
      <div class="modal">
        <h3>编辑元数据</h3>
        <p class="modal-subtitle">{{ editForm.sourceName }}</p>
        <div class="edit-form">
          <label>标签（逗号分隔）
            <input type="text" v-model="editForm.tags" placeholder="如：公安,年报,通知" />
          </label>
          <label>文件文号
            <input type="text" v-model="editForm.docNumber" placeholder="如：国发〔2026〕1号" />
          </label>
          <label>来源单位
            <input type="text" v-model="editForm.unit" placeholder="如：甘肃省公安厅" />
          </label>
          <label>可见度
            <select v-model="editForm.visibility">
              <option value="INTERNAL">INTERNAL — 内部</option>
              <option value="PUBLIC">PUBLIC — 公开</option>
              <option value="DEPT">DEPT — 部门</option>
              <option value="PRIVATE">PRIVATE — 私有</option>
              <option value="GRANT">GRANT — 授权</option>
            </select>
          </label>
          <label>部门编码
            <input type="text" v-model="editForm.deptCode" placeholder="12位行政区划编码" />
          </label>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="editDialogVisible = false">取消</button>
          <button class="btn-save" @click="saveMeta" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- 版本历史弹层 -->
    <div class="modal-overlay" v-if="historyDialogVisible" @click.self="historyDialogVisible = false">
      <div class="modal modal-wide">
        <h3>版本历史 — {{ historyDoc?.sourceName }}</h3>
        <div v-if="historyLoading" class="history-loading">加载中...</div>
        <table v-else-if="historyList.length" class="history-table">
          <thead>
            <tr><th>版本</th><th>最新</th><th>状态</th><th>切片数</th><th>内容哈希</th><th>入库时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="h in historyList" :key="h.id">
              <td><span class="version-badge">v{{ h.docVersion }}</span></td>
              <td>
                <span :class="h.isLatest ? 'latest-yes' : 'latest-no'">{{ h.isLatest ? '✓' : '—' }}</span>
              </td>
              <td>{{ h.status }}</td>
              <td>{{ h.chunkCount }}</td>
              <td class="hash-cell">{{ h.contentHash ? h.contentHash.substring(0, 8) + '...' : '—' }}</td>
              <td>{{ formatDate(h.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state" style="padding: 30px">暂无历史记录</div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="historyDialogVisible = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1';

// ─── 列表状态 ────────────────────────────────────────────────────
const docs        = ref([]);
const total       = ref(0);
const currentPage = ref(1);
const pageSize    = ref(20);
const keyword     = ref('');
const statusFilter = ref('INDEXED');
const loading     = ref(false);

const totalPages = computed(() => Math.ceil(total.value / pageSize.value));

/**
 * 分页查询文档列表
 * 调用：GET /api/v1/admin/docs
 */
const fetchDocs = async () => {
  loading.value = true;
  try {
    const res = await axios.get(`${API_BASE}/admin/docs`, {
      params: {
        page:    currentPage.value,
        size:    pageSize.value,
        keyword: keyword.value || undefined,
        status:  statusFilter.value || undefined
      }
    });
    if (res.data.code === 200) {
      docs.value  = res.data.data.records;
      total.value = res.data.data.total;
    }
  } catch (err) {
    console.error('文档列表加载失败', err);
  } finally {
    loading.value = false;
  }
};

const changePage = (p) => {
  if (p < 1 || p > totalPages.value) return;
  currentPage.value = p;
  fetchDocs();
};

onMounted(fetchDocs);

// ─── 删除 ────────────────────────────────────────────────────────
/**
 * 逻辑删除文档
 * 调用：DELETE /api/v1/admin/docs/{id}
 */
const confirmDelete = async (doc) => {
  if (!confirm(`确认删除「${doc.sourceName}」？\n（逻辑删除，不会物理删除记录）`)) return;
  try {
    const res = await axios.delete(`${API_BASE}/admin/docs/${doc.id}`);
    if (res.data.code === 200) {
      fetchDocs();
    } else {
      alert('删除失败: ' + res.data.msg);
    }
  } catch (err) {
    console.error('删除失败', err);
    alert('请求失败，请检查后端运行状态');
  }
};

// ─── 编辑元数据 ──────────────────────────────────────────────────
const editDialogVisible = ref(false);
const saving = ref(false);
const editForm = ref({});

const openEdit = (doc) => {
  editForm.value = {
    id:          doc.id,
    sourceName:  doc.sourceName,
    tags:        doc.tags || '',
    docNumber:   doc.docNumber || '',
    unit:        doc.unit || '',
    visibility:  doc.visibility || 'INTERNAL',
    deptCode:    doc.deptCode || ''
  };
  editDialogVisible.value = true;
};

/**
 * 保存元数据更新
 * 调用：PUT /api/v1/admin/docs/{id}/meta
 */
const saveMeta = async () => {
  saving.value = true;
  try {
    const { id, sourceName, ...fields } = editForm.value;
    const res = await axios.put(`${API_BASE}/admin/docs/${id}/meta`, fields);
    if (res.data.code === 200) {
      editDialogVisible.value = false;
      fetchDocs();
    } else {
      alert('保存失败: ' + res.data.msg);
    }
  } catch (err) {
    console.error('保存失败', err);
  } finally {
    saving.value = false;
  }
};

// ─── 版本历史 ────────────────────────────────────────────────────
const historyDialogVisible = ref(false);
const historyLoading = ref(false);
const historyDoc  = ref(null);
const historyList = ref([]);

/**
 * 查询文档所有版本历史
 * 调用：GET /api/v1/admin/docs/{id}/versions
 */
const openHistory = async (doc) => {
  historyDoc.value  = doc;
  historyList.value = [];
  historyLoading.value = true;
  historyDialogVisible.value = true;
  try {
    const res = await axios.get(`${API_BASE}/admin/docs/${doc.id}/versions`);
    if (res.data.code === 200) {
      historyList.value = res.data.data;
    }
  } catch (err) {
    console.error('历史版本加载失败', err);
  } finally {
    historyLoading.value = false;
  }
};

// ─── 工具函数 ────────────────────────────────────────────────────
const truncate = (str, len) => str && str.length > len ? str.substring(0, len) + '…' : str;
const formatDate = (str) => {
  if (!str) return '—';
  return new Date(str).toLocaleString('zh-CN', { hour12: false });
};
</script>

<style scoped>
.doc-management-container {
  max-width: 1300px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 32px;
  animation: fadeIn 0.5s ease;
}
@keyframes fadeIn { from { opacity:0; transform:translateY(8px) } to { opacity:1; transform:none } }

.page-header h2 { font-size: 30px; font-weight: 800; color: var(--text-main); margin: 0 0 8px; letter-spacing: -0.5px; }
.subtitle { color: var(--text-muted); font-size: 14px; }

/* 工具栏 */
.toolbar {
  background: var(--snow-bg);
  border-radius: 20px;
  padding: 28px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
}
.search-row { display: flex; gap: 12px; }
.search-input {
  flex: 1;
  padding: 12px 18px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
}
.search-input:focus { border-color: var(--accent-blue); box-shadow: 0 0 0 3px var(--accent-soft); }
.status-select {
  padding: 12px 16px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  cursor: pointer;
}
.btn-primary {
  background: var(--accent-blue);
  color: white;
  padding: 12px 24px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  transition: all 0.2s;
}
.btn-primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(37,99,235,0.25); }
.btn-primary:disabled { background: var(--border-light); color: var(--text-muted); cursor: not-allowed; }

.stat-row { display: flex; align-items: center; gap: 12px; }
.stat-badge {
  font-size: 13px; font-weight: 600;
  color: var(--text-muted); background: var(--snow-soft);
  padding: 4px 12px; border-radius: 20px;
  border: 1px solid var(--border-ultra-light);
}

/* 表格 */
.table-section {
  background: var(--snow-bg);
  border-radius: 20px;
  padding: 28px 32px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
}
.doc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.doc-table th {
  text-align: left;
  padding: 10px 14px;
  font-size: 11px;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid var(--border-ultra-light);
}
.doc-table td {
  padding: 14px 14px;
  border-bottom: 1px solid var(--border-ultra-light);
  color: var(--text-main);
  vertical-align: middle;
}
.doc-table tr:last-child td { border-bottom: none; }
.doc-table tbody tr:hover td { background: var(--snow-soft); }
.deleted-row td { opacity: 0.45; }
.name-cell { font-weight: 600; max-width: 240px; }
.center { text-align: center; }
.time-cell { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
.hash-cell { font-family: monospace; font-size: 12px; color: var(--text-muted); }

.version-badge { background: var(--accent-soft); color: var(--accent-blue); font-weight: 800; font-size: 11px; padding: 3px 8px; border-radius: 6px; }
.vis-tag { padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; text-transform: uppercase; }
.vis-tag.public   { background: #DCFCE7; color: #16A34A; }
.vis-tag.internal { background: #EFF6FF; color: #2563EB; }
.vis-tag.dept     { background: #FEF3C7; color: #D97706; }
.vis-tag.private  { background: #FEE2E2; color: #DC2626; }
.vis-tag.grant    { background: #F3E8FF; color: #7C3AED; }

.tags-cell { display: flex; flex-wrap: wrap; gap: 4px; }
.tag-chip { background: var(--snow-soft); border: 1px solid var(--border-light); color: var(--text-muted); font-size: 11px; padding: 2px 7px; border-radius: 6px; }
.no-data { color: var(--border-light); }

.action-cell { display: flex; gap: 6px; white-space: nowrap; }
.btn-action { padding: 5px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; transition: all 0.15s; }
.btn-edit    { background: var(--accent-soft); color: var(--accent-blue); }
.btn-history { background: #F3E8FF; color: #7C3AED; }
.btn-delete  { background: #FEE2E2; color: #DC2626; }
.btn-edit:hover:not(:disabled)    { filter: brightness(0.92); }
.btn-history:hover:not(:disabled) { filter: brightness(0.92); }
.btn-delete:hover:not(:disabled)  { filter: brightness(0.92); }
.btn-action:disabled { opacity: 0.4; cursor: not-allowed; }

/* 分页 */
.pagination { display: flex; justify-content: center; align-items: center; gap: 16px; padding-top: 24px; }
.page-btn { padding: 8px 16px; border-radius: 10px; background: var(--snow-soft); color: var(--text-main); font-weight: 600; }
.page-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.page-info { font-size: 13px; color: var(--text-muted); }

/* 空/加载状态 */
.empty-state { text-align: center; padding: 60px 20px; color: var(--text-muted); }
.empty-icon  { font-size: 40px; margin-bottom: 12px; }
.empty-state p { font-size: 15px; font-weight: 600; }
.loading-state { text-align: center; padding: 60px; color: var(--text-muted); }

/* 弹层 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}
.modal {
  background: var(--snow-bg);
  border-radius: 20px;
  padding: 32px 36px;
  width: 480px;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: 0 24px 60px rgba(0,0,0,0.18);
}
.modal-wide { width: 700px; }
.modal h3 { font-size: 18px; font-weight: 800; color: var(--text-main); margin: 0 0 6px; }
.modal-subtitle { font-size: 13px; color: var(--text-muted); margin-bottom: 24px; }

.edit-form { display: flex; flex-direction: column; gap: 14px; }
.edit-form label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.edit-form input, .edit-form select {
  padding: 10px 14px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}
.edit-form input:focus, .edit-form select:focus { border-color: var(--accent-blue); }

.modal-footer { display: flex; justify-content: flex-end; gap: 12px; margin-top: 28px; }
.btn-cancel { padding: 10px 20px; border-radius: 10px; background: var(--snow-soft); color: var(--text-muted); font-weight: 600; }
.btn-save   { padding: 10px 24px; border-radius: 10px; background: var(--accent-blue); color: white; font-weight: 700; }

/* 历史版本表格 */
.history-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 16px; }
.history-table th {
  text-align: left; padding: 8px 12px;
  font-size: 11px; font-weight: 700; color: var(--text-muted);
  border-bottom: 1px solid var(--border-ultra-light);
}
.history-table td { padding: 10px 12px; border-bottom: 1px solid var(--border-ultra-light); }
.history-loading { text-align: center; padding: 30px; color: var(--text-muted); }
.latest-yes { color: #16A34A; font-weight: 800; }
.latest-no  { color: var(--border-light); }
</style>
